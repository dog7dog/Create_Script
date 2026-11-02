package com.creatrbypc.createanalytical.script.compiler

import com.creatrbypc.createanalytical.script.ast.Assignment
import com.creatrbypc.createanalytical.script.ast.BinaryExpression
import com.creatrbypc.createanalytical.script.ast.BinaryOperator
import com.creatrbypc.createanalytical.script.ast.BooleanLiteral
import com.creatrbypc.createanalytical.script.ast.BreakStatement
import com.creatrbypc.createanalytical.script.ast.CallExpression
import com.creatrbypc.createanalytical.script.ast.CallableDeclaration
import com.creatrbypc.createanalytical.script.ast.ClassDeclaration
import com.creatrbypc.createanalytical.script.ast.ContinueStatement
import com.creatrbypc.createanalytical.script.ast.DoubleLiteral
import com.creatrbypc.createanalytical.script.ast.Expression
import com.creatrbypc.createanalytical.script.ast.ExpressionStatement
import com.creatrbypc.createanalytical.script.ast.FloatLiteral
import com.creatrbypc.createanalytical.script.ast.ForStatement
import com.creatrbypc.createanalytical.script.ast.FunctionDeclaration
import com.creatrbypc.createanalytical.script.ast.IfStatement
import com.creatrbypc.createanalytical.script.ast.IndexExpression
import com.creatrbypc.createanalytical.script.ast.IntLiteral
import com.creatrbypc.createanalytical.script.ast.LogicalExpression
import com.creatrbypc.createanalytical.script.ast.LogicalOperator
import com.creatrbypc.createanalytical.script.ast.MemberAccessExpression
import com.creatrbypc.createanalytical.script.ast.NameReference
import com.creatrbypc.createanalytical.script.ast.RangeKind
import com.creatrbypc.createanalytical.script.ast.ReturnStatement
import com.creatrbypc.createanalytical.script.ast.Statement
import com.creatrbypc.createanalytical.script.ast.StringLiteral
import com.creatrbypc.createanalytical.script.ast.ThisExpression
import com.creatrbypc.createanalytical.script.ast.UnaryExpression
import com.creatrbypc.createanalytical.script.ast.UnaryOperator
import com.creatrbypc.createanalytical.script.ast.VariableDeclaration
import com.creatrbypc.createanalytical.script.ast.WhileStatement
import com.creatrbypc.createanalytical.script.diagnostic.Diagnostic
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCollector
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticPhase
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticSeverity
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import com.creatrbypc.createanalytical.script.lexer.Lexer
import com.creatrbypc.createanalytical.script.parser.Parser
import com.creatrbypc.createanalytical.script.semantic.AnalyzedProgram
import com.creatrbypc.createanalytical.script.semantic.BuiltinFunctions
import com.creatrbypc.createanalytical.script.semantic.LinalgOperation
import com.creatrbypc.createanalytical.script.semantic.ListOperation
import com.creatrbypc.createanalytical.script.semantic.MathCall
import com.creatrbypc.createanalytical.script.semantic.MathOperation
import com.creatrbypc.createanalytical.script.semantic.RandomOperation
import com.creatrbypc.createanalytical.script.semantic.SemanticAnalyzer
import com.creatrbypc.createanalytical.script.semantic.ScriptType
import com.creatrbypc.createanalytical.script.semantic.SemanticResult
import com.creatrbypc.createanalytical.script.semantic.VariableSymbol
import com.creatrbypc.createanalytical.script.vm.ClassInfo
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.Constant
import com.creatrbypc.createanalytical.script.vm.FieldInfo
import com.creatrbypc.createanalytical.script.vm.FunctionInfo
import com.creatrbypc.createanalytical.script.vm.HostFunction
import com.creatrbypc.createanalytical.script.vm.Instruction
import com.creatrbypc.createanalytical.script.vm.ListShape
import com.creatrbypc.createanalytical.script.vm.LinalgFunction
import com.creatrbypc.createanalytical.script.vm.MathFunction
import com.creatrbypc.createanalytical.script.vm.NumericKind
import com.creatrbypc.createanalytical.script.vm.Opcode
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.VmLimits
import java.util.IdentityHashMap

// program はエラーが無いときだけ返す。診断は前の段階の分を含めて最大16件
data class CompileResult(
    val program: CompiledProgram?,
    val diagnostics: List<Diagnostic>,
    val diagnosticsTruncated: Boolean,
    val hasErrors: Boolean,
)

// 意味解析済みの AST をスタック型 VM のバイトコードへ変換する。Minecraft に依存しない純粋な Kotlin
class Compiler private constructor(private val analyzed: AnalyzedProgram, private val diagnostics: DiagnosticCollector) {
    private val instructions = ArrayList<Instruction>()
    private val constants = ArrayList<Constant>()

    // 同じ型・同じ値の定数は1つにまとめ、最初に現れた順に番号を付ける（A10）
    private val constantIndices = HashMap<Constant, Int>()
    private val slots = IdentityHashMap<VariableSymbol, Int>()

    // 使った変数の番号の数。ソースの変数のほか、for の終端を入れる番号（名前なし）も数える
    private var slotCount = 0

    // いまコンパイルしている関数のローカル変数の番号とその数（第3.8c段階。トップレベルの文では null）。
    // 関数の中の変数は、呼び出しごとに作られる呼び出しフレームの変数（LOAD_LOCAL / STORE_LOCAL）で、番号は関数ごとに 0 から振る
    private var locals: IdentityHashMap<VariableSymbol, Int>? = null
    private var localCount = 0

    // 手続き（関数・コンストラクタ・メソッド: 第3.9b段階）の番号（AnalyzedProgram.routines の順。CALL の operand）
    private val routineIndices = IdentityHashMap<CallableDeclaration, Int>()

    // クラスの番号（宣言の順。NEW の operand: 第3.9a段階）
    private val classIndices = IdentityHashMap<ClassDeclaration, Int>()

    // リストの形（NEW_LIST の operand: 第3.10a段階）。同じ形は1つにまとめ、最初に現れた順に番号を付ける
    private val listShapes = ArrayList<ListShape>()
    private val listShapeIndices = HashMap<ListShape, Int>()

