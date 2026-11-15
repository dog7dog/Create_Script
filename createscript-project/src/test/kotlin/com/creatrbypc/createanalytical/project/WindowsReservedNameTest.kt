package com.creatrbypc.createanalytical.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// S8.2 L-7: Windows の予約名（CON・PRN・AUX・NUL・COM0〜COM9・LPT0〜LPT9）を、パッケージ名・モジュール名・エントリーのパス・アーカイブの中のパスに使えない
class WindowsReservedNameTest {
    private val reserved = listOf("con", "prn", "aux", "nul") + (0..9).flatMap { listOf("com$it", "lpt$it") }

    private fun variants(name: String) = listOf(name, name.uppercase(), name.replaceFirstChar { it.uppercase() }, name.mapIndexed { i, c -> if (i % 2 == 1) c.uppercaseChar() else c }.joinToString(""))

    @Test
    fun allReservedNamesInAnyCaseAndWithAnyExtension() {
        assertEquals(24, reserved.size)
        for (name in reserved) {
            for (form in variants(name)) {
                for (written in listOf(form, "$form.cscr", "$form.txt", "$form.tar.gz", "$form.", "$form ", "$form . txt", "$form..")) {
                    assertTrue(ProjectRules.isWindowsReserved(written), "<$written>")
                }
            }
        }
        // 予約名ではないもの（予約名で始まる・続く・数字が 2 桁・区切りが . でない）
        for (name in listOf("console", "connect", "conf", "aux2", "auxiliary", "nul1", "null_", "prnt", "com", "com10", "lpt", "lpt10", "comx", "con-x", "con_x", "xcon", "my.con", "a.nul", "vector_math", "numbers")) {
            assertFalse(ProjectRules.isWindowsReserved(name), name)
        }
    }

    @Test
    fun packageNames() {
        for (name in reserved) {
            assertFalse(ProjectRules.isValidName(name), name)
            assertEquals(ProjectDiagnosticCode.RESERVED_NAME, ProjectRules.nameCode(name, ProjectRules.isValidName(name), ProjectDiagnosticCode.INVALID_NAME), name)
        }
        for (name in listOf("console", "com10", "lpt-utils", "aux-tools", "nul-safe", "vector-math")) assertTrue(ProjectRules.isValidName(name), name)
        // 予約名でない正しくない名前は、これまでどおり INVALID_NAME
        assertEquals(ProjectDiagnosticCode.INVALID_NAME, ProjectRules.nameCode("Vector", false, ProjectDiagnosticCode.INVALID_NAME))
    }

    @Test
    fun moduleNames() {
        for (name in reserved) for (form in variants(name)) assertFalse(ProjectRules.isValidModuleName(form), form)
        for (name in listOf("console", "Com10", "LPT_utils", "aux_tools", "Nul2", "vector_math")) assertTrue(ProjectRules.isValidModuleName(name), name)
    }

    @Test
    fun entryPaths() {
        for (path in listOf("con.cscr", "src/AUX.cscr", "Nul/main.cscr", "src/com0.cscr", "src/LPT9.cscr", "src/prn.backup.cscr", "lpt0/x/main.cscr")) {
            val problem = ProjectRules.pathProblem(path)
            assertNotNull(problem, path)
            assertTrue(problem!!.contains("is a reserved name on Windows"), "$path: $problem")
        }
        for (path in listOf("src/main.cscr", "src/console.cscr", "com10/main.cscr", "src/nul_safe.cscr")) assertNull(ProjectRules.pathProblem(path), path)
    }

    // ---- マニフェスト・ロックファイル ----

    private val application = """{"schemaVersion": 1, "name": "app", "version": "0.1.0", "entry": "src/main.cscr", "dependencies": {"numbers": {"version": "3.0.0", "source": "github:owner/numbers"}}}"""
    private val library = """{"schemaVersion": 2, "kind": "library", "name": "vector-math", "version": "1.2.0", "modules": ["vector_math"]}"""

    private fun manifestCodes(text: String) = Manifests.parse(text.toByteArray()).let { assertNull(it.manifest, text); it.diagnostics }

