package com.creatrbypc.createanalytical.project.registry

import com.creatrbypc.createanalytical.project.ProjectDiagnosticCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// S8.3 Phase 1: レジストリのリポジトリのファイル（registry.json・reserved-names.json・packages/<頭文字>/<名前>.json）
class RegistryFilesTest {
    private val entryText = """
        {
          "registryVersion": 1,
          "name": "vector-math",
          "source": "github:alice/vector-math",
          "repositoryId": 123456789,
          "owners": [
            {
              "login": "alice",
              "id": 1001
            }
          ],
          "description": "2D and 3D vector helpers",
          "license": "MIT",
          "versions": {
            "1.2.0": {
              "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "dependencies": {
                "numbers": "3.0.0"
              },
              "review": {
                "pullRequest": 42,
                "reviewers": [
                  "dog7dog"
                ],
                "date": "2026-11-20"
              }
            }
          }
        }

    """.trimIndent()

    private val path = "packages/v/vector-math.json"

    private fun entry(text: String, at: String = path) = RegistryFiles.parseEntry(at, text.toByteArray(Charsets.UTF_8))

    private fun codes(text: String, at: String = path) = entry(text, at).let { result ->
        assertNull(result.value, text)
        result.diagnostics.map { it.code }
    }

    // 1 か所を変えた文書
    private fun changed(old: String, new: String): String {
        assertTrue(old in entryText, old)
        return entryText.replace(old, new)
    }

    @Test
    fun readsAValidEntryAndWritesTheSameBytes() {
        val result = entry(entryText)
        val value = result.value ?: throw AssertionError(result.diagnostics.toString())
        assertEquals("vector-math", value.name)
        assertEquals(123456789L, value.repositoryId)
        assertEquals(listOf(Owner("alice", 1001)), value.owners)
        assertEquals(mapOf("numbers" to "3.0.0"), value.versions.getValue("1.2.0").dependencies)
        assertEquals(Review(42, listOf("dog7dog"), java.time.LocalDate.of(2026, 11, 20)), value.versions.getValue("1.2.0").review)
        assertNull(value.removed)
        assertEquals(entryText, RegistryFiles.writeEntry(value))
        // 取り下げ・削除
        val withdrawn = value.copy(
            removed = Withdrawal("malicious code was found", java.time.LocalDate.of(2026, 12, 1)),
            versions = value.versions.mapValues { it.value.copy(yanked = Withdrawal("broken build", java.time.LocalDate.of(2026, 11, 30))) },
        )
        val text = RegistryFiles.writeEntry(withdrawn)
        assertEquals(withdrawn, entry(text).value)
    }

    @Test
    fun entriesMustBeCanonicalAndInTheRightPlace() {
        val reordered = changed("  \"description\": \"2D and 3D vector helpers\",\n  \"license\": \"MIT\",", "  \"license\": \"MIT\",\n  \"description\": \"2D and 3D vector helpers\",")
        val diagnostic = entry(reordered).diagnostics.single()
        assertEquals(ProjectDiagnosticCode.REGISTRY_NOT_CANONICAL, diagnostic.code)
        assertEquals(12, diagnostic.line)
        assertEquals(listOf(ProjectDiagnosticCode.REGISTRY_NOT_CANONICAL), codes(entryText.replace("\n", "\r\n")))
        assertEquals(listOf(ProjectDiagnosticCode.REGISTRY_NOT_CANONICAL), codes(entryText.trimEnd()))
        assertEquals(listOf(ProjectDiagnosticCode.ENTRY_PATH_MISMATCH), codes(entryText, "packages/n/vector-math.json"))
        assertEquals(listOf(ProjectDiagnosticCode.ENTRY_PATH_MISMATCH), codes(entryText, "packages/v/vector-math.JSON"))
        assertEquals("packages/v/vector-math.json", RegistryRules.entryPath("vector-math"))
    }

