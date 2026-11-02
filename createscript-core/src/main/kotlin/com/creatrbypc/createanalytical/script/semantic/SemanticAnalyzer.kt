package com.creatrbypc.createanalytical.script.semantic

import com.creatrbypc.createanalytical.script.ast.Assignment
import com.creatrbypc.createanalytical.script.ast.BinaryExpression
import com.creatrbypc.createanalytical.script.ast.BinaryOperator
import com.creatrbypc.createanalytical.script.ast.Block
import com.creatrbypc.createanalytical.script.ast.BreakStatement
import com.creatrbypc.createanalytical.script.ast.BooleanLiteral
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
import com.creatrbypc.createanalytical.script.ast.Program
import com.creatrbypc.createanalytical.script.ast.ReturnStatement
import com.creatrbypc.createanalytical.script.ast.Statement
import com.creatrbypc.createanalytical.script.ast.StringLiteral
import com.creatrbypc.createanalytical.script.ast.ThisExpression
import com.creatrbypc.createanalytical.script.ast.TypeReference
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
import com.creatrbypc.createanalytical.script.parser.ParseResult
import java.util.Collections
import java.util.IdentityHashMap

// CreateScript の値の型。Unit は呼び出しの結果としてのみ現れ、値として使えない（A22）。代入・引数・戻り値での型どうしの暗黙の変換は無い
// （数値の演算・大小比較の項だけは numericOperation の規則で広い方の型にそろえる: 第3.11a段階）
sealed class ScriptType {
    abstract val displayName: String

    data object INT : ScriptType() {
        override val displayName = "Int"
    }

    data object STRING : ScriptType() {
        override val displayName = "String"
    }

    data object UNIT : ScriptType() {
        override val displayName = "Unit"
    }

    data object BOOLEAN : ScriptType() {
        override val displayName = "Boolean"
    }

    // IEEE 754 の 32 ビット・64 ビットの浮動小数点数（第3.11a段階）
    data object FLOAT : ScriptType() {
        override val displayName = "Float"
    }

    data object DOUBLE : ScriptType() {
        override val displayName = "Double"
    }

    // Vector（第3.11e段階）。1〜16 次元の Double のベクトルで、値は変更できない（演算は新しい Vector を作る）。List<Double> とは別の型で、
    // 互いに代入できず、== で比べられない。型名は予約しない（同じ名前のクラスがあればそちらを指す）
    data object VECTOR : ScriptType() {
        override val displayName = VECTOR_NAME
    }

    // Matrix（第3.11e段階）。1〜8 行 × 1〜8 列の Double の行列で、値は変更できない。m[i] は行の Vector。型名は予約しない
    data object MATRIX : ScriptType() {
        override val displayName = MATRIX_NAME
    }

    // クラス型（第3.9a段階）。同じクラス宣言どうしだけが同じ型（名前が同じでも別の宣言なら別の型）
    class ClassType(val declaration: ClassDeclaration) : ScriptType() {
        override val displayName get() = declaration.name

        override fun equals(other: Any?) = other is ClassType && other.declaration === declaration

        override fun hashCode() = System.identityHashCode(declaration)

        override fun toString() = "ClassType($displayName)"
    }

    // リスト（第3.10a段階）。List<T> は読み取り専用（変更できない参照）、MutableList<T> は変更できる。同じリストを両方の型の参照が指すことがある。
    // 要素もリストにできる（第3.10b段階。可変性は段ごと）。深い入れ子でも再帰しないよう、名前は必要になったときに外側から順に組み立て、
    // 等価は外側から順にたどって判定し、ハッシュは作るときに 1 回だけ（内側の値から）計算する
    class ListType(val element: ScriptType, val mutable: Boolean) : ScriptType() {
        private val hash = (element.hashCode() * 31 + if (mutable) 1 else 0) * 31 + 7

        override fun hashCode() = hash

        override fun equals(other: Any?): Boolean {
            var left: ListType = this
            var right = other
            while (true) {
                if (left === right) return true
                if (right !is ListType || right.hash != left.hash || right.mutable != left.mutable) return false
                val inner = left.element
                if (inner !is ListType) return inner == right.element
                left = inner
                right = right.element
            }
        }

        override val displayName: String by lazy {
            val text = StringBuilder()
            var depth = 0
            var current: ScriptType = this
            while (current is ListType) {
                text.append(if (current.mutable) MUTABLE_LIST else LIST).append('<')
                depth++
                current = current.element
            }
            text.append(current.displayName)
            repeat(depth) { text.append('>') }
            text.toString()
        }

        override fun toString() = displayName
    }

    companion object {
        const val LIST = "List"
        const val MUTABLE_LIST = "MutableList"
        const val VECTOR_NAME = "Vector"
        const val MATRIX_NAME = "Matrix"

        // Vector・Matrix（第3.11e段階）。比較できず、読み出し専用の添字・メンバーだけを持つ
        fun isLinearAlgebra(type: ScriptType) = type == VECTOR || type == MATRIX

        // 組み込みの型の名前（クラス名には使えない）。入れ子の object を参照すると、クラスの初期化が循環するため文字列で持つ
        val BUILTIN_NAMES = setOf("Int", "String", "Unit", "Boolean", "Float", "Double", LIST, MUTABLE_LIST)

        fun isNumeric(type: ScriptType) = type == INT || type == FLOAT || type == DOUBLE

        // 数値の演算（+ - * / %）・大小比較の項をそろえる型（第3.11a段階。Kotlin と同じ）。同じ型ならその型、Double を含めば Double、
        // それ以外（Int と Float）は Float。数値でない型を含めば null。Int を Float にそろえると 2^24 を超える値は丸められる（Kotlin と同じ）
        fun numericOperation(left: ScriptType, right: ScriptType): ScriptType? = when {
            !isNumeric(left) || !isNumeric(right) -> null
            left == right -> left
            left == DOUBLE || right == DOUBLE -> DOUBLE
            else -> FLOAT
        }
    }
}

// 数値の変換（第3.11a段階）。x.toInt()・x.toFloat()・x.toDouble() は Int・Float・Double のメソッドで、結果は変換先の型
object NumericConversions {
    val TARGETS = mapOf("toInt" to ScriptType.INT, "toFloat" to ScriptType.FLOAT, "toDouble" to ScriptType.DOUBLE)
}

// 数学関数（第3.11b段階。Kotlin の kotlin.math と同じ結果）。名前だけで呼ぶもの（abs(x) など）と、数値のメソッド（x.pow(y) など）。
// 名前は予約しない（変数・関数・メソッド・クラスに同じ名前を使え、ユーザー定義の関数・メソッド・クラスがあればそちらを呼ぶ）。
// floating は Float・Double だけに使えるか（それ以外は Int も使える）。引数の数値の型は暗黙に変換しない
enum class MathOperation(val arity: Int, val floating: Boolean) {
    ABS(1, false),
    MIN(2, false),
    MAX(2, false),
    SQRT(1, true),
    FLOOR(1, true),
    CEIL(1, true),
    ROUND(1, true),

    // x.pow(y): 受け手が Float・Double で、y は受け手と同じ型か Int
    POW(2, true),
    ROUND_TO_INT(1, true),
    COERCE_IN(3, false),

    // 三角関数・双曲線関数（第3.11c段階。角度はラジアン）。atan2(y, x) は同じ型の 2 つ
    SIN(1, true),
    COS(1, true),
    TAN(1, true),
    ASIN(1, true),
    ACOS(1, true),
    ATAN(1, true),
    ATAN2(2, true),
    SINH(1, true),
    COSH(1, true),
    TANH(1, true),

    // 対数・指数（第3.11d段階）。ln は自然対数、hypot(x, y) は同じ型の 2 つ、sign は Math.signum（-0.0・NaN はそのまま）
    LN(1, true),
    LOG10(1, true),
    EXP(1, true),
    HYPOT(2, true),
    SIGN(1, true),

    // x.isNaN()・x.isInfinite()（第3.11d段階）: 受け手が Float・Double で、結果は Boolean
    IS_NAN(1, true),
    IS_INFINITE(1, true),

    // isClose(a, b)・isClose(a, b, tolerance)（第3.11e段階）: 同じ型の 2 つか 3 つで、結果は Boolean。tolerance を省くと型ごとの初期値
    IS_CLOSE(3, true),
    ;

    companion object {
        val FUNCTIONS = mapOf(
            "abs" to ABS, "min" to MIN, "max" to MAX, "sqrt" to SQRT, "floor" to FLOOR, "ceil" to CEIL, "round" to ROUND,
            "sin" to SIN, "cos" to COS, "tan" to TAN, "asin" to ASIN, "acos" to ACOS, "atan" to ATAN, "atan2" to ATAN2,
            "sinh" to SINH, "cosh" to COSH, "tanh" to TANH, "ln" to LN, "log10" to LOG10, "exp" to EXP, "hypot" to HYPOT, "sign" to SIGN,
            "isClose" to IS_CLOSE,
        )

        // isClose の tolerance を省いたときの値（第3.11e段階）
        const val DEFAULT_DOUBLE_TOLERANCE = 1e-9
        const val DEFAULT_FLOAT_TOLERANCE = 1e-5f
        val METHODS = mapOf(
            "pow" to POW, "roundToInt" to ROUND_TO_INT, "coerceIn" to COERCE_IN, "isNaN" to IS_NAN, "isInfinite" to IS_INFINITE,
        )
    }
}

// 乱数（第3.11e段階）。random()・random(min, max)・randomInt(min, max)・randomSeed(seed) で、名前だけで呼ぶ。範囲は max を含まない。
// 名前は予約しない（同じ名前のユーザー定義の関数・メソッド・クラスがあればそちらを呼ぶ）。引数の数値の型は暗黙に変換しない
enum class RandomOperation {
    RANDOM,
    RANDOM_RANGE,
    RANDOM_INT,
    RANDOM_SEED,
    ;

    companion object {
        const val RANDOM_NAME = "random"
        const val RANDOM_INT_NAME = "randomInt"
        const val RANDOM_SEED_NAME = "randomSeed"
        val NAMES = setOf(RANDOM_NAME, RANDOM_INT_NAME, RANDOM_SEED_NAME)
    }
}

// Vector・Matrix の演算（第3.11e段階）。名前だけで呼ぶ関数（vector・dot・length・normalize・matrix・transpose・matmul。名前は予約しない）と、
// 演算子（Vector は + - * / と単項の -、Matrix は + - *）、m.columnCount。スカラーは Double だけ（Int・Float は暗黙に変換しない）
enum class LinalgOperation {
    VECTOR_OF,
    VECTOR_OF_LIST,
    ADD,
    SUBTRACT,
    NEGATE,
    TIMES,
    TIMES_LEFT,
    DIVIDE,
    DOT,
    LENGTH,
    NORMALIZE,
    MATRIX_OF,
    MATRIX_ADD,
    MATRIX_SUBTRACT,
    MATRIX_TIMES,
    MATRIX_TIMES_LEFT,
    TRANSPOSE,
    MATMUL,
    MATMUL_VECTOR,
    COLUMN_COUNT,
    ;