    // いまコンパイルしている手続きの番号（トップレベルは -1）と、文脈（トップレベル・手続き）ごとのスタックの最大の深さ（第3.8d段階）
    private var currentFunction = -1
    private var contextMaxDepth = 0
    private val functionMaxDepths = ArrayList<Int>()

    // ユーザー定義関数の呼び出し。base は呼び出し先の実行中に残る呼び出し元のスタックの深さ（引数を除く）
    private class CallSite(val caller: Int, val callee: Int, val base: Int, val position: SourcePosition)

    private val callSites = ArrayList<CallSite>()

    // 手続きごとの、呼び出しの関係の循環（再帰: 第3.8e段階）に入りうるか（自分が循環の上にあるか、循環に入りうる手続きを呼ぶ）
    private var reachesRecursion = BooleanArray(0)
    private var stackDepth = 0
    private var maxStackDepth = 0

    // 囲んでいる while / for（最も内側が末尾）。continueTarget は continue の移り先（while は条件の先頭。for は本体の後で決まるため null）。
    // breaks / continues は移り先が決まった後に埋める JUMP の位置
    private class Loop(val continueTarget: Int?) {
        val breaks = ArrayList<Int>()
        val continues = ArrayList<Int>()
    }

    private val loops = ArrayList<Loop>()

    // 上限超過。報告済みのため、これ以上変換しない
    private class CompileAbort : RuntimeException(null, null, false, false)

    companion object {
        fun compile(semanticResult: SemanticResult): CompileResult {
            val diagnostics = DiagnosticCollector()
            diagnostics.merge(semanticResult.diagnostics, semanticResult.diagnosticsTruncated, semanticResult.hasErrors)
            // 前の段階でエラーがあれば変換しない（A8）
            val program = semanticResult.program?.let { Compiler(it, diagnostics).run() }?.let { verified(it, diagnostics) }
            return CompileResult(program.takeUnless { diagnostics.hasErrors }, diagnostics.diagnostics, diagnostics.isTruncated, diagnostics.hasErrors)
        }

        // ソースから Lexer → Parser → Semantic → Compiler を順に実行する
        fun compileSource(source: String): CompileResult =
            compile(SemanticAnalyzer.analyze(Parser.parse(Lexer.tokenize(source))))

        // 出力したバイトコードを SnapshotVerifier で検証する（第3.7b段階）。分岐先・合流点のスタックの深さと型・変数の初期化を確かめ、
        // スタックの深さが maxStackDepth を超えないことも確かめる。通らなければ Compiler の内部エラーとして報告し、プログラムを返さない
        internal fun verified(program: CompiledProgram, diagnostics: DiagnosticCollector): CompiledProgram? {
            val frames = SnapshotVerifier.framesOf(program)
            if (frames != null && frames.all { (it?.stack?.size ?: 0) <= program.maxStackDepth }) return program
            diagnostics.report(Diagnostic(DiagnosticCode.COMP_VERIFICATION_FAILED, DiagnosticSeverity.ERROR, DiagnosticPhase.COMPILER, END))
            return null
        }

        private val END = SourcePosition(0, 1, 1)
    }

    // トップレベルの文（HALT で終わる）の後に、手続きを AnalyzedProgram.routines の順に並べる。関数の無いプログラムの命令は第3.8b段階と、
    // プロパティ・メソッドの無いクラスだけのプログラムの命令は第3.9a段階と同じ
    private fun run(): CompiledProgram? = try {
        analyzed.routines.forEachIndexed { index, routine -> routineIndices[routine] = index }
        analyzed.program.classes.forEachIndexed { index, declaration -> classIndices[declaration] = index }
        for (declaration in analyzed.program.classes) {
            if (declaration.properties.size > VmLimits.MAX_PROPERTIES) {
                abort(DiagnosticCode.COMP_TOO_MANY_PROPERTIES, declaration.properties[VmLimits.MAX_PROPERTIES].namePosition, VmLimits.MAX_PROPERTIES)
            }
        }
        reachesRecursion = recursionReachability()
        checkCallDepth()
        analyzed.program.statements.forEach(::compileStatement)
        emit(Opcode.HALT, 0, END, 0)
        val functions = analyzed.routines.map { routine ->
            when (routine) {
                is FunctionDeclaration -> compileFunction(routine)
                is ClassDeclaration -> compileConstructor(routine)
            }
        }
        checkTotalStackDepth()
        val classes = analyzed.program.classes.map { declaration ->
            val fields = declaration.properties.map { FieldInfo(it.name, slotTypeOf(analyzed.propertyTypes.getValue(it))) }
            ClassInfo(declaration.name, fields, routineIndices[declaration] ?: -1)
        }
        CompiledProgram(instructions.toList(), constants.toList(), slotCount, maxStackDepth, functions, classes, listShapes.toList())
    } catch (abort: CompileAbort) {
        null
    }

    // 同じ型は同じインスタンスにする（第3.10b段階。入れ子の深い型どうしの比較が、VM・SnapshotVerifier で同一性の確認だけで済む）
    private val slotTypes = HashMap<ScriptType, SnapshotVerifier.SlotType>()

    // 入れ子のリストは、外側から順にたどってまだ変換していない段を集め、内側から組み立てる（深い入れ子でも再帰しない）
    private fun slotTypeOf(type: ScriptType): SnapshotVerifier.SlotType {
        val pending = ArrayList<ScriptType.ListType>()
        var current = type
        var converted = slotTypes[current]
        while (converted == null && current is ScriptType.ListType) {
            pending.add(current)
            current = current.element
            converted = slotTypes[current]
        }
        var result = converted ?: when (current) {
            ScriptType.INT -> SnapshotVerifier.SlotType.INT
            ScriptType.BOOLEAN -> SnapshotVerifier.SlotType.BOOLEAN
            ScriptType.STRING -> SnapshotVerifier.SlotType.STRING
            ScriptType.FLOAT -> SnapshotVerifier.SlotType.FLOAT
            ScriptType.DOUBLE -> SnapshotVerifier.SlotType.DOUBLE
            ScriptType.VECTOR -> SnapshotVerifier.SlotType.VECTOR
            ScriptType.MATRIX -> SnapshotVerifier.SlotType.MATRIX
            ScriptType.UNIT -> error("Semantic analysis should have rejected a Unit property")
            is ScriptType.ClassType -> SnapshotVerifier.SlotType.OBJECT(classIndices.getValue(current.declaration))
            is ScriptType.ListType -> error("unreachable")
        }.also { slotTypes[current] = it }
        for (list in pending.asReversed()) {
            result = SnapshotVerifier.SlotType.LIST(result)
            slotTypes[list] = result
        }
        return result
    }

