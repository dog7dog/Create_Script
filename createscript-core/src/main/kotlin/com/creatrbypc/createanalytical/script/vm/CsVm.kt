package com.creatrbypc.createanalytical.script.vm

import com.creatrbypc.createanalytical.script.diagnostic.Diagnostic
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticPhase
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticSeverity
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import com.creatrbypc.createanalytical.script.diagnostic.truncateUtf16
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.nextDown
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

// VM の値（型タグ付き: D14）。String は定数プールの番号で持つ（Stage 3.5 の String はリテラルだけのため）
sealed interface Value {
    data class IntValue(val value: Int) : Value
    data class BoolValue(val value: Boolean) : Value
    data class StringValue(val constantIndex: Int) : Value
    data object UnitValue : Value

    // 浮動小数点数（第3.11a段階）。値はビット列（Float.toRawBits / Double.toRawBits）のまま持つ。equals はビットの一致（NaN のビットの違いも区別し、
    // 0.0 と -0.0 は別）で、保存・復元が値を正確に戻したかの確認だけに使う。スクリプトの == は CsVm の IEEE 754 の比較（ieeeEquals）で行い、equals は使わない
    data class FloatValue(val bits: Int) : Value {
        val value: Float get() = Float.fromBits(bits)

        companion object {
            fun of(value: Float) = FloatValue(value.toRawBits())
        }
    }

    data class DoubleValue(val bits: Long) : Value {
        val value: Double get() = Double.fromBits(bits)

        companion object {
            fun of(value: Double) = DoubleValue(value.toRawBits())
        }
    }

    // オブジェクトの参照（第3.9a段階）。id はその CS_VM のヒープの中でだけ意味を持つ（別の VM の id とは関係が無い）
    data class ObjectRef(val id: Int) : Value
}

// プログラムの実行状態（動力による一時停止は Scheduler 側で別に管理する）
enum class VmState {
    // 実行できる
    RUNNING,

    // waitTicks の待機中
    WAITING,

    // 最後まで実行して終了した（自動では再実行しない）
    HALTED,

    // 実行時エラーで停止した
    ERROR,
}

// println の出力。最大 32 行、1行は最大 64 UTF-16 単位（D5）
class Console {
    private val lines = ArrayDeque<String>()

    val history: List<String> get() = lines.toList()

    // 外部へ通知する行（第3.12段階 S4）。ScriptRun が通知先を持つときだけ記録し、tick の終わりに取り出す。
    // 履歴（最大 32 行）から押し出された行も含めて、print した順にすべて残す（1行の長さは履歴と同じ 64 単位）
    private var captured: ArrayList<String>? = null

    fun print(text: String) {
        if (lines.size == MAX_LINES) lines.removeFirst()
        val line = fit(text)
        lines.addLast(line)
        captured?.add(line)
    }

    internal fun startCapture() {
        captured = ArrayList()
    }

    // 記録した行を返して空にする（記録していなければ空のリスト）
    internal fun drainCaptured(): List<String> {
        val current = captured ?: return emptyList()
        if (current.isEmpty()) return emptyList()
        captured = ArrayList()
        return current
    }

    // 復元時に保存済みの履歴へ置き換える（行数・長さは SnapshotVerifier で検証済み）
    internal fun replaceHistory(history: List<String>) {
        lines.clear()
        lines.addAll(history)
    }

    companion object {
        const val MAX_LINES = 32
        const val MAX_LINE_LENGTH = 64
        private const val ELLIPSIS = "…"

        // 超えた場合は「…」を含めて 64 単位に収める。サロゲートペアは分断しない（元の値は変えない）
        fun fit(text: String): String =
            if (text.length <= MAX_LINE_LENGTH) text else truncateUtf16(text, MAX_LINE_LENGTH - ELLIPSIS.length) + ELLIPSIS
    }
}

// CS_VM（CreateScript Virtual Machine）。CreateScript のスタック型 VM で、Minecraft に依存しない純粋な Kotlin。
// randomSeed は乱数（第3.11e段階）の最初の状態で、ホスト（Minecraft では Integration 側）が渡す
class CsVm(private val program: CompiledProgram, randomSeed: Long = 0L) {
    var state = VmState.RUNNING
        private set

    // ERROR になった原因（CS_RUN_*）
    var error: Diagnostic? = null
        private set

    val console = Console()

    // waitTicks の残り tick 数（WAITING のときだけ意味を持つ）
    var waitRemaining = 0
        private set

    private var pc = 0
    private val stack = ArrayList<Value>()

    // トップレベルの変数（大域変数）。関数の中からも LOAD / STORE で読み書きする
    private val variables = arrayOfNulls<Value>(program.variableCount.coerceIn(0, VmLimits.MAX_VARIABLES))

    // 呼び出し中の関数（第3.8c段階）。ローカル変数は呼び出しごとに新しいフレームに置くため、呼び出しの間で値が混ざらない。
    // stackBase はこのフレームのスタックの始まり（それより下は呼び出し元の計算途中の値: 第3.8d段階）
    private class CallFrame(val function: Int, val returnPc: Int, val locals: Array<Value?>, val stackBase: Int)

    private val callFrames = ArrayList<CallFrame>()

    // ヒープの実体（第3.9a段階）。値（Value.ObjectRef）は id だけを持ち、実体はヒープに置く。marked は回収のときだけ使う印。
    // child は回収でたどる値（プロパティ・要素）
    private sealed class HeapEntry {
        var marked = false
        abstract val childCount: Int

        abstract fun child(index: Int): Value?
    }

    // クラスのインスタンス。fields はプロパティの値（第3.9b段階。null は未初期化）
    private class Instance(val classId: Int, val fields: Array<Value?>) : HeapEntry() {
        override val childCount get() = fields.size

        override fun child(index: Int) = fields[index]
    }

    // リスト（第3.10a段階）。要素はすべて element の型の値（未初期化は無い）。mutable でない（listOf で作った）リストは変更できない。
    // classId は種類（ObjectSnapshot.LIST_CLASS のリストか、VECTOR_CLASS の Vector・MATRIX_CLASS の Matrix: 第3.11e段階。
    // Vector は変更できない Double のリスト、Matrix は変更できない行の Vector のリスト）
    private class ListEntry(
        val element: SnapshotVerifier.SlotType,
        val mutable: Boolean,
        val elements: ArrayList<Value>,
        val classId: Int = ObjectSnapshot.LIST_CLASS,
    ) : HeapEntry() {
        override val childCount get() = elements.size

        override fun child(index: Int) = elements[index]
    }

    // id → 実体。id は 1 から順に発行し、回収したオブジェクトの id も再利用しない（表示 Robot@1 が保存・復元をまたいで変わらない）
    private val heap = HashMap<Int, HeapEntry>()
    private var nextObjectId = 1

    // ヒープにあるリストの要素数の合計（回収前のリストを含む。第3.10a段階）。生成・add・removeAt・clear・回収・復元で増減し、
    // VmLimits.MAX_LIST_ELEMENTS を超えない
    private var listElements = 0

    // テストからの整合性の確認用（ヒープのリストの要素数を数え直した値と一致する）
    internal val listElementCount: Int get() = listElements

    // 回収中の状態（第3.9b段階の GC 対策）。ヒープが満杯の NEW で始め、終わるまでこの VM の命令は実行しない。
    // 回収の作業は Scheduler が命令とは別枠の予算で collectGarbage を呼んで進める。保存しない（保存・復元すると NEW からやり直す）
    private var collector: Collector? = null

    // 回収を終えた直後（次の NEW でヒープがまだ満杯なら、もう一度回収せずに CS_RUN_TOO_MANY_OBJECTS にする）
    private var collected = false

    // 回収中（命令を実行しない）
    val isCollecting: Boolean get() = collector != null

    // SUSPEND から再開するとき、呼び出しの結果として Unit を1回だけ積む（A23）
    private var resumeWithUnit = false

    // 乱数の状態（第3.11e段階。SplitMix64。VM ごとに独立し、保存・復元で同じ列が続く）
    private var randomState = randomSeed

