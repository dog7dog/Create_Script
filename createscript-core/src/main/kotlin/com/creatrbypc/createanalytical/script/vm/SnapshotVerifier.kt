package com.creatrbypc.createanalytical.script.vm

// スナップショットを復元してよいかの検証で見つかった問題
enum class SnapshotProblem {
    // バイトコードから各命令位置の状態を求められない（深さ・型が合わない、未初期化の変数を読むなど）
    UNVERIFIABLE_PROGRAM,
    INVALID_STATE,
    INVALID_PC,
    INVALID_WAIT,
    INVALID_RESUME,
    INVALID_STACK,
    INVALID_VARIABLES,
    INVALID_CONSOLE,

    // 呼び出しフレームの数・関数・戻り先・ローカル変数の数がバイトコードと合わない（第3.8c段階）
    INVALID_CALL_STACK,

    // オブジェクトのヒープ（数・id・クラス）か、次に発行する id が正しくない（第3.9a段階）。
    // プロパティ（第3.9b段階）の数・型が合わない、初期化中でないオブジェクトに未初期化のプロパティがある。
    // リスト（第3.10a段階）の要素の型・数・合計が正しくない
    INVALID_OBJECTS,
}

// 復元前の意味検証（D14）。バイトコードを抽象解釈して各命令位置の直前に成り立つスタックの深さと型・変数の初期化状態と型を求め、
// スナップショットがそれと一致するかを確かめる。制御フローの合流点では状態を突き合わせる（データフロー解析）。
// 読み出せるのは、すべての経路で初期化済みの変数だけ。Minecraft に依存しない。
// 関数（第3.8c段階）: 命令はトップレベルと関数ごとの区間に分かれ、分岐・次の命令は同じ区間の中だけに移る。関数の先頭の状態は
// すべての呼び出し元の大域変数の状態を合流したもの（スタックは空、ローカル変数は引数の型で初期化済み・残りは未初期化）で、
// CALL の次の命令の状態は、呼び出し前の状態から引数を除いて関数の結果の型を積み、関数のすべての RETURN の大域変数の状態を
// 合わせたもの（afterCall）。VM のスタックは呼び出し元の計算途中の値の上に積み重なるため、スナップショットのスタックは
// 呼び出しフレームごとの区間に分けて照合する（第3.8d段階）
object SnapshotVerifier {
    // スタック・変数の値の型。OBJECT はクラスごとに別の型（classId は CompiledProgram.classes の番号: 第3.9a段階）。
    // LIST は要素の型ごとに別の型（第3.10a段階。読み取り専用かどうかは型に含めず、変更できないリストは VM が実行時に拒否する）
    sealed interface SlotType {
        data object INT : SlotType
        data object BOOLEAN : SlotType
        data object STRING : SlotType
        data object UNIT : SlotType

        // 浮動小数点数（第3.11a段階）
        data object FLOAT : SlotType
        data object DOUBLE : SlotType
        data class OBJECT(val classId: Int) : SlotType

        // Vector（第3.11e段階）。実体は変更できない Double のリスト（ヒープの classId は VECTOR_CLASS）。List<Double> とは別の型
        data object VECTOR : SlotType

        // Matrix（第3.11e段階）。実体は変更できない、行の Vector のリスト（ヒープの classId は MATRIX_CLASS。行の長さはそろっている）
        data object MATRIX : SlotType
        // 入れ子（第3.10b段階）が深くても再帰しないよう、等価は外側から順にたどって判定し、ハッシュは作るときに 1 回だけ（内側の値から）計算する
        class LIST(val element: SlotType) : SlotType {
            private val hash = element.hashCode() * 31 + 5

            override fun hashCode() = hash

            override fun equals(other: Any?): Boolean {
                var left: LIST = this
                var right = other
                while (true) {
                    if (left === right) return true
                    if (right !is LIST || right.hash != left.hash) return false
                    val inner = left.element
                    if (inner !is LIST) return inner == right.element
                    left = inner
                    right = right.element
                }
            }

            override fun toString(): String {
                var depth = 0
                var current: SlotType = this
                while (current is LIST) {
                    depth++
                    current = current.element
                }
                return "LIST(".repeat(depth) + current + ")".repeat(depth)
            }
        }
    }

    // 値の照合に使う、プログラムとスナップショットのヒープ（オブジェクトの id → その実体の型。インスタンスは OBJECT、リストは LIST、Vector は VECTOR、
    // Matrix は MATRIX）
    private class Context(val program: CompiledProgram, val typeOf: Map<Int, SlotType>)

    // 変数の状態。合流点で「初期化済み」と「未初期化」が出会うと MaybeInitialized になる
    sealed interface VariableState {
        data object Uninitialized : VariableState
        data class Initialized(val type: SlotType) : VariableState
        data class MaybeInitialized(val type: SlotType) : VariableState
    }

    // 命令を実行する直前の状態。stack はその呼び出しフレームのスタック、variables は大域変数、locals はその関数のローカル変数（トップレベルでは空）
    data class Frame(val stack: List<SlotType>, val variables: List<VariableState>, val locals: List<VariableState> = emptyList())

    // 命令位置の区間のうち、トップレベルを表す番号（関数は CompiledProgram.functions の番号）
    private const val TOP_LEVEL = -1

    private class Unverifiable : RuntimeException(null, null, false, false)

    // 検証の結果。Valid の stackBases は、各呼び出しフレーム（外側から順）のスタックの始まり（CsVm.restore が使う）
    sealed interface Verdict {
        class Valid(val stackBases: List<Int>) : Verdict
        class Invalid(val problem: SnapshotProblem) : Verdict
    }

