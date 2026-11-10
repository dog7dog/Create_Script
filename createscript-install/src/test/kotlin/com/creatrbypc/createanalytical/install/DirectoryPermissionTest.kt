package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.Lockfiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

// S8.2 L-5 の案 C: ほかのユーザーが書き換えられるディレクトリでは、インストールしない。作るディレクトリ・ファイルは、所有者だけが書き込める
class DirectoryPermissionTest {
    @TempDir
    lateinit var temp: Path

    private val store = MemoryReleases()
    private val numbers = Packages.source("numbers")
    private val vectorMath = Packages.source("vector-math")
    private val spec = PackageSpec(vectorMath, "1.2.0")

    @BeforeEach
    fun setUp() {
        store.publish(numbers, "3.0.0", Packages.library("numbers", "3.0.0"))
        store.publish(vectorMath, "1.2.0", Packages.library("vector-math", "1.2.0", mapOf("numbers" to ("3.0.0" to numbers))))
    }

    private fun installer(root: Path, fileSystem: InstallFileSystem = InstallFileSystem.DEFAULT, hooks: InstallHooks = InstallHooks.NONE) =
        Installer(root, PackageFetcher(store, ArchiveCache(temp.resolve("cache"))), InstallLimits(), hooks, DirectoryKeys.FILE_KEY, fileSystem)

    private fun mode(path: Path): Int = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int and 0b111_111_111_111

    // sticky ビットは PosixFilePermission では付けられないので、テストだけ chmod を使う
    private fun chmod(mode: String, path: Path) {
        val process = ProcessBuilder("chmod", mode, path.toString()).redirectErrorStream(true).start()
        assertEquals(0, process.waitFor(), process.inputStream.bufferedReader().readText())
    }

    private fun refused(root: Path, fileSystem: InstallFileSystem = InstallFileSystem.DEFAULT): String {
        val failure = assertThrows(InstallFailure::class.java) { installer(root, fileSystem).install(spec, false) }
        assertEquals(InstallFailure.Kind.UNSAFE, failure.kind, failure.message)
        assertTrue(failure.message!!.endsWith("Nothing was changed"), failure.message)
        return failure.message!!
    }

    // ---- 正常なもの ----

    // 所有者だけが書き込めるディレクトリ。作ったディレクトリ・ファイルは、グループ・ほかのユーザーが書き込めない
    @Test
    fun installsInADirectoryThatOnlyTheOwnerCanChange() {
        val root = createProject(temp.resolve("app"))
        assertEquals(2, installer(root).install(spec, false).packages.size)
        Files.walk(root.resolve(".createscript")).use { s ->
            for (path in s) assertEquals(0, mode(path) and 0b000_010_010, "$path ${Integer.toOctalString(mode(path))}")
        }
        val lockfile = Lockfiles.parse(Files.readAllBytes(root.resolve("createscript.lock"))).lockfile!!
        assertEquals(2, InstalledPackages.verify(root, lockfile).size)
    }

    // ---- ほかのユーザーが書き込めるもの: 拒否し、権限は変えない ----

    @Test
    fun refusesGroupAndWorldWritableProjects() {
        for (permissions in listOf("775", "757", "777")) {
            val root = createProject(temp.resolve("app-$permissions"))
            chmod(permissions, root)
            val message = refused(root)
            assertTrue(message.contains("${root.toRealPath()} can be changed by other users (permissions ") && message.contains("chmod go-w '${root.toRealPath()}'"), message)
            assertEquals(Integer.parseInt(permissions, 8), mode(root), "the permissions were not changed")
            assertFalse(Files.exists(root.resolve(".createscript")), "nothing was created")
            chmod("755", root)
        }
        // 親のディレクトリが書き込める
        val parent = Files.createDirectories(temp.resolve("shared"))
        val root = createProject(parent.resolve("app"))
        chmod("777", parent)
        assertTrue(refused(root).contains("${parent.toRealPath()} can be changed by other users (permissions rwxrwxrwx)"))
        assertEquals(0b111_111_111, mode(parent))
        assertFalse(Files.exists(root.resolve(".createscript")))
        chmod("755", parent)
    }

    // .createscript の下（以前の umask で作られたものなど）。権限は変えない
    @Test
    fun refusesWritableDirectoriesInsideTheStateDirectory() {
        val root = createProject(temp.resolve("app"))
        installer(root).install(spec, false)
        val numbersDir = root.resolve(".createscript/packages/numbers")
        chmod("775", numbersDir)
        val before = snapshot(root)
        val message = refused(root)
        assertTrue(message.contains("${numbersDir.toRealPath()} can be changed by other users (permissions rwxrwxr-x)"), message)
        assertEquals(0b111_111_101, mode(numbersDir))
        assertEquals(before, snapshot(root))
        // run の前の確認も、同じ規則で使わない
        val lockfile = Lockfiles.parse(Files.readAllBytes(root.resolve("createscript.lock"))).lockfile!!
        val runFailure = assertThrows(InstallFailure::class.java) { InstalledPackages.verify(root, lockfile) }
        assertEquals(InstallFailure.Kind.INVALID, runFailure.kind)
        assertTrue(runFailure.message!!.contains("can be changed by other users") && runFailure.message!!.endsWith("The packages were not used"), runFailure.message)
        chmod("755", numbersDir)
        assertEquals(2, InstalledPackages.verify(root, lockfile).size)
    }

    // ---- sticky ビット ----

