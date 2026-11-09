package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.Lockfiles
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// S8.2 L-5: インストールの途中で、置き場所の途中のディレクトリ（.createscript/packages/<name> など）がシンボリックリンクに差し替えられたとき。
// 移動の前の確認と、移動の後・マニフェストを書き換える前の確認。差し替えの時点はフックで決める（実際の競合の試験は別に行う）
class PackageDirectoryRaceTest {
    @TempDir
    lateinit var temp: Path

    private val store = MemoryReleases()
    private val numbers = Packages.source("numbers")
    private val vectorMath = Packages.source("vector-math")
    private val spec = PackageSpec(vectorMath, "1.2.0")
    private lateinit var root: Path
    private lateinit var outside: Path
    private lateinit var originalManifest: ByteArray

    @BeforeEach
    fun setUp() {
        store.publish(numbers, "3.0.0", Packages.library("numbers", "3.0.0"))
        store.publish(vectorMath, "1.2.0", Packages.library("vector-math", "1.2.0", mapOf("numbers" to ("3.0.0" to numbers))))
        root = createProject(temp.resolve("app"))
        originalManifest = Files.readAllBytes(root.resolve("createscript.json"))
        // プロジェクトの外の、利用者のディレクトリ
        outside = Files.createDirectories(temp.resolve("outside"))
        Files.writeString(outside.resolve("precious.txt"), "user data")
        Files.createDirectories(outside.resolve("numbers"))
        Files.writeString(outside.resolve("numbers/notes.txt"), "more user data")
    }

    private fun installer(hooks: InstallHooks = InstallHooks.NONE) = Installer(root, PackageFetcher(store, ArchiveCache(temp.resolve("cache"))), InstallLimits(), hooks)

    private val numbersDir get() = root.resolve(".createscript/packages/numbers")

    // 実際のディレクトリを外へ移し、同じ名前でプロジェクトの外へのリンクを作る。移したディレクトリを返す
    private fun swapToLink(directory: Path, linkTo: Path = outside): Path {
        val away = temp.resolve("away-${directory.fileName}-${System.nanoTime()}")
        Files.move(directory, away)
        Files.createSymbolicLink(directory, linkTo)
        return away
    }

    private fun outsideFiles(): Map<String, String> = Files.walk(outside).use { s ->
        s.filter { it != outside }.toList().associate { outside.relativize(it).toString() to if (Files.isDirectory(it)) "dir" else Files.readString(it) }
    }

    private val userFiles = mapOf("precious.txt" to "user data", "numbers" to "dir", "numbers/notes.txt" to "more user data")

    // インストールは成功扱いにならず、プロジェクトのファイルは前のまま
    private fun assertNotInstalled(failure: InstallFailure) {
        assertEquals(InstallFailure.Kind.UNSAFE, failure.kind, failure.message)
        assertArrayEquals(originalManifest, Files.readAllBytes(root.resolve("createscript.json")))
        assertFalse(Files.exists(root.resolve("createscript.lock")))
        assertFalse(Files.exists(root.resolve(".createscript/transaction.json")))
    }

    // ---- 正常なインストール ----

    @Test
    fun aNormalInstallationIsUnchanged() {
        val result = installer().install(spec, false)
        assertEquals(2, result.packages.count { it.installed })
        InstalledPackages.verify(root, Lockfiles.parse(Files.readAllBytes(root.resolve("createscript.lock"))).lockfile!!)
        assertEquals(userFiles, outsideFiles())
    }

    // ---- 始めから、途中のディレクトリがリンク ----

    @Test
    fun refusesALinkedParentFromTheStart() {
        for (link in listOf(".createscript/packages/numbers", ".createscript/packages", ".createscript")) {
            val project = createProject(temp.resolve("linked-${link.replace('/', '-')}"))
            val path = project.resolve(link)
            Files.createDirectories(path.parent)
            Files.createSymbolicLink(path, if (link.endsWith("numbers")) outside.resolve("numbers") else outside)
            val failure = assertThrows(InstallFailure::class.java) { Installer(project, PackageFetcher(store, ArchiveCache(temp.resolve("cache"))), InstallLimits(), InstallHooks.NONE).install(spec, false) }
            assertEquals(InstallFailure.Kind.UNSAFE, failure.kind, link)
            assertTrue(failure.message!!.contains("is a symbolic link"), "$link: ${failure.message}")
            assertEquals(userFiles, outsideFiles(), link)
            assertFalse(Files.exists(project.resolve("createscript.lock")), link)
        }
    }

    // ---- 確かめた直後（移動の直前）に差し替える ----

