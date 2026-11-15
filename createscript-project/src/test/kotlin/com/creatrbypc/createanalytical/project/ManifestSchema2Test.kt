package com.creatrbypc.createanalytical.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

// S8.2 Phase 1: createscript.json の schemaVersion 2（kind・modules）
class ManifestSchema2Test {
    private fun parse(text: String) = Manifests.parse(text.toByteArray())

    private fun codes(text: String): List<ProjectDiagnosticCode> = parse(text).let { assertNull(it.manifest, text); it.diagnostics.map { d -> d.code } }

    private val library = """{"schemaVersion": 2, "kind": "library", "name": "vector-math", "version": "1.2.0", "modules": ["vector_math", "matrix_ops"]}"""

    @Test
    fun readsLibrariesAndApplications() {
        val manifest = parse(library).manifest!!
        assertEquals(PackageKind.LIBRARY, manifest.kind)
        assertEquals(2, manifest.schemaVersion)
        assertNull(manifest.entry)
        // modules は名前の順
        assertEquals(listOf("matrix_ops", "vector_math"), manifest.modules)
        // library にも entry を書ける（例・試しの実行に使う）
        assertNotNull(parse(library.replace("\"modules\"", "\"entry\": \"src/demo.cscr\", \"modules\"")).manifest)
        val application = parse("""{"schemaVersion": 2, "kind": "application", "name": "app", "version": "1.0.0", "entry": "src/main.cscr"}""").manifest!!
        assertEquals(PackageKind.APPLICATION, application.kind)
        assertEquals(emptyList<String>(), application.modules)
        // schemaVersion 1 は今までどおり（application）
        val first = parse("""{"schemaVersion": 1, "name": "app", "version": "1.0.0", "entry": "src/main.cscr"}""").manifest!!
        assertEquals(1, first.schemaVersion)
        assertEquals(PackageKind.APPLICATION, first.kind)
    }

    @Test
    fun checksKindAndModules() {
        // kind は schemaVersion 2 で必須。application・library だけ
        assertEquals(listOf(ProjectDiagnosticCode.MISSING_FIELD), codes(library.replace("\"kind\": \"library\", ", "")))
        assertEquals(listOf(ProjectDiagnosticCode.INVALID_KIND), codes(library.replace("\"library\"", "\"plugin\"")))
        assertEquals(listOf(ProjectDiagnosticCode.WRONG_TYPE), codes(library.replace("\"library\"", "1")))
        // schemaVersion 1 には kind・modules を書けない（知らない項目）
        assertEquals(listOf(ProjectDiagnosticCode.UNKNOWN_FIELD, ProjectDiagnosticCode.UNKNOWN_FIELD), codes(library.replace("\"schemaVersion\": 2", "\"schemaVersion\": 1").replace("\"name\"", "\"entry\": \"m.cscr\", \"name\"")))
        // application の modules・entry の無い application
        assertEquals(listOf(ProjectDiagnosticCode.FIELD_NOT_ALLOWED), codes(library.replace("\"library\"", "\"application\"").replace("\"name\"", "\"entry\": \"m.cscr\", \"name\"")))
        assertEquals(listOf(ProjectDiagnosticCode.MISSING_FIELD), codes("""{"schemaVersion": 2, "kind": "application", "name": "app", "version": "1.0.0"}"""))
        // library の modules: 必須・空でない・配列・重複なし
        assertEquals(listOf(ProjectDiagnosticCode.MISSING_FIELD), codes("""{"schemaVersion": 2, "kind": "library", "name": "lib", "version": "1.0.0"}"""))
        assertEquals(listOf(ProjectDiagnosticCode.NO_MODULES), codes(library.replace("[\"vector_math\", \"matrix_ops\"]", "[]")))
        assertEquals(listOf(ProjectDiagnosticCode.WRONG_TYPE), codes(library.replace("[\"vector_math\", \"matrix_ops\"]", "\"vector_math\"")))
        assertEquals(listOf(ProjectDiagnosticCode.WRONG_TYPE), codes(library.replace("\"matrix_ops\"", "7")))
        assertEquals(listOf(ProjectDiagnosticCode.DUPLICATE_MODULE), codes(library.replace("\"matrix_ops\"", "\"vector_math\"")))
        // 知らない項目は、schemaVersion 2 でもエラー
        assertEquals(listOf(ProjectDiagnosticCode.UNKNOWN_FIELD), codes(library.replace("\"modules\"", "\"exports\": [], \"modules\"")))
    }

