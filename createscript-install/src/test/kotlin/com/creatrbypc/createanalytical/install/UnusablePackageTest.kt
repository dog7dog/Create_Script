package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.Lockfiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// S8.2 L-4: 使えないパッケージのディレクトリ（中断・取り消しの後に編集されたものなど）を、次の install が知らせる。何も消さず・直さない
class UnusablePackageTest {
    @TempDir
    lateinit var temp: Path

    private val store = MemoryReleases()
    private val numbers = Packages.source("numbers")
    private val vectorMath = Packages.source("vector-math")
    private val spec = PackageSpec(vectorMath, "1.2.0")
    private lateinit var root: Path

    @BeforeEach
    fun setUp() {
        store.publish(numbers, "3.0.0", Packages.library("numbers", "3.0.0"))
        store.publish(numbers, "3.1.0", Packages.library("numbers", "3.1.0"))
        store.publish(vectorMath, "1.2.0", Packages.library("vector-math", "1.2.0", mapOf("numbers" to ("3.0.0" to numbers))))
        root = createProject(temp.resolve("app"))
    }

    private fun installer(hooks: InstallHooks = InstallHooks.NONE) = Installer(root, PackageFetcher(store, ArchiveCache(temp.resolve("cache"))), InstallLimits(), hooks)

    private fun packageDir(name: String, version: String): Path = root.resolve(".createscript/packages/$name/$version")

    // 置き場所の中身（パス → 種類・SHA-256・fileKey）
    private fun packagesState(): Map<String, String> = snapshot(root.resolve(".createscript/packages"))

    private fun scanWarnings(result: InstallResult) = result.warnings.filter { it.startsWith(".createscript/packages") }

    private fun assertWarnsAbout(warnings: List<String>, path: String, reason: String) {
        val warning = warnings.singleOrNull { it.startsWith(path) } ?: throw AssertionError("no warning for $path in $warnings")
        assertTrue(warning.contains(reason), warning)
        assertTrue(warning.contains("createscript does not use it and did not change it. If you edited files there, keep a copy of your changes; then remove the directory"), warning)
    }

    // ---- 正常なもの ----

    @Test
    fun noWarningForUsablePackages() {
        installer().install(spec, false)
        // もう一度（置き場所の 2 つとも使える）
        assertEquals(emptyList<String>(), installer().install(null, false).warnings)
    }

    // ロックファイルに無い古い版（正常なもの）は知らせない
    @Test
    fun noWarningForOldUsableVersions() {
        installer().install(PackageSpec(numbers, "3.0.0"), false)
        installer().install(PackageSpec(numbers, "3.1.0"), false)
        assertTrue(Files.isDirectory(packageDir("numbers", "3.0.0")))
        assertFalse(Files.readString(root.resolve("createscript.lock")).contains("\"3.0.0\""))
        assertEquals(emptyList<String>(), installer().install(null, false).warnings)
        // macOS の Finder が作る .DS_Store も見ない
        Files.writeString(root.resolve(".createscript/packages/.DS_Store"), "x")
        Files.writeString(root.resolve(".createscript/packages/numbers/.DS_Store"), "x")
        assertEquals(emptyList<String>(), installer().install(null, false).warnings)
    }

    // ---- 中断・取り消しの後に編集されたもの（L-4 の再現） ----

    @Test
    fun warnsAboutAPackageEditedAfterAnInterruptedInstallation() {
        assertThrows(SimulatedCrash::class.java) { installer({ if (it == "package:vector-math") throw SimulatedCrash(it) }).install(spec, false) }
        val edited = packageDir("vector-math", "1.2.0").resolve("modules/vector_math.cscr")
        Files.writeString(edited, "fun one(): Int {\n    return 42\n}\n")
        // 1 回目: 取り消して、編集したディレクトリを残したことを知らせて止まる（これまでどおり）
        val first = assertThrows(InstallFailure::class.java) { installer().install(null, false) }
        assertTrue(first.message!!.contains("undid an interrupted installation, but some files were changed"), first.message)
        val before = packagesState()
        // 2 回目（引数なし）: 以前は何も知らせずに成功していた。今は、そのディレクトリを知らせる
        val second = installer().install(null, false)
        assertWarnsAbout(scanWarnings(second), ".createscript/packages/vector-math/1.2.0", "modules/vector_math.cscr was changed")
        assertTrue(scanWarnings(second).single().endsWith("and run createscript install again if a project needs vector-math 1.2.0"), scanWarnings(second).toString())
        assertEquals(before, packagesState())
        assertEquals("fun one(): Int {\n    return 42\n}\n", Files.readString(edited))
        // そのパッケージを使う install は、これまでどおり触らずに止まる
        assertTrue(assertThrows(InstallFailure::class.java) { installer().install(spec, false) }.message!!.contains("Remove it by hand"))
        assertEquals(before, packagesState())
    }

    // ---- 印だけ・ソースだけの変更 ----