    // 移動はリンクの先へ行く（防げない）。移動の後の確認で見つけ、成功扱いにしない。リンクの先のファイルは、置かれたものも含めて消さない
    @Test
    fun aSwapRightBeforeTheMoveIsFoundAfterIt() {
        var away: Path? = null
        val failure = assertThrows(InstallFailure::class.java) {
            installer({ if (it == "moving:numbers") away = swapToLink(numbersDir) }).install(spec, false)
        }
        assertNotInstalled(failure)
        val message = failure.message!!
        assertTrue(message.contains("numbers 3.0.0 was not found in .createscript/packages/numbers/3.0.0 after it was moved there"), message)
        assertTrue(message.contains("may have been written outside the project; nothing was removed there"), message)
        // 取り消しは、リンクの先を読まず・消さない
        assertTrue(message.contains(".createscript/packages/numbers/3.0.0 (not touched: .createscript/packages/numbers is a symbolic link"), message)
        // 外への書き込みは起きた（移動の前には防げない）が、利用者のファイルは変わらず、置かれたものも消していない
        assertEquals(userFiles, outsideFiles().filterKeys { !it.startsWith("3.0.0") })
        assertTrue(Files.exists(outside.resolve("3.0.0/.createscript-package.json")))
        assertTrue(Files.isDirectory(away!!))
    }

    // 差し替えて、移動の後にすぐ元のディレクトリに戻すもの（途中のディレクトリは、確かめるときには元のまま）も、置いた場所に無いので見つける
    @Test
    fun aSwapThatIsUndoneBeforeTheCheckIsFound() {
        var away: Path? = null
        val failure = assertThrows(InstallFailure::class.java) {
            installer({
                when (it) {
                    "moving:numbers" -> away = swapToLink(numbersDir)
                    "moved:numbers" -> {
                        Files.delete(numbersDir)
                        Files.move(away!!, numbersDir)
                    }
                }
            }).install(spec, false)
        }
        assertNotInstalled(failure)
        assertTrue(failure.message!!.contains(".createscript/packages/numbers/3.0.0 is not there"), failure.message)
        assertTrue(Files.exists(outside.resolve("3.0.0/.createscript-package.json")))
        assertEquals(userFiles, outsideFiles().filterKeys { !it.startsWith("3.0.0") })
    }

    // ---- 移動の後に差し替える ----

    // 移動の直後: 外への書き込みは起きない。移した先（パッケージを含む）にも触らない
    @Test
    fun aSwapRightAfterTheMoveIsFound() {
        var away: Path? = null
        val failure = assertThrows(InstallFailure::class.java) {
            installer({ if (it == "moved:numbers") away = swapToLink(numbersDir) }).install(spec, false)
        }
        assertNotInstalled(failure)
        assertTrue(failure.message!!.contains(".createscript/packages/numbers is a symbolic link"), failure.message)
        assertEquals(userFiles, outsideFiles())
        assertTrue(Files.exists(away!!.resolve("3.0.0/.createscript-package.json")))
    }

    // 移動を確かめた後（ほかのパッケージを置いている間）に差し替える: マニフェストを書き換える前の確認で見つける
    @Test
    fun aSwapAfterThePackageWasCheckedIsFoundBeforeTheManifestIsWritten() {
        for (step in listOf("package:numbers", "moved:vector-math", "placed")) {
            setUp()
            root = createProject(temp.resolve("late-${step.replace(':', '-')}"))
            originalManifest = Files.readAllBytes(root.resolve("createscript.json"))
            val failure = assertThrows(InstallFailure::class.java) {
                installer({ if (it == step) swapToLink(numbersDir) }).install(spec, false)
            }
            assertNotInstalled(failure)
            assertTrue(failure.message!!.contains("numbers 3.0.0 was not found in .createscript/packages/numbers/3.0.0"), "$step: ${failure.message}")
            assertEquals(userFiles, outsideFiles(), step)
        }
    }

    // ---- 中断の後の復旧 ----

    // 中断の後にリンクへ差し替えられていたら、復旧はリンクの先を読まず・書かずに止まる
    @Test
    fun recoveryStopsAtALinkedParent() {
        assertThrows(SimulatedCrash::class.java) {
            installer({ if (it == "package:numbers") throw SimulatedCrash(it) }).install(spec, false)
        }
        val away = swapToLink(numbersDir)
        val before = snapshot(root)
        val failure = assertThrows(InstallFailure::class.java) { installer().install(null, false) }
        assertTrue(failure.message!!.contains("cannot be finished or undone safely: .createscript/packages/numbers is a symbolic link"), failure.message)
        assertEquals(before, snapshot(root))
        assertEquals(userFiles, outsideFiles())
        assertTrue(Files.exists(away.resolve("3.0.0/.createscript-package.json")))
    }

    // 作業用のディレクトリがリンクに差し替えられたら、ジャーナルを公開せず、どこも消さない
    @Test
    fun aLinkedTransactionDirectoryIsNotPublishedOrRemoved() {
        var away: Path? = null
        val failure = assertThrows(InstallFailure::class.java) {
            installer({
                if (it == "staged") {
                    val txn = Files.list(root.resolve(".createscript")).use { s -> s.filter { p -> p.fileName.toString().startsWith("txn-") }.findFirst().orElseThrow() }
                    away = swapToLink(txn)
                }
            }).install(spec, false)
        }
        assertNotInstalled(failure)
        assertTrue(failure.message!!.contains("is a symbolic link (it points to ") && failure.message!!.contains("another program changed .createscript during the installation"), failure.message)
        assertEquals(userFiles, outsideFiles())
        assertTrue(Files.exists(away!!.resolve("journal.tmp")))
    }
}