    companion object {
        const val VECTOR = "vector"
        const val DOT_NAME = "dot"
        const val LENGTH_NAME = "length"
        const val NORMALIZE_NAME = "normalize"
        const val MATRIX = "matrix"
        const val TRANSPOSE_NAME = "transpose"
        const val MATMUL_NAME = "matmul"
        val FUNCTIONS = setOf(VECTOR, DOT_NAME, LENGTH_NAME, NORMALIZE_NAME, MATRIX, TRANSPOSE_NAME, MATMUL_NAME)

        // m.rowCount・m.columnCount
        const val ROW_COUNT = "rowCount"
        const val COLUMN_COUNT_NAME = "columnCount"

        // vector(x1, …, xn) の要素の数・matrix(row1, …, rowr) の行の数の上限（VM の VmLimits.MAX_VECTOR_SIZE・MAX_MATRIX_SIZE と同じ値）
        const val MAX_VECTOR_SIZE = 16
        const val MAX_MATRIX_SIZE = 8
    }
}

// 数学の定数（第3.11c段階。Kotlin の kotlin.math.PI・E と同じ Double）。名前は予約しない（同じ名前の変数・引数・プロパティがあればそちらを参照する）。
// 代入・呼び出しはできない
object MathConstants {
    val VALUES = mapOf("PI" to kotlin.math.PI, "E" to kotlin.math.E)
}

// 数学関数の呼び出し（第3.11b段階）。type は計算する数値の型（最初の引数・受け手の型）
data class MathCall(val operation: MathOperation, val type: ScriptType)

// リストのメンバーの操作（第3.10a段階。要素の読み書きは IndexExpression）
enum class ListOperation { SIZE, ADD, REMOVE_AT, CLEAR }

// Stage 3.5 の組み込み関数。変数名として再宣言できない（D20）
object BuiltinFunctions {
    const val PRINTLN = "println"
    const val WAIT_TICKS = "waitTicks"

    // リストの生成（第3.10a段階）
    const val LIST_OF = "listOf"
    const val MUTABLE_LIST_OF = "mutableListOf"
    val NAMES = setOf(PRINTLN, WAIT_TICKS, LIST_OF, MUTABLE_LIST_OF)
}

// type が null の変数は、宣言にエラーがあり型を決められなかったもの（後続の誤診断を防ぐため、型検査の対象外にする）
class VariableSymbol(val name: String, val type: ScriptType?, val isMutable: Boolean, val declarationPosition: SourcePosition)

// 意味解析の結果。AST ノードは同じ内容でも別ノードとして扱うため、すべて同一性（identity）で引く
class AnalyzedProgram(
    val program: Program,
    // 型が決まった式の型
    val expressionTypes: Map<Expression, ScriptType>,
    // 整数定数の値。-2147483648 のように「符号 + 整数リテラル」を1つの定数にまとめたものは UnaryExpression に記録する
    val intConstants: Map<Expression, Int>,
    val references: Map<NameReference, VariableSymbol>,
    val declarations: Map<VariableDeclaration, VariableSymbol>,
    // for のループ変数（第3.8b段階）。宣言にエラーがあった for は含まない
    val loopVariables: Map<ForStatement, VariableSymbol>,
    // ユーザー定義関数・メソッド（第3.9b段階）の呼び出しと、呼び出す関数（第3.8c段階）
    val calls: Map<CallExpression, FunctionDeclaration>,
    // 呼び出しフレームを作る手続き（第3.9b段階）: 関数（定義の順）と、クラスごと（宣言の順）のコンストラクタ（プロパティのあるクラスだけ）・
    // メソッド（定義の順）。この順の番号が CALL の operand になる
    val routines: List<CallableDeclaration>,
    // 手続きごとの、本体・初期化式から呼ぶ手続き（呼び出しの順。重複を含む）。再帰（第3.8e段階）があれば循環する
    val callees: Map<CallableDeclaration, List<CallableDeclaration>>,
    // 関数・メソッドごとの引数（宣言の順。ローカル変数の先頭に入る。メソッドは this の後）と戻り値の型（第3.8d段階）
    val parameters: Map<FunctionDeclaration, List<VariableSymbol>>,
    val returnTypes: Map<FunctionDeclaration, ScriptType>,
    // インスタンスの生成（クラス名の呼び出し）と、生成するクラス（第3.9a段階）
    val constructions: Map<CallExpression, ClassDeclaration>,
    // プロパティの読み書き（第3.9b段階。暗黙の this の NameReference と MemberAccessExpression）と、プロパティの番号（宣言の順）
    val propertyAccesses: Map<Expression, Int>,
    // メソッドと、それを定義したクラス（第3.9b段階）
    val owners: Map<FunctionDeclaration, ClassDeclaration>,
    // プロパティの型（第3.9b段階。型の注釈、または初期化式から推論した型）
    val propertyTypes: Map<VariableDeclaration, ScriptType>,
    // リストの生成（listOf / mutableListOf の呼び出し）と作るリストの型、リストのメンバーの操作（size の読み取り・add などの呼び出し: 第3.10a段階）
    val listCreations: Map<CallExpression, ScriptType.ListType>,
    val listOperations: Map<Expression, ListOperation>,
    // 数値の演算（単項の - +・+ - * / %）・大小比較と、その計算を行う型（第3.11a段階。項の型がこれと違えば Compiler が変換を挟む）
    val numericOperations: Map<Expression, ScriptType>,
    // 数値の変換（x.toDouble() など）の呼び出しと、変換先の型（第3.11a段階。変換元は受け手の式の型）
    val conversions: Map<CallExpression, ScriptType>,
    // 数学関数の呼び出し（第3.11b段階）
    val mathCalls: Map<CallExpression, MathCall>,
    // 数学の定数の参照と、その値（第3.11c段階）
    val mathConstants: Map<NameReference, Double>,
    // 乱数の呼び出し（第3.11e段階）
    val randomCalls: Map<CallExpression, RandomOperation>,
    // Vector の演算（第3.11e段階。関数の呼び出し・演算子の式）
    val linalgOperations: Map<Expression, LinalgOperation>,
)

// program はエラーが無いときだけ返す（警告だけなら成功）。診断は Lexer・Parser の分を含めて最大16件
data class SemanticResult(
    val program: AnalyzedProgram?,
    val diagnostics: List<Diagnostic>,
    val diagnosticsTruncated: Boolean,
    val hasErrors: Boolean,
)

// CreateScript の意味解析器。Minecraft に依存しない純粋な Kotlin で、1回の analyze ごとに新しいインスタンスを使う
class SemanticAnalyzer private constructor(private val diagnostics: DiagnosticCollector) {
    // スコープの並び（先頭がプログラム全体、末尾がいま解析しているブロック: 第3.7c段階）。名前は内側から外側へ探す。
    // 同じスコープ内の重複宣言はエラー、内側での同名の宣言（シャドーイング）は可（D4）
    private val scopes = ArrayList<HashMap<String, VariableSymbol>>().also { it.add(HashMap()) }

    // いま解析している文を囲む while / for の数（break / continue はループの中でだけ使える）
    private var loopDepth = 0
    private val expressionTypes = IdentityHashMap<Expression, ScriptType>()
    private val intConstants = IdentityHashMap<Expression, Int>()
    private val references = IdentityHashMap<NameReference, VariableSymbol>()
    private val declarations = IdentityHashMap<VariableDeclaration, VariableSymbol>()
    private val loopVariables = IdentityHashMap<ForStatement, VariableSymbol>()

    // ユーザー定義関数（第3.8c段階）。名前 → 宣言（組み込み関数名・重複した定義は含まない）
    private val functions = HashMap<String, FunctionDeclaration>()

    // いま本体を解析している関数・メソッド（トップレベルの文・初期化式では null）
    private var currentFunction: FunctionDeclaration? = null

    // いま解析している手続き（関数・メソッドの本体、コンストラクタ〈クラス〉の初期化式。トップレベルの文では null: 第3.9b段階）
    private var currentRoutine: CallableDeclaration? = null

    // 関数ごとの引数の型と戻り値の型（第3.8d段階）。型の名前にエラーがあったものは null（呼び出し側で誤診断を重ねない）
    private class Signature(val parameterTypes: List<ScriptType?>, val returnType: ScriptType?)

    private val signatures = IdentityHashMap<FunctionDeclaration, Signature>()

    // クラス（第3.9a段階）。名前 → 宣言（組み込みの名前・重複した宣言は含まない）
    private val classes = HashMap<String, ClassDeclaration>()
    private val constructions = IdentityHashMap<CallExpression, ClassDeclaration>()
    private val parameters = IdentityHashMap<FunctionDeclaration, List<VariableSymbol>>()
    private val calls = IdentityHashMap<CallExpression, FunctionDeclaration>()

    // 手続きごとの、本体・初期化式から呼ぶ手続き（呼び出しの順）と、本体で直接使う（読む・代入する）トップレベルの変数
    private val callsIn = IdentityHashMap<CallableDeclaration, MutableList<CallableDeclaration>>()
    private val globalUses = IdentityHashMap<CallableDeclaration, MutableSet<VariableSymbol>>()

    // トップレベルのスコープの変数（大域変数）と、それを宣言したトップレベルの文の番号
    private val globalDeclarationIndices = IdentityHashMap<VariableSymbol, Int>()

    // トップレベルの文の中の、手続きの呼び出し（name は報告に使う関数名・メソッド名・クラス名）と、その文の番号
    private class TopLevelCall(val routine: CallableDeclaration, val name: String, val position: SourcePosition, val index: Int)

    private val topLevelCalls = ArrayList<TopLevelCall>()
    private var topLevelIndex = 0

    // ---- クラスのメンバー（第3.9b段階） ----

    // プロパティの初期化式の解析の状態。型の注釈の無いプロパティは、初期化式を解析し終えると型が決まる
    private enum class InitializerState { PENDING, ANALYZING, DONE }

    // プロパティ。index は宣言の順の番号（オブジェクトのプロパティの位置）。type は型の注釈、または初期化式から推論した型（決まる前・エラーは null）
    private class Property(val owner: ClassDeclaration, val index: Int, val declaration: VariableDeclaration) {
        var type: ScriptType? = null
        var state = InitializerState.PENDING
    }

    // クラスごとのプロパティ（宣言の順。名前の重複したものを含む）と、名前 → プロパティ・メソッド（重複・衝突した名前は含まない）
    private class Members(val properties: List<Property>) {
        val propertyNames = HashMap<String, Property>()
        val methods = HashMap<String, FunctionDeclaration>()
    }

    private val members = IdentityHashMap<ClassDeclaration, Members>()
    private val owners = IdentityHashMap<FunctionDeclaration, ClassDeclaration>()
    private val propertyAccesses = IdentityHashMap<Expression, Int>()
    private val listCreations = IdentityHashMap<CallExpression, ScriptType.ListType>()
    private val listOperations = IdentityHashMap<Expression, ListOperation>()
    private val numericOperations = IdentityHashMap<Expression, ScriptType>()
    private val conversions = IdentityHashMap<CallExpression, ScriptType>()
    private val mathCalls = IdentityHashMap<CallExpression, MathCall>()
    private val mathConstants = IdentityHashMap<NameReference, Double>()
    private val randomCalls = IdentityHashMap<CallExpression, RandomOperation>()
    private val linalgOperations = IdentityHashMap<Expression, LinalgOperation>()