    // 実行中の LINALG（第3.11e段階）。1 回の実行で計算の 1 単位を進め、すべて済んだら確定してオペランドを結果に置き換える。
    // 途中の状態は保存しない（スナップショットはこの命令を実行する前の状態で、復元した VM は最初から払い直す。GC の回収中の状態と同じ扱い）。
    // vectors は Vector のオペランド、matrices は Matrix のオペランドの行、columns は結果の Matrix の列数。結果は Vector なら results（matrix(…) は
    // 行の参照）、Matrix なら rows に計算の単位ごとに作り、確定は実体をヒープに置くだけ（行の数 + 1 回）にする
    private class LinalgProgress(
        val function: LinalgFunction,
        val arity: Int,
        val operands: List<Value>,
        val work: Int,
        val vectors: List<ListEntry> = emptyList(),
        val matrices: List<List<ListEntry>> = emptyList(),
        val scalar: Double = 0.0,
        val columns: Int = 0,
    ) {
        var step = 0
        var accumulator = 0.0
        val results = ArrayList<Value>()
        val rows = ArrayList<ArrayList<Value>>()

        fun cell(value: Double) {
            if (rows.isEmpty() || rows.last().size == columns) rows.add(ArrayList(columns))
            rows.last().add(Value.DoubleValue.of(value))
        }
    }

    private var linalg: LinalgProgress? = null

    // 実行時エラーを VM の外へ伝えるための内部例外
    private class RuntimeFailure : RuntimeException(null, null, false, false)

    init {
        if (program.variableCount !in 0..VmLimits.MAX_VARIABLES) recordError(DiagnosticCode.RUN_INVALID_BYTECODE, ORIGIN)
    }