    fun verify(program: CompiledProgram, snapshot: VmSnapshot): SnapshotProblem? = (check(program, snapshot) as? Verdict.Invalid)?.problem

    fun check(program: CompiledProgram, snapshot: VmSnapshot): Verdict {
        fun invalid(problem: SnapshotProblem) = Verdict.Invalid(problem)
        val frames = framesOf(program) ?: return invalid(SnapshotProblem.UNVERIFIABLE_PROGRAM)
        if (snapshot.state != VmState.RUNNING && snapshot.state != VmState.WAITING) return invalid(SnapshotProblem.INVALID_STATE)
        val frame = frames.getOrNull(snapshot.pc) ?: return invalid(SnapshotProblem.INVALID_PC)
        // RUNNING の待機残りは常に 0。WAITING は waitTicks の直後にしかならない
        if (snapshot.waitRemaining < 0 || (snapshot.state == VmState.RUNNING && snapshot.waitRemaining != 0)) return invalid(SnapshotProblem.INVALID_WAIT)
        if (snapshot.state == VmState.WAITING && !snapshot.resumeWithUnit) return invalid(SnapshotProblem.INVALID_RESUME)
        // 結果の Unit を積む前なら、直前の命令は waitTicks の呼び出しで、スタックは結果の Unit 1個分少ない
        val expectedStack = if (snapshot.resumeWithUnit) {
            val previous = program.instructions.getOrNull(snapshot.pc - 1)
            if (previous == null || previous.opcode != Opcode.CALL_HOST || previous.operand != HostFunction.WAIT_TICKS.id) return invalid(SnapshotProblem.INVALID_RESUME)
            if (frame.stack.lastOrNull() != SlotType.UNIT) return invalid(SnapshotProblem.INVALID_RESUME)
            frame.stack.dropLast(1)
        } else {
            frame.stack
        }
        if (!isValidHeap(program, snapshot)) return invalid(SnapshotProblem.INVALID_OBJECTS)
        val context = Context(program, snapshot.objects.associate { it.id to heapType(it) })
        val invalidValues = snapshot.objects.any { entry ->
            val list = entry.list
            if (list != null) {
                list.elements.any { !matches(context, it, list.element) }
            } else {
                entry.fields.withIndex().any { (index, value) -> value != null && !matches(context, value, program.classes[entry.classId].fields[index].type) }
            }
        }
        if (invalidValues || !hasRectangularMatrices(snapshot)) return invalid(SnapshotProblem.INVALID_OBJECTS)
        if (snapshot.stack.size > VmLimits.MAX_STACK_DEPTH) return invalid(SnapshotProblem.INVALID_STACK)
        if (snapshot.variables.size != program.variableCount) return invalid(SnapshotProblem.INVALID_VARIABLES)
        if (!matchesStates(context, snapshot.variables, frame.variables)) return invalid(SnapshotProblem.INVALID_VARIABLES)
        val stackBases = when (val calls = verifyCallStack(context, snapshot, frames)) {
            is Verdict.Invalid -> return calls
            is Verdict.Valid -> calls.stackBases
        }
        // 最も内側のフレーム（トップレベルの実行中ならスタック全体）の区間
        val current = snapshot.stack.drop(stackBases.lastOrNull() ?: 0)
        if (current.size != expectedStack.size || current.indices.any { !matches(context, current[it], expectedStack[it]) }) return invalid(SnapshotProblem.INVALID_STACK)
        if (!hasValidInitialization(program, snapshot)) return invalid(SnapshotProblem.INVALID_OBJECTS)
        if (snapshot.consoleLines.size > Console.MAX_LINES || snapshot.consoleLines.any { !isValidConsoleLine(it) }) return invalid(SnapshotProblem.INVALID_CONSOLE)
        return Verdict.Valid(stackBases)
    }