    // いま解析しているメソッドの本体・初期化式のクラス（クラスの外では null）と、解析している初期化式のプロパティの番号（初期化式の外では null）
    private var currentClass: ClassDeclaration? = null
    private var initializing: Int? = null

    // && || の右辺（評価されないことがある）の中の深さ
    private var conditionalDepth = 0

    // クラスごとの、初期化式で必ず（&& || の右辺以外で）生成するクラス
    private val constructionEdges = IdentityHashMap<ClassDeclaration, MutableList<ClassDeclaration>>()

    // トップレベルのスコープで宣言する変数の名前。初期化式からの参照を未宣言の変数と区別するため、解析の前に集める
    private val topLevelNames = HashSet<String>()

    // 初期化式を別の初期化式の途中で解析するときに、解析中の文脈を退避する
    private class Context(val owner: ClassDeclaration?, val initializing: Int?, val routine: CallableDeclaration?, val function: FunctionDeclaration?, val conditionalDepth: Int)

    companion object {
        private const val MAX_NEGATED_LITERAL = 2_147_483_648L

        // リストのメンバー（第3.10a段階）
        private const val SIZE = "size"
        private val LIST_METHODS = mapOf("add" to ListOperation.ADD, "removeAt" to ListOperation.REMOVE_AT, "clear" to ListOperation.CLEAR)

        fun analyze(parseResult: ParseResult): SemanticResult {
            val diagnostics = DiagnosticCollector()
            diagnostics.merge(parseResult.diagnostics, parseResult.diagnosticsTruncated, parseResult.hasErrors)
            // 構文エラーがあれば AST が無いため、意味解析しない（A8）
            val analyzed = parseResult.program?.let { SemanticAnalyzer(diagnostics).run(it) }
            return SemanticResult(
                analyzed.takeUnless { diagnostics.hasErrors },
                diagnostics.diagnostics,
                diagnostics.isTruncated,
                diagnostics.hasErrors,
            )
        }
    }

    // 関数（第3.8c段階）: 先に定義の名前を集めるため、定義の順に関係なく呼び出せる。トップレベルの文を解析してから関数の本体を解析するため、
    // 本体からはトップレベルのスコープのすべての変数（宣言の位置に関係なく）が見える。初期化前に使わないことは checkCalls が確かめる
    // クラスのメンバー（第3.9b段階）: プロパティの型の注釈とメソッドの引数・戻り値の型を先に決め、初期化式をトップレベルの文より先に解析して
    // 型の注釈の無いプロパティの型を決める（トップレベルの文・関数の本体からプロパティの型を使えるようにするため）。メソッドの本体は関数の本体の後
    private fun run(program: Program): AnalyzedProgram {
        program.statements.forEach { if (it is VariableDeclaration) topLevelNames.add(it.name) }
        program.classes.forEach(::declareClass)
        program.functions.forEach(::declareFunction)
        program.classes.forEach(::declareMembers)
        program.classes.forEach { declaration -> members.getValue(declaration).properties.forEach(::analyzeInitializer) }
        program.statements.forEachIndexed { index, statement ->
            topLevelIndex = index
            analyzeStatement(statement)
        }
        program.functions.forEach(::analyzeFunction)
        for (declaration in program.classes) {
            currentClass = declaration
            declaration.methods.forEach(::analyzeFunction)
            currentClass = null
        }
        checkCalls(program)
        checkConstructionCycles(program)
        val callees = IdentityHashMap<CallableDeclaration, List<CallableDeclaration>>()
        routinesOf(program).forEach { routine -> callees[routine] = callsIn[routine].orEmpty() }
        val returnTypes = IdentityHashMap<FunctionDeclaration, ScriptType>()
        signatures.forEach { (function, signature) -> returnTypes[function] = signature.returnType ?: ScriptType.UNIT }
        val propertyTypes = IdentityHashMap<VariableDeclaration, ScriptType>()
        members.values.forEach { classMembers -> classMembers.properties.forEach { property -> property.type?.let { propertyTypes[property.declaration] = it } } }
        return AnalyzedProgram(
            program,
            Collections.unmodifiableMap(expressionTypes),
            Collections.unmodifiableMap(intConstants),
            Collections.unmodifiableMap(references),
            Collections.unmodifiableMap(declarations),
            Collections.unmodifiableMap(loopVariables),
            Collections.unmodifiableMap(calls),
            routinesOf(program),
            Collections.unmodifiableMap(callees),
            Collections.unmodifiableMap(parameters),
            Collections.unmodifiableMap(returnTypes),
            Collections.unmodifiableMap(constructions),
            Collections.unmodifiableMap(propertyAccesses),
            Collections.unmodifiableMap(owners),
            Collections.unmodifiableMap(propertyTypes),
            Collections.unmodifiableMap(listCreations),
            Collections.unmodifiableMap(listOperations),
            Collections.unmodifiableMap(numericOperations),
            Collections.unmodifiableMap(conversions),
            Collections.unmodifiableMap(mathCalls),
            Collections.unmodifiableMap(mathConstants),
            Collections.unmodifiableMap(randomCalls),
            Collections.unmodifiableMap(linalgOperations),
        )
    }

    // 呼び出しフレームを作る手続き: 関数（定義の順）と、クラスごと（宣言の順）のコンストラクタ（プロパティのあるクラスだけ）・メソッド（定義の順）
    private fun routinesOf(program: Program): List<CallableDeclaration> =
        program.functions + program.classes.flatMap { declaration -> (if (declaration.properties.isEmpty()) emptyList() else listOf(declaration)) + declaration.methods }

    // ---- 関数（第3.8c段階） ----

    // クラス（第3.9a段階）は関数より先に集めるため、宣言の順に関係なく型・生成に使える。組み込みの関数名・型名と同じ名前、
    // 2つ目以降の同名の宣言は登録しない
    private fun declareClass(declaration: ClassDeclaration) {
        when (declaration.name) {
            in BuiltinFunctions.NAMES -> reportError(DiagnosticCode.SEM_BUILTIN_REDECLARATION, declaration.namePosition, declaration.name)
            in ScriptType.BUILTIN_NAMES -> reportError(DiagnosticCode.SEM_CLASS_NAME_CONFLICT, declaration.namePosition, declaration.name)
            in classes -> reportError(DiagnosticCode.SEM_DUPLICATE_CLASS, declaration.namePosition, declaration.name)
            else -> classes[declaration.name] = declaration
        }
    }

    // 組み込み関数と同じ名前・クラスと同じ名前・2つ目以降の同名の定義は登録しない（本体は解析する）。引数・戻り値の型は呼び出しより先に決める。
    // 引数の型は Int・Boolean・String（Unit は値として使えない）、戻り値の型はそれに Unit を加えたもの。省略した戻り値の型は Unit
    private fun declareFunction(function: FunctionDeclaration) {
        declareSignature(function)
        when (function.name) {
            in BuiltinFunctions.NAMES -> reportError(DiagnosticCode.SEM_BUILTIN_REDECLARATION, function.namePosition, function.name)
            in classes -> reportError(DiagnosticCode.SEM_CLASS_NAME_CONFLICT, function.namePosition, function.name)
            in functions -> reportError(DiagnosticCode.SEM_DUPLICATE_FUNCTION, function.namePosition, function.name)
            else -> functions[function.name] = function
        }
    }

    // 引数の型は Int・Boolean・String・クラス（Unit は値として使えない）、戻り値の型はそれに Unit を加えたもの。省略した戻り値の型は Unit
    private fun declareSignature(function: FunctionDeclaration) {
        val parameterTypes = function.parameters.map { resolveType(it.type) }
        val returnType = function.returnType.let { if (it == null || it.name == ScriptType.UNIT.displayName) ScriptType.UNIT else resolveType(it) }
        signatures[function] = Signature(parameterTypes, returnType)
    }

    // メンバー（第3.9b段階）: プロパティの型の注釈とメソッドの引数・戻り値の型を、本体・初期化式の解析より先に決める。メンバーの名前には
    // 組み込み関数・クラスと同じ名前、プロパティにはトップレベルの関数と同じ名前（変数と同じ規則）も使えない。同じクラスの中で同じ名前の
    // プロパティ・メソッドは、ソースの順で 2 つ目以降を登録しない（本体・初期化式は解析する）
    private fun declareMembers(declaration: ClassDeclaration) {
        val classMembers = Members(declaration.properties.mapIndexed { index, property -> Property(declaration, index, property) })
        members[declaration] = classMembers
        val properties = classMembers.properties.associateBy { it.declaration }
        val ordered = (declaration.properties.map { it.position to it } + declaration.methods.map { it.position to it }).sortedBy { it.first.offset }
        for ((_, member) in ordered) {
            val property = (member as? VariableDeclaration)?.let(properties::getValue)
            val method = member as? FunctionDeclaration
            val name = property?.declaration?.name ?: method!!.name
            val position = property?.declaration?.namePosition ?: method!!.namePosition
            if (property != null) property.declaration.type?.let { property.type = resolveType(it) }
            if (method != null) {
                declareSignature(method)
                owners[method] = declaration
            }
            when {
                name in BuiltinFunctions.NAMES -> reportError(DiagnosticCode.SEM_BUILTIN_REDECLARATION, position, name)
                name in classes -> reportError(DiagnosticCode.SEM_CLASS_NAME_CONFLICT, position, name)
                property != null && name in functions -> reportError(DiagnosticCode.SEM_FUNCTION_NAME_CONFLICT, position, name)
                name in classMembers.propertyNames || name in classMembers.methods -> reportError(DiagnosticCode.SEM_DUPLICATE_MEMBER, position, declaration.name, name)
                property != null -> classMembers.propertyNames[name] = property
                else -> classMembers.methods[name] = method!!
            }
        }
    }

    // プロパティの初期化式（第3.9b段階）。インスタンスの生成時にコンストラクタの中で宣言の順に評価する。名前で読めるのは同じクラスの先に宣言した
    // プロパティ（名前だけ・this.名前）で、this（初期化中のインスタンス）・関数・メソッド・生成・他のインスタンスのプロパティも使える。
    // トップレベルの変数は直接は読めない（生成がその変数の宣言より前に起こりうるため。関数を通して読む場合は checkCalls が確かめる）。
    // 型の注釈の無いプロパティの型は初期化式の型。解析の途中で他のプロパティの型が必要になったら、その初期化式を先に解析する（propertyType）。
    // トップレベルの文より前（スコープは空のトップレベルだけ）に解析するため、ブロックのスコープは無い
    private fun analyzeInitializer(property: Property) {
        if (property.state != InitializerState.PENDING) return
        property.state = InitializerState.ANALYZING
        val saved = Context(currentClass, initializing, currentRoutine, currentFunction, conditionalDepth)
        currentClass = property.owner
        initializing = property.index
        currentRoutine = property.owner
        currentFunction = null
        conditionalDepth = 0
        val type = analyzeValue(property.declaration.initializer)
        currentClass = saved.owner
        initializing = saved.initializing
        currentRoutine = saved.routine
        currentFunction = saved.function
        conditionalDepth = saved.conditionalDepth
        val annotated = property.type
        if (property.declaration.type == null) {
            property.type = type
        } else if (annotated != null && type != null && !assignable(annotated, type)) {
            reportTypeMismatch(property.declaration.initializer.position, annotated, type)
        }
        property.state = InitializerState.DONE
    }