    // 引数はローカル変数の先頭（番号 0 から宣言の順: 第3.8d段階）。メソッド（第3.9b段階）はローカル変数 0 が this で、引数はその後。
    // Unit の関数は、本体の末尾に達したら呼び出し元へ戻る（return を省略した場合。到達しない場合も出す）。
    // 値を返す関数の末尾には、Semantic がすべての経路の return を確かめたため到達しない
    private fun compileFunction(function: FunctionDeclaration): FunctionInfo {
        val entry = instructions.size
        enterRoutine(function)
        val method = analyzed.owners.containsKey(function)
        val parameters = analyzed.parameters.getValue(function)
        // this もローカル変数の先頭に入るため、メソッドの引数は 1 つ少ない
        val maxParameters = if (method) VmLimits.MAX_PARAMETERS - 1 else VmLimits.MAX_PARAMETERS
        if (parameters.size > maxParameters) abort(DiagnosticCode.COMP_TOO_MANY_PARAMETERS, function.parameters[maxParameters].position, maxParameters)
        if (method) allocateSlot(function.position)
        parameters.forEach { symbol -> locals!![symbol] = allocateSlot(symbol.declarationPosition) }
        function.body.statements.forEach(::compileStatement)
        if (analyzed.returnTypes.getValue(function) == ScriptType.UNIT) emit(Opcode.RETURN, 0, function.position, 0)
        return exitRoutine(entry, if (method) parameters.size + 1 else parameters.size)
    }

    // コンストラクタ（第3.9b段階）。NEW で作った（プロパティが未初期化の）オブジェクトをローカル変数 0（this）に受け取り、プロパティを宣言の順に
    // 初期化式の値で初期化して、そのオブジェクトを返す: LOAD_LOCAL 0; 初期化式; SET_FIELD 0; … LOAD_LOCAL 0; RETURN 1
    private fun compileConstructor(declaration: ClassDeclaration): FunctionInfo {
        val entry = instructions.size
        enterRoutine(declaration)
        allocateSlot(declaration.position)
        declaration.properties.forEachIndexed { index, property ->
            emit(Opcode.LOAD_LOCAL, 0, property.namePosition, 1)
            compileExpression(property.initializer)
            emit(Opcode.SET_FIELD, index, property.namePosition, -2)
        }
        emit(Opcode.LOAD_LOCAL, 0, declaration.position, 1)
        emit(Opcode.RETURN, 1, declaration.position, -1)
        return exitRoutine(entry, 1)
    }

    private fun enterRoutine(routine: CallableDeclaration) {
        currentFunction = routineIndices.getValue(routine)
        contextMaxDepth = 0
        locals = IdentityHashMap()
        localCount = 0
    }

    private fun exitRoutine(entry: Int, parameterCount: Int): FunctionInfo {
        locals = null
        functionMaxDepths.add(contextMaxDepth)
        return FunctionInfo(entry, localCount, parameterCount)
    }

    // 呼び出し先のスタックは、呼び出し元の計算途中の値の上に積み重なる。呼び出しの連鎖に沿った深さの合計（ある文脈で必要な深さ =
    // その文脈の最大の深さと、各呼び出しの base + 呼び出し先で必要な深さ の大きい方）が上限を超えたら、トップレベルのその呼び出しを報告する。
    // これにより、再帰に入らない呼び出しは実行時に CS_RUN_STACK_OVERFLOW にならない（トップレベル自身の深さは emit が上限を確かめる）。
    // 再帰に入りうる関数の深さは実行時の引数で決まるため、ここでは確かめず、VM が実行時に上限で止める（第3.8e段階）。
    // 再帰に入らない関数が呼ぶ関数も再帰に入らないため、neededBy がたどる関係に循環は無く、必ず終わる
    private fun checkTotalStackDepth() {
        val needed = IntArray(functionMaxDepths.size) { -1 }
        fun neededBy(function: Int): Int {
            if (needed[function] < 0) {
                needed[function] = maxOf(functionMaxDepths[function], callSites.filter { it.caller == function }.maxOfOrNull { it.base + neededBy(it.callee) } ?: 0)
            }
            return needed[function]
        }
        val tooDeep = callSites.firstOrNull {
            it.caller == -1 && !reachesRecursion[it.callee] && it.base + neededBy(it.callee) > VmLimits.MAX_STACK_DEPTH
        } ?: return
        abort(DiagnosticCode.COMP_STACK_TOO_DEEP, tooDeep.position, VmLimits.MAX_STACK_DEPTH)
    }

    // 呼び出しの深さ（その関数から始まる最も長い呼び出しの連鎖に含まれる関数の数）が上限を超える関数を拒否する。
    // 再帰に入りうる関数は深さが実行時に決まるため対象外（VM が CS_RUN_CALL_DEPTH_EXCEEDED で止める）。
    // 再帰に入らない関数が呼ぶ関数も再帰に入らないため、depthOf がたどる関係に循環は無く、必ず終わる
    private fun checkCallDepth() {
        val depths = IdentityHashMap<CallableDeclaration, Int>()
        fun depthOf(routine: CallableDeclaration): Int =
            depths[routine] ?: (1 + (analyzed.callees.getValue(routine).maxOfOrNull(::depthOf) ?: 0)).also { depths[routine] = it }
        val tooDeep = analyzed.routines.firstOrNull { !reachesRecursion[routineIndices.getValue(it)] && depthOf(it) > VmLimits.MAX_CALL_DEPTH } ?: return
        val position = when (tooDeep) {
            is FunctionDeclaration -> tooDeep.namePosition
            is ClassDeclaration -> tooDeep.namePosition
        }
        abort(DiagnosticCode.COMP_CALL_TOO_DEEP, position, VmLimits.MAX_CALL_DEPTH)
    }

