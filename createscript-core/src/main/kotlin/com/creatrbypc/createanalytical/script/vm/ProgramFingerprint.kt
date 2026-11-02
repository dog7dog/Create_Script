package com.creatrbypc.createanalytical.script.vm

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

// 保存した実行状態が、どのプログラムのものかを確かめるためのハッシュ（D14。SHA-256、32 バイト）
object ProgramFingerprint {
    const val SIZE = 32

    // ソース（UTF-8）のハッシュ。カードのコードが変わっていないかを確かめる
    fun ofSource(source: String): ByteArray = sha256(source.toByteArray(Charsets.UTF_8))

    // コンパイル結果を決まった形に並べたもののハッシュ。同じソースでも Compiler の変更で命令が変われば値が変わる
    fun ofProgram(program: CompiledProgram): ByteArray {
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

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
