package com.creatrbypc.createanalytical.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// S8.1: createscript.json（schemaVersion 1）の読み取り・検査・書き出し
class ManifestTest {
    private fun parse(text: String) = Manifests.parse(text.toByteArray(Charsets.UTF_8))

    private val example = """
        {
          "schemaVersion": 1,
          "name": "example-project",
          "version": "0.1.0",
          "entry": "src/main.cscr",
          "license": "MIT",
          "dependencies": {
            "vector-math": { "version": "1.2.0", "source": "github:owner/vector-math" },
            "geometry": { "source": "github:Some-Owner/geometry.cs", "version": "2.0.0-beta.1+build.5" }
          }
        }
    """.trimIndent()

    // 1 つの項目だけを書き換えた例
    private fun with(field: String, value: String) = Regex("\"$field\": [^,\\n]+").replaceFirst(example, Regex.escapeReplacement("\"$field\": $value"))

    private fun codes(text: String): List<ProjectDiagnosticCode> {
        val result = parse(text)
        assertNull(result.manifest, text)
        return result.diagnostics.map { it.code }
    }

    @Test
    fun readsTheExample() {
        val result = parse(example)
        val manifest = result.manifest ?: error("${result.diagnostics}")
        assertEquals("example-project", manifest.name)
        assertEquals("0.1.0", manifest.version)
        assertEquals("src/main.cscr", manifest.entry)
        assertEquals("MIT", manifest.license)
        // 依存は名前の順
        assertEquals(
            listOf(Dependency("geometry", "2.0.0-beta.1+build.5", "github:Some-Owner/geometry.cs"), Dependency("vector-math", "1.2.0", "github:owner/vector-math")),
            manifest.dependencies,
        )
        assertEquals(5 to 12, result.entryPosition)
        // license・dependencies は省略できる
        val minimal = parse("""{"schemaVersion": 1, "name": "a", "version": "1.0.0", "entry": "main.cscr"}""").manifest!!
        assertNull(minimal.license)
        assertEquals(emptyList<Dependency>(), minimal.dependencies)
    }

    // 書き出しは決まった形で、読み直すと同じ内容になる
    @Test
    fun writesCanonicalManifests() {
        val manifest = parse(example).manifest!!
        val text = Manifests.write(manifest)
        val expected = """
            {
              "schemaVersion": 1,
              "name": "example-project",
              "version": "0.1.0",
              "entry": "src/main.cscr",
              "license": "MIT",
              "dependencies": {
                "geometry": {
                  "version": "2.0.0-beta.1+build.5",
                  "source": "github:Some-Owner/geometry.cs"
                },
                "vector-math": {
                  "version": "1.2.0",
                  "source": "github:owner/vector-math"
                }
              }
            }

        """.trimIndent()
        assertEquals(expected, text)
        assertEquals(manifest, parse(text).manifest)
        assertEquals("{\n  \"schemaVersion\": 1,\n  \"name\": \"hello\",\n  \"version\": \"0.1.0\",\n  \"entry\": \"src/main.cscr\",\n  \"dependencies\": {}\n}\n", Manifests.write(Manifest("hello", "0.1.0", "src/main.cscr")))
    }