    @Test
    fun warnsAboutChangesOfTheMarkerOrOfTheSources() {
        installer().install(PackageSpec(numbers, "3.0.0"), false)
        installer().install(PackageSpec(numbers, "3.1.0"), false)
        val old = packageDir("numbers", "3.0.0")
        val markerPath = old.resolve(InstallLayout.MARKER)
        val original = Files.readAllBytes(markerPath)
        val marker = Marker.parse(original)!!
        val source = old.resolve("modules/numbers.cscr")
        val sourceBytes = Files.readAllBytes(source)
        val forged = "fun one(): Int {\n    return 7\n}\n".toByteArray()
        val cases = linkedMapOf<String, Pair<() -> Any?, String>>(
            // 印だけ
            "marker damaged" to ({ Files.writeString(markerPath, "{") } to "its .createscript-package.json is damaged"),
            "marker removed" to ({ Files.delete(markerPath) } to "it has no .createscript-package.json"),
            "marker file hash" to ({ Files.write(markerPath, marker.copy(files = marker.files + ("LICENSE" to "0".repeat(64))).write()) } to "LICENSE was changed"),
            "marker archive" to ({ Files.write(markerPath, marker.copy(sha256 = "0".repeat(64)).write()) } to "its files do not make the archive named in .createscript-package.json"),
            "marker version" to ({ Files.write(markerPath, marker.copy(version = "9.9.9").write()) } to "its .createscript-package.json names numbers 9.9.9"),
            // ソースだけ
            "source" to ({ Files.write(source, forged) } to "modules/numbers.cscr was changed"),
            // 印とソースを同じように書き換えたもの（M-1）
            "source and marker" to ({
                Files.write(source, forged)
                Files.write(markerPath, marker.copy(files = marker.files + ("modules/numbers.cscr" to SafeIo.sha256Hex(forged))).write())
            } to "its files do not make the archive named in .createscript-package.json"),
            "extra file" to ({ Files.writeString(old.resolve("notes.txt"), "mine") } to "it has a file that was not installed: notes.txt"),
        )
        for ((label, case) in cases) {
            val (change, reason) = case
            change()
            val before = packagesState()
            val result = installer().install(null, false)
            assertWarnsAbout(scanWarnings(result), ".createscript/packages/numbers/3.0.0", reason)
            assertEquals(1, scanWarnings(result).size, "$label: ${result.warnings}")
            assertEquals(before, packagesState(), label)
            // 元に戻す（次の場合のため）
            Files.write(markerPath, original)
            Files.write(source, sourceBytes)
            Files.deleteIfExists(old.resolve("notes.txt"))
            assertEquals(emptyList<String>(), installer().install(null, false).warnings, "$label: restored")
        }
    }

    // ---- シンボリックリンク・正しくない名前 ----

    @Test
    fun linksAreReportedButNotFollowed() {
        installer().install(spec, false)
        val outside = Files.createDirectories(temp.resolve("outside"))
        Files.writeString(outside.resolve("precious.txt"), "user data")
        // パッケージのディレクトリがリンク（ロックファイルに無い版の場所）
        Files.createSymbolicLink(root.resolve(".createscript/packages/numbers/3.1.0"), outside)
        // packages/<name> がリンク
        Files.createSymbolicLink(root.resolve(".createscript/packages/linked"), outside)
        val outsideBefore = snapshot(outside)
        val result = installer().install(null, false)
        val warnings = scanWarnings(result)
        assertWarnsAbout(warnings, ".createscript/packages/numbers/3.1.0", "it is a symbolic link")
        assertWarnsAbout(warnings, ".createscript/packages/linked", "is a symbolic link; it was not followed")
        assertEquals(2, warnings.size, warnings.toString())
        assertEquals(outsideBefore, snapshot(outside))
        assertTrue(Files.isSymbolicLink(root.resolve(".createscript/packages/linked")))
        // packages そのものがリンク（パッケージを使わない install は続ける）
        val other = createProject(temp.resolve("other"))
        Files.createDirectories(other.resolve(".createscript"))
        Files.createSymbolicLink(other.resolve(".createscript/packages"), outside)
        val plain = Installer(other, PackageFetcher(store, ArchiveCache(temp.resolve("cache")))).install(null, false)
        assertTrue(plain.warnings.single().startsWith(".createscript/packages is a symbolic link; it was not followed"), plain.warnings.toString())
        assertEquals(outsideBefore, snapshot(outside))
    }

    // ---- 複数の問題 ----

    @Test
    fun reportsSeveralProblemsAtOnce() {
        installer().install(PackageSpec(numbers, "3.0.0"), false)
        installer().install(PackageSpec(numbers, "3.1.0"), false)
        // ロックファイルに無い古い版（numbers 3.0.0）を壊し、正しくない名前のディレクトリとファイルを置く
        Files.writeString(packageDir("numbers", "3.0.0").resolve("LICENSE"), "changed")
        Files.createDirectories(root.resolve(".createscript/packages/Bad_Name/1.0.0"))
        Files.createDirectories(root.resolve(".createscript/packages/numbers/not-a-version"))
        Files.writeString(root.resolve(".createscript/packages/stray.txt"), "x")
        val before = packagesState()
        val result = installer().install(null, false)
        val warnings = scanWarnings(result)
        assertEquals(4, warnings.size, warnings.toString())
        assertWarnsAbout(warnings, ".createscript/packages/numbers/3.0.0", "LICENSE was changed")
        assertWarnsAbout(warnings, ".createscript/packages/Bad_Name", "'Bad_Name' is not a valid package name")
        assertWarnsAbout(warnings, ".createscript/packages/numbers/not-a-version", "'not-a-version' is not a valid version")
        assertWarnsAbout(warnings, ".createscript/packages/stray.txt", "is not a package directory")
        assertEquals(before, packagesState())
    }

    // 確かめられないもの（読めないディレクトリ）でも、インストールは失敗しない
    @Test
    fun aDirectoryThatCannotBeReadDoesNotFailTheInstallation() {
        installer().install(PackageSpec(numbers, "3.0.0"), false)
        installer().install(PackageSpec(numbers, "3.1.0"), false)
        val locked = packageDir("numbers", "3.0.0")
        Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("---------"))
        try {
            val result = installer().install(null, false)
            assertTrue(scanWarnings(result).isNotEmpty(), result.warnings.toString())
            val lockfile = Lockfiles.parse(Files.readAllBytes(root.resolve("createscript.lock"))).lockfile!!
            assertEquals("3.1.0", InstalledPackages.verify(root, lockfile).single().version)
        } finally {
            Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }
}
