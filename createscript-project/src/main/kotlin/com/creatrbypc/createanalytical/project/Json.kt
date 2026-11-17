package com.creatrbypc.createanalytical.project

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

// JSON の値（S8.1）。line・column はその値の始まり（1 始まり。UTF-16 単位）
@InternalCreateScriptProjectApi
public sealed interface JsonValue {
    public val line: Int
    public val column: Int
}

// メンバーは書かれた順。キーは重複しない（重複は構文解析の段階でエラーにする）。keyPositions はキーの位置
@InternalCreateScriptProjectApi
public class JsonObject(public val members: Map<String, JsonValue>, internal val keyPositions: Map<String, Pair<Int, Int>>, override val line: Int, override val column: Int) : JsonValue

@InternalCreateScriptProjectApi
public class JsonArray(public val elements: List<JsonValue>, override val line: Int, override val column: Int) : JsonValue

@InternalCreateScriptProjectApi
public data class JsonString(val value: String, override val line: Int, override val column: Int) : JsonValue

// 数値は整数だけ（64 ビット）。小数点・指数のある数値はエラーにする（プロジェクトのファイルに小数を使う項目は無い）
@InternalCreateScriptProjectApi
public data class JsonInteger(val value: Long, override val line: Int, override val column: Int) : JsonValue

@InternalCreateScriptProjectApi
public data class JsonBoolean(val value: Boolean, override val line: Int, override val column: Int) : JsonValue

@InternalCreateScriptProjectApi
public data class JsonNull(override val line: Int, override val column: Int) : JsonValue

// value はエラーが無いときだけ
@InternalCreateScriptProjectApi
public class JsonResult(public val value: JsonValue?, public val diagnostics: List<ProjectDiagnostic>)

// RFC 8259 の厳密な JSON の構文解析器（S8.1）。既存のライブラリを使わない理由は docs/packages.md に記す。
// - 入力は UTF-8 のバイト列。maxBytes を超えるもの・UTF-8 として正しくないもの・BOM で始まるものは拒否する
// - コメント・末尾のカンマ・単引用符・引用符の無いキー・NaN などは使えない（RFC 8259 と同じ）。文字列の中の制御文字と、対にならないサロゲートは拒否する
// - 同じオブジェクトの中の同じキーは、どの深さでもエラー（後の値で上書きしない）
// - 入れ子は maxDepth まで。再帰せずに、読みかけのオブジェクト・配列を積んで読む（深い入れ子でも JVM のスタックを使わない）
// - 最初の誤りで止める（誤った JSON の後ろの部分は、意味のある形で解釈できないため）
@InternalCreateScriptProjectApi
public class JsonParser private constructor(private val text: String, private val maxDepth: Int) {
    private var index = 0
    private var line = 1
    private var column = 1

    private class Failure(val diagnostic: ProjectDiagnostic) : RuntimeException(null, null, false, false)

    // 読みかけのオブジェクト（次の値を入れるキー）と配列
    private class ObjectFrame(val line: Int, val column: Int) {
        val members = LinkedHashMap<String, JsonValue>()
        val keyPositions = HashMap<String, Pair<Int, Int>>()
        var key: String? = null
    }

    private class ArrayFrame(val line: Int, val column: Int) {
        val elements = ArrayList<JsonValue>()
    }

    public companion object {
        internal const val DEFAULT_MAX_DEPTH = 16

        public fun parse(bytes: ByteArray, maxBytes: Int, maxDepth: Int = DEFAULT_MAX_DEPTH): JsonResult {
            fun failed(code: ProjectDiagnosticCode, vararg arguments: String) = JsonResult(null, listOf(ProjectDiagnostic(code, 1, 1, arguments.toList())))
            if (bytes.size > maxBytes) return failed(ProjectDiagnosticCode.FILE_TOO_LARGE, maxBytes.toString())
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return failed(ProjectDiagnosticCode.BYTE_ORDER_MARK)
            val text = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (error: CharacterCodingException) {
                return failed(ProjectDiagnosticCode.NOT_UTF8)
            }
            return parse(text, maxDepth)
        }

        public fun parse(text: String, maxDepth: Int = DEFAULT_MAX_DEPTH): JsonResult {
            require(maxDepth > 0) { "maxDepth must be positive: $maxDepth" }
            return try {
                JsonResult(JsonParser(text, maxDepth).document(), emptyList())
            } catch (failure: Failure) {
                JsonResult(null, listOf(failure.diagnostic))
            }
        }
    }