    // 深さ優先でたどり、たどっている途中の関数へ戻る呼び出し（循環）を見つけたら、その経路の関数をすべて「循環に入りうる」とする。
    // 各関数は1回だけたどる（たどる深さは関数の数まで。関数の数は AST ノード数の上限 2048 で数百個に抑えられる）
    private fun recursionReachability(): BooleanArray {
        val functions = analyzed.routines
        // 0: 未訪問、1: たどっている途中、2: 循環に入らない、3: 循環に入りうる
        val state = IntArray(functions.size)
        fun visit(index: Int): Boolean {
            when (state[index]) {
                1, 3 -> return true
                2 -> return false
            }
            state[index] = 1
            var cyclic = false
            for (callee in analyzed.callees.getValue(functions[index])) {
                if (visit(routineIndices.getValue(callee))) cyclic = true
            }
            state[index] = if (cyclic) 3 else 2
            return cyclic
        }
        functions.indices.forEach(::visit)
        return BooleanArray(functions.size) { state[it] == 3 }
    }

    // 文の前後でスタックの深さは 0
    private fun compileStatement(statement: Statement) {
        when (statement) {
            is VariableDeclaration -> {
                compileExpression(statement.initializer)
                storeSlot(declareSlot(statement), statement.position)
            }
            // プロパティへの代入（第3.9b段階）は、受け手（暗黙の this はローカル変数 0）・値の順に積んで SET_FIELD
            // リストの要素への代入（第3.10a段階）は、受け手・添字・値の順に積んで LIST_SET
            is Assignment -> {
                val target = statement.target
                val property = analyzed.propertyAccesses[target]
                if (target is IndexExpression) {
                    compileExpression(target.receiver)
                    compileExpression(target.index)
                    compileExpression(statement.value)
                    emit(Opcode.LIST_SET, 0, target.position, -3)
                } else if (property == null) {
                    compileExpression(statement.value)
                    storeVariable(analyzed.references.getValue(target as NameReference), statement.position)
                } else {
                    compileReceiver(target)
                    compileExpression(statement.value)
                    emit(Opcode.SET_FIELD, property, statement.position, -2)
                }
            }
            // 式だけの文の結果（Unit を含む）は POP で捨てる（A2）
            is ExpressionStatement -> {
                compileExpression(statement.expression)
                emit(Opcode.POP, 0, statement.position, -1)
            }
            is IfStatement -> compileIf(statement)
            is WhileStatement -> compileWhile(statement)
            // 文の境界ではスタックの深さが 0 のため、ブロックを途中で抜けてもスタックに値は残らない。
            // ブロックの変数は宣言ごとに固有の番号で、外側からは参照されない（Semantic のスコープ）
            is BreakStatement -> loops.last().breaks.add(emitJump(Opcode.JUMP, statement.position, 0))
            // while は条件の先頭へ戻り、条件を評価し直す（本体の先頭ではない）。for はループ変数の更新へ移る
            is ContinueStatement -> {
                val loop = loops.last()
                val target = loop.continueTarget
                if (target != null) emit(Opcode.JUMP, target, statement.position, 0) else loop.continues.add(emitJump(Opcode.JUMP, statement.position, 0))
            }
            is ForStatement -> compileFor(statement)
            // 文の境界ではスタックの深さが 0 のため、ループの途中で戻ってもスタックに値は残らない
            is ReturnStatement -> {
                val value = statement.value
                if (value == null) {
                    emit(Opcode.RETURN, 0, statement.position, 0)
                } else {
                    compileExpression(value)
                    emit(Opcode.RETURN, 1, statement.position, -1)
                }
            }
        }
    }

    // while（11.5）: 先頭: 条件; JUMP_IF_FALSE 出口; 本体; JUMP 先頭; 出口:。既存の JUMP / JUMP_IF_FALSE だけを使う。
    // 本体の変数は宣言ごとに1つの番号で、反復ごとに宣言の初期化式で入れ直す（初期化必須: D19）。
    // 本体の break は出口へ、continue は先頭へ移る（第3.8a段階）
    private fun compileWhile(statement: WhileStatement) {
        val head = instructions.size
        val loop = Loop(head)
        compileExpression(statement.condition)
        val exit = emitJump(Opcode.JUMP_IF_FALSE, statement.position, -1)
        loops.add(loop)
        statement.body.statements.forEach(::compileStatement)
        loops.removeAt(loops.lastIndex)
        emit(Opcode.JUMP, head, statement.position, 0)
        patchJump(exit)
        loop.breaks.forEach(::patchJump)
    }

    // 整数範囲の for（第3.8b段階）。始点をループ変数に、終点を名前の無い変数（終端）に入れ、どちらもループの開始時に1回だけ評価する。
    // 既存の命令だけを使い、最後の反復の後に加算・減算しないことで Int の端でもオーバーフローしない:
    //   until:   i = 始点; 終端 = 終点; 先頭: i < 終端 でなければ出口; 本体; 更新: i = i + 1; JUMP 先頭; 出口:
    //            （i < 終端 ≦ Int.MAX_VALUE のときだけ加算する）
    //   .. / downTo: i = 始点; 終端 = 終点; i <= 終端（downTo は >=）でなければ出口; 本体: …; 更新: i == 終端 なら出口;
    //            i = i + 1（downTo は - 1）; JUMP 本体; 出口:（i が終端に達したら加算・減算の前に抜ける）
    // continue は更新へ、break は出口へ移る
    private fun compileFor(statement: ForStatement) {
        val position = statement.rangePosition
        val variable = allocateSlot(statement.variablePosition).also { (locals ?: slots)[analyzed.loopVariables.getValue(statement)] = it }
        compileExpression(statement.start)
        storeSlot(variable, statement.variablePosition)
        val end = allocateSlot(statement.variablePosition)
        compileExpression(statement.end)
        storeSlot(end, position)
        fun compare(opcode: Opcode) {
            loadSlot(variable, position)
            loadSlot(end, position)
            emit(opcode, 0, position, -1)
        }
        val exits = ArrayList<Int>()
        val firstCheck = instructions.size
        compare(
            when (statement.kind) {
                RangeKind.RANGE_TO -> Opcode.LESS_EQUAL
                RangeKind.UNTIL -> Opcode.LESS
                RangeKind.DOWN_TO -> Opcode.GREATER_EQUAL
            },
        )
        exits.add(emitJump(Opcode.JUMP_IF_FALSE, position, -1))
        val bodyStart = instructions.size
        val loop = Loop(null)
        loops.add(loop)
        statement.body.statements.forEach(::compileStatement)
        loops.removeAt(loops.lastIndex)
        loop.continues.forEach(::patchJump)
        if (statement.kind != RangeKind.UNTIL) {
            compare(Opcode.NOT_EQUAL)
            exits.add(emitJump(Opcode.JUMP_IF_FALSE, position, -1))
        }
        loadSlot(variable, position)
        emit(Opcode.PUSH_CONST, constantIndex(Constant.IntConstant(1), position), position, 1)
        emit(if (statement.kind == RangeKind.DOWN_TO) Opcode.SUBTRACT else Opcode.ADD, 0, position, -1)
        storeSlot(variable, position)
        emit(Opcode.JUMP, if (statement.kind == RangeKind.UNTIL) firstCheck else bodyStart, position, 0)
        exits.forEach(::patchJump)
        loop.breaks.forEach(::patchJump)
    }

