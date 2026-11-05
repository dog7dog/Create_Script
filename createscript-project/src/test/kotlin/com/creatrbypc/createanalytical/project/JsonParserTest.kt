package com.creatrbypc.createanalytical.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// S8.1: 厳密な JSON（RFC 8259）の構文解析と、決まった形の書き出し
class JsonParserTest {
    private fun parse(text: String) = JsonParser.parse(text.toByteArray(Charsets.UTF_8), 1 shl 20)

    private fun error(text: String): ProjectDiagnostic {
        val result = parse(text)
        assertNull(result.value, text)
        return result.diagnostics.single()
    }

    @Test
    fun parsesValidDocuments() {
        val value = parse("""{"a": [1, -2, 0, true, false, null, "x\n\"\u00e9\ud83d\ude00"], "b": {}, "c": [], "d": 9223372036854775807}""").value as JsonObject
        assertEquals(listOf("a", "b", "c", "d"), value.members.keys.toList())
        val array = value.members.getValue("a") as JsonArray
        assertEquals(listOf(1L, -2L, 0L), array.elements.take(3).map { (it as JsonInteger).value })
        assertEquals("x\n\"é😀", (array.elements[6] as JsonString).value)
        assertEquals(Long.MAX_VALUE, (value.members.getValue("d") as JsonInteger).value)
        // 空白は空白・タブ・改行・復帰
        assertTrue(parse(" \t\r\n[ 1 ,\r\n 2 ] \n").value is JsonArray)
        assertTrue(parse("\"top\"").value is JsonString)
    }

    @Test
    fun reportsPositions() {
        val diagnostic = error("{\n  \"a\": 1,\n  \"b\": x\n}")
        assertEquals(ProjectDiagnosticCode.JSON_SYNTAX, diagnostic.code)
        assertEquals(3 to 8, diagnostic.line to diagnostic.column)
        val value = parse("{\n  \"key\": \"v\"\n}").value as JsonObject
        assertEquals(2 to 3, value.keyPositions.getValue("key"))
        assertEquals(2 to 10, value.members.getValue("key").let { it.line to it.column })
        // \r\n と単独の \r も 1 つの改行
        assertEquals(4, error("[\r\n1,\r2,\nx]").line)
    }

    @Test
    fun rejectsNonStandardSyntax() {
        val invalid = listOf(
            "", " ", "{", "[1,]", "{\"a\": 1,}", "[1 2]", "{\"a\" 1}", "{a: 1}", "{'a': 1}", "// c\n{}", "/* c */ {}", "{} {}", "[01]", "[1.]",
            "[.5]", "[+1]", "[NaN]", "[Infinity]", "[undefined]", "[tru]", "[nul]", "[\"\\x41\"]", "[\"\\u12\"]", "[\"a\tb\"]", "[\"a\nb\"]",
            "[\"unterminated", "[\"\\ud800\"]", "[\"\\udc00\"]", "[\"\\ud800\\u0041\"]", "\u00a0[]", "[1]\u0000", "{\"a\":1}x",
        )
        for (text in invalid) assertEquals(ProjectDiagnosticCode.JSON_SYNTAX, error(text).code, text)
    }

    @Test
    fun rejectsDuplicateKeysAtAnyDepth() {
        for (text in listOf("{\"a\": 1, \"a\": 2}", "{\"x\": {\"b\": 1, \"c\": 2, \"b\": 3}}", "[{\"k\": 1}, {\"k\": 1, \"k\": 1}]", "{\"\\u0061\": 1, \"a\": 2}")) {
            val diagnostic = error(text)
            assertEquals(ProjectDiagnosticCode.JSON_DUPLICATE_KEY, diagnostic.code, text)
        }
        assertEquals(listOf("b"), error("{\"x\": {\"b\": 1, \"b\": 3}}").arguments)
        // 別のオブジェクトの同じキーは重複ではない
        assertTrue(parse("[{\"k\": 1}, {\"k\": 2}]").value is JsonArray)
    }