    // プロパティの型。型の注釈の無いプロパティは、初期化式をまだ解析していなければ先に解析する。その初期化式の解析中に（他のインスタンスを通して）
    // 自分の型が必要になった場合は、型が決まらない（位置は依存が循環した参照）
    private fun propertyType(property: Property, position: SourcePosition): ScriptType? {
        if (property.declaration.type != null) return property.type
        if (property.state == InitializerState.ANALYZING) {
            reportError(DiagnosticCode.SEM_RECURSIVE_PROPERTY_TYPE, position, property.owner.name, property.declaration.name)
            return null
        }
        analyzeInitializer(property)
        return property.type
    }

    // 初期化式からは、初期化中のインスタンス（名前だけ・this.名前）の先に宣言したプロパティだけを読める（自分自身・後のプロパティは未初期化。Kotlin と同じ）。
    // this を渡した先での読み取りは、実行時に確かめる（CS_RUN_UNINITIALIZED_PROPERTY）
    private fun readableDuringInitialization(property: Property, position: SourcePosition): Boolean {
        val index = initializing ?: return true
        if (property.owner !== currentClass || property.index < index) return true
        reportError(DiagnosticCode.SEM_UNINITIALIZED_PROPERTY, position, property.declaration.name)
        return false
    }

    // プロパティの初期化式で必ず（&& || の右辺以外で）生成するクラスをたどって自分に戻るクラスは、生成が終わらない（第3.9b段階）。
    // 関数・メソッドを通した生成は条件によるため対象外（実行時に CS_RUN_CALL_DEPTH_EXCEEDED で止まる）
    private fun checkConstructionCycles(program: Program) {
        for (declaration in program.classes) {
            val visited = Collections.newSetFromMap(IdentityHashMap<ClassDeclaration, Boolean>())
            val pending = ArrayList(constructionEdges[declaration].orEmpty())
            var cyclic = false
            while (pending.isNotEmpty() && !cyclic) {
                val next = pending.removeAt(pending.lastIndex)
                if (next === declaration) cyclic = true else if (visited.add(next)) pending.addAll(constructionEdges[next].orEmpty())
            }
            if (cyclic) reportError(DiagnosticCode.SEM_CYCLIC_CONSTRUCTION, declaration.namePosition, declaration.name)
        }
    }

    // 引数は本体のブロックを囲む専用のスコープに置く読み取り専用の変数（値渡し。本体の中での同名の宣言はシャドーイング）。
    // 値を返す関数は、本体のすべての経路が return で終わらなければならない。メソッド（第3.9b段階）は currentClass を設定して呼ぶ
    private fun analyzeFunction(function: FunctionDeclaration) {
        currentFunction = function
        currentRoutine = function
        val signature = signatures.getValue(function)
        scopes.add(HashMap())
        val symbols = ArrayList<VariableSymbol>()
        for ((parameter, type) in function.parameters.zip(signature.parameterTypes)) {
            val conflict = declarationConflict(parameter.name)
            when {
                conflict != null -> reportError(conflict, parameter.position, parameter.name)
                parameter.name in scopes.last() -> reportError(DiagnosticCode.SEM_DUPLICATE_DECLARATION, parameter.position, parameter.name)
                else -> VariableSymbol(parameter.name, type, false, parameter.position).also {
                    scopes.last()[parameter.name] = it
                    symbols.add(it)
                }
            }
        }
        parameters[function] = symbols
        analyzeBlock(function.body)
        scopes.removeAt(scopes.lastIndex)
        currentFunction = null
        currentRoutine = null
        val returnType = signature.returnType
        if (returnType != null && returnType != ScriptType.UNIT && !alwaysReturns(function.body.statements)) {
            reportError(DiagnosticCode.SEM_MISSING_RETURN, function.namePosition, function.name, returnType.displayName)
        }
    }

    // 文の並びが必ず return で終わる（その後の文には到達しない）。if は else があり、すべての分岐が必ず return で終わるときだけ。
    // while・for は条件・範囲によって本体を実行しないことがあるため、本体に return があっても終わるとはみなさない（while (true) も同じ）
    private fun alwaysReturns(statements: List<Statement>): Boolean = statements.any { statement ->
        when (statement) {
            is ReturnStatement -> true
            is IfStatement -> statement.elseBlock?.let { alwaysReturns(it.statements) } == true && statement.branches.all { alwaysReturns(it.body.statements) }
            else -> false
        }
    }

    // return の値は戻り値の型と同じ型（暗黙の変換なし）。Unit の関数は値を返せない（値の型と Unit の不一致として報告する）
    private fun analyzeReturn(statement: ReturnStatement) {
        val function = currentFunction
        if (function == null) {
            reportError(DiagnosticCode.SEM_RETURN_OUTSIDE_FUNCTION, statement.position)
            return
        }
        val expected = signatures.getValue(function).returnType
        val value = statement.value
        if (value == null) {
            if (expected != null && expected != ScriptType.UNIT) reportError(DiagnosticCode.SEM_RETURN_WITHOUT_VALUE, statement.position, function.name, expected.displayName)
            return
        }
        val actual = analyzeValue(value) ?: return
        if (expected != null && !assignable(expected, actual)) reportTypeMismatch(value.position, expected, actual)
    }

    // トップレベルからの各呼び出し（メソッドの呼び出し・プロパティのあるクラスの生成を含む: 第3.9b段階）について、その手続きが（呼び出す手続きを通して）
    // 使うトップレベルの変数が、呼び出しを含む文より前の文で宣言済み（＝初期化済み）であることを確かめる。トップレベルの文は順に実行され、
    // トップレベルのスコープの宣言は分岐・ループの中に無いため、前の文で宣言された変数は必ず初期化されている。
    // 再帰（第3.8e段階）で呼び出しの関係が循環するため、呼び出す手続きを通して使う変数は、集合が変わらなくなるまで広げて求める
    // （集合は増えるだけで、手続きの数 × 変数の数 回以内に必ず止まる）
    private fun checkCalls(program: Program) {
        val routines = routinesOf(program)
        val uses = IdentityHashMap<CallableDeclaration, MutableSet<VariableSymbol>>()
        routines.forEach { uses[it] = HashSet(globalUses[it].orEmpty()) }
        var changed = true
        while (changed) {
            changed = false
            for (routine in routines) {
                for (callee in callsIn[routine].orEmpty()) {
                    if (uses.getValue(routine).addAll(uses.getValue(callee))) changed = true
                }
            }
        }
        for (call in topLevelCalls) {
            val late = uses.getValue(call.routine).filter { globalDeclarationIndices.getValue(it) >= call.index }.minByOrNull { it.declarationPosition.offset } ?: continue
            reportError(DiagnosticCode.SEM_CALL_BEFORE_VARIABLE_DECLARATION, call.position, call.name, late.name)
        }
    }

    // 手続きの呼び出しを記録する（トップレベルの文からの呼び出しは、その文の番号とともに）
    private fun recordCall(routine: CallableDeclaration, name: String, position: SourcePosition) {
        val caller = currentRoutine
        if (caller == null) topLevelCalls.add(TopLevelCall(routine, name, position, topLevelIndex)) else callsIn.getOrPut(caller) { ArrayList() }.add(routine)
    }

    // ---- 文 ----

    private fun analyzeStatement(statement: Statement) {
        when (statement) {
            is VariableDeclaration -> analyzeDeclaration(statement)
            is Assignment -> analyzeAssignment(statement)
            is ExpressionStatement -> analyzeExpressionStatement(statement)
            is IfStatement -> analyzeIf(statement)
            is WhileStatement -> {
                analyzeCondition(statement.condition)
                loopDepth++
                analyzeBlock(statement.body)
                loopDepth--
            }
            is ForStatement -> analyzeFor(statement)
            // 到達しない文（break の後など）は警告しない（11.9-7）
            is BreakStatement -> if (loopDepth == 0) reportError(DiagnosticCode.SEM_BREAK_OUTSIDE_LOOP, statement.position)
            is ContinueStatement -> if (loopDepth == 0) reportError(DiagnosticCode.SEM_CONTINUE_OUTSIDE_LOOP, statement.position)
            is ReturnStatement -> analyzeReturn(statement)
        }
    }

    // 各ブロックは1つのスコープ
    private fun analyzeIf(statement: IfStatement) {
        for (branch in statement.branches) {
            analyzeCondition(branch.condition)
            analyzeBlock(branch.body)
        }
        statement.elseBlock?.let(::analyzeBlock)
    }

    // if / while の条件は Boolean のみ（暗黙の変換なし）
    private fun analyzeCondition(condition: Expression) {
        val type = analyzeValue(condition)
        if (type != null && type != ScriptType.BOOLEAN) reportError(DiagnosticCode.SEM_CONDITION_NOT_BOOLEAN, condition.position, type.displayName)
    }

    // 始点・終点は for の外のスコープで解析し（ループ変数はまだ見えない）、Int のみ（暗黙の変換なし）。
    // ループ変数は Int の val として、本体のブロックを囲む専用のスコープに置く（本体の中での同名の宣言はシャドーイング）
    private fun analyzeFor(statement: ForStatement) {
        for (bound in listOf(statement.start, statement.end)) {
            val type = analyzeValue(bound)
            if (type != null && type != ScriptType.INT) reportTypeMismatch(bound.position, ScriptType.INT, type)
        }
        scopes.add(HashMap())
        val conflict = declarationConflict(statement.variable)
        if (conflict != null) {
            reportError(conflict, statement.variablePosition, statement.variable)
        } else {
            val symbol = VariableSymbol(statement.variable, ScriptType.INT, false, statement.variablePosition)
            scopes.last()[statement.variable] = symbol
            loopVariables[statement] = symbol
        }
        loopDepth++
        analyzeBlock(statement.body)
        loopDepth--
        scopes.removeAt(scopes.lastIndex)
    }

    // ブロックの中の宣言はブロックを出ると見えなくなる
    private fun analyzeBlock(block: Block) {
        scopes.add(HashMap())
        block.statements.forEach(::analyzeStatement)
        scopes.removeAt(scopes.lastIndex)
    }

    private fun analyzeDeclaration(declaration: VariableDeclaration) {
        // 初期化式は宣言より前に解析する（初期化式の中では、宣言中の変数はまだ見えない）
        val initializerType = analyzeValue(declaration.initializer)
        val type = if (declaration.type == null) {
            initializerType
        } else {
            val annotated = resolveType(declaration.type)
            if (annotated != null && initializerType != null && !assignable(annotated, initializerType)) {
                reportTypeMismatch(declaration.initializer.position, annotated, initializerType)
            }
            annotated
        }
        val conflict = declarationConflict(declaration.name)
        when {
            conflict != null -> reportError(conflict, declaration.namePosition, declaration.name)
            declaration.name in scopes.last() -> reportError(DiagnosticCode.SEM_DUPLICATE_DECLARATION, declaration.namePosition, declaration.name)
            else -> {
                val symbol = VariableSymbol(declaration.name, type, declaration.isMutable, declaration.namePosition)
                scopes.last()[declaration.name] = symbol
                declarations[declaration] = symbol
                if (currentFunction == null && scopes.size == 1) globalDeclarationIndices[symbol] = topLevelIndex
            }
        }
    }