    // if / else if / else（11.5）。各条件が false なら次の条件（最後は else か出口）へ、ブロックの後は出口へ移る:
    //   c1; JUMP_IF_FALSE N1; ブロック1; JUMP E; N1: c2; JUMP_IF_FALSE N2; ブロック2; JUMP E; N2: else ブロック; E:
    // 後に何も続かない最後のブロックの後には JUMP を出さない。ブロックの変数は宣言ごとに別の番号（ブロックを出ても再利用しない）
    private fun compileIf(statement: IfStatement) {
        val exits = ArrayList<Int>()
        for ((index, branch) in statement.branches.withIndex()) {
            compileExpression(branch.condition)
            val next = emitJump(Opcode.JUMP_IF_FALSE, branch.position, -1)
            branch.body.statements.forEach(::compileStatement)
            if (index < statement.branches.lastIndex || statement.elseBlock != null) exits.add(emitJump(Opcode.JUMP, branch.position, 0))
            patchJump(next)
        }
        statement.elseBlock?.statements?.forEach(::compileStatement)
        exits.forEach(::patchJump)
    }

    private fun compileExpression(expression: Expression) {
        // 整数リテラルと「- 整数リテラル」は Semantic が決めた定数をそのまま積む（-2147483648 を含む: D16）
        val folded = analyzed.intConstants[expression]
        if (folded != null) {
            emit(Opcode.PUSH_CONST, constantIndex(Constant.IntConstant(folded), expression.position), expression.position, 1)
            return
        }
        // Matrix の columnCount（第3.11e段階）
        if (expression is MemberAccessExpression && analyzed.linalgOperations[expression] == LinalgOperation.COLUMN_COUNT) {
            compileExpression(expression.receiver)
            emitLinalg(LinalgFunction.COLUMN_COUNT, 0, expression.position)
            return
        }
        // リストの size（第3.10a段階。Vector の size・Matrix の rowCount: 第3.11e段階）
        if (expression is MemberAccessExpression && analyzed.listOperations[expression] == ListOperation.SIZE) {
            compileExpression(expression.receiver)
            emit(Opcode.LIST_SIZE, 0, expression.position, 0)
            return
        }
        when (expression) {
            is StringLiteral -> emit(Opcode.PUSH_CONST, constantIndex(Constant.StringConstant(expression.value), expression.position), expression.position, 1)
            // 浮動小数点数（第3.11a段階）はビット列の定数（ビットが同じものだけをまとめる）
            is FloatLiteral -> emit(Opcode.PUSH_CONST, constantIndex(Constant.FloatConstant(expression.value.toRawBits()), expression.position), expression.position, 1)
            is DoubleLiteral -> emit(Opcode.PUSH_CONST, constantIndex(Constant.DoubleConstant(expression.value.toRawBits()), expression.position), expression.position, 1)
            is BooleanLiteral -> emit(Opcode.PUSH_BOOL, if (expression.value) 1 else 0, expression.position, 1)
            // プロパティ（第3.9b段階）は、受け手（暗黙の this はローカル変数 0）を積んで GET_FIELD
            is NameReference, is MemberAccessExpression -> {
                val property = analyzed.propertyAccesses[expression]
                // 数学の定数（第3.11c段階）は Double の定数（同じビットのリテラルと 1 つにまとめる）
                val constant = analyzed.mathConstants[expression]
                if (constant != null) {
                    emit(Opcode.PUSH_CONST, constantIndex(Constant.DoubleConstant(constant.toRawBits()), expression.position), expression.position, 1)
                } else if (property == null) {
                    loadVariable(analyzed.references.getValue(expression as NameReference), expression.position)
                } else {
                    compileReceiver(expression)
                    emit(Opcode.GET_FIELD, property, expression.position, 0)
                }
            }
            is ThisExpression -> emit(Opcode.LOAD_LOCAL, 0, expression.position, 1)
            is IndexExpression -> {
                compileExpression(expression.receiver)
                compileExpression(expression.index)
                emit(Opcode.LIST_GET, 0, expression.position, -1)
            }
            is UnaryExpression -> {
                compileExpression(expression.operand)
                // Vector の単項の -（第3.11e段階）
                if (analyzed.linalgOperations[expression] != null) {
                    emitLinalg(LinalgFunction.NEGATE, 0, expression.position)
                    return
                }
                // 単項 + は値を変えないため命令を出さない
                when (expression.operator) {
                    UnaryOperator.MINUS -> emit(Opcode.NEGATE, numericKind(analyzed.numericOperations.getValue(expression)), expression.position, 0)
                    UnaryOperator.NOT -> emit(Opcode.NOT, 0, expression.position, 0)
                    UnaryOperator.PLUS -> Unit
                }
            }
            // 数値の演算・大小比較（第3.11a段階）は、Semantic が決めた計算の型（numericOperations）と型の違う項を、その項の直後に CONVERT でそろえる。
            // == != は両辺が同じ型で、Float・Double は IEEE 754 の等価（operand 1・2）、それ以外は operand 0
            is BinaryExpression -> {
                // Vector の演算子（第3.11e段階）: 左・右の順に積み、LINALG で計算する
                val linalg = analyzed.linalgOperations[expression]
                if (linalg != null) {
                    compileExpression(expression.left)
                    compileExpression(expression.right)
                    emitLinalg(linalgFunction(linalg), 0, expression.position)
                    return
                }
                val operation = analyzed.numericOperations[expression]
                compileExpression(expression.left)
                if (operation != null) convertTo(analyzed.expressionTypes.getValue(expression.left), operation, expression.position)
                compileExpression(expression.right)
                if (operation != null) convertTo(analyzed.expressionTypes.getValue(expression.right), operation, expression.position)
                val kind = when {
                    operation != null -> numericKind(operation)
                    else -> when (analyzed.expressionTypes.getValue(expression.left)) {
                        ScriptType.FLOAT -> NumericKind.FLOAT
                        ScriptType.DOUBLE -> NumericKind.DOUBLE
                        else -> NumericKind.INT
                    }
                }
                emit(binaryOpcode(expression.operator), kind, expression.position, -1)
            }
            is LogicalExpression -> compileLogical(expression)
            is CallExpression -> compileCall(expression)
            is IntLiteral -> error("Semantic analysis should have rejected: $expression")
        }
    }