    // 呼び出しフレーム（第3.8c段階）。外側から順に、戻り先の直前が「呼び出し元の区間にある、その関数を呼ぶ到達可能な CALL」で、
    // 最も内側の関数の区間に pc がある。各フレームのローカル変数は、そのフレームの関数の中の状態（呼び出し元のローカル変数は、
    // 呼び出した CALL の時点から変わらない。最も内側は pc の状態）と一致しなければならない。大域変数は、どの CALL の時点で
    // 初期化済みだったものも、その後に未初期化へ戻ることはないため、値が入っていて型が合わなければならない。
    // スタック（第3.8d段階）: 呼び出し元の区間は、CALL の時点のスタックから引数を除いたもの（関数の実行中は変わらない）
    private fun verifyCallStack(context: Context, snapshot: VmSnapshot, frames: List<Frame?>): Verdict {
        val program = context.program
        fun invalid(problem: SnapshotProblem) = Verdict.Invalid(problem)
        if (snapshot.callStack.size > VmLimits.MAX_CALL_DEPTH) return invalid(SnapshotProblem.INVALID_CALL_STACK)
        val regions = regionsOf(program) ?: return invalid(SnapshotProblem.UNVERIFIABLE_PROGRAM)
        val stackBases = ArrayList<Int>()
        var base = 0
        var callerRegion = TOP_LEVEL
        for ((depth, call) in snapshot.callStack.withIndex()) {
            val function = program.functions.getOrNull(call.function) ?: return invalid(SnapshotProblem.INVALID_CALL_STACK)
            val callPc = call.returnPc - 1
            val instruction = program.instructions.getOrNull(callPc) ?: return invalid(SnapshotProblem.INVALID_CALL_STACK)
            if (instruction.opcode != Opcode.CALL || instruction.operand != call.function || regions[callPc] != callerRegion) return invalid(SnapshotProblem.INVALID_CALL_STACK)
            val atCall = frames[callPc] ?: return invalid(SnapshotProblem.INVALID_CALL_STACK)
            if (call.locals.size != function.localCount) return invalid(SnapshotProblem.INVALID_CALL_STACK)
            // 呼び出し元のローカル変数（トップレベルなら無い）は、CALL の時点の状態のまま
            val callerLocals = if (callerRegion == TOP_LEVEL) emptyList() else snapshot.callStack[depth - 1].locals
            if (!matchesStates(context, callerLocals, atCall.locals)) return invalid(SnapshotProblem.INVALID_VARIABLES)
            val initializedAtCall = snapshot.variables.indices.all { index ->
                val state = atCall.variables[index]
                state !is VariableState.Initialized || snapshot.variables[index]?.let { matches(context, it, state.type) } == true
            }
            if (!initializedAtCall) return invalid(SnapshotProblem.INVALID_VARIABLES)
            val callerStack = atCall.stack.dropLast(function.parameterCount)
            if (base + callerStack.size > snapshot.stack.size) return invalid(SnapshotProblem.INVALID_STACK)
            if (callerStack.indices.any { !matches(context, snapshot.stack[base + it], callerStack[it]) }) return invalid(SnapshotProblem.INVALID_STACK)
            base += callerStack.size
            stackBases.add(base)
            callerRegion = call.function
        }
        if (regions[snapshot.pc] != callerRegion) return invalid(SnapshotProblem.INVALID_CALL_STACK)
        val currentLocals = snapshot.callStack.lastOrNull()?.locals ?: emptyList()
        if (!matchesStates(context, currentLocals, frames[snapshot.pc]!!.locals)) return invalid(SnapshotProblem.INVALID_VARIABLES)
        return Verdict.Valid(stackBases)
    }

    // ヒープ（第3.9a段階）: 数は上限以内、id は 1 以上・次に発行する id より小さく昇順で重複なし、クラスの番号はプログラムの範囲内、
    // プロパティの数はクラスと同じ（第3.9b段階）。どこからも参照されないオブジェクト（回収前）があってもよい。
    // 参照の先が無い・クラスが違う値は、値の照合（matches）で拒否する
    // リスト（第3.10a段階）: クラスの番号は LIST_CLASS でプロパティは無く、要素の型は値の型、要素数は 1 つ MAX_LIST_SIZE 以下・合計 MAX_LIST_ELEMENTS 以下
    private fun isValidHeap(program: CompiledProgram, snapshot: VmSnapshot): Boolean {
        if (snapshot.objects.size > VmLimits.MAX_OBJECTS || snapshot.nextObjectId < 1) return false
        var previous = 0
        var elements = 0
        for (entry in snapshot.objects) {
            if (entry.id <= previous || entry.id >= snapshot.nextObjectId) return false
            val list = entry.list
            if (list != null) {
                if (entry.fields.isNotEmpty() || !isValueType(program, list.element)) return false
                if (list.elements.size > VmLimits.MAX_LIST_SIZE) return false
                // Vector（第3.11e段階）は変更できない Double のリストで、要素は 1〜16 個。Matrix は変更できない Vector のリストで、行は 1〜8 個
                val vector = !list.mutable && list.element == SlotType.DOUBLE && list.elements.size in 1..VmLimits.MAX_VECTOR_SIZE
                val matrix = !list.mutable && list.element == SlotType.VECTOR && list.elements.size in 1..VmLimits.MAX_MATRIX_SIZE
                when (entry.classId) {
                    ObjectSnapshot.LIST_CLASS -> {}
                    ObjectSnapshot.VECTOR_CLASS -> if (!vector) return false
                    ObjectSnapshot.MATRIX_CLASS -> if (!matrix) return false
                    else -> return false
                }
                elements += list.elements.size
            } else {
                if (entry.classId !in program.classes.indices) return false
                if (entry.fields.size != program.classes[entry.classId].fields.size) return false
            }
            previous = entry.id
        }
        return elements <= VmLimits.MAX_LIST_ELEMENTS
    }

    // 未初期化のプロパティ（第3.9b段階）を持てるのは、初期化中のオブジェクト（そのクラスのコンストラクタの呼び出しフレームのローカル変数 0）だけ。
    // コンストラクタはプロパティを宣言の順に SET_FIELD で初期化し、未初期化に戻す命令は無いため、フレームの位置（最も内側は pc、
    // それ以外は次のフレームを呼んだ CALL）より前のコンストラクタの SET_FIELD の数だけ、先頭のプロパティは初期化済みでなければならない。
    // それより後のプロパティは、初期化式から this を渡した関数が先に代入している場合があるため、どちらでもよい。
    // NEW の直後（次の命令がそのクラスのコンストラクタの CALL）は、スタックの一番上のオブジェクトがまだ1つも初期化されていない
    private fun hasValidInitialization(program: CompiledProgram, snapshot: VmSnapshot): Boolean {
        val initialized = HashMap<Int, Int>()
        val next = program.instructions[snapshot.pc]
        val pending = snapshot.stack.lastOrNull() as? Value.ObjectRef
        if (next.opcode == Opcode.CALL && pending != null) {
            val classId = program.classes.indexOfFirst { it.constructor == next.operand }
            if (classId >= 0 && snapshot.objects.any { it.id == pending.id && it.classId == classId }) initialized[pending.id] = 0
        }
        for ((depth, frame) in snapshot.callStack.withIndex()) {
            val classId = program.classes.indexOfFirst { it.constructor == frame.function }
            val target = frame.locals.firstOrNull() as? Value.ObjectRef ?: continue
            if (classId < 0 || snapshot.objects.none { it.id == target.id && it.classId == classId }) continue
            val pc = if (depth == snapshot.callStack.lastIndex) snapshot.pc else snapshot.callStack[depth + 1].returnPc - 1
            initialized[target.id] = (program.functions[frame.function].entry until pc).count { program.instructions[it].opcode == Opcode.SET_FIELD }
        }
        return snapshot.objects.all { entry ->
            val done = initialized[entry.id]
            entry.fields.withIndex().all { (index, value) -> value != null || (done != null && index >= done) }
        }
    }