    // 変数・引数・ループ変数に使えない名前（組み込み関数・関数・クラスと同じ名前: 第3.8c・3.9a段階）の診断コード。
    // クラスの中（第3.9b段階）では、そのクラスのメソッドと同じ名前も使えない（名前だけの参照が関数を値として使うことと区別できないため）
    private fun declarationConflict(name: String): DiagnosticCode? = when {
        name in BuiltinFunctions.NAMES -> DiagnosticCode.SEM_BUILTIN_REDECLARATION
        name in functions || name in currentMethods() -> DiagnosticCode.SEM_FUNCTION_NAME_CONFLICT
        name in classes -> DiagnosticCode.SEM_CLASS_NAME_CONFLICT
        else -> null
    }

    private fun currentMethods(): Set<String> = currentClass?.let { members.getValue(it).methods.keys } ?: emptySet()

    // 値の型 actual を型 expected の場所（代入・引数・戻り値・初期化）に使える。同じ型か、要素の型が同じ MutableList<T> を List<T> に使う場合
    // （第3.10a段階。同じリストを読み取り専用の参照で指す）。要素の型は完全に一致しなければならない（共変性は無い）。それ以外の暗黙の変換は無い
    private fun assignable(expected: ScriptType, actual: ScriptType): Boolean =
        expected == actual || (expected is ScriptType.ListType && actual is ScriptType.ListType && !expected.mutable && actual.mutable && expected.element == actual.element)

    // 代入先は変数か、var のプロパティ（名前だけ・this.名前・式.名前: 第3.9b段階）か、MutableList の要素（第3.10a段階）。
    // 代入先を先に解析し（受け手・添字の順）、値は代入先の型に使える型
    private fun analyzeAssignment(assignment: Assignment) {
        val target = when (val assigned = assignment.target) {
            is NameReference -> when (val resolved = resolveName(assigned)) {
                is NameTarget.Variable -> Assignable(resolved.symbol.name, resolved.symbol.isMutable, resolved.symbol.type)
                is NameTarget.Member -> Assignable(resolved.property.declaration.name, resolved.property.declaration.isMutable, propertyType(resolved.property, assigned.position))
                // 数学の定数（第3.11c段階）は val と同じく代入できない
                NameTarget.Constant -> Assignable(assigned.name, false, ScriptType.DOUBLE)
                null -> null
            }
            is MemberAccessExpression -> when (val receiver = analyzeValue(assigned.receiver)) {
                null -> null
                // リスト・Vector の size は読み取り専用
                is ScriptType.ListType -> listProperty(assigned, receiver)?.let { Assignable(assigned.name, false, it) }
                ScriptType.VECTOR, ScriptType.MATRIX -> linalgProperty(assigned, receiver)?.let { Assignable(assigned.name, false, it) }
                else -> resolveProperty(assigned, receiver)?.let { Assignable(it.declaration.name, it.declaration.isMutable, propertyType(it, assigned.position)) }
            }
            is IndexExpression -> analyzeIndex(assigned)?.let { list ->
                if (list.mutable) {
                    Assignable("[]", true, list.element)
                } else {
                    // List と Vector（第3.11e段階）の要素は変更できない
                    reportError(DiagnosticCode.SEM_READ_ONLY_LIST, assigned.position, list.displayName)
                    null
                }
            }
            else -> error("Parser should have rejected: $assigned")
        }
        val valueType = analyzeValue(assignment.value)
        if (target == null) return
        if (!target.isMutable) {
            reportError(DiagnosticCode.SEM_VAL_REASSIGNMENT, assignment.target.position, target.name)
        } else if (target.type != null && valueType != null && !assignable(target.type, valueType)) {
            reportTypeMismatch(assignment.value.position, target.type, valueType)
        }
    }

    private class Assignable(val name: String, val isMutable: Boolean, val type: ScriptType?)

    // 副作用のない式（呼び出し以外）の結果が使われない場合は警告する（A4）。警告はコンパイルを妨げない
    private fun analyzeExpressionStatement(statement: ExpressionStatement) {
        val type = analyzeExpression(statement.expression)
        if (type != null && statement.expression !is CallExpression) {
            report(DiagnosticCode.SEM_UNUSED_EXPRESSION, DiagnosticSeverity.WARNING, statement.expression.position)
        }
    }

    // List<T> / MutableList<T>（第3.10a段階）は型引数が 1 つ。それ以外の型に型引数は無い。
    // 入れ子のリスト（第3.10b段階）は外側から順にたどって段ごとの可変性を集め、内側の型から組み立てる（深い入れ子でも再帰しない）
    private fun resolveType(type: TypeReference): ScriptType? {
        val levels = ArrayList<Boolean>()
        var current = type
        while (current.name == ScriptType.LIST || current.name == ScriptType.MUTABLE_LIST) {
            if (current.arguments.size != 1) {
                reportError(DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT, current.position, current.name, current.arguments.size)
                return null
            }
            levels.add(current.name == ScriptType.MUTABLE_LIST)
            current = current.arguments.single()
        }
        if (current.arguments.isNotEmpty()) {
            reportError(DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT, current.position, current.name, current.arguments.size)
            return null
        }
        var resolved = resolveSimpleType(current) ?: return null
        for (mutable in levels.asReversed()) resolved = ScriptType.ListType(resolved, mutable)
        return resolved
    }

    private fun resolveSimpleType(type: TypeReference): ScriptType? = when (type.name) {
        ScriptType.INT.displayName -> ScriptType.INT
        ScriptType.STRING.displayName -> ScriptType.STRING
        ScriptType.BOOLEAN.displayName -> ScriptType.BOOLEAN
        ScriptType.FLOAT.displayName -> ScriptType.FLOAT
        ScriptType.DOUBLE.displayName -> ScriptType.DOUBLE
        // Unit 型の変数は Stage 3.5 では禁止（A22）
        ScriptType.UNIT.displayName -> {
            reportError(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED, type.position)
            null
        }
        else -> classes[type.name]?.let { ScriptType.ClassType(it) } ?: run {
            // Vector・Matrix（第3.11e段階）は、同じ名前のクラスが無いときだけ組み込みの型
            if (type.name == ScriptType.VECTOR_NAME) return ScriptType.VECTOR
            if (type.name == ScriptType.MATRIX_NAME) return ScriptType.MATRIX
            reportError(DiagnosticCode.SEM_UNKNOWN_TYPE, type.position, type.name)
            null
        }
    }

    // ---- 式 ----

    // 値として使う式（初期化式・代入の右辺・演算の項・引数）。Unit は値として使えない（A22）
    private fun analyzeValue(expression: Expression): ScriptType? {
        val type = analyzeExpression(expression) ?: return null
        if (type == ScriptType.UNIT) {
            reportError(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED, expression.position)
            return null
        }
        return type
    }

    // 型が決まらなかった式（エラーを報告済み）は null を返す
    private fun analyzeExpression(expression: Expression): ScriptType? {
        val type = when (expression) {
            is IntLiteral -> analyzeIntLiteral(expression)
            is FloatLiteral -> ScriptType.FLOAT
            is DoubleLiteral -> ScriptType.DOUBLE
            is StringLiteral -> ScriptType.STRING
            is BooleanLiteral -> ScriptType.BOOLEAN
            is NameReference -> when (val target = resolveName(expression)) {
                is NameTarget.Variable -> target.symbol.type
                is NameTarget.Member -> propertyType(target.property, expression.position)
                NameTarget.Constant -> ScriptType.DOUBLE
                null -> null
            }
            is ThisExpression -> analyzeThis(expression)
            is UnaryExpression -> analyzeUnary(expression)
            is BinaryExpression -> analyzeBinary(expression)
            is LogicalExpression -> analyzeLogical(expression)
            is CallExpression -> analyzeCall(expression)
            is MemberAccessExpression -> when (val receiver = analyzeValue(expression.receiver)) {
                null -> null
                is ScriptType.ListType -> listProperty(expression, receiver)
                ScriptType.VECTOR, ScriptType.MATRIX -> linalgProperty(expression, receiver)
                else -> resolveProperty(expression, receiver)?.let { propertyType(it, expression.position) }
            }
            is IndexExpression -> analyzeIndex(expression)?.element
        }
        if (type != null) expressionTypes[expression] = type
        return type
    }

    private fun analyzeIntLiteral(literal: IntLiteral): ScriptType? {
        if (literal.value > Int.MAX_VALUE) {
            reportError(DiagnosticCode.SEM_INTEGER_OUT_OF_RANGE, literal.position, literal.value.toString())
            return null
        }
        intConstants[literal] = literal.value.toInt()
        return ScriptType.INT
    }

    private fun analyzeUnary(unary: UnaryExpression): ScriptType? {
        val operand = unary.operand
        // 「- 整数リテラル」は1つの定数として扱い、-2147483648 を許可する（D16）
        if (unary.operator == UnaryOperator.MINUS && operand is IntLiteral) {
            if (operand.value > MAX_NEGATED_LITERAL) {
                reportError(DiagnosticCode.SEM_INTEGER_OUT_OF_RANGE, operand.position, "-${operand.value}")
                return null
            }
            intConstants[unary] = (-operand.value).toInt()
            return ScriptType.INT
        }
        val type = analyzeValue(operand) ?: return null
        // "-" "+" は数値（Int・Float・Double: 第3.11a段階。結果は同じ型）、"!" は Boolean のみ
        if (unary.operator == UnaryOperator.NOT) {
            if (type == ScriptType.BOOLEAN) return type
            reportError(DiagnosticCode.SEM_INVALID_OPERAND_TYPE, unary.position, "!", type.displayName)
            return null
        }
        // Vector の単項の -（第3.11e段階）は各成分の符号を反転した Vector
        if (type == ScriptType.VECTOR && unary.operator == UnaryOperator.MINUS) {
            linalgOperations[unary] = LinalgOperation.NEGATE
            return type
        }
        if (!ScriptType.isNumeric(type)) {
            reportError(DiagnosticCode.SEM_INVALID_OPERAND_TYPE, unary.position, if (unary.operator == UnaryOperator.MINUS) "-" else "+", type.displayName)
            return null
        }
        numericOperations[unary] = type
        return type
    }

