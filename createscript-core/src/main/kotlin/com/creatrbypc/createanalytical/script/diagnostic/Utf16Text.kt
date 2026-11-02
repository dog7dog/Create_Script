package com.creatrbypc.createanalytical.script.diagnostic

// text を最大 maxLength UTF-16 コード単位に短縮する。切る位置がサロゲートペアの間に当たる場合は、ペアの手前で切る
fun truncateUtf16(text: String, maxLength: Int): String {
    if (text.length <= maxLength) return text
    var end = maxLength
    if (end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return text.substring(0, end)
}