    @Test
    fun rejectsInvalidValues() {
        val cases = linkedMapOf(
            changed("\"name\": \"vector-math\"", "\"name\": \"Vector-Math\"") to ProjectDiagnosticCode.INVALID_NAME,
            changed("\"name\": \"vector-math\"", "\"name\": \"vector--math\"") to ProjectDiagnosticCode.INVALID_NAME,
            changed("\"name\": \"vector-math\"", "\"name\": \"con\"") to ProjectDiagnosticCode.RESERVED_NAME,
            changed("\"1.2.0\": {", "\"1.2\": {") to ProjectDiagnosticCode.INVALID_VERSION,
            changed("\"1.2.0\": {", "\"01.2.0\": {") to ProjectDiagnosticCode.INVALID_VERSION,
            changed("\"1.2.0\": {", "\"1.2.0+build.5\": {") to ProjectDiagnosticCode.VERSION_BUILD_METADATA,
            changed("\"numbers\": \"3.0.0\"", "\"numbers\": \"^3.0.0\"") to ProjectDiagnosticCode.INVALID_VERSION,
            changed("\"numbers\": \"3.0.0\"", "\"Numbers\": \"3.0.0\"") to ProjectDiagnosticCode.INVALID_NAME,
            changed("\"numbers\": \"3.0.0\"", "\"vector-math\": \"1.0.0\"") to ProjectDiagnosticCode.DEPENDS_ON_ITSELF,
            changed("\"numbers\": \"3.0.0\"", "\"numbers\": 3") to ProjectDiagnosticCode.WRONG_TYPE,
            changed("github:alice/vector-math", "https://github.com/alice/vector-math") to ProjectDiagnosticCode.INVALID_SOURCE,
            changed("github:alice/vector-math", "github:alice/..") to ProjectDiagnosticCode.INVALID_SOURCE,
            changed("\"repositoryId\": 123456789", "\"repositoryId\": 0") to ProjectDiagnosticCode.INVALID_ID,
            changed("\"repositoryId\": 123456789", "\"repositoryId\": 9007199254740992") to ProjectDiagnosticCode.INVALID_ID,
            changed("\"repositoryId\": 123456789", "\"repositoryId\": \"123456789\"") to ProjectDiagnosticCode.WRONG_TYPE,
            changed("\"id\": 1001", "\"id\": -1") to ProjectDiagnosticCode.INVALID_ID,
            changed("\"login\": \"alice\"", "\"login\": \"-alice\"") to ProjectDiagnosticCode.INVALID_LOGIN,
            changed("\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"", "\"0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef\"") to ProjectDiagnosticCode.INVALID_SHA256,
            changed("\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"", "\"0123\"") to ProjectDiagnosticCode.INVALID_SHA256,
            changed("\"pullRequest\": 42", "\"pullRequest\": 0") to ProjectDiagnosticCode.INVALID_ID,
            changed("\"date\": \"2026-11-20\"", "\"date\": \"2026-02-30\"") to ProjectDiagnosticCode.INVALID_DATE,
            changed("\"date\": \"2026-11-20\"", "\"date\": \"2026-11-20T00:00:00Z\"") to ProjectDiagnosticCode.INVALID_DATE,
            changed("\"license\": \"MIT\"", "\"license\": \"MIT;rm\"") to ProjectDiagnosticCode.INVALID_LICENSE,
            changed("\"reviewers\": [\n          \"dog7dog\"\n        ]", "\"reviewers\": []") to ProjectDiagnosticCode.TOO_MANY_ENTRIES,
            changed("\"reviewers\": [\n          \"dog7dog\"\n        ]", "\"reviewers\": [\"dog7dog\", \"Dog7dog\"]") to ProjectDiagnosticCode.DUPLICATE_VALUE,
            changed("\"owners\": [\n    {\n      \"login\": \"alice\",\n      \"id\": 1001\n    }\n  ]", "\"owners\": []") to ProjectDiagnosticCode.TOO_MANY_ENTRIES,
            changed("\"owners\": [\n    {\n      \"login\": \"alice\",\n      \"id\": 1001\n    }\n  ]", "\"owners\": [{\"login\": \"alice\", \"id\": 1}, {\"login\": \"bob\", \"id\": 1}]") to ProjectDiagnosticCode.DUPLICATE_VALUE,
            RegistryFiles.writeEntry(entry(entryText).value!!.copy(versions = emptyMap())) to ProjectDiagnosticCode.NO_VERSIONS,
            changed("\"license\": \"MIT\",", "\"license\": \"MIT\",\n  \"removed\": null,") to ProjectDiagnosticCode.WRONG_TYPE,
            changed("\"license\": \"MIT\",", "\"license\": \"MIT\",\n  \"removed\": {\"reason\": \"x\"},") to ProjectDiagnosticCode.MISSING_FIELD,
            changed("\"registryVersion\": 1", "\"registryVersion\": 2") to ProjectDiagnosticCode.UNSUPPORTED_REGISTRY_VERSION,
        )
        for ((text, code) in cases) assertEquals(listOf(code), codes(text).distinct(), text)
    }