    // 四則演算・剰余・大小比較は数値同士（String の連結・大小比較は未対応: D2）。型の違う数値は広い方の型にそろえて計算する
    // （第3.11a段階: ScriptType.numericOperation。演算の結果はその型、大小比較は Boolean）。
    // == != は同じ型（Int・Boolean・String・Float・Double など）同士で、String は内容で比べる。型の違う数値（1 == 1.0）は Kotlin と同じく比べられない。
    // Unit は analyzeValue が拒否する
    private fun analyzeBinary(binary: BinaryExpression): ScriptType? {
        val left = analyzeValue(binary.left)
        val right = analyzeValue(binary.right)
        if (left == null || right == null) return null
        if (binary.operator == BinaryOperator.EQUAL || binary.operator == BinaryOperator.NOT_EQUAL) {
            // Vector・Matrix（第3.11e段階）は比べられない
            if ((assignable(left, right) || assignable(right, left)) && !ScriptType.isLinearAlgebra(left)) return ScriptType.BOOLEAN
            reportError(DiagnosticCode.SEM_INCOMPARABLE_TYPES, binary.position, binarySymbol(binary.operator), left.displayName, right.displayName)
            return null
        }
        if (ScriptType.isLinearAlgebra(left) || ScriptType.isLinearAlgebra(right)) return analyzeLinalgBinary(binary, left, right)
        val operation = ScriptType.numericOperation(left, right)
        if (operation == null) {
            val invalid = if (ScriptType.isNumeric(left)) right else left
            reportError(DiagnosticCode.SEM_INVALID_OPERAND_TYPE, binary.position, binarySymbol(binary.operator), invalid.displayName)
            return null
        }
        numericOperations[binary] = operation
        return when (binary.operator) {
            BinaryOperator.LESS, BinaryOperator.LESS_EQUAL, BinaryOperator.GREATER, BinaryOperator.GREATER_EQUAL -> ScriptType.BOOLEAN
            else -> operation
        }
    }

    // Vector・Matrix の演算子（第3.11e段階）: Vector + Vector・Vector - Vector・Vector * Double・Double * Vector・Vector / Double、
    // Matrix + Matrix・Matrix - Matrix・Matrix * Double・Double * Matrix（行列の積は matmul）。それ以外の組み合わせ・大小比較は、
    // Vector・Matrix でない方（両方とも Vector・Matrix なら右）の型を報告する
    private fun analyzeLinalgBinary(binary: BinaryExpression, left: ScriptType, right: ScriptType): ScriptType? {
        val vector = ScriptType.VECTOR
        val matrix = ScriptType.MATRIX
        val double = ScriptType.DOUBLE
        val operation = when (binary.operator) {
            BinaryOperator.ADD -> when {
                left == vector && right == vector -> LinalgOperation.ADD
                left == matrix && right == matrix -> LinalgOperation.MATRIX_ADD
                else -> null
            }
            BinaryOperator.SUBTRACT -> when {
                left == vector && right == vector -> LinalgOperation.SUBTRACT
                left == matrix && right == matrix -> LinalgOperation.MATRIX_SUBTRACT
                else -> null
            }
            BinaryOperator.MULTIPLY -> when {
                left == vector && right == double -> LinalgOperation.TIMES
                left == double && right == vector -> LinalgOperation.TIMES_LEFT
                left == matrix && right == double -> LinalgOperation.MATRIX_TIMES
                left == double && right == matrix -> LinalgOperation.MATRIX_TIMES_LEFT
                else -> null
            }
            BinaryOperator.DIVIDE -> LinalgOperation.DIVIDE.takeIf { left == vector && right == double }
            else -> null
        }
        if (operation == null) {
            val invalid = if (ScriptType.isLinearAlgebra(left)) right else left
            reportError(DiagnosticCode.SEM_INVALID_OPERAND_TYPE, binary.position, binarySymbol(binary.operator), invalid.displayName)
            return null
        }
        linalgOperations[binary] = operation
        return if (left == matrix || right == matrix) matrix else vector
    }

    // && || は Boolean 同士のみ
    private fun analyzeLogical(logical: LogicalExpression): ScriptType? {
        val left = analyzeValue(logical.left)
        conditionalDepth++
        val right = analyzeValue(logical.right)
        conditionalDepth--
        if (left == null || right == null) return null
        val symbol = if (logical.operator == LogicalOperator.AND) "&&" else "||"
        return requireOperands(logical.position, symbol, left, right, ScriptType.BOOLEAN)
    }

    // 両辺が expected なら expected を返す。そうでなければ、最初に合わない側の型を報告する
    private fun requireOperands(position: SourcePosition, symbol: String, left: ScriptType, right: ScriptType, expected: ScriptType): ScriptType? {
        val invalid = listOf(left, right).firstOrNull { it != expected } ?: return expected
        reportError(DiagnosticCode.SEM_INVALID_OPERAND_TYPE, position, symbol, invalid.displayName)
        return null
    }

    private fun binarySymbol(operator: BinaryOperator) = when (operator) {
        BinaryOperator.ADD -> "+"
        BinaryOperator.SUBTRACT -> "-"
        BinaryOperator.MULTIPLY -> "*"
        BinaryOperator.DIVIDE -> "/"
        BinaryOperator.REMAINDER -> "%"
        BinaryOperator.EQUAL -> "=="
        BinaryOperator.NOT_EQUAL -> "!="
        BinaryOperator.LESS -> "<"
        BinaryOperator.LESS_EQUAL -> "<="
        BinaryOperator.GREATER -> ">"
        BinaryOperator.GREATER_EQUAL -> ">="
    }

    // 名前だけの呼び出しは、組み込み関数 → クラスの中ではそのクラスのメソッド（暗黙の this: 第3.9b段階）→ 関数 → クラス（生成）→
    // 数学関数（第3.11b段階）の順に探す。同じ名前の変数があっても数学関数を呼べる（Kotlin と同じ）
    private fun analyzeCall(call: CallExpression): ScriptType? {
        val callee = call.callee
        if (callee is MemberAccessExpression) return analyzeMethodCall(call, callee)
        if (callee !is NameReference) {
            reportError(DiagnosticCode.SEM_INVALID_CALLEE, callee.position)
            return null
        }
        val list = callee.name == BuiltinFunctions.LIST_OF || callee.name == BuiltinFunctions.MUTABLE_LIST_OF
        // 型引数（第3.10a段階）は listOf / mutableListOf だけ
        if (call.typeArguments.isNotEmpty() && !list) {
            reportError(DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT, callee.position, callee.name, call.typeArguments.size)
            return null
        }
        val method = currentClass?.let { members.getValue(it).methods[callee.name] }
        return when {
            list -> analyzeListCreation(call, callee)
            callee.name == BuiltinFunctions.PRINTLN -> analyzePrintln(call, callee)
            callee.name == BuiltinFunctions.WAIT_TICKS -> analyzeWaitTicks(call, callee)
            method != null -> analyzeInvocation(call, callee.name, callee.position, method)
            callee.name in functions -> analyzeInvocation(call, callee.name, callee.position, functions.getValue(callee.name))
            callee.name in classes -> analyzeConstruction(call, callee, classes.getValue(callee.name))
            callee.name in MathOperation.FUNCTIONS -> analyzeMathFunction(call, callee, MathOperation.FUNCTIONS.getValue(callee.name))
            callee.name in RandomOperation.NAMES -> analyzeRandom(call, callee)
            callee.name in LinalgOperation.FUNCTIONS -> analyzeLinalgCall(call, callee)
            else -> {
                val isProperty = currentClass?.let { callee.name in members.getValue(it).propertyNames } == true
                val value = lookup(callee.name) != null || isProperty || callee.name in MathConstants.VALUES
                val code = if (value) DiagnosticCode.SEM_NOT_CALLABLE else DiagnosticCode.SEM_UNKNOWN_FUNCTION
                reportError(code, callee.position, callee.name)
                null
            }
        }
    }

    // ---- リスト（第3.10a段階） ----

    // listOf(…) / mutableListOf(…)。要素の型は型引数（listOf<Int>()）か、すべての要素の同じ型（型の違う要素の混在は不可。Any は無い。
    // 最上位の可変性だけが違うリストの要素は List<T>: 第3.10b段階）。
    // 要素も型も無い空のリストは型を決められない。listOf のリストは実行時にも変更できない
    private fun analyzeListCreation(call: CallExpression, callee: NameReference): ScriptType? {
        if (call.typeArguments.size > 1) {
            reportError(DiagnosticCode.SEM_WRONG_TYPE_ARGUMENT_COUNT, callee.position, callee.name, call.typeArguments.size)
            return null
        }
        val declared = call.typeArguments.firstOrNull()?.let { resolveType(it) ?: return null }
        var element = declared
        var valid = true
        for (argument in call.arguments) {
            val actual = analyzeValue(argument)
            val expected = element
            when {
                actual == null -> valid = false
                expected == null -> element = actual
                assignable(expected, actual) -> Unit
                // 第3.10b段階: 型引数の無いとき、要素の型の違いが最上位の可変性だけなら読み取り専用の List<T> にそろえる（要素の順序によらない）
                declared == null && expected is ScriptType.ListType && actual is ScriptType.ListType && expected.element == actual.element ->
                    element = ScriptType.ListType(expected.element, false)
                declared != null -> {
                    reportError(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH, argument.position, callee.name, expected.displayName, actual.displayName)
                    valid = false
                }
                else -> {
                    reportTypeMismatch(argument.position, expected, actual)
                    valid = false
                }
            }
        }
        if (element == null) {
            if (valid) reportError(DiagnosticCode.SEM_LIST_TYPE_REQUIRED, callee.position, callee.name)
            return null
        }
        if (!valid) return null
        val type = ScriptType.ListType(element, callee.name == BuiltinFunctions.MUTABLE_LIST_OF)
        listCreations[call] = type
        return type
    }

    // list.size（Int）。() の付かない add などは値として使えない
    private fun listProperty(access: MemberAccessExpression, list: ScriptType.ListType): ScriptType? {
        if (access.name == SIZE) {
            listOperations[access] = ListOperation.SIZE
            return ScriptType.INT
        }
        if (access.name in LIST_METHODS) {
            reportError(DiagnosticCode.SEM_FUNCTION_AS_VALUE, access.position, access.name)
        } else {
            reportError(DiagnosticCode.SEM_UNKNOWN_PROPERTY, access.position, list.displayName, access.name)
        }
        return null
    }

    // add(T): Unit・removeAt(Int): T・clear(): Unit。どれも MutableList だけ（List は読み取り専用）
    private fun analyzeListMethod(call: CallExpression, callee: MemberAccessExpression, list: ScriptType.ListType): ScriptType? {
        val operation = LIST_METHODS[callee.name]
        if (operation == null) {
            if (callee.name == SIZE) {
                reportError(DiagnosticCode.SEM_NOT_CALLABLE, callee.position, callee.name)
            } else {
                reportError(DiagnosticCode.SEM_UNKNOWN_METHOD, callee.position, list.displayName, callee.name)
            }
            return null
        }
        if (!list.mutable) {
            reportError(DiagnosticCode.SEM_READ_ONLY_LIST, callee.position, list.displayName)
            return null
        }
        val parameter = when (operation) {
            ListOperation.ADD -> list.element
            ListOperation.REMOVE_AT -> ScriptType.INT
            ListOperation.CLEAR, ListOperation.SIZE -> null
        }
        val expectedCount = if (parameter == null) 0 else 1
        if (call.arguments.size != expectedCount) {
            reportError(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, callee.position, callee.name, call.arguments.size)
            return null
        }
        if (parameter != null) {
            val argument = call.arguments.single()
            val actual = analyzeValue(argument) ?: return null
            if (!assignable(parameter, actual)) {
                reportError(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH, argument.position, callee.name, parameter.displayName, actual.displayName)
                return null
            }
        }
        listOperations[call] = operation
        return if (operation == ListOperation.REMOVE_AT) list.element else ScriptType.UNIT
    }

