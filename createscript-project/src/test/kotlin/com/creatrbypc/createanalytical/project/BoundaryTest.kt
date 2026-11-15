package com.creatrbypc.createanalytical.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

// S8.1 の最終検証: JSON の境界値（文字コード・大きさ・キー・Unicode）、壊れた入力で例外が漏れないこと、診断の表示、ロックファイルの整合
class BoundaryTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun code(input: ByteArray, max: Int = 1 shl 20) = JsonParser.parse(input, max).diagnostics.single().code

    // ---- 文字コード ----

    @Test
    fun rejectsInvalidUtf8() {
        val q = '"'.code
        val cases = mapOf(
            "overlong '/'" to bytes(q, 0xC0, 0xAF, q),
            "overlong NUL" to bytes(q, 0xE0, 0x80, 0x80, q),
            "UTF-16 surrogate in UTF-8 (CESU-8)" to bytes(q, 0xED, 0xA0, 0x80, q),
            "truncated sequence" to bytes(q, 0xE3, 0x81, q),
            "lone continuation byte" to bytes(q, 0x80, q),
            "above U+10FFFF" to bytes(q, 0xF4, 0x90, 0x80, 0x80, q),
            "invalid byte 0xFF" to bytes(q, 0xFF, q),
            "truncated at end" to bytes(q, 0xE3),
        )
        for ((label, input) in cases) assertEquals(ProjectDiagnosticCode.NOT_UTF8, code(input), label)
        // BOM だけのファイル・途中の BOM
        assertEquals(ProjectDiagnosticCode.BYTE_ORDER_MARK, code(bytes(0xEF, 0xBB, 0xBF)))
        assertEquals(ProjectDiagnosticCode.JSON_SYNTAX, code("[1,\uFEFF2]".toByteArray()))
        // 文字列の中の U+FEFF・非文字・4 バイトの文字・日本語は、そのまま値になる
        val value = JsonParser.parse("[\"\uFEFF\uFFFF\uD83D\uDE00日本\"]".toByteArray(), 100).value as JsonArray
        assertEquals("\uFEFF\uFFFF\uD83D\uDE00日本", (value.elements[0] as JsonString).value)
        // \u0000 のエスケープは値として読める（名前などの規則が、使える文字かを決める）。空のキーも使えるが、重複はエラー
        assertEquals("a\u0000b", ((JsonParser.parse("[\"a\\u0000b\"]").value as JsonArray).elements[0] as JsonString).value)
        assertEquals(ProjectDiagnosticCode.JSON_DUPLICATE_KEY, JsonParser.parse("{\"\": 1, \"\": 2}").diagnostics.single().code)
        assertEquals(ProjectDiagnosticCode.JSON_DUPLICATE_KEY, JsonParser.parse("{\"\\u0000\": 1, \"\\u0000\": 2}").diagnostics.single().code)
        // 書式送り・垂直タブは JSON の空白ではない
        for (space in listOf("\u000C", "\u000B", "\u2028", "\u3000")) assertEquals(ProjectDiagnosticCode.JSON_SYNTAX, JsonParser.parse("[1,${space}2]").diagnostics.single().code, space)
    }

    // ---- 大きさ ----

    @Test
    fun sizeLimitsAreExact() {
        val exact = ("[" + " ".repeat(98) + "]").toByteArray()
        assertEquals(100, exact.size)
        assertNotNull(JsonParser.parse(exact, 100).value)
        assertEquals(ProjectDiagnosticCode.FILE_TOO_LARGE, code(exact + ' '.code.toByte(), 100))
        // マニフェストの上限ちょうど（64 KiB）は読む
        val manifest = """{"schemaVersion": 1, "name": "a", "version": "1.0.0", "entry": "m.cscr"}"""
        val padded = manifest + " ".repeat(Manifests.MAX_BYTES - manifest.length)
        assertNotNull(Manifests.parse(padded.toByteArray()).manifest)
        assertEquals(ProjectDiagnosticCode.FILE_TOO_LARGE, Manifests.parse((padded + " ").toByteArray()).diagnostics.single().code)
        // 巨大な数値・長い文字列（診断の引数は 80 単位で切る）
        assertEquals(ProjectDiagnosticCode.JSON_NUMBER_NOT_INTEGER, JsonParser.parse("[1e999999999]").diagnostics.single().code)
        assertEquals(ProjectDiagnosticCode.JSON_NUMBER_OUT_OF_RANGE, JsonParser.parse("[-" + "9".repeat(60_000) + "]").diagnostics.single().code)
        val longName = Manifests.parse("""{"schemaVersion": 1, "name": "${"x".repeat(60_000)}", "version": "1.0.0", "entry": "m.cscr"}""".toByteArray()).diagnostics.single()
        assertEquals(ProjectDiagnosticCode.INVALID_NAME, longName.code)
        assertTrue(longName.format("f").length < 200)
        assertTrue(longName.format("f").endsWith("…\")"))
    }

    // ---- キー ----

    // 多くのキー・ハッシュ値の衝突するキー（"Aa" と "BB" は同じ hashCode）でも、短い時間で読み、重複を見つける
    @Test
    fun manyAndCollidingKeys() {
        val colliding = (0 until 4096).map { n -> (0 until 12).joinToString("") { bit -> if ((n shr bit) and 1 == 0) "Aa" else "BB" } }
        assertEquals(1, colliding.map { it.hashCode() }.distinct().size)
        val start = System.nanoTime()
        val text = colliding.joinToString(", ", "{", "}") { "\"$it\": 1" }
        val parsed = JsonParser.parse(text).value as JsonObject
        assertEquals(4096, parsed.members.size)
        val duplicate = JsonParser.parse(text.dropLast(1) + ", \"${colliding[1234]}\": 2}").diagnostics.single()
        assertEquals(ProjectDiagnosticCode.JSON_DUPLICATE_KEY, duplicate.code)
        assertEquals(listOf(colliding[1234]), duplicate.arguments)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
    }

    // ---- 壊れた入力 ----

    // 正しいマニフェスト・ロックファイルを 1〜4 バイトずつ壊したもの・切り詰めたもの。例外を漏らさず、値か診断のどちらかを返す
    @Test
    fun corruptedInputsNeverThrow() {
        val manifest = Manifests.write(Manifest("demo", "1.0.0", "src/main.cscr", "MIT", listOf(Dependency("lib", "2.0.0", "github:o/lib")))).toByteArray()
        val lock = Lockfiles.write(Lockfile.of(LockRoot("demo", "1.0.0"), listOf(LockedPackage("lib", "2.0.0", "github:o/lib", "a".repeat(64))))).toByteArray()
        val random = Random(81)
        val interesting = intArrayOf('{'.code, '}'.code, '['.code, ']'.code, '"'.code, '\\'.code, ':'.code, ','.code, '0'.code, '-'.code, 'e'.code, 'u'.code, 0x00, 0x7F, 0x80, 0xC3, 0xED, 0xEF, 0xFF, ' '.code, '\n'.code)
        var rejected = 0
        repeat(20_000) { iteration ->
            val base = if (iteration % 2 == 0) manifest else lock
            val input = when (iteration % 5) {
                0 -> base.copyOf(random.nextInt(base.size))
                else -> base.copyOf().also { copy ->
                    repeat(1 + random.nextInt(4)) { copy[random.nextInt(copy.size)] = (if (random.nextBoolean()) interesting[random.nextInt(interesting.size)] else random.nextInt(256)).toByte() }
                }
            }
            if (base === manifest) {
                val result = Manifests.parse(input)
                assertTrue(result.manifest != null || result.diagnostics.isNotEmpty())
                if (result.manifest == null) rejected++
            } else {
                val result = Lockfiles.parse(input)
                assertTrue(result.lockfile != null || result.diagnostics.isNotEmpty())
                result.lockfile?.let { Lockfiles.check(input, result, Manifest("demo", "1.0.0", "src/main.cscr", dependencies = listOf(Dependency("lib", "2.0.0", "github:o/lib")))) }
                if (result.lockfile == null) rejected++
            }
        }
        assertTrue(rejected > 10_000, "$rejected")
    }

    // ---- 診断の表示 ----

    // 引数（ファイルの中の文字列）の、端末の表示を変えうる文字は \uXXXX にする
    @Test
    fun formatEscapesTerminalControls() {
        fun shown(argument: String) = ProjectDiagnostic(ProjectDiagnosticCode.UNKNOWN_FIELD, 1, 1, listOf(argument)).format("f").substringAfter("(\"").removeSuffix("\")")
        assertEquals("\\u001b[31m", shown("\u001B[31m"))
        assertEquals("\\u009b31m", shown("\u009B31m"))
        assertEquals("\\u007f", shown("\u007F"))
        assertEquals("a\\u202eb\\u2066c\\u200bd\\ufeff", shown("a\u202Eb\u2066c\u200Bd\uFEFF"))
        assertEquals("\\u2028\\u2029", shown("\u2028\u2029"))
        assertEquals("\\ud800x\\udc00", shown("\uD800x\uDC00"))
        assertEquals("\\\"\\\\", shown("\"\\"))
        // 通常の文字・4 バイトの文字は、そのまま
        assertEquals("é日本😀 ok", shown("é日本😀 ok"))
        // 80 単位で切るときに、サロゲートの対を分けない
        val cut = shown("x".repeat(79) + "😀" + "tail")
        assertEquals("x".repeat(79) + "…", cut)
        assertFalse(cut.any { it.isSurrogate() })
    }

    // ---- ロックファイルの整合 ----

    private val sha = "b".repeat(64)

    private fun entry(name: String, vararg dependencies: String) = LockedPackage(name, "1.0.0", "github:o/$name", sha, dependencies.toList())

    private fun lockText(vararg entries: LockedPackage, root: String = "demo"): String {
        // Lockfile.of は正しくないものを作れないので、正規形の形のまま JSON を組み立てる
        val packages = entries.joinToString(",\n") { e ->
            "    {\n      \"name\": \"${e.name}\",\n      \"version\": \"${e.version}\",\n      \"source\": \"${e.source}\",\n      \"sha256\": \"${e.sha256}\",\n      \"dependencies\": [" +
                (if (e.dependencies.isEmpty()) "]" else e.dependencies.joinToString(",\n", "\n", "\n      ]") { "        \"$it\"" }) + "\n    }"
        }
        return "{\n  \"lockVersion\": 1,\n  \"root\": {\n    \"name\": \"$root\",\n    \"version\": \"1.0.0\"\n  },\n  \"packages\": [\n$packages\n  ]\n}\n"
    }

    private fun lockCodes(text: String, manifest: Manifest = Manifest("demo", "1.0.0", "m.cscr", dependencies = listOf(Dependency("a", "1.0.0", "github:o/a")))): List<ProjectDiagnosticCode> {
        val input = text.toByteArray()
        return Lockfiles.check(input, Lockfiles.parse(input), manifest).map { it.code }
    }

    @Test
    fun lockConsistency() {
        // 正しいもの（菱形: a → b, c → d）
        assertEquals(emptyList<ProjectDiagnosticCode>(), lockCodes(lockText(entry("a", "b", "c"), entry("b", "d"), entry("c", "d"), entry("d"))))
        // 循環（自分自身・2 つ・長い循環）
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_DEPENDENCY_CYCLE), lockCodes(lockText(entry("a", "a"))))
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_DEPENDENCY_CYCLE), lockCodes(lockText(entry("a", "b"), entry("b", "a"))))
        val ring = (1..100).map { entry("p$it", "p${it % 100 + 1}") }.sortedBy { it.name }
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_DEPENDENCY_CYCLE), lockCodes(lockText(entry("a", "p1"), *ring.toTypedArray())))
        // 存在しない参照（すべて報告する）
        assertEquals(List(2) { ProjectDiagnosticCode.LOCK_UNKNOWN_DEPENDENCY }, lockCodes(lockText(entry("a", "missing", "other"))))
        // 重複したパッケージ（版が違っても同じ名前は 1 つだけ）
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_DUPLICATE_PACKAGE), lockCodes(lockText(entry("a"), entry("a").copy(version = "2.0.0"))))
        // 余分なパッケージ（使われないパッケージだけが使うものも余分）
        assertEquals(List(2) { ProjectDiagnosticCode.LOCK_UNUSED_PACKAGE }, lockCodes(lockText(entry("a"), entry("x", "y"), entry("y"))))
        // プロジェクト自身と同じ名前のパッケージ
        assertEquals(listOf(ProjectDiagnosticCode.DEPENDS_ON_ITSELF), lockCodes(lockText(entry("a", "demo"), entry("demo"))))
        // 依存のあるプロジェクトの、空のロックファイル
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_MISSING_PACKAGE), lockCodes(lockText().replace("[\n\n  ]", "[]")))
        // 正しくない入力のときは、正規形・マニフェストとの整合は確かめない（parse の診断だけ）
        assertNull(Lockfiles.parse(lockText(entry("a", "a")).toByteArray()).lockfile)
    }
}