    private fun document(): JsonValue {
        val stack = ArrayList<Any>()
        var result: JsonValue? = null
        // 値を 1 つ読む → 囲むオブジェクト・配列に入れる、を繰り返す
        value@ while (true) {
            skipWhitespace()
            val startLine = line
            val startColumn = column
            val c = peek()
            var value: JsonValue? = when {
                c == null -> syntax("unexpected end of the file, expected a value")
                c == '{' -> {
                    if (stack.size >= maxDepth) fail(ProjectDiagnosticCode.JSON_TOO_DEEP, maxDepth.toString())
                    advance()
                    skipWhitespace()
                    if (peek() == '}') {
                        advance()
                        JsonObject(emptyMap(), emptyMap(), startLine, startColumn)
                    } else {
                        val frame = ObjectFrame(startLine, startColumn)
                        stack.add(frame)
                        readKey(frame)
                        continue@value
                    }
                }
                c == '[' -> {
                    if (stack.size >= maxDepth) fail(ProjectDiagnosticCode.JSON_TOO_DEEP, maxDepth.toString())
                    advance()
                    skipWhitespace()
                    if (peek() == ']') {
                        advance()
                        JsonArray(emptyList(), startLine, startColumn)
                    } else {
                        stack.add(ArrayFrame(startLine, startColumn))
                        continue@value
                    }
                }
                c == '"' -> JsonString(readString(), startLine, startColumn)
                c == 't' -> literal("true", JsonBoolean(true, startLine, startColumn))
                c == 'f' -> literal("false", JsonBoolean(false, startLine, startColumn))
                c == 'n' -> literal("null", JsonNull(startLine, startColumn))
                c == '-' || isAsciiDigit(c) -> readNumber(startLine, startColumn)
                else -> syntax("unexpected character '$c', expected a value")
            }
            // 読み終えた値を、囲むオブジェクト・配列に入れる。閉じたものは、さらに外側に入れる
            while (true) {
                val top = stack.lastOrNull()
                if (top == null) {
                    result = value
                    break@value
                }
                skipWhitespace()
                when (top) {
                    is ObjectFrame -> {
                        top.members[top.key!!] = value!!
                        when (peek()) {
                            ',' -> {
                                advance()
                                readKey(top)
                                continue@value
                            }
                            '}' -> {
                                advance()
                                stack.removeAt(stack.lastIndex)
                                value = JsonObject(top.members, top.keyPositions, top.line, top.column)
                            }
                            else -> syntax("expected ',' or '}' in an object")
                        }
                    }
                    is ArrayFrame -> {
                        top.elements.add(value!!)
                        when (peek()) {
                            ',' -> {
                                advance()
                                continue@value
                            }
                            ']' -> {
                                advance()
                                stack.removeAt(stack.lastIndex)
                                value = JsonArray(top.elements, top.line, top.column)
                            }
                            else -> syntax("expected ',' or ']' in an array")
                        }
                    }
                }
            }
        }
        skipWhitespace()
        if (index < text.length) syntax("unexpected content after the end of the JSON value")
        return result!!
    }

    // オブジェクトのキーと ":"。同じオブジェクトに同じキーがあればエラー
    private fun readKey(frame: ObjectFrame) {
        skipWhitespace()
        if (peek() != '"') syntax(if (peek() == '}') "a ',' must be followed by a key (no trailing commas)" else "expected a key in double quotes")
        val keyLine = line
        val keyColumn = column
        val key = readString()
        if (key in frame.members) throw Failure(ProjectDiagnostic(ProjectDiagnosticCode.JSON_DUPLICATE_KEY, keyLine, keyColumn, listOf(key)))
        skipWhitespace()
        if (peek() != ':') syntax("expected ':' after the key")
        advance()
        frame.key = key
        frame.keyPositions[key] = keyLine to keyColumn
    }