    // import のモジュール名: Core の識別子で、キーワード・組み込み関数・_ だけの名前でなく、32 文字まで。パッケージ名とは別で、ハイフンは使えない（自動で変換しない）
    @Test
    fun checksModuleNames() {
        for (name in listOf("a", "_x", "vector_math2", "V2", "x".repeat(32), "import", "sqrt", "random")) {
            assertNotNull(parse(library.replace("\"matrix_ops\"", "\"$name\"")).manifest, name)
        }
        for (name in listOf("vector-math", "1x", "", "_", "__", "fun", "class", "println", "waitTicks", "listOf", "mutableListOf", "x".repeat(33), "é", "a b", "a.b")) {
            assertEquals(listOf(ProjectDiagnosticCode.INVALID_MODULE_NAME), codes(library.replace("\"matrix_ops\"", "\"$name\"")), name)
        }
    }

    @Test
    fun writesCanonicalSchema2() {
        val manifest = parse(library.replace("\"version\": \"1.2.0\"", "\"version\": \"1.2.0\", \"license\": \"MIT\", \"dependencies\": {\"numbers\": {\"source\": \"github:o/numbers\", \"version\": \"3.0.0\"}}")).manifest!!
        val expected = """
            {
              "schemaVersion": 2,
              "kind": "library",
              "name": "vector-math",
              "version": "1.2.0",
              "license": "MIT",
              "modules": [
                "matrix_ops",
                "vector_math"
              ],
              "dependencies": {
                "numbers": {
                  "version": "3.0.0",
                  "source": "github:o/numbers"
                }
              }
            }

        """.trimIndent()
        assertEquals(expected, Manifests.write(manifest))
        assertEquals(manifest, parse(Manifests.write(manifest)).manifest)
        // schemaVersion 1 の書き出しは S8.1 と同じ
        assertEquals(
            "{\n  \"schemaVersion\": 1,\n  \"name\": \"hello\",\n  \"version\": \"0.1.0\",\n  \"entry\": \"src/main.cscr\",\n  \"dependencies\": {}\n}\n",
            Manifests.write(Manifest("hello", "0.1.0", "src/main.cscr")),
        )
    }

    @Test
    fun modelRejectsInconsistentValues() {
        assertThrows(IllegalArgumentException::class.java) { Manifest("a", "1.0.0", null) }
        assertThrows(IllegalArgumentException::class.java) { Manifest("a", "1.0.0", null, schemaVersion = 1, kind = PackageKind.LIBRARY, modules = listOf("a")) }
        assertThrows(IllegalArgumentException::class.java) { Manifest("a", "1.0.0", null, schemaVersion = 2, kind = PackageKind.LIBRARY) }
        assertThrows(IllegalArgumentException::class.java) { Manifest("a", "1.0.0", "m.cscr", schemaVersion = 2, modules = listOf("a")) }
        assertThrows(IllegalArgumentException::class.java) { Manifest("a", "1.0.0", null, schemaVersion = 2, kind = PackageKind.LIBRARY, modules = listOf("b", "a")) }
        assertThrows(IllegalArgumentException::class.java) { Manifest("a", "1.0.0", null, schemaVersion = 2, kind = PackageKind.LIBRARY, modules = listOf("fun")) }
        assertThrows(IllegalArgumentException::class.java) { Manifest("a", "1.0.0", "m.cscr", schemaVersion = 3) }
    }
}