    // 変数の値（null は未初期化）が、その位置の初期化状態と型に合う
    private fun matchesStates(context: Context, values: List<Value?>, states: List<VariableState>): Boolean =
        values.size == states.size && values.indices.all { index ->
            val value = values[index]
            when (val expected = states[index]) {
                VariableState.Uninitialized -> value == null
                is VariableState.Initialized -> value != null && matches(context, value, expected.type)
                is VariableState.MaybeInitialized -> value == null || matches(context, value, expected.type)
            }
        }

    // 各命令位置の直前の状態（到達しない位置は null）。求められなければ null。
    // 状態が変わった位置に印を付け、命令位置の順に印の付いた位置だけを処理することを、変化が無くなるまで繰り返す。
    // 逆向きの分岐（ループ）の合流は次の周回でまとめて伝わるため、ループの本体を何度もたどり直さない。
    // 合流で状態が変わるのは変数が Uninitialized / Initialized から MaybeInitialized になるときだけで（一方向）、各位置の状態は
    // 最初に決まった後に最大（大域変数 + ローカル変数の数）回しか変わらない。関数の RETURN の状態（exits）も同じく一方向に変わり、
    // 変わるたびにその関数を呼ぶ CALL を処理し直す。処理回数は maxSteps 以下で必ず終わる。
    // その上限（stepLimit。JUnit では打ち切りの確認のために小さくする）を超えたら、検証できないものとして打ち切る
    fun framesOf(program: CompiledProgram, stepLimit: Long = maxSteps(program)): List<Frame?>? {
        if (program.variableCount !in 0..VmLimits.MAX_VARIABLES || program.instructions.isEmpty()) return null
        if (!hasValidClasses(program)) return null
        val regions = regionsOf(program) ?: return null
        val size = program.instructions.size
        val frames = arrayOfNulls<Frame>(size)
        frames[0] = Frame(emptyList(), List(program.variableCount) { VariableState.Uninitialized })
        val changed = BooleanArray(size).also { it[0] = true }
        // 関数ごとの、すべての RETURN の直前の大域変数の状態を合流したものと結果の型（まだ RETURN に達していなければ null）と、その関数を呼ぶ CALL の位置
        val exits = arrayOfNulls<Exit>(program.functions.size)
        val callSites = List(program.functions.size) { ArrayList<Int>() }
        program.instructions.forEachIndexed { pc, instruction -> if (instruction.opcode == Opcode.CALL) callSites.getOrNull(instruction.operand)?.add(pc) }
        var remainingSteps = stepLimit
        try {
            var sweepAgain = true
            while (sweepAgain) {
                sweepAgain = false
                for (pc in frames.indices) {
                    if (!changed[pc]) continue
                    changed[pc] = false
                    if (--remainingSteps < 0) throw Unverifiable()
                    val instruction = program.instructions[pc]
                    val frame = frames[pc]!!
                    fun flowTo(next: Int, state: Frame) {
                        val merged = frames[next]?.let { merge(it, state) ?: throw Unverifiable() } ?: state
                        if (merged != frames[next]) {
                            frames[next] = merged
                            changed[next] = true
                            // 前の位置へ戻る分岐の変化は、次の周回で処理する
                            if (next <= pc) sweepAgain = true
                        }
                    }
                    when (instruction.opcode) {
                        Opcode.CALL -> {
                            val function = program.functions.getOrNull(instruction.operand) ?: throw Unverifiable()
                            // 引数はスタックの上の parameterCount 個（左の引数が下）。Unit は引数にできない
                            if (frame.stack.size < function.parameterCount) throw Unverifiable()
                            val arguments = frame.stack.takeLast(function.parameterCount)
                            if (SlotType.UNIT in arguments) throw Unverifiable()
                            val locals = arguments.map { VariableState.Initialized(it) } + List(function.localCount - function.parameterCount) { VariableState.Uninitialized }
                            flowTo(function.entry, Frame(emptyList(), frame.variables, locals))
                            val exit = exits[instruction.operand]
                            if (exit != null) {
                                if (regions.getOrNull(pc + 1) != regions[pc]) throw Unverifiable()
                                flowTo(pc + 1, Frame(frame.stack.dropLast(function.parameterCount) + exit.result, afterCall(frame.variables, exit.variables), frame.locals))
                            }
                        }
                        Opcode.RETURN -> {
                            val function = regions[pc]
                            // operand 0 は空のスタックで Unit を返し、1 はスタックの値1つ（Unit 以外）を返す
                            val result = when {
                                function == TOP_LEVEL -> throw Unverifiable()
                                instruction.operand == 0 && frame.stack.isEmpty() -> SlotType.UNIT
                                instruction.operand == 1 && frame.stack.size == 1 && frame.stack[0] != SlotType.UNIT -> frame.stack[0]
                                else -> throw Unverifiable()
                            }
                            val previous = exits[function]
                            if (previous != null && previous.result != result) throw Unverifiable()
                            val merged = Exit(previous?.let { mergeVariables(it.variables, frame.variables) ?: throw Unverifiable() } ?: frame.variables, result)
                            if (merged != previous) {
                                exits[function] = merged
                                for (site in callSites[function]) {
                                    if (frames[site] == null) continue
                                    changed[site] = true
                                    if (site <= pc) sweepAgain = true
                                }
                            }
                        }
                        else -> {
                            // 関数の中で HALT は使わない（関数の終わりは RETURN）
                            if (instruction.opcode == Opcode.HALT && regions[pc] != TOP_LEVEL) throw Unverifiable()
                            val after = transfer(program, instruction, frame)
                            for (next in successors(pc, instruction)) {
                                // 範囲外・別の区間（関数の外）へは移れない
                                if (regions.getOrNull(next) != regions[pc]) throw Unverifiable()
                                flowTo(next, after)
                            }
                        }
                    }
                }
            }
        } catch (unverifiable: Unverifiable) {
            return null
        }
        return frames.toList()
    }