    @Test
    fun checksTheStructure() {
        assertEquals(listOf(ProjectDiagnosticCode.JSON_SYNTAX), codes("{\"schemaVersion\": 1,}"))
        assertEquals(listOf(ProjectDiagnosticCode.NOT_AN_OBJECT), codes("[]"))
        assertEquals(listOf(ProjectDiagnosticCode.MISSING_FIELD), codes("{}"))
        assertEquals(listOf(ProjectDiagnosticCode.WRONG_TYPE), codes("{\"schemaVersion\": \"1\"}"))
        assertEquals(listOf(ProjectDiagnosticCode.JSON_NUMBER_NOT_INTEGER), codes("{\"schemaVersion\": 1.0}"))
        // 対応していない版は、他の項目を確かめずに報告する
        val future = parse("""{"schemaVersion": 2, "anything": true}""").diagnostics.single()
        assertEquals(ProjectDiagnosticCode.UNSUPPORTED_SCHEMA_VERSION, future.code)
        assertEquals(listOf("2", "1"), future.arguments)
        assertEquals(listOf(ProjectDiagnosticCode.UNSUPPORTED_SCHEMA_VERSION), codes("""{"schemaVersion": 0}"""))
        // 必須の項目・型・知らない項目
        assertEquals(List(3) { ProjectDiagnosticCode.MISSING_FIELD }, codes("""{"schemaVersion": 1}"""))
        val unknown = parse(example.replace("\"license\": \"MIT\",", "\"license\": \"MIT\",\n  \"lisence\": \"MIT\","))
        assertEquals(listOf(ProjectDiagnosticCode.UNKNOWN_FIELD), unknown.diagnostics.map { it.code })
        assertEquals(listOf("lisence"), unknown.diagnostics.single().arguments)
        assertEquals(7 to 3, unknown.diagnostics.single().let { it.line to it.column })
        assertEquals(listOf(ProjectDiagnosticCode.WRONG_TYPE), codes(with("name", "42")))
        assertEquals(listOf(ProjectDiagnosticCode.WRONG_TYPE), codes(with("license", "null")))
        assertEquals(listOf(ProjectDiagnosticCode.WRONG_TYPE), codes(example.replace(Regex("\"dependencies\": \\{[\\s\\S]*\\}\\s*\\}"), "\"dependencies\": []\n}")))
        // 重複キー（どの深さでも）
        assertEquals(listOf(ProjectDiagnosticCode.JSON_DUPLICATE_KEY), codes(example.replace("\"license\": \"MIT\",", "\"license\": \"MIT\",\n  \"name\": \"other\",")))
        assertEquals(listOf(ProjectDiagnosticCode.JSON_DUPLICATE_KEY), codes(example.replace("\"version\": \"1.2.0\", ", "\"version\": \"1.2.0\", \"version\": \"1.3.0\", ")))
        // ファイルの大きさ
        assertEquals(listOf(ProjectDiagnosticCode.FILE_TOO_LARGE), codes(example + " ".repeat(Manifests.MAX_BYTES)))
    }

    @Test
    fun checksNames() {
        for (name in listOf("a", "my-app", "a1-b2-c3", "x".repeat(64))) assertNotNull(parse(with("name", "\"$name\"")).manifest, name)
        for (name in listOf("", "Vector", "1abc", "-a", "a-", "a--b", "a_b", "a.b", "a b", "é", "x".repeat(65), "ａ")) {
            assertEquals(listOf(ProjectDiagnosticCode.INVALID_NAME), codes(with("name", "\"$name\"")), name)
        }
        // 依存の名前と、自分自身への依存
        assertEquals(listOf(ProjectDiagnosticCode.INVALID_NAME), codes(example.replace("\"vector-math\":", "\"Vector_Math\":")))
        assertEquals(listOf(ProjectDiagnosticCode.DEPENDS_ON_ITSELF), codes(example.replace("\"vector-math\":", "\"example-project\":")))
    }