    // 重複キー・未知の項目・文字コード・大きさ（S8.1 の厳密な JSON をそのまま使う）
    @Test
    fun strictJson() {
        assertEquals(listOf(ProjectDiagnosticCode.JSON_DUPLICATE_KEY), codes(changed("\"license\": \"MIT\",", "\"license\": \"MIT\",\n  \"license\": \"MIT\",")))
        assertEquals(listOf(ProjectDiagnosticCode.JSON_DUPLICATE_KEY), codes(changed("\"numbers\": \"3.0.0\"", "\"numbers\": \"3.0.0\", \"numbers\": \"3.0.0\"")))
        assertEquals(listOf(ProjectDiagnosticCode.UNKNOWN_FIELD), codes(changed("\"license\": \"MIT\",", "\"license\": \"MIT\",\n  \"homepage\": \"https://example.com\",")))
        assertEquals(listOf(ProjectDiagnosticCode.UNKNOWN_FIELD), codes(changed("\"pullRequest\": 42,", "\"pullRequest\": 42,\n          \"approved\": true,")))
        assertEquals(listOf(ProjectDiagnosticCode.BYTE_ORDER_MARK), codes("﻿" + entryText))
        val invalidUtf8 = entryText.toByteArray(Charsets.UTF_8).also { it[it.indexOf('2'.code.toByte())] = 0xC0.toByte() }
        assertEquals(listOf(ProjectDiagnosticCode.NOT_UTF8), RegistryFiles.parseEntry(path, invalidUtf8).diagnostics.map { it.code })
        assertEquals(listOf(ProjectDiagnosticCode.JSON_SYNTAX), codes(changed("2D and 3D", "2D \\ud800 3D")))
        val large = ByteArray(RegistryLimits.MAX_ENTRY_BYTES + 1) { ' '.code.toByte() }
        assertEquals(listOf(ProjectDiagnosticCode.FILE_TOO_LARGE), RegistryFiles.parseEntry(path, large).diagnostics.map { it.code })
    }

    // 説明・理由の文字列: 表示を偽れる文字・NFC でないもの・長すぎるもの
    @Test
    fun textMustBeSafeToShow() {
        for ((text, reason) in listOf(
            "2D ‮selif‬" to "U+202E",
            "line break" to "U+2028",
            "tab\\there" to "U+0009",
            "zero​width" to "U+200B",
            "private " to "U+E000",
            "nonchar ﷐" to "U+FDD0",
            "café" to "NFC",
            " padded" to "space",
            "" to "empty",
            "x".repeat(201) to "longer than 200",
        )) {
            val result = entry(changed("2D and 3D vector helpers", text))
            val diagnostic = result.diagnostics.single()
            assertEquals(ProjectDiagnosticCode.INVALID_TEXT, diagnostic.code, text)
            assertTrue(diagnostic.arguments[1].contains(reason), diagnostic.arguments.toString())
        }
        // 日本語・絵文字・NFC の é は使える
        val ok = entry(changed("2D and 3D vector helpers", "ベクトルの計算 café 😀"))
        assertEquals(emptyList<Any>(), ok.diagnostics)
    }

