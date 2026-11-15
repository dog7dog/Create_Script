package com.creatrbypc.createanalytical.project

// 署名する JSON の正規形（S8.3 Phase 1。docs/registry.md の「Canonical form of the index」）。
// RFC 8785（JSON Canonicalization Scheme, JCS）を、このプロジェクトの JSON（数値は整数だけ）に絞ったもの:
// - 空白を入れない。オブジェクトのキーは UTF-16 のコード単位の順（Kotlin の String.compareTo と同じ）に並べる
// - 文字列は " と \ と U+0000〜U+001F だけをエスケープする（\b \t \n \f \r と、それ以外は \u00xx の小文字の 16 進数）。ほかの文字は UTF-8 のまま
// - 整数は 10 進数で、-(2^53-1)〜2^53-1 だけ（JCS の数値の書き方と同じになる範囲）。それを超える整数・対にならないサロゲートは書けない（IllegalArgumentException）
// 同じ値からは、必ず同じバイト列になる。入れ子が深くても再帰しない
@InternalCreateScriptProjectApi
public object CanonicalJson {
    internal const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    public fun write(value: JsonValue): ByteArray {
        val out = StringBuilder()
        // 書く値か、そのまま書く文字列（区切り・キー・閉じ括弧）
        val pending = ArrayList<Any>()
        pending.add(value)
        while (pending.isNotEmpty()) {
            when (val next = pending.removeAt(pending.lastIndex)) {
                is String -> out.append(next)
                is JsonObject -> {
                    out.append('{')
                    pending.add("}")
                    val keys = next.members.keys.sorted()
                    for (i in keys.indices.reversed()) {
                        pending.add(next.members.getValue(keys[i]))
                        pending.add((if (i > 0) "," else "") + quoted(keys[i]) + ":")
                    }
                }
                is JsonArray -> {
                    out.append('[')
                    pending.add("]")
                    for (i in next.elements.indices.reversed()) {
                        pending.add(next.elements[i])
                        if (i > 0) pending.add(",")
                    }
                }
                is JsonString -> out.append(quoted(next.value))
                is JsonInteger -> {
                    require(next.value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) { "the integer ${next.value} is outside -(2^53-1)..2^53-1" }
                    out.append(next.value)
                }
                is JsonBoolean -> out.append(next.value)
                is JsonNull -> out.append("null")
            }
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun quoted(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\t' -> out.append("\\t")
                c == '\n' -> out.append("\\n")
                c == '\u000C' -> out.append("\\f")
                c == '\r' -> out.append("\\r")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                c.isHighSurrogate() -> {
                    require(i + 1 < value.length && value[i + 1].isLowSurrogate()) { "a string has an unpaired surrogate" }
                    out.append(c).append(value[i + 1])
                    i++
                }
                c.isLowSurrogate() -> throw IllegalArgumentException("a string has an unpaired surrogate")
                else -> out.append(c)
            }
            i++
        }
        return out.append('"').toString()
    }
}