    @Test
    fun acceptsOnlyIntegers() {
        for (text in listOf("[1.5]", "[1e3]", "[1E+3]", "[-0.0]", "[2e-1]")) assertEquals(ProjectDiagnosticCode.JSON_NUMBER_NOT_INTEGER, error(text).code, text)
        for (text in listOf("[9223372036854775808]", "[-9223372036854775809]", "[" + "9".repeat(400) + "]")) assertEquals(ProjectDiagnosticCode.JSON_NUMBER_OUT_OF_RANGE, error(text).code, text)
        assertEquals(Long.MIN_VALUE, ((parse("[-9223372036854775808]").value as JsonArray).elements[0] as JsonInteger).value)
        // ASCII 以外の数字は数字ではない
        assertEquals(ProjectDiagnosticCode.JSON_SYNTAX, error("[\u0661]").code)
    }

    @Test
    fun limitsSizeDepthAndEncoding() {
        assertEquals(ProjectDiagnosticCode.FILE_TOO_LARGE, JsonParser.parse(ByteArray(11) { ' '.code.toByte() }, 10).diagnostics.single().code)
        assertEquals(listOf("10"), JsonParser.parse(ByteArray(11), 10).diagnostics.single().arguments)
        assertEquals(ProjectDiagnosticCode.BYTE_ORDER_MARK, JsonParser.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), '{'.code.toByte(), '}'.code.toByte()), 100).diagnostics.single().code)
        assertEquals(ProjectDiagnosticCode.NOT_UTF8, JsonParser.parse(byteArrayOf('['.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), 0x28, '"'.code.toByte(), ']'.code.toByte()), 100).diagnostics.single().code)
        // 入れ子は既定で 16 まで
        assertTrue(parse("[".repeat(16) + "]".repeat(16)).value is JsonArray)
        assertEquals(ProjectDiagnosticCode.JSON_TOO_DEEP, error("[".repeat(17) + "]".repeat(17)).code)
        assertEquals(ProjectDiagnosticCode.JSON_TOO_DEEP, error("{\"a\":".repeat(17) + "1" + "}".repeat(17)).code)
        // 非常に深い入れ子でも、再帰しないためスタックを使い切らない（上限で止まる）
        assertEquals(ProjectDiagnosticCode.JSON_TOO_DEEP, JsonParser.parse("[".repeat(500_000)).diagnostics.single().code)
        assertTrue(JsonParser.parse("[".repeat(10_000) + "]".repeat(10_000), maxDepth = 10_000).value is JsonArray)
    }

    @Test
    fun writesCanonicalJson() {
        val value = JsonWriter.obj(
            "s" to JsonWriter.string("a\"b\\c\n\t\u0001\u007fé"),
            "n" to JsonWriter.integer(-5),
            "empty" to JsonWriter.obj(),
            "list" to JsonWriter.array(listOf(JsonWriter.integer(1), JsonWriter.obj("k" to JsonWriter.array(emptyList())))),
        )
        val expected = "{\n  \"s\": \"a\\\"b\\\\c\\n\\t\\u0001\\u007fé\",\n  \"n\": -5,\n  \"empty\": {},\n  \"list\": [\n    1,\n    {\n      \"k\": []\n    }\n  ]\n}\n"
        assertEquals(expected, JsonWriter.write(value))
        // 書き出したものを読むと同じ値になり、もう一度書くと同じ文字列になる
        val reparsed = parse(expected).value!!
        assertEquals(expected, JsonWriter.write(reparsed))
        assertEquals("[]\n", JsonWriter.write(JsonWriter.array(emptyList())))
        // 深い入れ子も再帰せずに書く
        var deep: JsonValue = JsonWriter.integer(1)
        repeat(10_000) { deep = JsonWriter.array(listOf(deep)) }
        assertTrue(JsonWriter.write(deep).startsWith("[\n  [\n"))
    }
}