    // 最大 instructionBudget 命令を実行し、実際に実行した命令数を返す（1命令 = 1単位、再開時の Unit 積み込みは数えない: A26）。
    // WAITING・HALTED・ERROR になったら、その時点で止まる
    fun run(instructionBudget: Int): Int {
        if (state != VmState.RUNNING || collector != null) return 0
        var executed = 0
        try {
            if (resumeWithUnit) {
                push(Value.UnitValue, ORIGIN)
                resumeWithUnit = false
            }
            while (state == VmState.RUNNING && collector == null && executed < instructionBudget) {
                val instruction = program.instructions.getOrNull(pc) ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, ORIGIN)
                pc++
                executed++
                execute(instruction)
                // 回収を始めた NEW は実行していない（PC は NEW に戻してある）ため、命令数に数えない
                if (collector != null) executed--
            }
        } catch (failure: RuntimeFailure) {
            // 報告済み。state は ERROR
        }
        return executed
    }

    // 実行途中の状態を写し取る（D14）。VM の状態は変更しない。RUNNING / WAITING 以外（終了・エラー）は保存しないため null
    fun snapshot(): VmSnapshot? {
        if (state != VmState.RUNNING && state != VmState.WAITING) return null
        val callStack = callFrames.map { CallFrameSnapshot(it.function, it.returnPc, it.locals.toList()) }
        // 回収の SWEEP の途中では、まだ消していないゴミが、消したゴミを参照していることがある。保存する状態には根からたどれるオブジェクトだけを含め、
        // 回収を終えた後のヒープと同じにする（回収の途中経過は保存せず、復元した VM は回収を最初からやり直す: 第3.9b段階の B 案）
        val live = if (collector?.isSweeping == true) reachableIds() else null
        val objects = heap.entries.filter { live == null || it.key in live }.sortedBy { it.key }.map { (id, entry) ->
            when (entry) {
                is Instance -> ObjectSnapshot(id, entry.classId, entry.fields.toList())
                is ListEntry -> ObjectSnapshot(id, entry.classId, list = ListSnapshot(entry.element, entry.mutable, entry.elements.toList()))
            }
        }
        return VmSnapshot(pc, state, waitRemaining, resumeWithUnit, stack.toList(), variables.toList(), console.history, callStack, objects, nextObjectId, randomState)
    }

    // 根（スタック・大域変数・呼び出しフレームのローカル変数）からたどれるオブジェクトの id（回収の印は使わない）
    private fun reachableIds(): Set<Int> {
        val reached = HashSet<Int>()
        val pending = ArrayList<HeapEntry>()
        fun visit(value: Value?) {
            if (value is Value.ObjectRef && reached.add(value.id)) heap[value.id]?.let(pending::add)
        }
        stack.forEach(::visit)
        variables.forEach(::visit)
        callFrames.forEach { it.locals.forEach(::visit) }
        while (pending.isNotEmpty()) {
            val entry = pending.removeAt(pending.lastIndex)
            for (index in 0 until entry.childCount) visit(entry.child(index))
        }
        return reached
    }

    // Scheduler の Phase 2 から、動力がありチャンクが読み込まれている tick に1回呼ぶ（D6）。
    // 残りが 0 なら実行可能に戻し、そうでなければ1減らして 0 になったら実行可能に戻す
    fun countWaitTick() {
        if (state != VmState.WAITING) return
        if (waitRemaining > 0) waitRemaining--
        if (waitRemaining == 0) state = VmState.RUNNING
    }

    private fun execute(instruction: Instruction) {
        val position = instruction.position
        when (instruction.opcode) {
            Opcode.PUSH_CONST -> push(constantValue(instruction.operand, position), position)
            Opcode.LOAD -> push(variables.getOrNull(instruction.operand) ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position), position)
            Opcode.STORE -> {
                if (instruction.operand !in variables.indices) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
                val value = pop(position)
                if (value == Value.UnitValue) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
                variables[instruction.operand] = value
            }
            Opcode.LOAD_LOCAL -> push(currentLocals(position).getOrNull(instruction.operand) ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position), position)
            Opcode.STORE_LOCAL -> {
                val locals = currentLocals(position)
                if (instruction.operand !in locals.indices) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
                val value = pop(position)
                if (value == Value.UnitValue) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
                locals[instruction.operand] = value
            }
            Opcode.POP -> pop(position)
            Opcode.ADD -> arithmetic(instruction.operand, position, { a, b -> Math.addExact(a, b) }, { a, b -> a + b }, { a, b -> a + b })
            Opcode.SUBTRACT -> arithmetic(instruction.operand, position, { a, b -> Math.subtractExact(a, b) }, { a, b -> a - b }, { a, b -> a - b })
            Opcode.MULTIPLY -> arithmetic(instruction.operand, position, { a, b -> Math.multiplyExact(a, b) }, { a, b -> a * b }, { a, b -> a * b })
            Opcode.DIVIDE -> arithmetic(
                instruction.operand,
                position,
                { a, b ->
                    if (b == 0) fail(DiagnosticCode.RUN_DIVISION_BY_ZERO, position)
                    // Int.MIN_VALUE / -1 はオーバーフロー（A11）
                    if (a == Int.MIN_VALUE && b == -1) fail(DiagnosticCode.RUN_INTEGER_OVERFLOW, position)
                    a / b
                },
                { a, b -> a / b },
                { a, b -> a / b },
            )
            // 浮動小数点数の % は JVM の % と同じ（Kotlin の rem。結果の符号は左辺と同じ）
            Opcode.REMAINDER -> arithmetic(
                instruction.operand,
                position,
                { a, b ->
                    if (b == 0) fail(DiagnosticCode.RUN_DIVISION_BY_ZERO, position)
                    // Int.MIN_VALUE % -1 は 0（A11。JVM の % と同じ）
                    a % b
                },
                { a, b -> a % b },
                { a, b -> a % b },
            )
            Opcode.NEGATE -> when (instruction.operand) {
                NumericKind.INT -> {
                    val value = popInt(position)
                    push(Value.IntValue(exact(position) { Math.negateExact(value) }), position)
                }
                // 符号のビットを反転する（0.0 は -0.0 になる）
                NumericKind.FLOAT -> push(Value.FloatValue.of(-popFloat(position)), position)
                NumericKind.DOUBLE -> push(Value.DoubleValue.of(-popDouble(position)), position)
                else -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
            }
            Opcode.PUSH_BOOL -> push(
                Value.BoolValue(
                    when (instruction.operand) {
                        0 -> false
                        1 -> true
                        else -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
                    }
                ),
                position,
            )
            Opcode.NOT -> push(Value.BoolValue(!popBool(position)), position)
            Opcode.EQUAL -> equality(instruction.operand, position, true)
            Opcode.NOT_EQUAL -> equality(instruction.operand, position, false)
            Opcode.LESS -> comparison(instruction.operand, position, { a, b -> a < b }, { a, b -> a < b }, { a, b -> a < b })
            Opcode.LESS_EQUAL -> comparison(instruction.operand, position, { a, b -> a <= b }, { a, b -> a <= b }, { a, b -> a <= b })
            Opcode.GREATER -> comparison(instruction.operand, position, { a, b -> a > b }, { a, b -> a > b }, { a, b -> a > b })
            Opcode.GREATER_EQUAL -> comparison(instruction.operand, position, { a, b -> a >= b }, { a, b -> a >= b }, { a, b -> a >= b })
            Opcode.JUMP -> pc = jumpTarget(instruction.operand, position)
            Opcode.JUMP_IF_FALSE -> {
                val condition = popBool(position)
                val target = jumpTarget(instruction.operand, position)
                if (!condition) pc = target
            }
            Opcode.CALL_HOST -> callHost(instruction.operand, position)
            Opcode.CALL -> call(instruction.operand, position)
            Opcode.RETURN -> returnFromCall(instruction.operand, position)
            Opcode.NEW -> allocate(instruction.operand, position)
            Opcode.HALT -> state = VmState.HALTED
            Opcode.GET_FIELD -> {
                val fields = popInstance(position).fields
                if (instruction.operand !in fields.indices) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
                push(fields[instruction.operand] ?: fail(DiagnosticCode.RUN_UNINITIALIZED_PROPERTY, position), position)
            }
            Opcode.SET_FIELD -> {
                val value = pop(position)
                val target = popInstance(position)
                val field = program.classes[target.classId].fields.getOrNull(instruction.operand) ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
                if (!fits(value, field.type)) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
                target.fields[instruction.operand] = value
            }
            Opcode.NEW_LIST -> newList(instruction.operand, position)
            Opcode.LIST_GET -> {
                val index = popInt(position)
                val list = popList(position)
                push(list.elements[checkedIndex(list, index, position)], position)
            }
            Opcode.LIST_SET -> {
                val value = pop(position)
                val index = popInt(position)
                val list = popMutableList(position)
                val checked = checkedIndex(list, index, position)
                if (!fits(value, list.element)) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
                list.elements[checked] = value
            }
            Opcode.LIST_SIZE -> push(Value.IntValue(popList(position).elements.size), position)
            Opcode.LIST_ADD -> addToList(position)
            Opcode.LIST_REMOVE_AT -> {
                val index = popInt(position)
                val list = popMutableList(position)
                val removed = list.elements.removeAt(checkedIndex(list, index, position))
                listElements--
                push(removed, position)
            }
            Opcode.LIST_CLEAR -> {
                val list = popMutableList(position)
                listElements -= list.elements.size
                list.elements.clear()
                push(Value.UnitValue, position)
            }
            Opcode.CONVERT -> convert(instruction.operand, position)
            Opcode.MATH -> math(instruction.operand, position)
            Opcode.LINALG -> linalg(instruction.operand, position)
        }
    }

    // LINALG（第3.11e段階）。最初の 1 単位でオペランドを確かめ、計算の単位（W）を 1 回に 1 つずつ進め、最後の 1 単位で確定する。
    // 確定するまで PC をこの命令に戻し、オペランドはスタックに残す（GC の根になり、保存する状態は実行前のまま）
    private fun linalg(operand: Int, position: SourcePosition) {
        val progress = linalg ?: startLinalg(operand, position).also { linalg = it }
        if (progress.step < progress.work) {
            linalgStep(progress, position)
            progress.step++
            pc--
            return
        }
        commitLinalg(progress, position)
    }

    private fun startLinalg(operand: Int, position: SourcePosition): LinalgProgress {
        val (function, count) = LinalgFunction.byOperand(operand) ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        val arity = function.arityOf(count)
        if (stack.size < arity) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        val operands = stack.subList(stack.size - arity, stack.size).toList()
        fun scalarAt(index: Int) = (operands[index] as? Value.DoubleValue)?.value ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        return when (function) {
            LinalgFunction.VECTOR_OF -> {
                if (operands.any { it !is Value.DoubleValue }) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
                LinalgProgress(function, arity, operands, count)
            }
            LinalgFunction.VECTOR_OF_LIST -> {
                val list = listAt(operands[0], position)
                if (list.classId != ObjectSnapshot.LIST_CLASS || list.element != SnapshotVerifier.SlotType.DOUBLE) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
                val size = list.elements.size
                if (size !in 1..VmLimits.MAX_VECTOR_SIZE) fail(DiagnosticCode.RUN_INVALID_DIMENSION, position, size, VmLimits.MAX_VECTOR_SIZE)
                LinalgProgress(function, arity, operands, size, vectors = listOf(list))
            }
            LinalgFunction.ADD, LinalgFunction.SUBTRACT, LinalgFunction.DOT -> {
                val left = vectorAt(operands[0], position)
                val right = vectorAt(operands[1], position)
                if (left.elements.size != right.elements.size) fail(DiagnosticCode.RUN_DIMENSION_MISMATCH, position, left.elements.size, right.elements.size)
                LinalgProgress(function, arity, operands, left.elements.size, vectors = listOf(left, right))
            }
            LinalgFunction.NEGATE, LinalgFunction.LENGTH -> vectorAt(operands[0], position).let { LinalgProgress(function, arity, operands, it.elements.size, vectors = listOf(it)) }
            LinalgFunction.NORMALIZE -> vectorAt(operands[0], position).let { LinalgProgress(function, arity, operands, 2 * it.elements.size, vectors = listOf(it)) }
            LinalgFunction.TIMES, LinalgFunction.DIVIDE -> vectorAt(operands[0], position).let { LinalgProgress(function, arity, operands, it.elements.size, vectors = listOf(it), scalar = scalarAt(1)) }
            LinalgFunction.TIMES_LEFT -> vectorAt(operands[1], position).let { LinalgProgress(function, arity, operands, it.elements.size, vectors = listOf(it), scalar = scalarAt(0)) }
            // 行はそのまま共有する（Vector は変更できない）。行の長さがそろっていて、列数が上限以下であること
            LinalgFunction.MATRIX_OF -> {
                val rows = operands.map { vectorAt(it, position) }
                val columns = rows[0].elements.size
                rows.forEachIndexed { index, row -> if (row.elements.size != columns) fail(DiagnosticCode.RUN_RAGGED_MATRIX, position, columns, index, row.elements.size) }
                if (columns > VmLimits.MAX_MATRIX_SIZE) fail(DiagnosticCode.RUN_INVALID_DIMENSION, position, columns, VmLimits.MAX_MATRIX_SIZE)
                LinalgProgress(function, arity, operands, count)
            }
            LinalgFunction.MATRIX_ADD, LinalgFunction.MATRIX_SUBTRACT -> {
                val left = matrixAt(operands[0], position)
                val right = matrixAt(operands[1], position)
                if (left.size != right.size || columnsOf(left) != columnsOf(right)) fail(DiagnosticCode.RUN_DIMENSION_MISMATCH, position, left.size, columnsOf(left), right.size, columnsOf(right))
                LinalgProgress(function, arity, operands, left.size * columnsOf(left), matrices = listOf(left, right), columns = columnsOf(left))
            }
            LinalgFunction.MATRIX_TIMES -> matrixAt(operands[0], position).let { LinalgProgress(function, arity, operands, it.size * columnsOf(it), matrices = listOf(it), scalar = scalarAt(1), columns = columnsOf(it)) }
            LinalgFunction.MATRIX_TIMES_LEFT -> matrixAt(operands[1], position).let { LinalgProgress(function, arity, operands, it.size * columnsOf(it), matrices = listOf(it), scalar = scalarAt(0), columns = columnsOf(it)) }
            LinalgFunction.TRANSPOSE -> matrixAt(operands[0], position).let { LinalgProgress(function, arity, operands, it.size * columnsOf(it), matrices = listOf(it), columns = it.size) }
            LinalgFunction.MATMUL -> {
                val left = matrixAt(operands[0], position)
                val right = matrixAt(operands[1], position)
                if (columnsOf(left) != right.size) fail(DiagnosticCode.RUN_DIMENSION_MISMATCH, position, left.size, columnsOf(left), right.size, columnsOf(right))
                LinalgProgress(function, arity, operands, left.size * right.size * columnsOf(right), matrices = listOf(left, right), columns = columnsOf(right))
            }
            LinalgFunction.MATMUL_VECTOR -> {
                val matrix = matrixAt(operands[0], position)
                val vector = vectorAt(operands[1], position)
                if (columnsOf(matrix) != vector.elements.size) fail(DiagnosticCode.RUN_DIMENSION_MISMATCH, position, matrix.size, columnsOf(matrix), vector.elements.size)
                LinalgProgress(function, arity, operands, matrix.size * columnsOf(matrix), vectors = listOf(vector), matrices = listOf(matrix))
            }
            LinalgFunction.COLUMN_COUNT -> LinalgProgress(function, arity, operands, 0, matrices = listOf(matrixAt(operands[0], position)))
        }
    }

    // 計算の 1 単位。和は左から順に足し（dot・matmul は積を、length は hypot を）、FMA は使わない（どの環境でも同じ値になる）
    private fun linalgStep(progress: LinalgProgress, position: SourcePosition) {
        val index = progress.step
        fun component(vector: Int, at: Int) = doubleOf(progress.vectors[vector].elements[at], position)
        fun entry(matrix: Int, row: Int, column: Int) = doubleOf(progress.matrices[matrix][row].elements[column], position)
        fun result(value: Double) = progress.results.add(Value.DoubleValue.of(value))
        when (progress.function) {
            LinalgFunction.VECTOR_OF, LinalgFunction.MATRIX_OF -> progress.results.add(progress.operands[index])
            LinalgFunction.VECTOR_OF_LIST -> result(component(0, index))
            LinalgFunction.ADD -> result(component(0, index) + component(1, index))
            LinalgFunction.SUBTRACT -> result(component(0, index) - component(1, index))
            LinalgFunction.NEGATE -> result(-component(0, index))
            LinalgFunction.TIMES -> result(component(0, index) * progress.scalar)
            LinalgFunction.TIMES_LEFT -> result(progress.scalar * component(0, index))
            LinalgFunction.DIVIDE -> result(component(0, index) / progress.scalar)
            LinalgFunction.DOT -> progress.accumulator += component(0, index) * component(1, index)
            LinalgFunction.LENGTH -> progress.accumulator = hypot(progress.accumulator, component(0, index))
            // 前半で長さ（hypot を左から順に）、後半で各成分をその長さで割る。長さ 0（すべての成分が ±0.0）は実行時エラー
            LinalgFunction.NORMALIZE -> {
                val size = progress.vectors[0].elements.size
                if (index < size) {
                    progress.accumulator = hypot(progress.accumulator, component(0, index))
                } else {
                    if (index == size && progress.accumulator == 0.0) fail(DiagnosticCode.RUN_ZERO_VECTOR, position)
                    result(component(0, index - size) / progress.accumulator)
                }
            }
            LinalgFunction.MATRIX_ADD -> progress.cell(entry(0, index / progress.columns, index % progress.columns) + entry(1, index / progress.columns, index % progress.columns))
            LinalgFunction.MATRIX_SUBTRACT -> progress.cell(entry(0, index / progress.columns, index % progress.columns) - entry(1, index / progress.columns, index % progress.columns))
            LinalgFunction.MATRIX_TIMES -> progress.cell(entry(0, index / progress.columns, index % progress.columns) * progress.scalar)
            LinalgFunction.MATRIX_TIMES_LEFT -> progress.cell(progress.scalar * entry(0, index / progress.columns, index % progress.columns))
            // 結果の行 index / 元の行数・列 index % 元の行数は、元の列・行
            LinalgFunction.TRANSPOSE -> progress.cell(entry(0, index % progress.columns, index / progress.columns))
            // 結果の (i, j) は、k を 0 から順に a[i][k] × b[k][j] を足す（index = (i × 列数 + j) × k の数 + k）
            LinalgFunction.MATMUL -> {
                val inner = progress.matrices[1].size
                val k = index % inner
                val cellIndex = index / inner
                progress.accumulator += entry(0, cellIndex / progress.columns, k) * entry(1, k, cellIndex % progress.columns)
                if (k == inner - 1) {
                    progress.cell(progress.accumulator)
                    progress.accumulator = 0.0
                }
            }
            LinalgFunction.MATMUL_VECTOR -> {
                val inner = progress.vectors[0].elements.size
                val k = index % inner
                progress.accumulator += entry(0, index / inner, k) * component(0, k)
                if (k == inner - 1) {
                    result(progress.accumulator)
                    progress.accumulator = 0.0
                }
            }
            LinalgFunction.COLUMN_COUNT -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        }
    }

    // 確定の 1 単位。結果の実体を置く空きが無ければ、NEW と同じく回収を待つ（この単位は数えない。計算済みの結果は残し、回収の後に確定し直す）
    private fun commitLinalg(progress: LinalgProgress, position: SourcePosition) {
        val result = when (progress.function) {
            LinalgFunction.DOT, LinalgFunction.LENGTH -> Value.DoubleValue.of(progress.accumulator)
            LinalgFunction.COLUMN_COUNT -> Value.IntValue(columnsOf(progress.matrices[0]))
            LinalgFunction.MATRIX_ADD, LinalgFunction.MATRIX_SUBTRACT, LinalgFunction.MATRIX_TIMES, LinalgFunction.MATRIX_TIMES_LEFT, LinalgFunction.TRANSPOSE, LinalgFunction.MATMUL -> {
                if (!reserve(progress.rows.size + 1, progress.rows.sumOf { it.size } + progress.rows.size, position)) return
                val rows = progress.rows.map { row -> allocate(ListEntry(SnapshotVerifier.SlotType.DOUBLE, false, row, ObjectSnapshot.VECTOR_CLASS)) }
                allocate(ListEntry(SnapshotVerifier.SlotType.VECTOR, false, ArrayList<Value>(rows), ObjectSnapshot.MATRIX_CLASS))
            }
            LinalgFunction.MATRIX_OF -> {
                if (!reserve(1, progress.results.size, position)) return
                allocate(ListEntry(SnapshotVerifier.SlotType.VECTOR, false, progress.results, ObjectSnapshot.MATRIX_CLASS))
            }
            else -> {
                if (!reserve(1, progress.results.size, position)) return
                allocate(ListEntry(SnapshotVerifier.SlotType.DOUBLE, false, progress.results, ObjectSnapshot.VECTOR_CLASS))
            }
        }
        repeat(progress.arity) { pop(position) }
        linalg = null
        push(result, position)
    }

    // objects 個の実体と elements 個の要素を置く空きを確かめる。空きが無ければ、まだ回収していなければ回収を始めて false（PC はこの命令に戻る）、
    // 回収の後も空きが無ければ実行時エラー
    private fun reserve(objects: Int, elements: Int, position: SourcePosition): Boolean {
        if (nextObjectId > Int.MAX_VALUE - objects) fail(DiagnosticCode.RUN_TOO_MANY_OBJECTS, position, VmLimits.MAX_OBJECTS)
        val objectsFull = heap.size + objects > VmLimits.MAX_OBJECTS
        val elementsFull = listElements + elements > VmLimits.MAX_LIST_ELEMENTS
        if (waitForCollection(objectsFull || elementsFull)) return false
        if (objectsFull) fail(DiagnosticCode.RUN_TOO_MANY_OBJECTS, position, VmLimits.MAX_OBJECTS)
        if (elementsFull) fail(DiagnosticCode.RUN_TOO_MANY_LIST_ELEMENTS, position, VmLimits.MAX_LIST_ELEMENTS)
        return true
    }

    private fun allocate(entry: ListEntry): Value.ObjectRef {
        val id = nextObjectId++
        heap[id] = entry
        listElements += entry.elements.size
        return Value.ObjectRef(id)
    }

    private fun doubleOf(value: Value, position: SourcePosition): Double = (value as? Value.DoubleValue)?.value ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)

    private fun listAt(value: Value, position: SourcePosition): ListEntry =
        (value as? Value.ObjectRef)?.let { heap[it.id] as? ListEntry } ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)

    private fun vectorAt(value: Value, position: SourcePosition): ListEntry =
        listAt(value, position).takeIf { it.classId == ObjectSnapshot.VECTOR_CLASS } ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)

    // Matrix の行（どれも Vector で、長さがそろっている）
    private fun matrixAt(value: Value, position: SourcePosition): List<ListEntry> {
        val matrix = listAt(value, position).takeIf { it.classId == ObjectSnapshot.MATRIX_CLASS } ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        val rows = matrix.elements.map { vectorAt(it, position) }
        if (rows.isEmpty() || rows.any { it.elements.size != rows[0].elements.size }) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        return rows
    }

    private fun columnsOf(rows: List<ListEntry>) = rows[0].elements.size

    // 数値の変換（第3.11a段階）。変換元の型の値を取り出し、変換先の型の値を積む。Int から Float・Double から Float は最も近い値に丸め
    // （範囲外の Double は Infinity）、Float・Double から Int は Kotlin の toInt と同じ（0 の方向へ切り捨て、NaN は 0、範囲外は Int の最小値・最大値）
    // 数学関数（第3.11b段階・三角関数は第3.11c段階・対数などは第3.11d段階）。計算は Kotlin の kotlin.math・coerceIn・isNaN・isInfinite と
    // 同じ関数（java.lang.Math など）で行い、Kotlin で例外になる場合だけ先に実行時エラーにする。結果の型は MathFunction.result
    private fun math(operand: Int, position: SourcePosition) {
        val function = MathFunction.byOperand(operand) ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        val result = when (operand % NumericKind.COUNT) {
            NumericKind.INT -> Value.IntValue(intMath(function, position))
            NumericKind.FLOAT -> when (function.result) {
                MathResult.SAME -> Value.FloatValue.of(floatMath(function, position))
                MathResult.INT -> Value.IntValue(popFloat(position).also { checkRoundable(it.isNaN(), position) }.roundToInt())
                MathResult.BOOLEAN -> Value.BoolValue(if (function == MathFunction.IS_CLOSE) floatClose(position) else floatTest(function, popFloat(position), position))
            }
            else -> when (function.result) {
                MathResult.SAME -> Value.DoubleValue.of(doubleMath(function, position))
                MathResult.INT -> Value.IntValue(popDouble(position).also { checkRoundable(it.isNaN(), position) }.roundToInt())
                MathResult.BOOLEAN -> Value.BoolValue(if (function == MathFunction.IS_CLOSE) doubleClose(position) else doubleTest(function, popDouble(position), position))
            }
        }
        push(result, position)
    }

    // isClose(a, b, tolerance)（第3.11e段階）。tolerance を a・b より先に確かめる。Float の値は Double で正確に表せるため、Double で判定する
    private fun floatClose(position: SourcePosition): Boolean {
        val tolerance = popFloat(position)
        val b = popFloat(position)
        val a = popFloat(position)
        if (!tolerance.isFinite() || tolerance < 0f) fail(DiagnosticCode.RUN_INVALID_TOLERANCE, position)
        return ApproximateComparison.isClose(a.toDouble(), b.toDouble(), tolerance.toDouble())
    }

    private fun doubleClose(position: SourcePosition): Boolean {
        val tolerance = popDouble(position)
        val b = popDouble(position)
        val a = popDouble(position)
        if (!tolerance.isFinite() || tolerance < 0.0) fail(DiagnosticCode.RUN_INVALID_TOLERANCE, position)
        return ApproximateComparison.isClose(a, b, tolerance)
    }

    // Boolean を返す数学関数（第3.11d段階）
    private fun floatTest(function: MathFunction, value: Float, position: SourcePosition): Boolean = when (function) {
        MathFunction.IS_NAN -> value.isNaN()
        MathFunction.IS_INFINITE -> value.isInfinite()
        else -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
    }

    private fun doubleTest(function: MathFunction, value: Double, position: SourcePosition): Boolean = when (function) {
        MathFunction.IS_NAN -> value.isNaN()
        MathFunction.IS_INFINITE -> value.isInfinite()
        else -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
    }

    // 引数は右から取り出す（左の引数が下）
    private fun intMath(function: MathFunction, position: SourcePosition): Int = when (function) {
        MathFunction.ABS -> abs(popInt(position))
        MathFunction.MIN -> popInt(position).let { b -> min(popInt(position), b) }
        MathFunction.MAX -> popInt(position).let { b -> max(popInt(position), b) }
        MathFunction.COERCE_IN -> {
            val maximum = popInt(position)
            val minimum = popInt(position)
            val value = popInt(position)
            if (minimum > maximum) fail(DiagnosticCode.RUN_EMPTY_COERCE_RANGE, position)
            value.coerceIn(minimum, maximum)
        }
        else -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
    }

    private fun floatMath(function: MathFunction, position: SourcePosition): Float = when (function) {
        MathFunction.ABS -> abs(popFloat(position))
        MathFunction.MIN -> popFloat(position).let { b -> min(popFloat(position), b) }
        MathFunction.MAX -> popFloat(position).let { b -> max(popFloat(position), b) }
        MathFunction.SQRT -> sqrt(popFloat(position))
        MathFunction.FLOOR -> floor(popFloat(position))
        MathFunction.CEIL -> ceil(popFloat(position))
        MathFunction.ROUND -> round(popFloat(position))
        MathFunction.SIN -> sin(popFloat(position))
        MathFunction.COS -> cos(popFloat(position))
        MathFunction.TAN -> tan(popFloat(position))
        MathFunction.ASIN -> asin(popFloat(position))
        MathFunction.ACOS -> acos(popFloat(position))
        MathFunction.ATAN -> atan(popFloat(position))
        MathFunction.ATAN2 -> popFloat(position).let { x -> atan2(popFloat(position), x) }
        MathFunction.SINH -> sinh(popFloat(position))
        MathFunction.COSH -> cosh(popFloat(position))
        MathFunction.TANH -> tanh(popFloat(position))
        MathFunction.LN -> ln(popFloat(position))
        MathFunction.LOG10 -> log10(popFloat(position))
        MathFunction.EXP -> exp(popFloat(position))
        MathFunction.HYPOT -> popFloat(position).let { y -> hypot(popFloat(position), y) }
        MathFunction.SIGN -> sign(popFloat(position))
        MathFunction.COERCE_IN -> {
            val maximum = popFloat(position)
            val minimum = popFloat(position)
            val value = popFloat(position)
            // NaN を含む範囲は空とみなさない（Kotlin と同じ）
            if (minimum > maximum) fail(DiagnosticCode.RUN_EMPTY_COERCE_RANGE, position)
            value.coerceIn(minimum, maximum)
        }
        else -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
    }

    private fun doubleMath(function: MathFunction, position: SourcePosition): Double = when (function) {
        MathFunction.ABS -> abs(popDouble(position))
        MathFunction.MIN -> popDouble(position).let { b -> min(popDouble(position), b) }
        MathFunction.MAX -> popDouble(position).let { b -> max(popDouble(position), b) }
        MathFunction.SQRT -> sqrt(popDouble(position))
        MathFunction.FLOOR -> floor(popDouble(position))
        MathFunction.CEIL -> ceil(popDouble(position))
        MathFunction.ROUND -> round(popDouble(position))
        MathFunction.SIN -> sin(popDouble(position))
        MathFunction.COS -> cos(popDouble(position))
        MathFunction.TAN -> tan(popDouble(position))
        MathFunction.ASIN -> asin(popDouble(position))
        MathFunction.ACOS -> acos(popDouble(position))
        MathFunction.ATAN -> atan(popDouble(position))
        MathFunction.ATAN2 -> popDouble(position).let { x -> atan2(popDouble(position), x) }
        MathFunction.SINH -> sinh(popDouble(position))
        MathFunction.COSH -> cosh(popDouble(position))
        MathFunction.TANH -> tanh(popDouble(position))
        MathFunction.LN -> ln(popDouble(position))
        MathFunction.LOG10 -> log10(popDouble(position))
        MathFunction.EXP -> exp(popDouble(position))
        MathFunction.HYPOT -> popDouble(position).let { y -> hypot(popDouble(position), y) }
        MathFunction.SIGN -> sign(popDouble(position))
        // Kotlin の Double.pow と同じ Math.pow
        MathFunction.POW -> popDouble(position).let { exponent -> popDouble(position).pow(exponent) }
        MathFunction.COERCE_IN -> {
            val maximum = popDouble(position)
            val minimum = popDouble(position)
            val value = popDouble(position)
            if (minimum > maximum) fail(DiagnosticCode.RUN_EMPTY_COERCE_RANGE, position)
            value.coerceIn(minimum, maximum)
        }
        else -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
    }

    // roundToInt は 0.5 を正の方向へ丸め、範囲外は Int.MIN_VALUE / Int.MAX_VALUE（Kotlin と同じ）。NaN は Kotlin では例外になるため実行時エラー
    private fun checkRoundable(nan: Boolean, position: SourcePosition) {
        if (nan) fail(DiagnosticCode.RUN_ROUND_NAN, position)
    }

    private fun convert(operand: Int, position: SourcePosition) {
        if (operand !in 0 until NumericKind.COUNT * NumericKind.COUNT) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        val from = operand / NumericKind.COUNT
        val to = operand % NumericKind.COUNT
        if (from == to) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        val result = when (from) {
            NumericKind.INT -> popInt(position).let { if (to == NumericKind.FLOAT) Value.FloatValue.of(it.toFloat()) else Value.DoubleValue.of(it.toDouble()) }
            NumericKind.FLOAT -> popFloat(position).let { if (to == NumericKind.INT) Value.IntValue(it.toInt()) else Value.DoubleValue.of(it.toDouble()) }
            else -> popDouble(position).let { if (to == NumericKind.INT) Value.IntValue(it.toInt()) else Value.FloatValue.of(it.toFloat()) }
        }
        push(result, position)
    }

    // トップレベルでは呼び出しフレームが無いため、ローカル変数は読み書きできない
    private fun currentLocals(position: SourcePosition): Array<Value?> = callFrames.lastOrNull()?.locals ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)

    // 戻り先は CALL の次の命令。深さの上限を超える呼び出しは、サーバーのメモリを使い続けないよう実行時エラーにする
    // （再帰に入らない呼び出しの連鎖は Compiler が CS_COMP_CALL_TOO_DEEP で拒否する。再帰の深さは実行時の引数で決まる: 第3.8e段階）。
    // 引数はスタックの上から取り出し、左の引数から順にローカル変数 0, 1, … へ入れる（値渡し）
    private fun call(index: Int, position: SourcePosition) {
        val function = program.functions.getOrNull(index) ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        if (function.localCount !in 0..VmLimits.MAX_VARIABLES || function.parameterCount !in 0..function.localCount) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        if (stack.size < function.parameterCount) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        if (callFrames.size >= VmLimits.MAX_CALL_DEPTH) fail(DiagnosticCode.RUN_CALL_DEPTH_EXCEEDED, position, VmLimits.MAX_CALL_DEPTH)
        val entry = jumpTarget(function.entry, position)
        val locals = arrayOfNulls<Value>(function.localCount)
        for (parameter in function.parameterCount - 1 downTo 0) {
            val argument = pop(position)
            if (argument == Value.UnitValue) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
            locals[parameter] = argument
        }
        callFrames.add(CallFrame(index, pc, locals, stack.size))
        pc = entry
    }

    // 同時に存在できるオブジェクトは MAX_OBJECTS 個まで。満杯なら PC を NEW に戻して回収を始め（スタック・id・ヒープは変えない）、
    // 回収を終えたら同じ NEW をもう一度実行する。それでも空きが無い・id を使い切った場合は実行時エラー（サーバーのメモリを使い続けない）。
    // プロパティは未初期化で作る
    private fun allocate(classId: Int, position: SourcePosition) {
        val info = program.classes.getOrNull(classId) ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        if (info.fields.size > VmLimits.MAX_PROPERTIES) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        if (nextObjectId == Int.MAX_VALUE) fail(DiagnosticCode.RUN_TOO_MANY_OBJECTS, position, VmLimits.MAX_OBJECTS)
        val full = heap.size >= VmLimits.MAX_OBJECTS
        if (waitForCollection(full)) return
        if (full) fail(DiagnosticCode.RUN_TOO_MANY_OBJECTS, position, VmLimits.MAX_OBJECTS)
        val id = nextObjectId++
        heap[id] = Instance(classId, arrayOfNulls(info.fields.size))
        push(Value.ObjectRef(id), position)
    }

    // 空きが無い（full）とき、まだ回収していなければ PC を命令に戻して回収を始め、true を返す（スタック・ヒープはまだ変えていない）。
    // 回収を終えた直後の再実行（回収は1回だけ）なら false で、空きが無ければ呼び出し元が実行時エラーにする
    private fun waitForCollection(full: Boolean): Boolean {
        val retried = collected
        collected = false
        if (!full || retried) return false
        collector = Collector()
        pc--
        return true
    }

    // リストを作る（第3.10a段階）。オブジェクトの数か、リストの要素数の合計が上限に達していれば、要素を取り出す前に回収を待つ
    private fun newList(shapeIndex: Int, position: SourcePosition) {
        val shape = program.lists.getOrNull(shapeIndex) ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        if (shape.size !in 0..VmLimits.MAX_LIST_SIZE || stack.size < shape.size) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        if (nextObjectId == Int.MAX_VALUE) fail(DiagnosticCode.RUN_TOO_MANY_OBJECTS, position, VmLimits.MAX_OBJECTS)
        val objectsFull = heap.size >= VmLimits.MAX_OBJECTS
        val elementsFull = listElements + shape.size > VmLimits.MAX_LIST_ELEMENTS
        if (waitForCollection(objectsFull || elementsFull)) return
        if (objectsFull) fail(DiagnosticCode.RUN_TOO_MANY_OBJECTS, position, VmLimits.MAX_OBJECTS)
        if (elementsFull) fail(DiagnosticCode.RUN_TOO_MANY_LIST_ELEMENTS, position, VmLimits.MAX_LIST_ELEMENTS)
        val values = arrayOfNulls<Value>(shape.size)
        for (index in shape.size - 1 downTo 0) {
            val value = pop(position)
            if (!fits(value, shape.element)) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
            values[index] = value
        }
        val id = nextObjectId++
        heap[id] = ListEntry(shape.element, shape.mutable, values.mapTo(ArrayList(shape.size)) { it!! })
        listElements += shape.size
        push(Value.ObjectRef(id), position)
    }

    // 末尾に加える。1 つのリストの上限は回収しても変わらないため先に確かめる。VM 全体の合計が上限なら、値とリストを取り出す前に回収を待つ
    private fun addToList(position: SourcePosition) {
        val list = (stack.getOrNull(stack.size - 2) as? Value.ObjectRef)?.let { heap[it.id] as? ListEntry } ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        if (!list.mutable) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        if (list.elements.size >= VmLimits.MAX_LIST_SIZE) fail(DiagnosticCode.RUN_LIST_TOO_LARGE, position, VmLimits.MAX_LIST_SIZE)
        val full = listElements >= VmLimits.MAX_LIST_ELEMENTS
        if (waitForCollection(full)) return
        if (full) fail(DiagnosticCode.RUN_TOO_MANY_LIST_ELEMENTS, position, VmLimits.MAX_LIST_ELEMENTS)
        val value = pop(position)
        pop(position)
        if (!fits(value, list.element)) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        list.elements.add(value)
        listElements++
        push(Value.UnitValue, position)
    }

    // 添字は 0 以上・要素数未満（負数・範囲外・空のリストは CS_RUN_INDEX_OUT_OF_BOUNDS。引数: 添字、要素数）
    private fun checkedIndex(list: ListEntry, index: Int, position: SourcePosition): Int =
        if (index in list.elements.indices) index else fail(DiagnosticCode.RUN_INDEX_OUT_OF_BOUNDS, position, index, list.elements.size)

    // 回収を最大 workBudget 単位進め、使った単位数を返す（回収中でなければ 0）。終わったら、次の run で NEW をやり直す
    fun collectGarbage(workBudget: Int): Int {
        val current = collector ?: return 0
        val used = current.advance(workBudget)
        if (current.done) {
            collector = null
            collected = true
        }
        return used
    }

    // 分割できる mark & sweep。スタック・大域変数・呼び出しフレームのローカル変数（this・初期化中のオブジェクトを含む）から参照される
    // オブジェクトと、そのプロパティ・リストの要素（第3.10a段階）からたどれるオブジェクトが生きている。それ以外（互いに参照し合うだけの組を含む）を回収する。
    // 回収中は VM が命令を実行しないため、参照の関係は回収の間変わらない。印を付け終える（MARK）まで何も消さず、SWEEP は印の無いものだけを消す。
    // 作業の単位（1 単位はおおむね同じ計算量）: 根の値 1 つの検査、オブジェクト 1 つの取り出し、プロパティ・要素 1 つの検査、SWEEP で調べるオブジェクト 1 つ。
    // 合計は 根の数（最大 64 + 64 + 16 × 64）+ 生きているオブジェクトの数 × 2 + プロパティ・要素の数（最大 256 × 16 + 4096）+ ヒープの数 + 2 以下で、
    // 必ず終わる（印を付けたものは二度と積まない）。SWEEP で消したリストの要素はリストの要素数の合計から引く
    private inner class Collector {
        // 根を調べている位置（0 はスタック、1 は大域変数、2 以降は呼び出しフレーム）
        private var rootSegment = 0
        private var rootIndex = 0
        private val pending = ArrayList<HeapEntry>()
        private var scanning: HeapEntry? = null
        private var fieldIndex = 0
        private var sweep: MutableIterator<HeapEntry>? = null
        var done = false
            private set
        val isSweeping get() = sweep != null

        fun advance(budget: Int): Int {
            var used = 0
            while (used < budget && !done) {
                used++
                val sweeping = sweep
                val current = scanning
                when {
                    sweeping != null -> if (sweeping.hasNext()) {
                        val target = sweeping.next()
                        if (target.marked) {
                            target.marked = false
                        } else {
                            sweeping.remove()
                            if (target is ListEntry) listElements -= target.elements.size
                        }
                    } else {
                        done = true
                    }
                    current != null -> if (fieldIndex < current.childCount) mark(current.child(fieldIndex++)) else scanning = null
                    pending.isNotEmpty() -> {
                        scanning = pending.removeAt(pending.lastIndex)
                        fieldIndex = 0
                    }
                    !markNextRoot() -> sweep = heap.values.iterator()
                }
            }
            return used
        }

        // 次の根の値に印を付ける。根を調べ終えたら false（値の無い区間は単位を使わずに飛ばす）
        private fun markNextRoot(): Boolean {
            while (true) {
                val values: List<Value?> = when (rootSegment) {
                    0 -> stack
                    1 -> variables.asList()
                    else -> callFrames.getOrNull(rootSegment - 2)?.locals?.asList() ?: return false
                }
                if (rootIndex < values.size) {
                    mark(values[rootIndex++])
                    return true
                }
                rootSegment++
                rootIndex = 0
            }
        }

        private fun mark(value: Value?) {
            if (value !is Value.ObjectRef) return
            val target = heap[value.id] ?: return
            if (target.marked) return
            target.marked = true
            pending.add(target)
        }
    }

    // オブジェクトの参照を取り出し、そのインスタンスを返す（リストは使えない）
    private fun popInstance(position: SourcePosition): Instance =
        (pop(position) as? Value.ObjectRef)?.let { heap[it.id] as? Instance } ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)

    // リストの参照を取り出し、その実体を返す
    private fun popList(position: SourcePosition): ListEntry =
        (pop(position) as? Value.ObjectRef)?.let { heap[it.id] as? ListEntry } ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)

    // 変更できるリスト（mutableListOf）だけ。listOf のリストの変更は不正なバイトコード（Compiler は出さない）
    private fun popMutableList(position: SourcePosition): ListEntry = popList(position).takeIf { it.mutable } ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)

    // プロパティに入れる値が、プロパティの型に合う（オブジェクトはヒープにある同じクラスのもの）
    private fun fits(value: Value, type: SnapshotVerifier.SlotType): Boolean = when (value) {
        is Value.IntValue -> type == SnapshotVerifier.SlotType.INT
        is Value.BoolValue -> type == SnapshotVerifier.SlotType.BOOLEAN
        is Value.StringValue -> type == SnapshotVerifier.SlotType.STRING
        is Value.FloatValue -> type == SnapshotVerifier.SlotType.FLOAT
        is Value.DoubleValue -> type == SnapshotVerifier.SlotType.DOUBLE
        is Value.ObjectRef -> when (val target = heap[value.id]) {
            is Instance -> type is SnapshotVerifier.SlotType.OBJECT && target.classId == type.classId
            is ListEntry -> when (target.classId) {
                ObjectSnapshot.VECTOR_CLASS -> type == SnapshotVerifier.SlotType.VECTOR
                ObjectSnapshot.MATRIX_CLASS -> type == SnapshotVerifier.SlotType.MATRIX
                else -> type is SnapshotVerifier.SlotType.LIST && target.element == type.element
            }
            null -> false
        }
        Value.UnitValue -> false
    }

    // 関数のスタックは、Unit を返すなら空、値を返すならその値1つだけでなければならない（呼び出し元の値には触れない）
    private fun returnFromCall(kind: Int, position: SourcePosition) {
        val frame = callFrames.lastOrNull() ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        val result = when (kind) {
            0 -> if (stack.size == frame.stackBase) Value.UnitValue else fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
            1 -> if (stack.size == frame.stackBase + 1) pop(position) else fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
            else -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        }
        if (kind == 1 && result == Value.UnitValue) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        callFrames.removeAt(callFrames.lastIndex)
        pc = frame.returnPc
        push(result, position)
    }

    private fun callHost(id: Int, position: SourcePosition) {
        when (HostFunction.byId(id)) {
            HostFunction.PRINTLN -> {
                console.print(display(pop(position), position))
                push(Value.UnitValue, position)
            }
            HostFunction.PRINTLN_EMPTY -> {
                console.print("")
                push(Value.UnitValue, position)
            }
            HostFunction.WAIT_TICKS -> {
                val ticks = popInt(position)
                if (ticks < 0) fail(DiagnosticCode.RUN_NEGATIVE_WAIT, position, ticks)
                // SUSPEND: 引数は消費済み、PC は次の命令を指している。結果の Unit は再開時に積む（A23）
                waitRemaining = ticks
                resumeWithUnit = true
                state = VmState.WAITING
            }
            HostFunction.RANDOM -> push(Value.DoubleValue.of(nextUnit()), position)
            HostFunction.RANDOM_RANGE -> {
                val max = popDouble(position)
                val min = popDouble(position)
                if (!min.isFinite() || !max.isFinite() || !(max > min)) fail(DiagnosticCode.RUN_INVALID_RANDOM_RANGE, position)
                push(Value.DoubleValue.of(nextBetween(min, max)), position)
            }
            HostFunction.RANDOM_INT -> {
                val max = popInt(position)
                val min = popInt(position)
                if (max <= min) fail(DiagnosticCode.RUN_INVALID_RANDOM_RANGE, position)
                push(Value.IntValue(nextInt(min, max)), position)
            }
            HostFunction.RANDOM_SEED -> {
                randomState = popInt(position).toLong()
                push(Value.UnitValue, position)
            }
            null -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        }
    }

    // SplitMix64（第3.11e段階）。JDK の SplittableRandom(seed).nextLong() と同じ列
    private fun nextLong(): Long {
        randomState += GOLDEN_GAMMA
        var z = randomState
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    // [0, 1) の Double（上位 53 ビット。SplittableRandom.nextDouble() と同じ）
    private fun nextUnit(): Double = (nextLong() ushr 11) * DOUBLE_UNIT

    // [min, max) の Double（有限の min < max）。幅が無限大になる範囲は半分にして計算し、丸めで max になったら max の直前の値にする
    private fun nextBetween(min: Double, max: Double): Double {
        val u = nextUnit()
        val width = max - min
        val value = if (width.isInfinite()) (min / 2 + u * (max / 2 - min / 2)) * 2 else min + u * width
        return if (value >= max) max.nextDown() else value
    }

    // [min, max) の Int（min < max）。幅は Long で計算し、63 ビットの値の剰余で偏りが出る端の値は捨てて引き直す（java.util.Random と同じ棄却法。
    // 引き直す確率は 1 回あたり 2^-31 以下）
    private fun nextInt(min: Int, max: Int): Int {
        val bound = max.toLong() - min
        var bits = nextLong() ushr 1
        var value = bits % bound
        while (bits - value + (bound - 1) < 0) {
            bits = nextLong() ushr 1
            value = bits % bound
        }
        return (min + value).toInt()
    }

    private fun display(value: Value, position: SourcePosition): String = when (value) {
        is Value.IntValue -> value.value.toString()
        is Value.BoolValue -> value.value.toString()
        is Value.StringValue -> stringOf(value, position)
        // Kotlin/JVM の toString と同じ（1.5・47.0・1.0E10・NaN・Infinity・-0.0。第3.11a段階）
        is Value.FloatValue -> value.value.toString()
        is Value.DoubleValue -> value.value.toString()
        // クラス名@id（第3.9a段階。id はこの VM の中で一意で、保存・復元をまたいで変わらない）
        is Value.ObjectRef -> {
            when (val target = heap[value.id]) {
                is Instance -> "${program.classes[target.classId].name}@${value.id}"
                is ListEntry -> StringBuilder().also { appendList(target, it, position) }.toString()
                null -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
            }
        }
        Value.UnitValue -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
    }

    // [要素, 要素, …]（Kotlin と同じ形。文字列は引用符なし: 第3.10a段階）。入れ子のリスト（第3.10b段階）も同じ StringBuilder に書き、
    // 全体でコンソールの 1 行（64 単位）を超えたら残りの要素は文字列にしない（表示は Console.fit が「…」で切る）。
    // 段ごとに "[" を書いてから中へ進むため、入れ子をたどる深さも 1 行の長さまで（要素数・入れ子の深さに比例する処理をしない）
    private fun appendList(list: ListEntry, text: StringBuilder, position: SourcePosition) {
        text.append('[')
        for ((index, element) in list.elements.withIndex()) {
            if (text.length > Console.MAX_LINE_LENGTH) return
            if (index > 0) text.append(", ")
            val inner = (element as? Value.ObjectRef)?.let { heap[it.id] }
            if (inner is ListEntry) appendList(inner, text, position) else text.append(display(element, position))
        }
        text.append(']')
    }

    private fun stringOf(value: Value.StringValue, position: SourcePosition): String =
        (program.constants.getOrNull(value.constantIndex) as? Constant.StringConstant)?.value ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)

    // == / !=。両辺は同じ型でなければならない（Unit・型の違いは不正なバイトコード: A1・A22）。String は内容で比べる。
    // kind 0 は Float・Double 以外の値、1・2 は Float・Double（第3.11a段階）で、IEEE 754 の等価で比べる（ieeeEquals。値のビットの一致ではない）
    private fun equality(kind: Int, position: SourcePosition, equal: Boolean) {
        val same = when (kind) {
            NumericKind.INT -> {
                val right = pop(position)
                val left = pop(position)
                when {
                    left is Value.IntValue && right is Value.IntValue -> left.value == right.value
                    left is Value.BoolValue && right is Value.BoolValue -> left.value == right.value
                    left is Value.StringValue && right is Value.StringValue -> stringOf(left, position) == stringOf(right, position)
                    // オブジェクトは参照の同一性で比べる（第3.9a段階）
                    left is Value.ObjectRef && right is Value.ObjectRef -> left.id == right.id
                    // Float・Double はここでは比べない（kind 1・2 だけ）
                    else -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
                }
            }
            NumericKind.FLOAT -> {
                val right = popFloat(position)
                ieeeEquals(popFloat(position), right)
            }
            NumericKind.DOUBLE -> {
                val right = popDouble(position)
                ieeeEquals(popDouble(position), right)
            }
            else -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        }
        push(Value.BoolValue(same == equal), position)
    }

    // IEEE 754 の等価（第3.11a段階）。引数の型が Float・Double と決まっているため、Kotlin の == は IEEE 754 の比較になる
    // （NaN はどの値とも等しくなく、0.0 と -0.0 は等しい）。Value.FloatValue / DoubleValue の equals（ビットの一致）とは違う
    private fun ieeeEquals(left: Float, right: Float): Boolean = left == right

    private fun ieeeEquals(left: Double, right: Double): Boolean = left == right

    // 大小比較。kind は数値の型（NumericKind）。浮動小数点数は IEEE 754 の比較（NaN との比較はすべて false、-0.0 < 0.0 は false）
    private inline fun comparison(
        kind: Int,
        position: SourcePosition,
        int: (Int, Int) -> Boolean,
        float: (Float, Float) -> Boolean,
        double: (Double, Double) -> Boolean,
    ) {
        val result = when (kind) {
            NumericKind.INT -> {
                val right = popInt(position)
                int(popInt(position), right)
            }
            NumericKind.FLOAT -> {
                val right = popFloat(position)
                float(popFloat(position), right)
            }
            NumericKind.DOUBLE -> {
                val right = popDouble(position)
                double(popDouble(position), right)
            }
            else -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        }
        push(Value.BoolValue(result), position)
    }

    // 移り先は命令の範囲内でなければならない
    private fun jumpTarget(target: Int, position: SourcePosition): Int =
        if (target in program.instructions.indices) target else fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)

    private fun constantValue(index: Int, position: SourcePosition): Value = when (val constant = program.constants.getOrNull(index)) {
        is Constant.IntConstant -> Value.IntValue(constant.value)
        is Constant.StringConstant -> Value.StringValue(index)
        is Constant.FloatConstant -> Value.FloatValue(constant.bits)
        is Constant.DoubleConstant -> Value.DoubleValue(constant.bits)
        null -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
    }

    // 数値の演算。kind は数値の型（NumericKind）。Int はオーバーフローを実行時エラーにし、Float・Double は IEEE 754 の演算
    // （第3.11a段階。0 除算は Infinity・NaN になり、エラーにしない。オーバーフローは Infinity）
    private inline fun arithmetic(
        kind: Int,
        position: SourcePosition,
        int: (Int, Int) -> Int,
        float: (Float, Float) -> Float,
        double: (Double, Double) -> Double,
    ) {
        val result = when (kind) {
            NumericKind.INT -> {
                val right = popInt(position)
                val left = popInt(position)
                Value.IntValue(exact(position) { int(left, right) })
            }
            NumericKind.FLOAT -> {
                val right = popFloat(position)
                Value.FloatValue.of(float(popFloat(position), right))
            }
            NumericKind.DOUBLE -> {
                val right = popDouble(position)
                Value.DoubleValue.of(double(popDouble(position), right))
            }
            else -> fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)
        }
        push(result, position)
    }

    // Math.*Exact のオーバーフローを CS_RUN_INTEGER_OVERFLOW に変換する
    private inline fun exact(position: SourcePosition, operation: () -> Int): Int =
        try {
            operation()
        } catch (overflow: ArithmeticException) {
            fail(DiagnosticCode.RUN_INTEGER_OVERFLOW, position)
        }

    private fun push(value: Value, position: SourcePosition) {
        if (stack.size >= VmLimits.MAX_STACK_DEPTH) fail(DiagnosticCode.RUN_STACK_OVERFLOW, position)
        stack.add(value)
    }

    private fun pop(position: SourcePosition): Value =
        if (stack.isEmpty()) fail(DiagnosticCode.RUN_INVALID_BYTECODE, position) else stack.removeAt(stack.lastIndex)

    // 型タグの検証（A1）。Int 以外が来たら不正なバイトコード
    private fun popInt(position: SourcePosition): Int =
        (pop(position) as? Value.IntValue)?.value ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)

    private fun popFloat(position: SourcePosition): Float =
        (pop(position) as? Value.FloatValue)?.value ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)

    private fun popDouble(position: SourcePosition): Double =
        (pop(position) as? Value.DoubleValue)?.value ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)

    private fun popBool(position: SourcePosition): Boolean =
        (pop(position) as? Value.BoolValue)?.value ?: fail(DiagnosticCode.RUN_INVALID_BYTECODE, position)

    private fun fail(code: DiagnosticCode, position: SourcePosition, vararg arguments: Int): Nothing {
        recordError(code, position, *arguments)
        throw RuntimeFailure()
    }

    private fun recordError(code: DiagnosticCode, position: SourcePosition, vararg arguments: Int) {
        state = VmState.ERROR
        error = Diagnostic(code, DiagnosticSeverity.ERROR, DiagnosticPhase.RUNTIME, position, arguments.map { DiagnosticArgument.IntArgument(it) })
    }

    companion object {
        private val ORIGIN = SourcePosition(0, 1, 1)

        // SplitMix64 の定数（第3.11e段階）
        private val GOLDEN_GAMMA = 0x9e3779b97f4a7c15uL.toLong()
        private val MIX_1 = 0xbf58476d1ce4e5b9uL.toLong()
        private val MIX_2 = 0x94d049bb133111ebuL.toLong()
        private const val DOUBLE_UNIT = 1.0 / (1L shl 53)

        // スナップショットから実行途中の VM を作る（D14）。SnapshotVerifier の検証に通らなければ null。
        // 各呼び出しフレームのスタックの始まりは保存せず、検証がバイトコードから求めた値を使う
        fun restore(program: CompiledProgram, snapshot: VmSnapshot): CsVm? {
            val verdict = SnapshotVerifier.check(program, snapshot) as? SnapshotVerifier.Verdict.Valid ?: return null
            val machine = CsVm(program)
            machine.pc = snapshot.pc
            machine.state = snapshot.state
            machine.waitRemaining = snapshot.waitRemaining
            machine.resumeWithUnit = snapshot.resumeWithUnit
            machine.stack.addAll(snapshot.stack)
            snapshot.variables.forEachIndexed { index, value -> machine.variables[index] = value }
            snapshot.callStack.forEachIndexed { depth, frame ->
                machine.callFrames.add(CallFrame(frame.function, frame.returnPc, frame.locals.toTypedArray(), verdict.stackBases[depth]))
            }
            machine.console.replaceHistory(snapshot.consoleLines)
            val types = programTypes(program)
            for (entry in snapshot.objects) {
                val list = entry.list
                machine.heap[entry.id] = if (list == null) {
                    Instance(entry.classId, entry.fields.toTypedArray())
                } else {
                    ListEntry(types[list.element] ?: list.element, list.mutable, ArrayList(list.elements), entry.classId)
                }
                machine.listElements += list?.elements?.size ?: 0
            }
            machine.nextObjectId = snapshot.nextObjectId
            machine.randomState = snapshot.randomState
            return machine
        }

        // プログラムに現れる型（リストの形の要素の型とその内側の型・プロパティの型）。復元したリストの要素の型をプログラムの同じ型のインスタンスにそろえ、
        // 実行中の型の比較を同一性の確認で済ませる（第3.10b段階。保存データから読んだ型は別のインスタンスのため）
        private fun programTypes(program: CompiledProgram): Map<SnapshotVerifier.SlotType, SnapshotVerifier.SlotType> {
            val types = HashMap<SnapshotVerifier.SlotType, SnapshotVerifier.SlotType>()
            val roots = program.lists.map { it.element } + program.classes.flatMap { info -> info.fields.map { it.type } }
            for (root in roots) {
                var current: SnapshotVerifier.SlotType? = root
                while (current != null) {
                    types.putIfAbsent(current, current)
                    current = (current as? SnapshotVerifier.SlotType.LIST)?.element
                }
            }
            return types
        }
    }
}