    private fun readString(): String {
        advance()
        val value = StringBuilder()
        while (true) {
            val c = peek() ?: syntax("unterminated string")
            when {
                c == '"' -> {
                    advance()
                    return value.toString()
                }
                c == '\\' -> {
                    advance()
                    val escape = peek() ?: syntax("unterminated string")
                    when (escape) {
                        '"', '\\', '/' -> value.append(escape)
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000C')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            advance()
                            val unit = readHex()
                            // サロゲートは \uD8xx\uDCxx の対でだけ使える
                            if (unit in 0xD800..0xDBFF) {
                                if (peek() != '\\' || text.getOrNull(index + 1) != 'u') syntax("a high surrogate must be followed by a low surrogate")
                                advance()
                                advance()
                                val low = readHex()
                                if (low !in 0xDC00..0xDFFF) syntax("a high surrogate must be followed by a low surrogate")
                                value.append(unit.toChar()).append(low.toChar())
                            } else {
                                if (unit in 0xDC00..0xDFFF) syntax("a low surrogate without a high surrogate")
                                value.append(unit.toChar())
                            }
                            continue
                        }
                        else -> syntax("invalid escape '\\$escape'")
                    }
                    advance()
                }
                c < ' ' -> syntax("control characters must be escaped in strings")
                else -> {
                    value.append(c)
                    advance()
                }
            }
        }
    }

    private fun readHex(): Int {
        var unit = 0
        repeat(4) {
            val c = peek() ?: syntax("expected 4 hexadecimal digits after \\u")
            val digit = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> syntax("expected 4 hexadecimal digits after \\u")
            }
            unit = unit * 16 + digit
            advance()
        }
        return unit
    }

    // -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?。小数点・指数のあるものは、形が正しくても整数でないエラー
    private fun readNumber(startLine: Int, startColumn: Int): JsonInteger {
        val start = index
        if (peek() == '-') advance()
        when {
            peek() == '0' -> {
                advance()
                if (isAsciiDigit(peek())) syntax("numbers must not have leading zeros")
            }
            isAsciiDigit(peek()) -> while (isAsciiDigit(peek())) advance()
            else -> syntax("expected a digit")
        }
        var integer = true
        if (peek() == '.') {
            integer = false
            advance()
            if (!isAsciiDigit(peek())) syntax("expected a digit after '.'")
            while (isAsciiDigit(peek())) advance()
        }
        if (peek() == 'e' || peek() == 'E') {
            integer = false
            advance()
            if (peek() == '+' || peek() == '-') advance()
            if (!isAsciiDigit(peek())) syntax("expected a digit in the exponent")
            while (isAsciiDigit(peek())) advance()
        }
        if (!integer) throw Failure(ProjectDiagnostic(ProjectDiagnosticCode.JSON_NUMBER_NOT_INTEGER, startLine, startColumn, listOf(text.substring(start, index))))
        val digits = text.substring(start, index)
        val value = digits.toLongOrNull() ?: throw Failure(ProjectDiagnostic(ProjectDiagnosticCode.JSON_NUMBER_OUT_OF_RANGE, startLine, startColumn, listOf(digits)))
        return JsonInteger(value, startLine, startColumn)
    }

    private fun <T : JsonValue> literal(word: String, value: T): T {
        if (!text.startsWith(word, index)) syntax("unexpected character '${peek()}', expected a value")
        repeat(word.length) { advance() }
        return value
    }

    // JSON の空白は空白・タブ・改行・復帰だけ
    private fun skipWhitespace() {
        while (true) {
            when (peek()) {
                ' ', '\t', '\n', '\r' -> advance()
                else -> return
            }
        }
    }

    private fun peek(): Char? = text.getOrNull(index)

    // ASCII の数字だけ（Char.isDigit は他の文字体系の数字も含むため使わない）
    private fun isAsciiDigit(c: Char?) = c != null && c in '0'..'9'

    // 改行（\n、\r\n、単独の \r）で行を進める
    private fun advance() {
        val c = text[index]
        index++
        if (c == '\n' || (c == '\r' && text.getOrNull(index) != '\n')) {
            line++
            column = 1
        } else {
            column++
        }
    }

    private fun syntax(message: String): Nothing = throw Failure(ProjectDiagnostic(ProjectDiagnosticCode.JSON_SYNTAX, line, column, listOf(message)))

    private fun fail(code: ProjectDiagnosticCode, vararg arguments: String): Nothing = throw Failure(ProjectDiagnostic(code, line, column, arguments.toList()))
}