    @Test
    fun manifestsReportTheReservedName() {
        for (name in listOf("con", "com1", "lpt0")) {
            val diagnostics = manifestCodes(application.replace("\"name\": \"app\"", "\"name\": \"$name\""))
            assertEquals(listOf(ProjectDiagnosticCode.RESERVED_NAME), diagnostics.map { it.code }, name)
            assertEquals(listOf(name), diagnostics.single().arguments)
            assertEquals(listOf(ProjectDiagnosticCode.RESERVED_NAME), manifestCodes(application.replace("\"numbers\":", "\"$name\":").replace("owner/numbers", "owner/$name")).map { it.code }, name)
        }
        for (module in listOf("con", "CON", "Aux", "nul", "COM9", "Lpt3")) {
            val diagnostics = manifestCodes(library.replace("\"vector_math\"", "\"$module\""))
            assertEquals(listOf(ProjectDiagnosticCode.RESERVED_NAME), diagnostics.map { it.code }, module)
            assertEquals("createscript.json:1:${library.indexOf("\"vector_math\"") + 1}: error CS_PKG_RESERVED_NAME (\"$module\")", diagnostics.single().format("createscript.json"))
        }
        val path = manifestCodes(application.replace("src/main.cscr", "src/con.cscr")).single()
        assertEquals(ProjectDiagnosticCode.INVALID_PATH, path.code)
        assertTrue(path.arguments[1].contains("'con.cscr' is a reserved name on Windows"), path.arguments.toString())
        // 正しい名前は、これまでどおり
        assertNotNull(Manifests.parse(application.toByteArray()).manifest)
        assertNotNull(Manifests.parse(library.replace("\"vector_math\"", "\"console\"").toByteArray()).manifest)
    }

    @Test
    fun lockfilesReportTheReservedName() {
        val sha = "a".repeat(64)
        val lock = Lockfiles.write(Lockfile.of(LockRoot("app", "0.1.0"), listOf(LockedPackage("numbers", "3.0.0", "github:owner/numbers", sha, emptyList()))))
        assertNotNull(Lockfiles.parse(lock.toByteArray()).lockfile)
        for ((label, text) in mapOf(
            "root" to lock.replaceFirst("\"name\": \"app\"", "\"name\": \"aux\""),
            "package" to lock.replace("\"name\": \"numbers\"", "\"name\": \"nul\""),
        )) {
            val parsed = Lockfiles.parse(text.toByteArray())
            assertNull(parsed.lockfile, label)
            assertTrue(parsed.diagnostics.any { it.code == ProjectDiagnosticCode.RESERVED_NAME }, "$label: ${parsed.diagnostics.map { it.format("createscript.lock") }}")
        }
    }

    // ---- アーカイブ（ZIP のパスでの回避を含む） ----

    private val reservedLibrary = """{"schemaVersion": 2, "kind": "library", "name": "tools", "version": "1.0.0", "modules": ["tools"]}"""
    private val source = "fun one(): Int {\n    return 1\n}\n".toByteArray()

    private fun read(vararg entries: Pair<String, ByteArray>): PackageArchives.ArchiveResult {
        val builder = ZipBuilder()
        for ((name, data) in entries) builder.add(name, data)
        return PackageArchives.read(builder.build())
    }

    @Test
    fun archivesRefuseReservedModuleFilesInEveryForm() {
        val manifest = "createscript.json" to reservedLibrary.toByteArray()
        val tools = "modules/tools.cscr" to source
        assertNotNull(read(manifest, tools).archive)
        for (path in listOf(
            "modules/con.cscr", "modules/CON.cscr", "modules/Aux.cscr", "modules/nul.cscr", "modules/com0.cscr", "modules/COM1.cscr", "modules/lpt9.cscr",
            // 区切り・拡張子・空白・. による回避
            "modules/con.txt.cscr", "modules/con .cscr", "modules/con..cscr", "modules/con.cscr.", "modules/./con.cscr", "modules//con.cscr",
            "./modules/con.cscr", "modules\\con.cscr", "modules/sub/con.cscr", "MODULES/con.cscr", "con.cscr", "modules/con", "modules/c%6Fn.cscr",
            "modules/con\u0000.cscr", "modules/con:x.cscr", "nul", "NUL.md", "aux.txt",
        )) {
            val result = read(manifest, tools, path to source)
            assertNull(result.archive, path)
            val codes = result.diagnostics.map { it.diagnostic.code }
            assertTrue(ProjectDiagnosticCode.ARCHIVE_INVALID_PATH in codes, "$path: ${result.diagnostics.map { it.diagnostic.format(it.path ?: "archive") }}")
        }
        // 予約名の理由を示す
        val reason = read(manifest, tools, "modules/Com1.cscr" to source).diagnostics.single().diagnostic
        assertTrue(reason.arguments.last().contains("'Com1' is a reserved name on Windows"), reason.arguments.toString())
        // マニフェストの modules に予約名
        val listed = read("createscript.json" to reservedLibrary.replace("\"tools\"]", "\"nul\"]").toByteArray(), "modules/nul.cscr" to source)
        assertNull(listed.archive)
        assertTrue(listed.diagnostics.any { it.diagnostic.code == ProjectDiagnosticCode.ARCHIVE_INVALID_PATH || it.diagnostic.code == ProjectDiagnosticCode.RESERVED_NAME }, listed.diagnostics.toString())
        // パッケージ名が予約名
        val named = read("createscript.json" to reservedLibrary.replace("\"name\": \"tools\"", "\"name\": \"prn\"").toByteArray(), tools)
        assertNull(named.archive)
        assertTrue(named.diagnostics.any { it.diagnostic.code == ProjectDiagnosticCode.RESERVED_NAME }, named.diagnostics.map { it.diagnostic.format(it.path ?: "archive") }.toString())
    }
}