    // 関数のすべての RETURN を合流した大域変数の状態と、結果の型（同じ関数の RETURN はすべて同じ型を返す）
    private data class Exit(val variables: List<VariableState>, val result: SlotType)

    // 処理回数の上限。各位置は（変数の数 + 1）回まで、CALL はさらに関数の RETURN の状態が変わるたび（大域変数の数 + 1 回まで）処理し直す
    fun maxSteps(program: CompiledProgram): Long {
        val maxLocals = program.functions.maxOfOrNull { it.localCount } ?: 0
        val calls = program.instructions.count { it.opcode == Opcode.CALL }
        return program.instructions.size.toLong() * (program.variableCount + maxLocals + 1) + calls.toLong() * (program.variableCount + 1)
    }

    // クラス（第3.9b段階）: プロパティの数は上限以内で、型は値の型。コンストラクタは無い（-1）か範囲内の関数で、
    // プロパティのあるクラスの数だけ別々の関数（1つの関数が2つのクラスのコンストラクタを兼ねない）。
    // リストの形（第3.10a段階）: 要素の型は値の型で、最初の要素の数は MAX_LIST_SIZE 以下
    private fun hasValidClasses(program: CompiledProgram): Boolean {
        val constructors = program.classes.map { it.constructor }.filter { it != -1 }
        if (constructors.any { it !in program.functions.indices } || constructors.toSet().size != constructors.size) return false
        if (program.lists.any { it.size !in 0..VmLimits.MAX_LIST_SIZE || !isValueType(program, it.element) }) return false
        return program.classes.all { info -> info.fields.size <= VmLimits.MAX_PROPERTIES && info.fields.all { isValueType(program, it.type) } }
    }

    // 変数・プロパティ・要素に入れられる型（Unit 以外。オブジェクトは範囲内のクラス、リストは要素が値の型）
    // 入れ子のリスト（第3.10b段階）は内側までたどる（再帰しない）
    // ヒープの実体の型（インスタンスは OBJECT、リストは LIST、Vector・Matrix は VECTOR・MATRIX: 第3.11e段階）
    private fun heapType(entry: ObjectSnapshot): SlotType {
        val list = entry.list ?: return SlotType.OBJECT(entry.classId)
        return when (entry.classId) {
            ObjectSnapshot.VECTOR_CLASS -> SlotType.VECTOR
            ObjectSnapshot.MATRIX_CLASS -> SlotType.MATRIX
            else -> SlotType.LIST(list.element)
        }
    }

    // Matrix の行（どれも Vector であることは値の照合で確かめ済み）の長さがそろっていて、1〜8（第3.11e段階）
    private fun hasRectangularMatrices(snapshot: VmSnapshot): Boolean {
        val sizes = snapshot.objects.associate { it.id to (it.list?.elements?.size ?: 0) }
        return snapshot.objects.filter { it.classId == ObjectSnapshot.MATRIX_CLASS }.all { entry ->
            val rows = entry.list!!.elements.map { sizes[(it as Value.ObjectRef).id] }
            rows.distinct().size == 1 && rows[0]!! in 1..VmLimits.MAX_MATRIX_SIZE
        }
    }

    private fun isValueType(program: CompiledProgram, type: SlotType): Boolean {
        var current = type
        while (current is SlotType.LIST) current = current.element
        return when (current) {
            SlotType.UNIT -> false
            is SlotType.OBJECT -> current.classId in program.classes.indices
            SlotType.INT, SlotType.BOOLEAN, SlotType.STRING, SlotType.FLOAT, SlotType.DOUBLE, SlotType.VECTOR, SlotType.MATRIX -> true
            is SlotType.LIST -> error("unreachable")
        }
    }

    // 命令位置ごとの区間（TOP_LEVEL か関数の番号）。関数の先頭は 0 より後で昇順に並び、ローカル変数・引数の数は上限以内でなければならない
    private fun regionsOf(program: CompiledProgram): IntArray? {
        val regions = IntArray(program.instructions.size) { TOP_LEVEL }
        var previousEntry = 0
        for ((index, function) in program.functions.withIndex()) {
            if (function.entry <= previousEntry || function.entry >= regions.size || function.localCount !in 0..VmLimits.MAX_VARIABLES) return null
            if (function.parameterCount !in 0..minOf(function.localCount, VmLimits.MAX_PARAMETERS)) return null
            regions.fill(index, function.entry, regions.size)
            previousEntry = function.entry
        }
        return regions
    }

