package com.creatrbypc.createanalytical.project.registry

import com.creatrbypc.createanalytical.project.CanonicalJson
import com.creatrbypc.createanalytical.project.JsonParser
import com.creatrbypc.createanalytical.project.JsonWriter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

// S8.3 Phase 1: 署名する JSON の正規形（RFC 8785 を整数に絞ったもの）
class CanonicalJsonTest {
    private fun canonical(text: String) = CanonicalJson.write(JsonParser.parse(text.toByteArray(Charsets.UTF_8), 1 shl 20).value!!).toString(Charsets.UTF_8)

    @Test
    fun sortsKeysAndRemovesSpaces() {
        assertEquals("""{"a":[1,-2,true,false,null],"b":{"x":"y"},"c":{}}""", canonical("""{ "c": {}, "b": { "x": "y" }, "a": [1, -2, true, false, null] }"""))
        // キーは UTF-16 のコード単位の順（大文字は小文字より前、"a" は "aa" より前、é（U+00E9）は z より後、😀（U+D83D…）は U+FFFD より前）
        assertEquals("""{"B":1,"a":2,"aa":3,"z":4,"é":5,"😀":6,"�":7}""", canonical("""{"�":7,"😀":6,"é":5,"z":4,"aa":3,"a":2,"B":1}"""))
    }

    @Test
    fun escapesOnlyWhatRfc8785Escapes() {
        // " \ と制御文字だけ。/・DEL・U+2028・非 ASCII は、そのまま UTF-8
        assertEquals("\"\\\"\\\\\\b\\t\\n\\f\\r\\u0000\\u001f/\u007f\u2028é😀\"", canonical("\"\\\"\\\\\\b\\t\\n\\f\\r\\u0000\\u001F\\/\\u007f\\u2028\\u00e9\\ud83d\\ude00\""))
    }

    @Test
    fun sameContentGivesTheSameBytes() {
        val a = canonical("""{"serial": 5, "packages": {"b": {"v": "1"}, "a": {"v": "2"}}}""")
        val b = canonical("{\n  \"packages\":{\"a\":{\"v\":\"\\u0032\"},\"b\":{\"v\":\"1\"}},\n\"serial\":5}")
        assertEquals(a, b)
        assertArrayEquals(a.toByteArray(Charsets.UTF_8), canonical(a).toByteArray(Charsets.UTF_8))
    }

    @Test
    fun refusesWhatCannotBeWrittenExactly() {
        assertEquals("9007199254740991", CanonicalJson.write(JsonWriter.integer(9_007_199_254_740_991L)).toString(Charsets.UTF_8))
        assertEquals("-9007199254740991", CanonicalJson.write(JsonWriter.integer(-9_007_199_254_740_991L)).toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { CanonicalJson.write(JsonWriter.integer(9_007_199_254_740_992L)) }
        assertThrows(IllegalArgumentException::class.java) { CanonicalJson.write(JsonWriter.string("a\ud800b")) }
        assertThrows(IllegalArgumentException::class.java) { CanonicalJson.write(JsonWriter.string("\udc00")) }
        assertThrows(IllegalArgumentException::class.java) { CanonicalJson.write(JsonWriter.obj("\ud800" to JsonWriter.integer(1))) }
    }

    // 深い入れ子でも再帰しない
    @Test
    fun deepNestingDoesNotRecurse() {
        var value: com.creatrbypc.createanalytical.project.JsonValue = JsonWriter.array(emptyList())
        repeat(100_000) { value = JsonWriter.array(listOf(value)) }
        assertEquals(200_002, CanonicalJson.write(value).size)
    }
}