    // 数値の型の NumericKind（第3.11a段階）
    private fun numericKind(type: ScriptType): Int = when (type) {
        ScriptType.INT -> NumericKind.INT
        ScriptType.FLOAT -> NumericKind.FLOAT
        ScriptType.DOUBLE -> NumericKind.DOUBLE
        else -> error("not a numeric type: $type")
    }

    // スタックの一番上の数値（型 from）を型 to にする。同じ型なら命令を出さない
    private fun convertTo(from: ScriptType, to: ScriptType, position: SourcePosition) {
        if (from != to) emit(Opcode.CONVERT, NumericKind.conversion(numericKind(from), numericKind(to)), position, 0)
    }

    private fun binaryOpcode(operator: BinaryOperator) = when (operator) {
        BinaryOperator.ADD -> Opcode.ADD
        BinaryOperator.SUBTRACT -> Opcode.SUBTRACT
        BinaryOperator.MULTIPLY -> Opcode.MULTIPLY
        BinaryOperator.DIVIDE -> Opcode.DIVIDE
        BinaryOperator.REMAINDER -> Opcode.REMAINDER
        BinaryOperator.EQUAL -> Opcode.EQUAL
        BinaryOperator.NOT_EQUAL -> Opcode.NOT_EQUAL
        BinaryOperator.LESS -> Opcode.LESS
        BinaryOperator.LESS_EQUAL -> Opcode.LESS_EQUAL
        BinaryOperator.GREATER -> Opcode.GREATER
        BinaryOperator.GREATER_EQUAL -> Opcode.GREATER_EQUAL
    }

    // 短絡評価（11.5）。右辺を評価しない経路では結果を PUSH_BOOL で積み、どちらの経路も結果の Boolean 1つで合流する
    //   a && b: a; JUMP_IF_FALSE L; b; JUMP E; L: PUSH_BOOL 0; E:
    //   a || b: a; JUMP_IF_FALSE R; PUSH_BOOL 1; JUMP E; R: b; E:
    private fun compileLogical(logical: LogicalExpression) {
        compileExpression(logical.left)
        val branch = emitJump(Opcode.JUMP_IF_FALSE, logical.position, -1)
        if (logical.operator == LogicalOperator.AND) compileExpression(logical.right) else emit(Opcode.PUSH_BOOL, 1, logical.position, 1)
        val skip = emitJump(Opcode.JUMP, logical.position, 0)
        // 分岐先では、もう一方の経路が積んだ結果はまだ無い
        stackDepth--
        patchJump(branch)
        if (logical.operator == LogicalOperator.AND) emit(Opcode.PUSH_BOOL, 0, logical.position, 1) else compileExpression(logical.right)
        patchJump(skip)
    }

    // 移り先は後で patchJump が決める
    private fun emitJump(opcode: Opcode, position: SourcePosition, stackEffect: Int): Int {
        emit(opcode, 0, position, stackEffect)
        return instructions.lastIndex
    }

    // 移り先を次に出す命令の位置にする
    private fun patchJump(index: Int) {
        instructions[index] = instructions[index].copy(operand = instructions.size)
    }

    // プロパティの読み書きの受け手（名前だけの参照は暗黙の this = ローカル変数 0、式.名前 は式の値）
    private fun compileReceiver(access: Expression) {
        if (access is MemberAccessExpression) compileExpression(access.receiver) else emit(Opcode.LOAD_LOCAL, 0, access.position, 1)
    }