    // 次に実行されうる命令位置（CALL・RETURN は framesOf が扱う）
    private fun successors(pc: Int, instruction: Instruction): List<Int> = when (instruction.opcode) {
        Opcode.HALT -> emptyList()
        Opcode.JUMP -> listOf(instruction.operand)
        Opcode.JUMP_IF_FALSE -> if (instruction.operand == pc + 1) listOf(pc + 1) else listOf(pc + 1, instruction.operand)
        else -> listOf(pc + 1)
    }

    // 命令1つ分の状態の変化（VM の実行処理と同じ規則）。変数の一覧は STORE のときだけ写して変える（それ以外は共有する）
    private fun transfer(program: CompiledProgram, instruction: Instruction, frame: Frame): Frame {
        val stack = frame.stack.toMutableList()
        var variables = frame.variables
        var locals = frame.locals
        fun pop(): SlotType = stack.removeLastOrNull() ?: throw Unverifiable()
        fun popExactly(type: SlotType) = if (pop() == type) Unit else throw Unverifiable()
        fun popInt() = popExactly(SlotType.INT)
        fun popBoolean() = if (pop() == SlotType.BOOLEAN) Unit else throw Unverifiable()
        fun push(type: SlotType) {
            if (stack.size >= VmLimits.MAX_STACK_DEPTH) throw Unverifiable()
            stack.add(type)
        }
        when (instruction.opcode) {
            Opcode.PUSH_CONST -> push(
                when (program.constants.getOrNull(instruction.operand)) {
                    is Constant.IntConstant -> SlotType.INT
                    is Constant.StringConstant -> SlotType.STRING
                    is Constant.FloatConstant -> SlotType.FLOAT
                    is Constant.DoubleConstant -> SlotType.DOUBLE
                    null -> throw Unverifiable()
                }
            )
            Opcode.LOAD -> push((variables.getOrNull(instruction.operand) as? VariableState.Initialized)?.type ?: throw Unverifiable())
            Opcode.STORE -> {
                if (instruction.operand !in variables.indices) throw Unverifiable()
                val type = pop()
                if (type == SlotType.UNIT) throw Unverifiable()
                variables = variables.toMutableList().also { it[instruction.operand] = VariableState.Initialized(type) }
            }
            Opcode.LOAD_LOCAL -> push((locals.getOrNull(instruction.operand) as? VariableState.Initialized)?.type ?: throw Unverifiable())
            Opcode.STORE_LOCAL -> {
                if (instruction.operand !in locals.indices) throw Unverifiable()
                val type = pop()
                if (type == SlotType.UNIT) throw Unverifiable()
                locals = locals.toMutableList().also { it[instruction.operand] = VariableState.Initialized(type) }
            }
            Opcode.POP -> pop()
            // 数値の演算（第3.11a段階）: 両方の項が operand の数値の型と同じ型（型の違う項は Compiler が CONVERT でそろえる。暗黙の変換は無い）
            Opcode.ADD, Opcode.SUBTRACT, Opcode.MULTIPLY, Opcode.DIVIDE, Opcode.REMAINDER -> {
                val type = numericType(instruction.operand)
                popExactly(type)
                popExactly(type)
                push(type)
            }
            Opcode.NEGATE -> {
                val type = numericType(instruction.operand)
                popExactly(type)
                push(type)
            }
            Opcode.PUSH_BOOL -> if (instruction.operand == 0 || instruction.operand == 1) push(SlotType.BOOLEAN) else throw Unverifiable()
            Opcode.NOT -> {
                popBoolean()
                push(SlotType.BOOLEAN)
            }
            // operand 0 は Float・Double 以外の同じ型どうし、1・2 は Float・Double どうし（IEEE 754 の等価: 第3.11a段階）
            Opcode.EQUAL, Opcode.NOT_EQUAL -> {
                val right = pop()
                val left = pop()
                if (left != right || left == SlotType.UNIT) throw Unverifiable()
                val floating = left == SlotType.FLOAT || left == SlotType.DOUBLE
                val valid = when (instruction.operand) {
                    NumericKind.INT -> !floating
                    else -> floating && left == numericType(instruction.operand)
                }
                if (!valid) throw Unverifiable()
                push(SlotType.BOOLEAN)
            }
            Opcode.LESS, Opcode.LESS_EQUAL, Opcode.GREATER, Opcode.GREATER_EQUAL -> {
                val type = numericType(instruction.operand)
                popExactly(type)
                popExactly(type)
                push(SlotType.BOOLEAN)
            }
            Opcode.JUMP -> {}
            Opcode.JUMP_IF_FALSE -> popBoolean()
            Opcode.CALL_HOST -> when (HostFunction.byId(instruction.operand)) {
                HostFunction.PRINTLN -> {
                    if (pop() == SlotType.UNIT) throw Unverifiable()
                    push(SlotType.UNIT)
                }
                HostFunction.PRINTLN_EMPTY -> push(SlotType.UNIT)
                HostFunction.WAIT_TICKS -> {
                    popInt()
                    push(SlotType.UNIT)
                }
                // 乱数（第3.11e段階）
                HostFunction.RANDOM -> push(SlotType.DOUBLE)
                HostFunction.RANDOM_RANGE -> {
                    popExactly(SlotType.DOUBLE)
                    popExactly(SlotType.DOUBLE)
                    push(SlotType.DOUBLE)
                }
                HostFunction.RANDOM_INT -> {
                    popInt()
                    popInt()
                    push(SlotType.INT)
                }
                HostFunction.RANDOM_SEED -> {
                    popInt()
                    push(SlotType.UNIT)
                }
                null -> throw Unverifiable()
            }
            Opcode.HALT -> {}
            Opcode.NEW -> if (instruction.operand in program.classes.indices) push(SlotType.OBJECT(instruction.operand)) else throw Unverifiable()
            // プロパティ（第3.9b段階）: オブジェクトの参照のクラスにその番号のプロパティがあり、入れる値はプロパティの型と同じ
            Opcode.GET_FIELD -> push(fieldOf(program, pop(), instruction.operand).type)
            Opcode.SET_FIELD -> {
                val value = pop()
                if (fieldOf(program, pop(), instruction.operand).type != value) throw Unverifiable()
            }
            // リスト（第3.10a段階）: 要素・入れる値は要素の型、添字は Int
            Opcode.NEW_LIST -> {
                val shape = program.lists.getOrNull(instruction.operand) ?: throw Unverifiable()
                repeat(shape.size) { if (pop() != shape.element) throw Unverifiable() }
                push(SlotType.LIST(shape.element))
            }
            // Vector（第3.11e段階）の要素の読み出しと要素数は、リストと同じ命令（変更する命令は使えない）
            Opcode.LIST_GET -> {
                popInt()
                push(readableElementOf(pop()))
            }
            Opcode.LIST_SET -> {
                val value = pop()
                popInt()
                if (elementOf(pop()) != value) throw Unverifiable()
            }
            Opcode.LIST_SIZE -> {
                readableElementOf(pop())
                push(SlotType.INT)
            }
            Opcode.LIST_ADD -> {
                val value = pop()
                if (elementOf(pop()) != value) throw Unverifiable()
                push(SlotType.UNIT)
            }
            Opcode.LIST_REMOVE_AT -> {
                popInt()
                push(elementOf(pop()))
            }
            Opcode.LIST_CLEAR -> {
                elementOf(pop())
                push(SlotType.UNIT)
            }
            // 数値の変換（第3.11a段階）: 別の数値の型どうしの 6 通りだけ
            Opcode.CONVERT -> {
                val operand = instruction.operand
                if (operand !in 0 until NumericKind.COUNT * NumericKind.COUNT || operand / NumericKind.COUNT == operand % NumericKind.COUNT) throw Unverifiable()
                popExactly(numericType(operand / NumericKind.COUNT))
                push(numericType(operand % NumericKind.COUNT))
            }
            // 数学関数（第3.11b段階）: その関数に使える数値の型の値を引数の数だけ取り出し、結果の型（同じ型・roundToInt は Int・
            // isNaN・isInfinite は Boolean: 第3.11d段階）を積む
            Opcode.MATH -> {
                val function = MathFunction.byOperand(instruction.operand) ?: throw Unverifiable()
                val type = numericType(instruction.operand % NumericKind.COUNT)
                repeat(function.arity) { popExactly(type) }
                push(
                    when (function.result) {
                        MathResult.SAME -> type
                        MathResult.INT -> SlotType.INT
                        MathResult.BOOLEAN -> SlotType.BOOLEAN
                    },
                )
            }
            // Vector の演算（第3.11e段階）: 関数ごとのオペランドの型を取り出し、結果の型を積む
            Opcode.LINALG -> {
                val (function, count) = LinalgFunction.byOperand(instruction.operand) ?: throw Unverifiable()
                val operands = when (function) {
                    LinalgFunction.VECTOR_OF -> List(count) { SlotType.DOUBLE }
                    LinalgFunction.VECTOR_OF_LIST -> listOf(SlotType.LIST(SlotType.DOUBLE))
                    LinalgFunction.ADD, LinalgFunction.SUBTRACT, LinalgFunction.DOT -> listOf(SlotType.VECTOR, SlotType.VECTOR)
                    LinalgFunction.NEGATE, LinalgFunction.LENGTH, LinalgFunction.NORMALIZE -> listOf(SlotType.VECTOR)
                    LinalgFunction.TIMES, LinalgFunction.DIVIDE -> listOf(SlotType.VECTOR, SlotType.DOUBLE)
                    LinalgFunction.TIMES_LEFT -> listOf(SlotType.DOUBLE, SlotType.VECTOR)
                    LinalgFunction.MATRIX_OF -> List(count) { SlotType.VECTOR }
                    LinalgFunction.MATRIX_ADD, LinalgFunction.MATRIX_SUBTRACT, LinalgFunction.MATMUL -> listOf(SlotType.MATRIX, SlotType.MATRIX)
                    LinalgFunction.MATRIX_TIMES -> listOf(SlotType.MATRIX, SlotType.DOUBLE)
                    LinalgFunction.MATRIX_TIMES_LEFT -> listOf(SlotType.DOUBLE, SlotType.MATRIX)
                    LinalgFunction.TRANSPOSE, LinalgFunction.COLUMN_COUNT -> listOf(SlotType.MATRIX)
                    LinalgFunction.MATMUL_VECTOR -> listOf(SlotType.MATRIX, SlotType.VECTOR)
                }
                for (type in operands.asReversed()) popExactly(type)
                push(
                    when (function) {
                        LinalgFunction.DOT, LinalgFunction.LENGTH -> SlotType.DOUBLE
                        LinalgFunction.COLUMN_COUNT -> SlotType.INT
                        LinalgFunction.MATRIX_OF, LinalgFunction.MATRIX_ADD, LinalgFunction.MATRIX_SUBTRACT, LinalgFunction.MATRIX_TIMES, LinalgFunction.MATRIX_TIMES_LEFT,
                        LinalgFunction.TRANSPOSE, LinalgFunction.MATMUL -> SlotType.MATRIX
                        else -> SlotType.VECTOR
                    },
                )
            }
            Opcode.CALL, Opcode.RETURN -> error("handled by framesOf")
        }
        return Frame(stack, variables, locals)
    }