    @Test
    fun stickyDirectoriesProtectEntriesOfTheirOwner() {
        // /tmp と同じ: 全員が書き込めるが sticky ビットがあり、プロジェクトはこのユーザーのもの
        val shared = Files.createDirectories(temp.resolve("sticky"))
        val root = createProject(shared.resolve("app"))
        chmod("1777", shared)
        assertEquals(2, installer(root).install(spec, false).packages.size)
        assertEquals(0b001_111_111_111, mode(shared))
        // プロジェクトのルートそのものが sticky で全員が書き込める: .createscript が無ければ、ほかのユーザーが先に作れるので拒否
        val stickyRoot = createProject(temp.resolve("sticky-root"))
        chmod("1777", stickyRoot)
        assertTrue(refused(stickyRoot).contains("can be changed by other users (permissions rwxrwxrwt)"))
        assertFalse(Files.exists(stickyRoot.resolve(".createscript")))
        // .createscript がこのユーザーのもので既にあれば、ほかのユーザーは差し替えられない
        chmod("755", stickyRoot)
        Files.createDirectory(stickyRoot.resolve(".createscript"))
        chmod("1777", stickyRoot)
        assertEquals(2, installer(stickyRoot).install(spec, false).packages.size)
        chmod("755", shared)
        chmod("755", stickyRoot)
    }

    // ---- シンボリックリンク ----

    // リンクを通して指定したプロジェクトは、実パスの上のディレクトリを確かめる。リンクそのもの・リンクの先の権限は変えない
    @Test
    fun linksOnTheWayAreResolvedToTheRealPath() {
        val real = Files.createDirectories(temp.resolve("real"))
        val link = Files.createSymbolicLink(temp.resolve("link"), real)
        val root = createProject(real.resolve("app"))
        assertEquals(2, installer(link.resolve("app")).install(spec, false).packages.size)
        // 実際の親が書き込めるなら、リンクを通しても拒否する（表示は実パス）
        val other = createProject(real.resolve("other"))
        chmod("777", real)
        assertTrue(refused(link.resolve("other")).contains("${real.toRealPath()} can be changed by other users"))
        assertEquals(0b111_111_111, mode(real))
        chmod("755", real)
        assertTrue(Files.isSymbolicLink(link))
        assertTrue(Files.exists(root.resolve("createscript.lock")) && !Files.exists(other.resolve("createscript.lock")))
        // .createscript の下のリンクはたどらない（たどった先が全員の書き込めるディレクトリでも、権限の確認はそれを見ない。リンクは install の別の確認が拒否する）
        val outside = Files.createDirectories(temp.resolve("outside"))
        chmod("777", outside)
        Files.createSymbolicLink(root.resolve(".createscript/linked"), outside)
        assertEquals(null, DirectoryPermissions(PermissionReader.DEFAULT, DirectoryPermissions.currentUser()).treeProblem(root.resolve(".createscript").toRealPath()))
        assertEquals(0b111_111_111, mode(outside))
        chmod("755", outside)
    }

    // ---- macOS の ACL（限界: 読めないので確かめない） ----

    @Test
    fun aclsAreNotDetected() {
        assumeTrue(System.getProperty("os.name").lowercase().startsWith("mac"), "chmod +a is macOS only")
        val root = createProject(temp.resolve("acl"))
        val process = ProcessBuilder("chmod", "+a", "everyone allow add_file,add_subdirectory,delete_child", root.toString()).redirectErrorStream(true).start()
        assertEquals(0, process.waitFor(), process.inputStream.bufferedReader().readText())
        // POSIX の権限は所有者だけが書き込めるので、インストールは行う（ACL がほかのユーザーに書き込みを許していることは、Java 21 では分からない。docs/packages.md）
        assertEquals(2, installer(root).install(spec, false).packages.size)
        val list = ProcessBuilder("ls", "-led", root.toString()).redirectErrorStream(true).start()
        val text = list.inputStream.bufferedReader().readText()
        list.waitFor()
        assertTrue(text.contains("everyone allow add_file,add_subdirectory,delete_child"), "the ACL was not changed: $text")
    }

    // ---- 確かめられないもの ----

    @Test
    fun refusesWhenThePermissionsOrTheUserCannotBeRead() {
        val root = createProject(temp.resolve("app"))
        assertTrue(refused(root, InstallFileSystem(permissions = { null })).contains("the owner and permissions of / cannot be read, so createscript cannot confirm that no other user can change it"))
        assertTrue(refused(root, InstallFileSystem(currentUser = { null })).contains("the current user cannot be determined"))
        // ほかのユーザーのディレクトリ（テストでは作れないので、読んだ所有者を変える）
        val foreign = PermissionReader { path -> PermissionReader.DEFAULT.read(path)?.let { if (path == root.toRealPath()) DirectoryAttributes("someone", it.mode, it.symbolicLink, it.directory) else it } }
        assertTrue(refused(root, InstallFileSystem(permissions = foreign)).contains("${root.toRealPath()} belongs to the user someone"))
        assertFalse(Files.exists(root.resolve(".createscript")))
        assertEquals(2, installer(root).install(spec, false).packages.size)
    }

    // ---- 中断の後の復旧 ----

    // 権限が安全でなければ、復旧も行わない（ジャーナルはそのまま）。直せば復旧する
    @Test
    fun recoveryWaitsForSafePermissions() {
        val root = createProject(temp.resolve("app"))
        assertThrows(SimulatedCrash::class.java) { installer(root, hooks = { if (it == "package:numbers") throw SimulatedCrash(it) }).install(spec, false) }
        chmod("777", root.resolve(".createscript/packages"))
        val before = snapshot(root)
        assertTrue(refused(root).contains("can be changed by other users"))
        assertEquals(before, snapshot(root))
        assertTrue(Files.exists(root.resolve(".createscript/transaction.json")))
        chmod("755", root.resolve(".createscript/packages"))
        assertEquals("completed an interrupted installation", installer(root).install(null, false).recovery)
    }
}
