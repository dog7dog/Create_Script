package com.creatrbypc.createanalytical.script.vm

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * SHA-256 hashes that tell whether a saved run ([VmSnapshot]) belongs to a program. `CreateScript.resume` checks
 * only the shape of a saved state, so a host that saves runs stores these next to it and resumes only when they
 * are the same. Each function returns a new array of [SIZE] bytes.
 */
public object ProgramFingerprint {
    /** The size of every fingerprint in bytes (32). */
    public const val SIZE: Int = 32

    /** The hash of [source] as UTF-8: changes when the source text changes. */
    public fun ofSource(source: String): ByteArray = sha256(source.toByteArray(Charsets.UTF_8))

    /**
     * The hash of the entry source and its modules ([modules] maps module name or id to source). Without modules it
     * equals [ofSource]`(entry)`. It does not depend on the order of `import`s, and changes when any file changes.
     * `ModuleCompileResult.sourceFingerprint` is this value for a compilation.
     */
    public fun ofModules(entry: String, modules: Map<String, String>): ByteArray {
        if (modules.isEmpty()) return ofSource(entry)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(MODULES_MAGIC)
            writeText(out, entry)
            out.writeInt(modules.size)
            for (name in modules.keys.sorted()) {
                writeText(out, name)
                writeText(out, modules.getValue(name))
            }
        }
        return sha256(bytes.toByteArray())
    }

    // "CSMOD" と 0 と版 1。0 を含むため、単一のソース（NUL を含むソースはコンパイルできない）の ofSource とは前置きが重ならない
    private val MODULES_MAGIC = byteArrayOf(0x43, 0x53, 0x4D, 0x4F, 0x44, 0x00, 0x01)

    private fun writeText(out: DataOutputStream, text: String) {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        out.writeInt(utf8.size)
        out.write(utf8)
    }

    /**
     * The hash of the compiled program. It can change for the same source when a new release of the compiler
     * produces different instructions, so compare it as well as the source fingerprint.
     */
    public fun ofProgram(program: CompiledProgram): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(program.instructions.size)
            for (instruction in program.instructions) {
                out.writeUTF(instruction.opcode.name)
                out.writeInt(instruction.operand)
                out.writeInt(instruction.position.offset)
                out.writeInt(instruction.position.line)
                out.writeInt(instruction.position.column)
            }
            out.writeInt(program.constants.size)
            for (constant in program.constants) {
                when (constant) {
                    is Constant.IntConstant -> {
                        out.writeByte(0)
                        out.writeInt(constant.value)
                    }
                    is Constant.StringConstant -> {
                        out.writeByte(1)
                        val utf8 = constant.value.toByteArray(Charsets.UTF_8)
                        out.writeInt(utf8.size)
                        out.write(utf8)
                    }
                    // 浮動小数点数（第3.11a段階）はビット列
                    is Constant.FloatConstant -> {
                        out.writeByte(2)
                        out.writeInt(constant.bits)
                    }
                    is Constant.DoubleConstant -> {
                        out.writeByte(3)
                        out.writeLong(constant.bits)
                    }
                }
            }
            out.writeInt(program.variableCount)
            out.writeInt(program.maxStackDepth)
            // 関数の無いプログラム（第3.8b段階まで）のハッシュは変えない
            if (program.functions.isNotEmpty()) {
                out.writeInt(program.functions.size)
                for (function in program.functions) {
                    out.writeInt(function.entry)
                    out.writeInt(function.localCount)
                }
                // 引数のある関数（第3.8d段階）があるときだけ引数の数を加える（引数の無い関数だけのプログラムのハッシュは第3.8c段階と同じ）
                if (program.functions.any { it.parameterCount > 0 }) program.functions.forEach { out.writeInt(it.parameterCount) }
            }
            // クラスのあるプログラム（第3.9a段階）だけクラスの一覧を加える（それ以前のプログラムのハッシュは変えない）
            if (program.classes.isNotEmpty()) {
                out.writeInt(program.classes.size)
                program.classes.forEach { out.writeUTF(it.name) }
                // プロパティかコンストラクタのあるクラス（第3.9b段階）があるときだけ加える（第3.9a段階のプログラムのハッシュは変えない）
                if (program.classes.any { it.fields.isNotEmpty() || it.constructor != -1 }) {
                    for (info in program.classes) {
                        out.writeInt(info.constructor)
                        out.writeInt(info.fields.size)
                        for (field in info.fields) {
                            out.writeUTF(field.name)
                            writeType(out, field.type)
                        }
                    }
                }
            }
            // リストを作るプログラム（第3.10a段階）だけリストの形を加える（それ以前のプログラムのハッシュは変えない）
            if (program.lists.isNotEmpty()) {
                out.writeInt(program.lists.size)
                for (shape in program.lists) {
                    writeType(out, shape.element)
                    out.writeBoolean(shape.mutable)
                    out.writeInt(shape.size)
                }
            }
            // エラーフォールバックの範囲（第9.2段階）があるときだけ、印（負の数。リストの形の数とは重ならない）の後に加える（それ以前のプログラムのハッシュは変えない）
            if (program.handlers.isNotEmpty()) {
                out.writeInt(HANDLERS_MARK)
                out.writeInt(program.handlers.size)
                for (handler in program.handlers) {
                    out.writeInt(handler.start)
                    out.writeInt(handler.end)
                    out.writeInt(handler.handler)
                    out.writeInt(handler.stackDepth)
                }
            }
        }
        return sha256(bytes.toByteArray())
    }

    // Int・Boolean・String・Unit は負の数、オブジェクトはクラスの番号（0 以上）、リストは -5 の後に要素の型（第3.10a段階）。
    // 入れ子のリスト（第3.10b段階）は段の数だけ -5 を書いてから内側の型を書く（再帰しない）。
    // Float・Double（第3.11a段階）は保存データの型の表と同じ Int.MIN_VALUE・Int.MIN_VALUE + 1（それ以前のプログラムのハッシュは変えない）。
    // Vector・Matrix（第3.11e段階）は Int.MIN_VALUE + 2・+ 3
    private fun writeType(out: DataOutputStream, type: SnapshotVerifier.SlotType) {
        var current = type
        while (current is SnapshotVerifier.SlotType.LIST) {
            out.writeInt(-5)
            current = current.element
        }
        when (current) {
            SnapshotVerifier.SlotType.INT -> out.writeInt(-1)
            SnapshotVerifier.SlotType.BOOLEAN -> out.writeInt(-2)
            SnapshotVerifier.SlotType.STRING -> out.writeInt(-3)
            SnapshotVerifier.SlotType.UNIT -> out.writeInt(-4)
            SnapshotVerifier.SlotType.FLOAT -> out.writeInt(Int.MIN_VALUE)
            SnapshotVerifier.SlotType.DOUBLE -> out.writeInt(Int.MIN_VALUE + 1)
            SnapshotVerifier.SlotType.VECTOR -> out.writeInt(Int.MIN_VALUE + 2)
            SnapshotVerifier.SlotType.MATRIX -> out.writeInt(Int.MIN_VALUE + 3)
            is SnapshotVerifier.SlotType.OBJECT -> out.writeInt(current.classId)
            is SnapshotVerifier.SlotType.LIST -> error("unreachable")
        }
    }

    private const val HANDLERS_MARK = -0x3f21

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