    // 呼び出し先はコンパイル時に HostFunction（D12）・ユーザー定義関数・メソッド（CALL。第3.8c・3.9b段階）・リストの命令（第3.10a段階）へ解決する
    private fun compileCall(call: CallExpression) {
        // Vector・Matrix の関数（第3.11e段階）: 引数を左から積み、LINALG で計算する（vector(…)・matrix(…) は個数を operand に含める）
        val linalg = analyzed.linalgOperations[call]
        if (linalg != null) {
            call.arguments.forEach(::compileExpression)
            val count = if (linalg == LinalgOperation.VECTOR_OF || linalg == LinalgOperation.MATRIX_OF) call.arguments.size else 0
            emitLinalg(linalgFunction(linalg), count, call.position)
            return
        }
        val math = analyzed.mathCalls[call]
        if (math != null) {
            compileMath(call, math)
            return
        }
        // 数値の変換（第3.11a段階）: 受け手を積み、型が違えば CONVERT（同じ型への変換は命令を出さない）
        val target = analyzed.conversions[call]
        if (target != null) {
            val receiver = (call.callee as MemberAccessExpression).receiver
            compileExpression(receiver)
            convertTo(analyzed.expressionTypes.getValue(receiver), target, call.position)
            return
        }
        // リストの生成: 要素を左から積み、NEW_LIST がまとめて取り出す
        val created = analyzed.listCreations[call]
        if (created != null) {
            call.arguments.forEach(::compileExpression)
            val shape = ListShape(slotTypeOf(created.element), created.mutable, call.arguments.size)
            val index = listShapeIndices.getOrPut(shape) {
                listShapes.add(shape)
                listShapes.lastIndex
            }
            emit(Opcode.NEW_LIST, index, call.position, 1 - call.arguments.size)
            return
        }
        // リストのメソッド: 受け手・引数の順に積む。add・clear は Unit、removeAt は取り除いた要素を積む
        val operation = analyzed.listOperations[call]
        if (operation != null) {
            compileExpression((call.callee as MemberAccessExpression).receiver)
            call.arguments.forEach(::compileExpression)
            val opcode = when (operation) {
                ListOperation.ADD -> Opcode.LIST_ADD
                ListOperation.REMOVE_AT -> Opcode.LIST_REMOVE_AT
                ListOperation.CLEAR -> Opcode.LIST_CLEAR
                ListOperation.SIZE -> error("size is not a call")
            }
            emit(opcode, 0, call.position, -call.arguments.size)
            return
        }
        // インスタンスの生成（第3.9a段階。引数は無い）。プロパティのあるクラス（第3.9b段階）は、作ったオブジェクトをコンストラクタに渡す
        val constructed = analyzed.constructions[call]
        if (constructed != null) {
            emit(Opcode.NEW, classIndices.getValue(constructed), call.position, 1)
            if (constructed.properties.isNotEmpty()) emitCall(routineIndices.getValue(constructed), 1, call.position)
            return
        }
        val function = analyzed.calls[call]
        // メソッド（第3.9b段階）は受け手（名前だけの呼び出しは this）を最初の引数として渡す
        val receiver = function != null && analyzed.owners.containsKey(function)
        if (receiver) compileReceiver(call.callee)
        call.arguments.forEach(::compileExpression)
        if (function != null) {
            emitCall(routineIndices.getValue(function), call.arguments.size + if (receiver) 1 else 0, call.position)
            return
        }
        // 乱数（第3.11e段階）: 引数を左から積み、CALL_HOST で VM の乱数の状態から計算する
        val random = analyzed.randomCalls[call]
        if (random != null) {
            val randomHost = when (random) {
                RandomOperation.RANDOM -> HostFunction.RANDOM
                RandomOperation.RANDOM_RANGE -> HostFunction.RANDOM_RANGE
                RandomOperation.RANDOM_INT -> HostFunction.RANDOM_INT
                RandomOperation.RANDOM_SEED -> HostFunction.RANDOM_SEED
            }
            emit(Opcode.CALL_HOST, randomHost.id, call.position, 1 - call.arguments.size)
            return
        }
        val name = (call.callee as NameReference).name
        val host = when {
            name == BuiltinFunctions.PRINTLN && call.arguments.isEmpty() -> HostFunction.PRINTLN_EMPTY
            name == BuiltinFunctions.PRINTLN -> HostFunction.PRINTLN
            name == BuiltinFunctions.WAIT_TICKS -> HostFunction.WAIT_TICKS
            else -> error("Unknown builtin: $name")
        }
        emit(Opcode.CALL_HOST, host.id, call.position, 1 - call.arguments.size)
    }

    // 数学関数（第3.11b段階）: 受け手（メソッドのとき）・引数を左から積み、MATH で計算する。pow は受け手・指数をそれぞれ積んだ直後に
    // Double へ変換し、結果を受け手の型に戻す（Kotlin の Float.pow・Double.pow(Int) と同じく Double の Math.pow で計算する）
    private fun compileMath(call: CallExpression, math: MathCall) {
        val callee = call.callee
        val operands = if (callee is MemberAccessExpression) listOf(callee.receiver) + call.arguments else call.arguments
        val function = mathFunction(math.operation)
        if (function == MathFunction.POW) {
            for (operand in operands) {
                compileExpression(operand)
                convertTo(analyzed.expressionTypes.getValue(operand), ScriptType.DOUBLE, call.position)
            }
            emit(Opcode.MATH, function.operand(NumericKind.DOUBLE), call.position, -1)
            convertTo(ScriptType.DOUBLE, math.type, call.position)
            return
        }
        operands.forEach(::compileExpression)
        // isClose の tolerance を省いたときは、型ごとの初期値を積む（第3.11e段階）
        if (function == MathFunction.IS_CLOSE && operands.size == function.arity - 1) {
            val tolerance = if (math.type == ScriptType.FLOAT) {
                Constant.FloatConstant(MathOperation.DEFAULT_FLOAT_TOLERANCE.toRawBits())
            } else {
                Constant.DoubleConstant(MathOperation.DEFAULT_DOUBLE_TOLERANCE.toRawBits())
            }
            emit(Opcode.PUSH_CONST, constantIndex(tolerance, call.position), call.position, 1)
        }
        emit(Opcode.MATH, function.operand(numericKind(math.type)), call.position, 1 - function.arity)
    }

    private fun emitLinalg(function: LinalgFunction, count: Int, position: SourcePosition) {
        emit(Opcode.LINALG, function.operand(count), position, 1 - function.arityOf(count))
    }

    private fun linalgFunction(operation: LinalgOperation) = when (operation) {
        LinalgOperation.VECTOR_OF -> LinalgFunction.VECTOR_OF
        LinalgOperation.VECTOR_OF_LIST -> LinalgFunction.VECTOR_OF_LIST
        LinalgOperation.ADD -> LinalgFunction.ADD
        LinalgOperation.SUBTRACT -> LinalgFunction.SUBTRACT
        LinalgOperation.NEGATE -> LinalgFunction.NEGATE
        LinalgOperation.TIMES -> LinalgFunction.TIMES
        LinalgOperation.TIMES_LEFT -> LinalgFunction.TIMES_LEFT
        LinalgOperation.DIVIDE -> LinalgFunction.DIVIDE
        LinalgOperation.DOT -> LinalgFunction.DOT
        LinalgOperation.LENGTH -> LinalgFunction.LENGTH
        LinalgOperation.NORMALIZE -> LinalgFunction.NORMALIZE
        LinalgOperation.MATRIX_OF -> LinalgFunction.MATRIX_OF
        LinalgOperation.MATRIX_ADD -> LinalgFunction.MATRIX_ADD
        LinalgOperation.MATRIX_SUBTRACT -> LinalgFunction.MATRIX_SUBTRACT
        LinalgOperation.MATRIX_TIMES -> LinalgFunction.MATRIX_TIMES
        LinalgOperation.MATRIX_TIMES_LEFT -> LinalgFunction.MATRIX_TIMES_LEFT
        LinalgOperation.TRANSPOSE -> LinalgFunction.TRANSPOSE
        LinalgOperation.MATMUL -> LinalgFunction.MATMUL
        LinalgOperation.MATMUL_VECTOR -> LinalgFunction.MATMUL_VECTOR
        LinalgOperation.COLUMN_COUNT -> LinalgFunction.COLUMN_COUNT
    }

