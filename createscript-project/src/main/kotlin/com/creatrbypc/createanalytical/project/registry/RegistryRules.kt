package com.creatrbypc.createanalytical.project.registry

import com.creatrbypc.createanalytical.project.InternalCreateScriptProjectApi
import com.creatrbypc.createanalytical.project.ProjectRules
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// CreateScript-Registry の値の規則（S8.3 Phase 1。docs/registry.md）。パッケージ名・版・取得元・SHA-256 は S8.2 と同じ規則（ProjectRules）を使う
@InternalCreateScriptProjectApi
public object RegistryRules {
    // GitHub の数値 ID・PR の番号・通し番号の上限（2^53-1。正規形の JSON（RFC 8785）で正確に書ける整数の範囲）
    public const val MAX_ID: Long = 9_007_199_254_740_991L

    // 説明・理由の長さ（コードポイント）
    internal const val MAX_TEXT_LENGTH = 200

    internal const val MAX_KEY_ID_LENGTH = 64

    // GitHub のユーザー名（英数字と、間に 1 つずつのハイフンで 39 文字まで。ProjectRules の取得元の持ち主と同じ規則）
    private val LOGIN = Regex("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}")

    // 署名の鍵の ID（例: createscript-registry-2026-1）
    private val KEY_ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")

    private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    // 日時は UTC の秒まで（小数の秒・時差の表記は使わない）
    private val TIMESTAMP = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")

    public fun isValidId(value: Long): Boolean = value in 1..MAX_ID

    public fun isValidLogin(login: String): Boolean = LOGIN.matches(login)

    internal fun isValidKeyId(keyId: String) = keyId.length <= MAX_KEY_ID_LENGTH && KEY_ID.matches(keyId)

    // レジストリの版: S8.2 の版の規則に加えて、ビルドメタデータ（+…）を使わない（1.0.0+a と 1.0.0+b は SemVer では同じ順位で、取り違えやすい）
    internal fun isValidRegistryVersion(version: String) = ProjectRules.isValidVersion(version) && '+' !in version

    // YYYY-MM-DD（存在する日付だけ）。正しくなければ null
    internal fun parseDate(text: String): LocalDate? {
        if (!DATE.matches(text)) return null
        return try {
            LocalDate.parse(text).takeIf { it.toString() == text }
        } catch (error: DateTimeParseException) {
            null
        }
    }

    // YYYY-MM-DDTHH:MM:SSZ（存在する日時だけ。うるう秒の :60 も使わない）。正しくなければ null
    public fun parseTimestamp(text: String): Instant? {
        if (!TIMESTAMP.matches(text)) return null
        return try {
            Instant.parse(text).takeIf { formatTimestamp(it) == text }
        } catch (error: DateTimeParseException) {
            null
        }
    }

    // 秒の単位の Instant を YYYY-MM-DDTHH:MM:SSZ にする
    public fun formatTimestamp(instant: Instant): String {
        require(instant.nano == 0) { "timestamps are whole seconds: $instant" }
        return DateTimeFormatter.ISO_INSTANT.format(instant)
    }

    // 説明・理由の文字列が使えなければ、その理由。使えれば null。人が読む文字列なので Unicode を使えるが、表示を偽れる文字は使えない:
    // 制御文字・書式の制御文字（双方向テキストの制御文字など）・行と段落の区切り・対にならないサロゲート・私用の文字・非文字。
    // 同じ見た目の文字列が別のバイト列にならないように、NFC で正規化してあることを求める
    internal fun textProblem(text: String, maxLength: Int = MAX_TEXT_LENGTH): String? {
        if (text.isEmpty()) return "it is empty"
        if (text.codePointCount(0, text.length) > maxLength) return "it is longer than $maxLength characters"
        if (text.first().isWhitespace() || text.last().isWhitespace()) return "it starts or ends with a space"
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val type = Character.getType(codePoint).toByte()
            val forbidden = type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR ||
                type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE || type == Character.PRIVATE_USE ||
                codePoint in 0xFDD0..0xFDEF || codePoint and 0xFFFE == 0xFFFE
            if (forbidden) return "it contains U+%04X, which is not allowed (control, format, separator, private use or noncharacter)".format(codePoint)
            i += Character.charCount(codePoint)
        }
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) return "it is not in Unicode normalization form NFC"
        return null
    }

    // 取得元が同じか（GitHub の持ち主・リポジトリの名前は大文字・小文字を区別しない）
    public fun sameSource(a: String, b: String): Boolean = a.lowercase() == b.lowercase()

    // 取り違えやすい名前を比べるための形: ハイフンを除き、0 を o、1 を l にする（vector-math・vectormath・vect0r-math は同じ形）
    internal fun skeleton(name: String): String = buildString {
        for (c in name) {
            when (c) {
                '-' -> {}
                '0' -> append('o')
                '1' -> append('l')
                else -> append(c)
            }
        }
    }

    // others のうち、name と似ている名前（形が同じか、形の編集距離（1 文字の挿入・削除・置換・隣り合う 2 文字の入れ替え）が 1）。name そのものは除く。
    // 形が同じものは登録できない（エラー）。距離 1 のものは、審査する人への知らせに使う（エラーにはしない）
    public fun similarNames(name: String, others: Collection<String>): List<String> {
        val form = skeleton(name)
        return others.filter { it != name && editDistanceAtMostOne(form, skeleton(it)) }.sorted()
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return when {
            a.length == b.length -> a.substring(i + 1) == b.substring(i + 1) ||
                (i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2))
            a.length > b.length -> a.substring(i + 1) == b.substring(i)
            else -> a.substring(i) == b.substring(i + 1)
        }
    }

    // パッケージのファイルの置き場所: packages/<名前の最初の文字>/<名前>.json
    public fun entryPath(name: String): String = "packages/${name.first()}/$name.json"
}