    // 添字で読み書きする受け手の、要素の型・変更できるか・型の名前（診断用）
    private class Indexed(val element: ScriptType, val mutable: Boolean, val displayName: String)

    // list[index]。受け手・添字の順に評価する。受け手はリストか Vector（第3.11e段階。要素は Double で、変更できない）、添字は Int
    private fun analyzeIndex(index: IndexExpression): Indexed? {
        val receiver = analyzeValue(index.receiver)
        val indexType = analyzeValue(index.index)
        if (receiver == null || indexType == null) return null
        val indexed = when (receiver) {
            is ScriptType.ListType -> Indexed(receiver.element, receiver.mutable, receiver.displayName)
            ScriptType.VECTOR -> Indexed(ScriptType.DOUBLE, false, receiver.displayName)
            // Matrix の m[i] は行の Vector（第3.11e段階。変更できない）
            ScriptType.MATRIX -> Indexed(ScriptType.VECTOR, false, receiver.displayName)
            else -> {
                reportError(DiagnosticCode.SEM_INVALID_OPERAND_TYPE, index.position, "[]", receiver.displayName)
                return null
            }
        }
        if (indexType != ScriptType.INT) {
            reportError(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH, index.index.position, "[]", ScriptType.INT.displayName, indexType.displayName)
            return null
        }
        return indexed
    }

    // Vector・Matrix のメンバー（第3.11e段階）: v.size・m.rowCount（どちらも要素の数）・m.columnCount（Int）だけ。それ以外の名前は他の値と同じく未対応
    private fun linalgProperty(access: MemberAccessExpression, receiver: ScriptType): ScriptType? {
        if ((receiver == ScriptType.VECTOR && access.name == SIZE) || (receiver == ScriptType.MATRIX && access.name == LinalgOperation.ROW_COUNT)) {
            listOperations[access] = ListOperation.SIZE
            return ScriptType.INT
        }
        if (receiver == ScriptType.MATRIX && access.name == LinalgOperation.COLUMN_COUNT_NAME) {
            linalgOperations[access] = LinalgOperation.COLUMN_COUNT
            return ScriptType.INT
        }
        reportError(DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED, access.position)
        return null
    }

    // Vector・Matrix の関数（第3.11e段階）。vector は Double 1〜16 個か List<Double> 1 つ、dot は Vector 2 つ、length・normalize は Vector 1 つ、
    // matrix は行の Vector 1〜8 個、transpose は Matrix 1 つ、matmul は Matrix と Matrix か Vector
    private fun analyzeLinalgCall(call: CallExpression, callee: NameReference): ScriptType? {
        val count = call.arguments.size
        if (callee.name == LinalgOperation.MATRIX || callee.name == LinalgOperation.TRANSPOSE_NAME || callee.name == LinalgOperation.MATMUL_NAME) return analyzeMatrixCall(call, callee)
        if (callee.name == LinalgOperation.VECTOR) {
            if (count !in 1..LinalgOperation.MAX_VECTOR_SIZE) {
                reportError(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, callee.position, callee.name, count)
                return null
            }
            val types = call.arguments.map { analyzeValue(it) }
            if (types.any { it == null }) return null
            val single = types.singleOrNull()
            if (single is ScriptType.ListType && single.element == ScriptType.DOUBLE) {
                linalgOperations[call] = LinalgOperation.VECTOR_OF_LIST
                return ScriptType.VECTOR
            }
            for ((index, type) in types.withIndex()) {
                if (type != ScriptType.DOUBLE) {
                    reportError(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH, call.arguments[index].position, callee.name, ScriptType.DOUBLE.displayName, type!!.displayName)
                    return null
                }
            }
            linalgOperations[call] = LinalgOperation.VECTOR_OF
            return ScriptType.VECTOR
        }
        val (operation, arity) = when (callee.name) {
            LinalgOperation.DOT_NAME -> LinalgOperation.DOT to 2
            LinalgOperation.LENGTH_NAME -> LinalgOperation.LENGTH to 1
            else -> LinalgOperation.NORMALIZE to 1
        }
        if (count != arity) {
            reportError(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, callee.position, callee.name, count)
            return null
        }
        var valid = true
        for (argument in call.arguments) {
            val type = analyzeValue(argument)
            if (type == null) {
                valid = false
            } else if (type != ScriptType.VECTOR) {
                reportError(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH, argument.position, callee.name, ScriptType.VECTOR.displayName, type.displayName)
                valid = false
            }
        }
        if (!valid) return null
        linalgOperations[call] = operation
        return if (operation == LinalgOperation.NORMALIZE) ScriptType.VECTOR else ScriptType.DOUBLE
    }

    private fun analyzeMatrixCall(call: CallExpression, callee: NameReference): ScriptType? {
        val count = call.arguments.size
        val expectedCount = when (callee.name) {
            LinalgOperation.MATRIX -> 1..LinalgOperation.MAX_MATRIX_SIZE
            LinalgOperation.TRANSPOSE_NAME -> 1..1
            else -> 2..2
        }
        if (count !in expectedCount) {
            reportError(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, callee.position, callee.name, count)
            return null
        }
        val types = call.arguments.map { analyzeValue(it) }
        if (types.any { it == null }) return null
        // 引数ごとの型（matmul の 2 つ目は Matrix か Vector）
        val expected = types.mapIndexed { index, type ->
            when {
                callee.name == LinalgOperation.MATRIX -> ScriptType.VECTOR
                callee.name == LinalgOperation.MATMUL_NAME && index == 1 && type == ScriptType.VECTOR -> ScriptType.VECTOR
                else -> ScriptType.MATRIX
            }
        }
        for (index in types.indices) {
            if (types[index] != expected[index]) {
                reportError(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH, call.arguments[index].position, callee.name, expected[index].displayName, types[index]!!.displayName)
                return null
            }
        }
        val operation = when {
            callee.name == LinalgOperation.MATRIX -> LinalgOperation.MATRIX_OF
            callee.name == LinalgOperation.TRANSPOSE_NAME -> LinalgOperation.TRANSPOSE
            types[1] == ScriptType.VECTOR -> LinalgOperation.MATMUL_VECTOR
            else -> LinalgOperation.MATMUL
        }
        linalgOperations[call] = operation
        return if (operation == LinalgOperation.MATMUL_VECTOR) ScriptType.VECTOR else ScriptType.MATRIX
    }

    // メソッドの呼び出し（第3.9b段階）。受け手を評価してから、引数を左から順に評価する。受け手がリストならリストの操作（第3.10a段階）
    private fun analyzeMethodCall(call: CallExpression, callee: MemberAccessExpression): ScriptType? {
        val receiver = analyzeValue(callee.receiver) ?: return null
        if (receiver is ScriptType.ListType) return analyzeListMethod(call, callee, receiver)
        // Vector・Matrix（第3.11e段階）にメソッドは無い（size・rowCount・columnCount は値。変更する add なども無い）
        if (ScriptType.isLinearAlgebra(receiver)) {
            if (callee.name == SIZE || callee.name == LinalgOperation.ROW_COUNT || callee.name == LinalgOperation.COLUMN_COUNT_NAME) {
                reportError(DiagnosticCode.SEM_NOT_CALLABLE, callee.position, callee.name)
            } else {
                reportError(DiagnosticCode.SEM_UNKNOWN_METHOD, callee.position, receiver.displayName, callee.name)
            }
            return null
        }
        if (ScriptType.isNumeric(receiver)) {
            val operation = MathOperation.METHODS[callee.name] ?: return analyzeConversion(call, callee)
            return analyzeMathMethod(call, callee, receiver, operation)
        }
        val declaration = classOf(receiver, callee.position) ?: return null
        val classMembers = members.getValue(declaration)
        val method = classMembers.methods[callee.name]
        if (method == null) {
            if (callee.name in classMembers.propertyNames) {
                reportError(DiagnosticCode.SEM_NOT_CALLABLE, callee.position, callee.name)
            } else {
                reportError(DiagnosticCode.SEM_UNKNOWN_METHOD, callee.position, declaration.name, callee.name)
            }
            return null
        }
        return analyzeInvocation(call, callee.name, callee.position, method)
    }

    // 数値の変換 toInt()・toFloat()・toDouble()（第3.11a段階）。引数は無い。それ以外の数値のメンバー（プロパティ・() の無い toInt を含む）は
    // 他のクラス以外の値と同じく未対応（CS_SEM_MEMBER_ACCESS_UNSUPPORTED）。Float・Double から Int への変換は 0 の方向へ切り捨て、NaN は 0、
    // Int の範囲外は Int.MIN_VALUE / Int.MAX_VALUE になる（Kotlin と同じ。実行時エラーにならない）。同じ型への変換は値を変えない
    private fun analyzeConversion(call: CallExpression, callee: MemberAccessExpression): ScriptType? {
        val target = NumericConversions.TARGETS[callee.name]
        if (target == null) {
            reportError(DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED, callee.position)
            return null
        }
        if (call.arguments.isNotEmpty()) {
            reportError(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, callee.position, callee.name, call.arguments.size)
            return null
        }
        conversions[call] = target
        return target
    }

    // 名前だけで呼ぶ数学関数（第3.11b段階）。引数を左から評価する。最初の引数の型で計算し（Int は floating でない関数だけ）、
    // 残りの引数はそれと同じ型（min(1, 2.0) は Kotlin と同じくエラー）
    private fun analyzeMathFunction(call: CallExpression, callee: NameReference, operation: MathOperation): ScriptType? {
        val tolerance = operation == MathOperation.IS_CLOSE && call.arguments.size == operation.arity - 1
        if (call.arguments.size != operation.arity && !tolerance) {
            reportError(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, callee.position, callee.name, call.arguments.size)
            return null
        }
        val types = call.arguments.map { analyzeValue(it) }
        val type = types.first() ?: return null
        if (!mathType(operation, type)) {
            reportError(DiagnosticCode.SEM_INVALID_OPERAND_TYPE, call.arguments.first().position, callee.name, type.displayName)
            return null
        }
        return recordMath(call, callee.name, operation, type, 1, types)
    }

    // 数値のメソッドの数学関数（第3.11b段階）: x.pow(y)・x.roundToInt()・x.coerceIn(min, max)・x.isNaN()・x.isInfinite()（第3.11d段階）。
    // 受け手の型で計算する。Int の pow・roundToInt・isNaN・isInfinite は Kotlin と同じく存在しない
    private fun analyzeMathMethod(call: CallExpression, callee: MemberAccessExpression, receiver: ScriptType, operation: MathOperation): ScriptType? {
        if (!mathType(operation, receiver)) {
            reportError(DiagnosticCode.SEM_UNKNOWN_METHOD, callee.position, receiver.displayName, callee.name)
            return null
        }
        if (call.arguments.size != operation.arity - 1) {
            reportError(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, callee.position, callee.name, call.arguments.size)
            return null
        }
        return recordMath(call, callee.name, operation, receiver, 0, call.arguments.map { analyzeValue(it) })
    }