    private fun mathFunction(operation: MathOperation) = when (operation) {
        MathOperation.ABS -> MathFunction.ABS
        MathOperation.MIN -> MathFunction.MIN
        MathOperation.MAX -> MathFunction.MAX
        MathOperation.SQRT -> MathFunction.SQRT
        MathOperation.FLOOR -> MathFunction.FLOOR
        MathOperation.CEIL -> MathFunction.CEIL
        MathOperation.ROUND -> MathFunction.ROUND
        MathOperation.POW -> MathFunction.POW
        MathOperation.ROUND_TO_INT -> MathFunction.ROUND_TO_INT
        MathOperation.COERCE_IN -> MathFunction.COERCE_IN
        MathOperation.SIN -> MathFunction.SIN
        MathOperation.COS -> MathFunction.COS
        MathOperation.TAN -> MathFunction.TAN
        MathOperation.ASIN -> MathFunction.ASIN
        MathOperation.ACOS -> MathFunction.ACOS
        MathOperation.ATAN -> MathFunction.ATAN
        MathOperation.ATAN2 -> MathFunction.ATAN2
        MathOperation.SINH -> MathFunction.SINH
        MathOperation.COSH -> MathFunction.COSH
        MathOperation.TANH -> MathFunction.TANH
        MathOperation.LN -> MathFunction.LN
        MathOperation.LOG10 -> MathFunction.LOG10
        MathOperation.EXP -> MathFunction.EXP
        MathOperation.HYPOT -> MathFunction.HYPOT
        MathOperation.SIGN -> MathFunction.SIGN
        MathOperation.IS_NAN -> MathFunction.IS_NAN
        MathOperation.IS_INFINITE -> MathFunction.IS_INFINITE
        MathOperation.IS_CLOSE -> MathFunction.IS_CLOSE
    }

    // 呼び出し先の実行中は、呼び出し元のスタックのうち引数を除いた分が残る
    private fun emitCall(callee: Int, argumentCount: Int, position: SourcePosition) {
        callSites.add(CallSite(currentFunction, callee, stackDepth - argumentCount, position))
        emit(Opcode.CALL, callee, position, 1 - argumentCount)
    }

    private fun declareSlot(declaration: VariableDeclaration): Int {
        val slot = allocateSlot(declaration.namePosition)
        (locals ?: slots)[analyzed.declarations.getValue(declaration)] = slot
        return slot
    }

    // 番号は宣言の順に 0 から振り、再利用しない。上限 64 は for の終端の番号も含めた総数（トップレベルと関数ごとに別々に数える）
    private fun allocateSlot(position: SourcePosition): Int {
        val count = if (locals == null) slotCount else localCount
        if (count >= VmLimits.MAX_VARIABLES) abort(DiagnosticCode.COMP_TOO_MANY_VARIABLES, position, VmLimits.MAX_VARIABLES)
        return if (locals == null) slotCount++ else localCount++
    }

    // いまの文脈（トップレベル / 関数）の番号の変数を読み書きする
    private fun loadSlot(slot: Int, position: SourcePosition) = emit(if (locals == null) Opcode.LOAD else Opcode.LOAD_LOCAL, slot, position, 1)

    private fun storeSlot(slot: Int, position: SourcePosition) = emit(if (locals == null) Opcode.STORE else Opcode.STORE_LOCAL, slot, position, -1)

    // 関数の中では、その関数の変数はローカル変数、それ以外（トップレベルのスコープの変数）は大域変数として読み書きする
    private fun loadVariable(symbol: VariableSymbol, position: SourcePosition) {
        val local = locals?.get(symbol)
        if (local != null) emit(Opcode.LOAD_LOCAL, local, position, 1) else emit(Opcode.LOAD, slots.getValue(symbol), position, 1)
    }

    private fun storeVariable(symbol: VariableSymbol, position: SourcePosition) {
        val local = locals?.get(symbol)
        if (local != null) emit(Opcode.STORE_LOCAL, local, position, -1) else emit(Opcode.STORE, slots.getValue(symbol), position, -1)
    }

    private fun constantIndex(constant: Constant, position: SourcePosition): Int = constantIndices.getOrPut(constant) {
        if (constants.size >= VmLimits.MAX_CONSTANTS) abort(DiagnosticCode.COMP_TOO_MANY_CONSTANTS, position, VmLimits.MAX_CONSTANTS)
        constants.add(constant)
        constants.lastIndex
    }

    // stackEffect は命令の実行によるスタックの増減。最大の深さを記録し、上限を超えたらエラーにする
    private fun emit(opcode: Opcode, operand: Int, position: SourcePosition, stackEffect: Int) {
        if (instructions.size >= VmLimits.MAX_INSTRUCTIONS) abort(DiagnosticCode.COMP_TOO_MANY_INSTRUCTIONS, position, VmLimits.MAX_INSTRUCTIONS)
        stackDepth += stackEffect
        if (stackDepth > VmLimits.MAX_STACK_DEPTH) abort(DiagnosticCode.COMP_STACK_TOO_DEEP, position, VmLimits.MAX_STACK_DEPTH)
        maxStackDepth = maxOf(maxStackDepth, stackDepth)
        contextMaxDepth = maxOf(contextMaxDepth, stackDepth)
        instructions.add(Instruction(opcode, operand, position))
    }

    private fun abort(code: DiagnosticCode, position: SourcePosition, limit: Int): Nothing {
        diagnostics.report(Diagnostic(code, DiagnosticSeverity.ERROR, DiagnosticPhase.COMPILER, position, listOf(DiagnosticArgument.IntArgument(limit))))
        throw CompileAbort()
    }
}