// 決まった形の JSON の書き出し（S8.1）。2 字下げ、キーと値の間は ": "、改行は LF、末尾に改行 1 つ。空のオブジェクト・配列は {}・[]。
// 文字列は " と \ と制御文字（U+0000〜U+001F・U+007F）だけをエスケープし、それ以外は UTF-8 のまま書く。メンバーは渡された順に書く（順は呼び出し側が決める）。
// 入れ子が深くても再帰しない
@InternalCreateScriptProjectApi
public object JsonWriter {
    public fun write(value: JsonValue): String {
        val out = StringBuilder()
        // 書く値（と字下げ）か、書き終えた後の閉じ括弧
        val pending = ArrayList<Any>()
        pending.add(Item(value, 0, null, false))
        while (pending.isNotEmpty()) {
            when (val next = pending.removeAt(pending.lastIndex)) {
                is String -> out.append(next)
                is Item -> {
                    indent(out, next.depth)
                    next.key?.let { string(out, it); out.append(": ") }
                    val current = next.value
                    when (current) {
                        is JsonObject -> if (current.members.isEmpty()) {
                            out.append("{}")
                        } else {
                            out.append("{\n")
                            pending.add(closing(next, "}"))
                            val entries = current.members.entries.toList()
                            for (i in entries.indices.reversed()) pending.add(Item(entries[i].value, next.depth + 1, entries[i].key, i < entries.lastIndex))
                        }
                        is JsonArray -> if (current.elements.isEmpty()) {
                            out.append("[]")
                        } else {
                            out.append("[\n")
                            pending.add(closing(next, "]"))
                            for (i in current.elements.indices.reversed()) pending.add(Item(current.elements[i], next.depth + 1, null, i < current.elements.lastIndex))
                        }
                        is JsonString -> string(out, current.value)
                        is JsonInteger -> out.append(current.value)
                        is JsonBoolean -> out.append(current.value)
                        is JsonNull -> out.append("null")
                    }
                    if (current !is JsonObject && current !is JsonArray || isEmpty(current)) out.append(if (next.comma) ",\n" else "\n")
                }
            }
        }
        return out.toString()
    }

    private class Item(val value: JsonValue, val depth: Int, val key: String?, val comma: Boolean)

    private fun isEmpty(value: JsonValue) = (value is JsonObject && value.members.isEmpty()) || (value is JsonArray && value.elements.isEmpty())

    private fun closing(item: Item, bracket: String) = "  ".repeat(item.depth) + bracket + if (item.comma) ",\n" else "\n"

    private fun indent(out: StringBuilder, depth: Int) {
        repeat(depth) { out.append("  ") }
    }

    private fun string(out: StringBuilder, value: String) {
        out.append('"')
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\t' -> out.append("\\t")
                c == '\r' -> out.append("\\r")
                c < ' ' || c == '\u007f' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    // 書き出し用の値を作る（位置は使わないので 0）
    public fun obj(vararg members: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*members), emptyMap(), 0, 0)

    public fun array(elements: List<JsonValue>): JsonArray = JsonArray(elements, 0, 0)

    public fun string(value: String): JsonString = JsonString(value, 0, 0)

    public fun integer(value: Long): JsonInteger = JsonInteger(value, 0, 0)
}