    private fun mathType(operation: MathOperation, type: ScriptType) =
        type == ScriptType.FLOAT || type == ScriptType.DOUBLE || (type == ScriptType.INT && !operation.floating)

    // types は引数の型（先頭の skip 個は検査済み）。引数は計算する型と同じ（pow の指数は Int も可）
    private fun recordMath(call: CallExpression, name: String, operation: MathOperation, type: ScriptType, skip: Int, types: List<ScriptType?>): ScriptType? {
        var valid = true
        for (index in skip until types.size) {
            val actual = types[index]
            if (actual == null) {
                valid = false
            } else if (actual != type && !(operation == MathOperation.POW && actual == ScriptType.INT)) {
                reportError(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH, call.arguments[index].position, name, type.displayName, actual.displayName)
                valid = false
            }
        }
        if (!valid) return null
        mathCalls[call] = MathCall(operation, type)
        return when (operation) {
            MathOperation.ROUND_TO_INT -> ScriptType.INT
            MathOperation.IS_NAN, MathOperation.IS_INFINITE, MathOperation.IS_CLOSE -> ScriptType.BOOLEAN
            else -> type
        }
    }

    // インスタンスのプロパティ（第3.9b段階）。受け手はクラス型の値。() の無いメソッドは値として使えない。
    // 初期化式の中の this.名前 は、先に宣言したプロパティだけ
    private fun resolveProperty(access: MemberAccessExpression, receiver: ScriptType): Property? {
        val declaration = classOf(receiver, access.position) ?: return null
        val classMembers = members.getValue(declaration)
        val property = classMembers.propertyNames[access.name]
        if (property == null) {
            if (access.name in classMembers.methods) {
                reportError(DiagnosticCode.SEM_FUNCTION_AS_VALUE, access.position, access.name)
            } else {
                reportError(DiagnosticCode.SEM_UNKNOWN_PROPERTY, access.position, declaration.name, access.name)
            }
            return null
        }
        if (access.receiver is ThisExpression && !readableDuringInitialization(property, access.position)) return null
        propertyAccesses[access] = property.index
        return property
    }

    // メンバーの受け手の型のクラス。クラス・リスト以外の値（Int など）のメンバーは未対応（位置はメンバー名）
    private fun classOf(type: ScriptType, position: SourcePosition): ClassDeclaration? {
        if (type is ScriptType.ClassType) return type.declaration
        reportError(DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED, position)
        return null
    }

    // this（第3.9b段階）はメソッドの本体と初期化式の中だけで使える。初期化式の中では初期化中のインスタンスで、
    // 渡した先で未初期化のプロパティを読むと実行時エラーになる
    private fun analyzeThis(expression: ThisExpression): ScriptType? {
        val owner = currentClass
        if (owner == null) {
            reportError(DiagnosticCode.SEM_THIS_OUTSIDE_CLASS, expression.position)
            return null
        }
        return ScriptType.ClassType(owner)
    }

    // インスタンスの生成（第3.9a段階）。引数の無いコンストラクタだけ。結果はそのクラスの型。
    // プロパティのあるクラス（第3.9b段階）はコンストラクタ（初期化式）を呼ぶ。初期化式の中で必ず行う生成は、生成の循環の検出に記録する
    private fun analyzeConstruction(call: CallExpression, callee: NameReference, declaration: ClassDeclaration): ScriptType? {
        if (call.arguments.isNotEmpty()) {
            reportError(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, callee.position, callee.name, call.arguments.size)
            return null
        }
        constructions[call] = declaration
        if (declaration.properties.isNotEmpty()) recordCall(declaration, declaration.name, call.position)
        val owner = currentClass
        if (owner != null && initializing != null && conditionalDepth == 0) constructionEdges.getOrPut(owner) { ArrayList() }.add(declaration)
        return ScriptType.ClassType(declaration)
    }

    // ユーザー定義関数・メソッドの呼び出し（第3.8c段階）。引数（第3.8d段階）は個数と型（暗黙の変換なし）を確かめ、左から順に評価する。
    // 結果は戻り値の型（Unit なら値として使えない）
    private fun analyzeInvocation(call: CallExpression, name: String, position: SourcePosition, function: FunctionDeclaration): ScriptType? {
        val signature = signatures.getValue(function)
        if (call.arguments.size != function.parameters.size) {
            reportError(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, position, name, call.arguments.size)
            return null
        }
        var valid = true
        for ((argument, expected) in call.arguments.zip(signature.parameterTypes)) {
            val actual = analyzeValue(argument)
            if (actual == null || expected == null) {
                valid = false
            } else if (!assignable(expected, actual)) {
                reportError(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH, argument.position, name, expected.displayName, actual.displayName)
                valid = false
            }
        }
        calls[call] = function
        recordCall(function, name, call.position)
        return if (valid) signature.returnType else null
    }

    // println() / println(値)（A3。Boolean は "true" / "false"、Float・Double は Kotlin と同じ表示: 第3.11a段階）
    private fun analyzePrintln(call: CallExpression, callee: NameReference): ScriptType? {
        if (call.arguments.size > 1) {
            reportError(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, callee.position, callee.name, call.arguments.size)
            return null
        }
        val argument = call.arguments.firstOrNull() ?: return ScriptType.UNIT
        return analyzeValue(argument)?.let { ScriptType.UNIT }
    }

    // 乱数（第3.11e段階）。random は引数 0 個か Double 2 つ、randomInt は Int 2 つ、randomSeed は Int 1 つ。範囲の検査は実行時に行う
    private fun analyzeRandom(call: CallExpression, callee: NameReference): ScriptType? {
        val count = call.arguments.size
        val (operation, parameter) = when {
            callee.name == RandomOperation.RANDOM_NAME && count == 0 -> RandomOperation.RANDOM to null
            callee.name == RandomOperation.RANDOM_NAME && count == 2 -> RandomOperation.RANDOM_RANGE to ScriptType.DOUBLE
            callee.name == RandomOperation.RANDOM_INT_NAME && count == 2 -> RandomOperation.RANDOM_INT to ScriptType.INT
            callee.name == RandomOperation.RANDOM_SEED_NAME && count == 1 -> RandomOperation.RANDOM_SEED to ScriptType.INT
            else -> {
                reportError(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, callee.position, callee.name, count)
                return null
            }
        }
        var valid = true
        for (argument in call.arguments) {
            val type = analyzeValue(argument)
            if (type == null) {
                valid = false
            } else if (type != parameter) {
                reportError(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH, argument.position, callee.name, parameter!!.displayName, type.displayName)
                valid = false
            }
        }
        if (!valid) return null
        randomCalls[call] = operation
        return when (operation) {
            RandomOperation.RANDOM, RandomOperation.RANDOM_RANGE -> ScriptType.DOUBLE
            RandomOperation.RANDOM_INT -> ScriptType.INT
            RandomOperation.RANDOM_SEED -> ScriptType.UNIT
        }
    }

    // waitTicks(Int)。負数の検査は実行時に行う（D6）
    private fun analyzeWaitTicks(call: CallExpression, callee: NameReference): ScriptType? {
        if (call.arguments.size != 1) {
            reportError(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, callee.position, callee.name, call.arguments.size)
            return null
        }
        val argument = call.arguments.single()
        val type = analyzeValue(argument) ?: return null
        if (type != ScriptType.INT) {
            reportError(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH, argument.position, callee.name, ScriptType.INT.displayName, type.displayName)
            return null
        }
        return ScriptType.UNIT
    }

    // 名前の参照先。変数（ローカル変数・引数・トップレベルの変数）か、暗黙の this のプロパティ（第3.9b段階）か、数学の定数（第3.11c段階）
    private sealed interface NameTarget {
        class Variable(val symbol: VariableSymbol) : NameTarget
        class Member(val property: Property) : NameTarget
        data object Constant : NameTarget
    }

    // 組み込み関数名・ユーザー定義関数名・メソッド名を値として参照することはできない。クラスの中（メソッドの本体・初期化式: 第3.9b段階）では、
    // ローカル変数・引数 → そのクラスのプロパティ → トップレベルの変数 の順に探す。どれにも無ければ数学の定数（第3.11c段階）。
    // 手続きの本体で使ったトップレベルの変数を記録する
    private fun resolveName(reference: NameReference): NameTarget? {
        val name = reference.name
        if (name in BuiltinFunctions.NAMES || name in functions || name in currentMethods()) {
            reportError(DiagnosticCode.SEM_FUNCTION_AS_VALUE, reference.position, name)
            return null
        }
        if (name in classes) {
            reportError(DiagnosticCode.SEM_CLASS_AS_VALUE, reference.position, name)
            return null
        }
        val owner = currentClass
        val symbol = if (owner == null) lookup(name) else scopes.drop(1).asReversed().firstNotNullOfOrNull { it[name] } ?: run {
            val property = members.getValue(owner).propertyNames[name]
            if (property != null) {
                if (!readableDuringInitialization(property, reference.position)) return null
                propertyAccesses[reference] = property.index
                return NameTarget.Member(property)
            }
            if (initializing != null && name in topLevelNames) {
                reportError(DiagnosticCode.SEM_TOP_LEVEL_VARIABLE_IN_INITIALIZER, reference.position, name)
                return null
            }
            scopes.first()[name]
        }
        if (symbol == null) {
            // どの変数・プロパティにも当たらない名前だけが数学の定数になる（第3.11c段階）
            val constant = MathConstants.VALUES[name]
            if (constant != null) {
                mathConstants[reference] = constant
                return NameTarget.Constant
            }
            reportError(DiagnosticCode.SEM_UNDECLARED_VARIABLE, reference.position, name)
            return null
        }
        references[reference] = symbol
        currentRoutine?.let { if (symbol in globalDeclarationIndices) globalUses.getOrPut(it) { HashSet() }.add(symbol) }
        return NameTarget.Variable(symbol)
    }

    private fun lookup(name: String): VariableSymbol? = scopes.asReversed().firstNotNullOfOrNull { it[name] }

    // ---- 診断 ----

    private fun reportTypeMismatch(position: SourcePosition, expected: ScriptType, actual: ScriptType) {
        reportError(DiagnosticCode.SEM_TYPE_MISMATCH, position, expected.displayName, actual.displayName)
    }

    private fun reportError(code: DiagnosticCode, position: SourcePosition, vararg arguments: Any) {
        report(code, DiagnosticSeverity.ERROR, position, *arguments)
    }

    // 引数は Int と String のみ（String は 64 単位を超えないよう短縮する）
    private fun report(code: DiagnosticCode, severity: DiagnosticSeverity, position: SourcePosition, vararg arguments: Any) {
        val converted = arguments.map {
            when (it) {
                is Int -> DiagnosticArgument.IntArgument(it)
                else -> DiagnosticArgument.StringArgument.truncated(it.toString())
            }
        }
        diagnostics.report(Diagnostic(code, severity, DiagnosticPhase.SEMANTIC, position, converted))
    }
}