    @Test
    fun configAndReservedNames() {
        val config = RegistryFiles.writeConfig(RegistryConfig(30, listOf("createscript-registry-2026-1")))
        assertEquals("{\n  \"registryVersion\": 1,\n  \"indexValidityDays\": 30,\n  \"signingKeyIds\": [\n    \"createscript-registry-2026-1\"\n  ]\n}\n", config)
        assertEquals(RegistryConfig(30, listOf("createscript-registry-2026-1")), RegistryFiles.parseConfig(config.toByteArray()).value)
        fun configCodes(text: String) = RegistryFiles.parseConfig(text.toByteArray()).diagnostics.map { it.code }
        assertEquals(listOf(ProjectDiagnosticCode.INVALID_SETTING), configCodes(config.replace("30", "91")))
        assertEquals(listOf(ProjectDiagnosticCode.INVALID_SETTING), configCodes(config.replace("30", "0")))
        assertEquals(listOf(ProjectDiagnosticCode.INVALID_KEY_ID), configCodes(config.replace("createscript-registry-2026-1", "Key 1")))
        assertEquals(listOf(ProjectDiagnosticCode.TOO_MANY_ENTRIES), configCodes(config.replace("[\n    \"createscript-registry-2026-1\"\n  ]", "[]")))
        assertEquals(listOf(ProjectDiagnosticCode.DUPLICATE_VALUE), configCodes(config.replace("\"createscript-registry-2026-1\"", "\"a\", \"a\"")))

        val reserved = RegistryFiles.writeReserved(ReservedNames(mapOf("std" to "a future standard library", "core" to "the CreateScript core")))
        assertTrue(reserved.indexOf("\"core\"") < reserved.indexOf("\"std\""), reserved)
        assertEquals(ReservedNames(mapOf("core" to "the CreateScript core", "std" to "a future standard library")), RegistryFiles.parseReserved(reserved.toByteArray()).value)
        fun reservedCodes(text: String) = RegistryFiles.parseReserved(text.toByteArray()).diagnostics.map { it.code }
        assertEquals(listOf(ProjectDiagnosticCode.REGISTRY_NOT_CANONICAL), reservedCodes(reserved.replace("\"core\": \"the CreateScript core\",\n    \"std\": \"a future standard library\"", "\"std\": \"a future standard library\",\n    \"core\": \"the CreateScript core\"")))
        assertEquals(listOf(ProjectDiagnosticCode.INVALID_NAME), reservedCodes(reserved.replace("\"core\"", "\"Core\"")))
        assertEquals(listOf(ProjectDiagnosticCode.JSON_DUPLICATE_KEY), reservedCodes(reserved.replace("\"std\"", "\"core\"")))
    }

    @Test
    fun similarNames() {
        assertEquals("vectormath", RegistryRules.skeleton("vector-math"))
        assertEquals(RegistryRules.skeleton("vector-math"), RegistryRules.skeleton("vect0r-math"))
        assertEquals(RegistryRules.skeleton("lib"), RegistryRules.skeleton("1ib"))
        val names = listOf("vector-math", "vectormath", "vector-maths", "vetcor-math", "vector-path", "numbers", "vectors")
        assertEquals(listOf("vector-maths", "vector-path", "vectormath", "vetcor-math"), RegistryRules.similarNames("vector-math", names))
        assertEquals(emptyList<String>(), RegistryRules.similarNames("numbers", listOf("vector-math", "colors")))
    }
}
