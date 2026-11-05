package com.creatrbypc.createanalytical.project

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

// テスト用の ZIP の組み立て（S8.2 Phase 1）。正しくない ZIP（悪意のあるアーカイブ）を作るため、各項目の値を自由に変えられる
internal class ZipBuilder {
    class Entry(
        var name: String,
        var data: ByteArray,
        var method: Int = 0,
        var flags: Int = 0,
        var madeBy: Int = (3 shl 8) or 20,
        var external: Long = 0x81A4L shl 16,
        // null なら中身から計算する
        var crc: Long? = null,
        var compressedSize: Long? = null,
        var size: Long? = null,
        // ローカルヘッダーだけの値（null なら中央ディレクトリと同じ）
        var localName: String? = null,
        var localSize: Long? = null,
        var nameBytes: ByteArray? = null,
    )

    val entries = ArrayList<Entry>()
    var prefix = ByteArray(0)
    var gapAfterFirst = ByteArray(0)
    var beforeCentral = ByteArray(0)
    var suffix = ByteArray(0)
    var comment = ByteArray(0)

    // 中央ディレクトリで、ある項目のローカルヘッダーの位置を別の項目と同じにする（重なり）
    var overlapSecondWithFirst = false
    var endEntries: Int? = null
    var endDisk = 0

    fun add(name: String, data: ByteArray, configure: Entry.() -> Unit = {}): ZipBuilder {
        entries.add(Entry(name, data).apply(configure))
        return this
    }

    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        out.write(prefix)
        val offsets = ArrayList<Int>()
        for ((index, entry) in entries.withIndex()) {
            val stored = if (entry.method == 8) deflate(entry.data) else entry.data
            val crc = entry.crc ?: CRC32().apply { update(entry.data) }.value
            val compressed = entry.compressedSize ?: stored.size.toLong()
            val size = entry.size ?: entry.data.size.toLong()
            val name = entry.nameBytes ?: entry.name.toByteArray(Charsets.ISO_8859_1)
            val localName = entry.localName?.toByteArray(Charsets.ISO_8859_1) ?: name
            offsets.add(out.size())
            out.i32(0x04034b50); out.i16(20); out.i16(entry.flags); out.i16(entry.method); out.i16(0); out.i16(0x21)
            out.i32(crc); out.i32(compressed); out.i32(entry.localSize ?: size); out.i16(localName.size); out.i16(0)
            out.write(localName); out.write(stored)
            if (index == 0) out.write(gapAfterFirst)
            val offset = if (overlapSecondWithFirst && index == 1) offsets[0] else offsets[index]
            central.i32(0x02014b50); central.i16(entry.madeBy); central.i16(20); central.i16(entry.flags); central.i16(entry.method); central.i16(0); central.i16(0x21)
            central.i32(crc); central.i32(compressed); central.i32(size); central.i16(name.size); central.i16(0); central.i16(0); central.i16(endDisk); central.i16(0)
            central.i32(entry.external); central.i32(offset.toLong() - prefix.size * 0L); central.write(name)
        }
        out.write(beforeCentral)
        val centralOffset = out.size()
        out.write(central.toByteArray())
        val count = endEntries ?: entries.size
        out.i32(0x06054b50); out.i16(endDisk); out.i16(endDisk); out.i16(count); out.i16(count)
        out.i32(central.size().toLong()); out.i32(centralOffset.toLong()); out.i16(comment.size); out.write(comment)
        out.write(suffix)
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.i16(value: Int) {
        write(value and 0xFF); write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.i32(value: Long) {
        for (shift in 0..24 step 8) write(((value shr shift) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.i32(value: Int) = i32(value.toLong() and 0xFFFFFFFFL)

    companion object {
        val MANIFEST = """{"schemaVersion": 2, "kind": "library", "name": "vector-math", "version": "1.2.0", "modules": ["vector_math"]}""".toByteArray()
        val MODULE = "fun length(x: Double, y: Double): Double {\n    return sqrt(x * x + y * y)\n}\n".toByteArray()

        // 正しいパッケージの中身（そこから 1 か所だけ変えて使う）
        fun valid(): ZipBuilder = ZipBuilder().add("createscript.json", MANIFEST).add("modules/vector_math.cscr", MODULE)
    }
}