    // 数値の演算・比較・変換の operand（NumericKind）が表す型（第3.11a段階）。それ以外の operand は検証できない
    private fun numericType(kind: Int): SlotType = when (kind) {
        NumericKind.INT -> SlotType.INT
        NumericKind.FLOAT -> SlotType.FLOAT
        NumericKind.DOUBLE -> SlotType.DOUBLE
        else -> throw Unverifiable()
    }

    private fun elementOf(target: SlotType): SlotType = (target as? SlotType.LIST)?.element ?: throw Unverifiable()

    // 読み出しだけの命令（LIST_GET・LIST_SIZE）の受け手: リストか Vector・Matrix（第3.11e段階。Matrix の要素は行の Vector）
    private fun readableElementOf(target: SlotType): SlotType = when (target) {
        SlotType.VECTOR -> SlotType.DOUBLE
        SlotType.MATRIX -> SlotType.VECTOR
        else -> elementOf(target)
    }

    private fun fieldOf(program: CompiledProgram, target: SlotType, index: Int): FieldInfo =
        (target as? SlotType.OBJECT)?.let { program.classes[it.classId].fields.getOrNull(index) } ?: throw Unverifiable()

    // 合流点: スタックは深さ・型とも一致しなければならない。変数は型が同じなら初期化状態をまとめ（片方だけ初期化済みなら MaybeInitialized）、
    // 型が違えば検証できない（null）。MaybeInitialized の変数は LOAD できない（transfer が Initialized だけを許す）
    internal fun merge(existing: Frame, incoming: Frame): Frame? {
        if (existing == incoming) return existing
        if (existing.stack != incoming.stack) return null
        val variables = mergeVariables(existing.variables, incoming.variables) ?: return null
        val locals = mergeVariables(existing.locals, incoming.locals) ?: return null
        return Frame(existing.stack, variables, locals)
    }

