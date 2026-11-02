package com.creatrbypc.createanalytical.script.vm

// CS_VM の実行途中の状態（D14）。命令と命令の間の状態だけを表す。Minecraft に依存しないデータで、保存形式への変換は Integration 側が行う。
// state は RUNNING / WAITING だけ（HALTED / ERROR の VM は Scheduler から外れるため保存しない）
data class VmSnapshot(
    val pc: Int,
    val state: VmState,
    val waitRemaining: Int,
    // waitTicks から再開するとき、結果の Unit をまだ積んでいない（A23）
    val resumeWithUnit: Boolean,
    val stack: List<Value>,
    // 変数の値（null は未初期化）。個数はプログラムの変数の数と同じ
    val variables: List<Value?>,
    val consoleLines: List<String>,
    // 呼び出し中の関数（外側から順。トップレベルの実行中は空。第3.8c段階）。pc は最も内側の関数の命令位置
    val callStack: List<CallFrameSnapshot> = emptyList(),
    // オブジェクトのヒープ（id の昇順）と、次に発行するオブジェクトの id（第3.9a段階）
    val objects: List<ObjectSnapshot> = emptyList(),
    val nextObjectId: Int = 1,
    // 乱数の状態（第3.11e段階。SplitMix64 の 64 ビット。どの値も有効）
    val randomState: Long = 0L,
)

// オブジェクト1つ分の実体。classId は CompiledProgram.classes の番号。fields はプロパティの値（第3.9b段階。宣言の順で、
// null は未初期化。初期化中のオブジェクトだけが持つ）。list はリスト（第3.10a段階。リストは classId が LIST_CLASS でプロパティが無い。
// Vector は VECTOR_CLASS、Matrix は MATRIX_CLASS: 第3.11e段階）
data class ObjectSnapshot(val id: Int, val classId: Int, val fields: List<Value?> = emptyList(), val list: ListSnapshot? = null) {
    companion object {
        const val LIST_CLASS = -1

        // Vector（第3.11e段階）。変更できない Double のリスト（要素は 1〜16 個）
        const val VECTOR_CLASS = -2

        // Matrix（第3.11e段階）。変更できない、行の Vector（1〜8 個。長さが同じで 1〜8）のリスト
        const val MATRIX_CLASS = -3
    }
}

// リストの中身（第3.10a段階）。element は要素の型、mutable は変更できるリスト（mutableListOf）か、elements は要素（先頭から）
data class ListSnapshot(val element: SnapshotVerifier.SlotType, val mutable: Boolean, val elements: List<Value>)

// 呼び出しフレーム1つ分。function は CompiledProgram.functions の番号、returnPc は戻り先（CALL の次の命令）、locals はローカル変数（null は未初期化）
data class CallFrameSnapshot(val function: Int, val returnPc: Int, val locals: List<Value?>)