    @Test
    fun checksVersions() {
        val valid = listOf("0.0.0", "1.2.3", "10.20.30", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-0.3.7", "1.0.0-x.7.z.92", "1.0.0+20130313144700", "1.0.0-beta+exp.sha.5114f85", "1.0.0-x-y-z.--")
        for (version in valid) assertNotNull(parse(with("version", "\"$version\"")).manifest, version)
        val invalid = listOf("1", "1.2", "1.2.3.4", "01.2.3", "1.02.3", "1.2.03", "v1.2.3", "1.2.3-", "1.2.3-01", "1.2.3+", "1.2.3-a..b", " 1.2.3", "1.2.3 ", "1.2.3-é", "1.0.0-" + "a".repeat(200), "")
        for (version in invalid) assertEquals(listOf(ProjectDiagnosticCode.INVALID_VERSION), codes(with("version", "\"$version\"")), version)
        assertEquals(listOf(ProjectDiagnosticCode.INVALID_VERSION), codes(example.replace("\"1.2.0\"", "\"^1.2.0\"")))
    }

    @Test
    fun checksEntryPaths() {
        for (path in listOf("main.cscr", "src/main.cscr", "a/b/c/d.cscr", "src/my_app-1.0/main.cscr", ".hidden/main.cscr")) assertNotNull(parse(with("entry", "\"$path\"")).manifest, path)
        val invalid = mapOf(
            "../main.cscr" to "'.' and '..'",
            "src/../../main.cscr" to "'.' and '..'",
            "./main.cscr" to "'.' and '..'",
            "/etc/main.cscr" to "absolute",
            "//server/main.cscr" to "absolute",
            "C:/main.cscr" to "':'",
            "C:main.cscr" to "':'",
            "src\\\\main.cscr" to "'/'",
            "src//main.cscr" to "empty",
            "src/" to "empty",
            "src/main.txt" to ".cscr",
            "src/.cscr" to ".cscr",
            "src/main.cscr/" to "empty",
            "src/con.cscr" to "reserved",
            "NUL/main.cscr" to "reserved",
            "src./main.cscr" to "end with",
            "src/ma in.cscr" to "only letters",
            "src/mäin.cscr" to "only letters",
            "src/\\u0000.cscr" to "only letters",
            "" to "empty",
            "a/".repeat(130) + "m.cscr" to "longer",
        )
        for ((path, reason) in invalid) {
            val diagnostic = parse(with("entry", "\"$path\"")).diagnostics.single()
            assertEquals(ProjectDiagnosticCode.INVALID_PATH, diagnostic.code, path)
            assertTrue(diagnostic.arguments[1].contains(reason), "$path: ${diagnostic.arguments}")
        }
    }

    @Test
    fun checksLicensesSourcesAndDependencies() {
        for (license in listOf("MIT", "Apache-2.0", "MIT OR Apache-2.0", "(MIT AND BSD-3-Clause)", "GPL-3.0-or-later")) assertNotNull(parse(with("license", "\"$license\"")).manifest, license)
        for (license in listOf("", " MIT", "MIT ", "MIT  OR Apache-2.0", "MIT;rm", "x".repeat(65), "MIT\\n")) {
            assertEquals(listOf(ProjectDiagnosticCode.INVALID_LICENSE), codes(with("license", "\"$license\"")), license)
        }
        val source = "github:owner/vector-math"
        for (valid in listOf("github:a/b", "github:A-b-C/repo.name_x-1", "github:" + "a".repeat(39) + "/" + "r".repeat(100))) {
            assertNotNull(parse(example.replace(source, valid)).manifest, valid)
        }
        val invalidSources = listOf(
            "https://github.com/owner/vector-math", "github:owner", "github:/repo", "github:owner/", "github:-owner/repo", "github:owner-/repo", "github:ow--ner/repo",
            "github:owner/re po", "github:owner/..", "github:owner/.", "github:owner/repo/extra", "gitlab:owner/repo", "github:" + "a".repeat(40) + "/r", "github:o/" + "r".repeat(101), "GITHUB:owner/repo",
        )
        for (invalid in invalidSources) assertEquals(listOf(ProjectDiagnosticCode.INVALID_SOURCE), codes(example.replace(source, invalid)), invalid)
        // 依存の項目は version と source だけ（必須）
        assertEquals(listOf(ProjectDiagnosticCode.MISSING_FIELD), codes(example.replace("\"version\": \"1.2.0\", ", "")))
        assertEquals(listOf(ProjectDiagnosticCode.UNKNOWN_FIELD), codes(example.replace("\"version\": \"1.2.0\", ", "\"version\": \"1.2.0\", \"sha256\": \"x\", ")))
        assertEquals(listOf(ProjectDiagnosticCode.WRONG_TYPE), codes(example.replace("{ \"version\": \"1.2.0\", \"source\": \"github:owner/vector-math\" }", "\"1.2.0\"")))
        // 依存の数
        val many = (1..Manifests.MAX_DEPENDENCIES + 1).joinToString(", ") { "\"d$it\": {\"version\": \"1.0.0\", \"source\": \"github:o/d$it\"}" }
        assertEquals(listOf(ProjectDiagnosticCode.TOO_MANY_ENTRIES), codes("""{"schemaVersion": 1, "name": "a", "version": "1.0.0", "entry": "m.cscr", "dependencies": {$many}}"""))
    }

    // 複数の誤りはまとめて報告する
    @Test
    fun reportsSeveralProblems() {
        val text = """{"schemaVersion": 1, "name": "Bad", "version": "1", "entry": "../m.cscr", "extra": 1}"""
        assertEquals(
            listOf(ProjectDiagnosticCode.UNKNOWN_FIELD, ProjectDiagnosticCode.INVALID_NAME, ProjectDiagnosticCode.INVALID_VERSION, ProjectDiagnosticCode.INVALID_PATH),
            codes(text),
        )
        assertEquals("createscript.json:1:30: error CS_PKG_INVALID_NAME (\"Bad\")", parse(text).diagnostics[1].format("createscript.json"))
    }

    @Test
    fun modelRejectsInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { Manifest("Bad", "1.0.0", "m.cscr") }
        assertThrows(IllegalArgumentException::class.java) { Manifest("a", "1.0.0", "../m.cscr") }
        assertThrows(IllegalArgumentException::class.java) { Manifest("a", "1.0.0", "m.cscr", dependencies = listOf(Dependency("z", "1.0.0", "github:o/z"), Dependency("b", "1.0.0", "github:o/b"))) }
    }
}