    private fun mergeVariables(existing: List<VariableState>, incoming: List<VariableState>): List<VariableState>? {
        if (existing == incoming) return existing
        if (existing.size != incoming.size) return null
        return existing.zip(incoming) { a, b ->
            when {
                a == b -> a
                a == VariableState.Uninitialized -> VariableState.MaybeInitialized(typeOf(b) ?: return null)
                b == VariableState.Uninitialized -> VariableState.MaybeInitialized(typeOf(a) ?: return null)
                typeOf(a) == typeOf(b) -> VariableState.MaybeInitialized(typeOf(a) ?: return null)
                else -> return null
            }
        }
    }

    // 関数から戻った後の大域変数の状態。呼び出し前に初期化済みの変数は、関数の中で未初期化に戻ることはない（そうする命令は無い）。
    // 関数がすべての経路で初期化した変数は初期化済みになる。どちらでもなければ、両方とも未初期化なら未初期化、それ以外は MaybeInitialized。
    // 型が違えば検証できない
    private fun afterCall(before: List<VariableState>, exit: List<VariableState>): List<VariableState> = before.zip(exit) { b, e ->
        val type = typeOf(b) ?: typeOf(e)
        if (typeOf(b) != null && typeOf(e) != null && typeOf(b) != typeOf(e)) throw Unverifiable()
        when {
            b is VariableState.Initialized -> b
            e is VariableState.Initialized -> e
            b == VariableState.Uninitialized && e == VariableState.Uninitialized -> b
            else -> VariableState.MaybeInitialized(type ?: throw Unverifiable())
        }
    }

    private fun typeOf(state: VariableState): SlotType? = when (state) {
        VariableState.Uninitialized -> null
        is VariableState.Initialized -> state.type
        is VariableState.MaybeInitialized -> state.type
    }

    // 値と型が一致し、String は定数プールの String を、オブジェクトの参照はヒープにある同じクラスのオブジェクトを指している
    private fun matches(context: Context, value: Value, type: SlotType): Boolean = when (value) {
        is Value.IntValue -> type == SlotType.INT
        is Value.BoolValue -> type == SlotType.BOOLEAN
        is Value.StringValue -> type == SlotType.STRING && context.program.constants.getOrNull(value.constantIndex) is Constant.StringConstant
        // 浮動小数点数はどのビット列も値（NaN・Infinity・-0.0 を含む: 第3.11a段階）
        is Value.FloatValue -> type == SlotType.FLOAT
        is Value.DoubleValue -> type == SlotType.DOUBLE
        is Value.ObjectRef -> (type is SlotType.OBJECT || type is SlotType.LIST || type == SlotType.VECTOR || type == SlotType.MATRIX) && context.typeOf[value.id] == type
        Value.UnitValue -> type == SlotType.UNIT
    }

    // コンソールの1行は最大 64 単位で、孤立サロゲートを含まない（Console.fit の結果と同じ条件）
    private fun isValidConsoleLine(line: String): Boolean {
        if (line.length > Console.MAX_LINE_LENGTH) return false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c.isHighSurrogate() && line.getOrNull(i + 1)?.isLowSurrogate() == true) {
                i += 2
                continue
            }
            if (c.isSurrogate()) return false
            i++
        }
        return true
    }
}
