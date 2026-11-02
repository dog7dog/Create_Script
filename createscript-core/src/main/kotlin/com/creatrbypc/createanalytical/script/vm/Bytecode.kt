package com.creatrbypc.createanalytical.script.vm

import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition

// 命令数・定数・変数・スタックの上限（定数 256 は A10 で確定、それ以外は暫定値）
object VmLimits {
    const val MAX_INSTRUCTIONS = 4096
    const val MAX_CONSTANTS = 256
    const val MAX_VARIABLES = 64
    const val MAX_STACK_DEPTH = 64

    // ユーザー定義関数の呼び出しの深さ（同時に積める呼び出しフレームの数。第3.8c段階の暫定値）
    const val MAX_CALL_DEPTH = 16

    // ユーザー定義関数の引数の数（第3.8d段階の暫定値。引数はローカル変数の先頭に入るため MAX_VARIABLES 以下）
    const val MAX_PARAMETERS = 16

    // 1 台の CS_VM で同時に存在できるオブジェクトの数（第3.9a段階の暫定値）
    const val MAX_OBJECTS = 256

    // 1 つのクラスのプロパティの数（第3.9b段階の暫定値。回収でたどる参照の数を抑える）
    const val MAX_PROPERTIES = 16

    // 1 つのリストの要素数と、1 台の CS_VM のリストの要素数の合計（回収前のリストを含む。第3.10a段階の暫定値）。
    // 合計 4096 は、プロパティの値の最大（MAX_OBJECTS × MAX_PROPERTIES）と同じ
    const val MAX_LIST_SIZE = 256
    const val MAX_LIST_ELEMENTS = 4096

    // Vector の次元・Matrix の行数と列数の上限（第3.11e段階）
    const val MAX_VECTOR_SIZE = 16
    const val MAX_MATRIX_SIZE = 8
}

enum class Opcode {
    // operand = 定数プールの番号。定数をスタックに積む
    PUSH_CONST,

    // operand = 変数の番号。変数の値を積む / スタックの値を取り出して変数に入れる
    LOAD,
    STORE,

    // operand = ローカル変数の番号。いま実行している関数の呼び出しフレームの変数を読み書きする（第3.8c段階）
    LOAD_LOCAL,
    STORE_LOCAL,

    // スタックの値を1つ捨てる（式だけの文の結果）
    POP,

    // 同じ数値の型の 2 つを取り出して計算し、結果を積む。operand は数値の型（NumericKind: 0 は Int、1 は Float、2 は Double。第3.11a段階）
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    REMAINDER,

    // 符号反転。operand は数値の型（NumericKind）
    NEGATE,

    // operand = 0（false）/ 1（true）。Boolean を積む（定数プールは使わない）
    PUSH_BOOL,

    // Boolean の否定
    NOT,

    // 同じ型の2つを取り出して比べ、Boolean を積む。operand 0 は Int・Boolean・String（内容で比べる）・参照（同一性）、
    // 1 は Float、2 は Double（NumericKind。IEEE 754 の等価: NaN はどの値とも等しくなく、0.0 と -0.0 は等しい。第3.11a段階）
    EQUAL,
    NOT_EQUAL,

    // 同じ数値の型の大小比較。2つ取り出して Boolean を積む。operand は数値の型（NumericKind。浮動小数点数は IEEE 754 の比較で、NaN との比較はすべて false）
    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL,

    // operand = 移り先の命令位置（絶対位置）
    JUMP,

    // Boolean を1つ取り出し、false なら operand の命令位置へ移る
    JUMP_IF_FALSE,

    // operand = HostFunction の id。引数を取り出し、結果（Stage 3.5 では Unit）を積む（D12）
    CALL_HOST,

    // operand = CompiledProgram.functions の番号。呼び出しフレーム（戻り先・ローカル変数）を積み、関数の先頭へ移る（第3.8c段階）。
    // 引数（第3.8d段階）はスタックの上の parameterCount 個（左の引数が下）を取り出し、ローカル変数の先頭へ順に入れる。
    // 残りのスタックは呼び出し元のもので、関数の実行中は触れない
    CALL,

    // operand = 0: 結果の Unit を返す（関数のスタックは空）。1: 関数のスタックの値1つを返す（第3.8d段階）。
    // 呼び出しフレームを外して戻り先へ移り、結果を呼び出し元のスタックに積む
    RETURN,

    // operand = CompiledProgram.classes の番号。そのクラスのオブジェクトを新しく作り、参照を積む（第3.9a段階）。
    // プロパティ（第3.9b段階）はすべて未初期化で作り、クラスのコンストラクタが宣言の順に初期化する
    NEW,

    // プログラムの終了
    HALT,

    // operand = プロパティの番号（ClassInfo.fields の位置。第3.9b段階）。オブジェクトの参照を取り出し、そのプロパティの値を積む。
    // 未初期化なら実行時エラー（CS_RUN_UNINITIALIZED_PROPERTY）
    GET_FIELD,

    // operand = プロパティの番号。値と、その下のオブジェクトの参照を取り出し、プロパティに値を入れる（第3.9b段階）
    SET_FIELD,

    // リスト（第3.10a段階）。リストはオブジェクトのヒープに置き、オブジェクトと同じ参照（id）で扱う。
    // NEW_LIST: operand = CompiledProgram.lists の番号。その形の要素数だけ値を取り出し（左の要素が下）、リストを作って参照を積む
    NEW_LIST,

    // LIST_GET: 添字とリストを取り出し、要素を積む。LIST_SET: 値・添字・リストを取り出し、要素を置き換える
    LIST_GET,
    LIST_SET,

    // LIST_SIZE: リストを取り出し、要素数を積む
    LIST_SIZE,

    // LIST_ADD: 値とリストを取り出して末尾に加え、Unit を積む。LIST_REMOVE_AT: 添字とリストを取り出し、その要素を取り除いて積む。
    // LIST_CLEAR: リストを取り出してすべての要素を取り除き、Unit を積む。変更できない（listOf で作った）リストには使えない
    LIST_ADD,
    LIST_REMOVE_AT,
    LIST_CLEAR,

    // 数値の変換（第3.11a段階）。operand = NumericKind.conversion(変換元, 変換先)（別の型どうしの 6 通り）。値を取り出し、変換した値を積む。
    // Float・Double から Int は 0 の方向へ切り捨て、NaN は 0、範囲外は Int.MIN_VALUE / Int.MAX_VALUE（Kotlin の toInt と同じ）
    CONVERT,

    // 数学関数（第3.11b段階）。operand = MathFunction.operand(関数, NumericKind)。関数の引数の数だけ同じ型の値を取り出し（左の引数が下）、
    // 結果を積む。結果の型は引数と同じ（roundToInt だけ Int）。coerceIn の空の範囲・roundToInt の NaN は実行時エラー
    MATH,

    // Vector・Matrix の演算（第3.11e段階）。operand = LinalgFunction.operand(関数, 個数)。計算の単位の数 W と確定の 1 単位を、1 回の実行で 1 単位ずつ進める
    // （PC は最後の単位までこの命令に留まり、オペランドは確定までスタックに残す）。途中の状態は保存しない（復元すると最初から払い直す）
    LINALG,
}

// 数値の演算・比較・変換の operand が表す数値の型（第3.11a段階。バイトコードに書き込まれるため変更しない）。
// Int は 0 で、第3.10b段階までのバイトコード（operand はすべて 0）と同じ
object NumericKind {
    const val INT = 0
    const val FLOAT = 1
    const val DOUBLE = 2
    const val COUNT = 3

    fun conversion(from: Int, to: Int) = from * COUNT + to
}

// MATH 命令の数学関数（第3.11b段階）。id はバイトコードに書き込まれるため、一度決めたら変更・再利用しない。
// arity は取り出す値の数、firstKind は使える最初の数値の型（firstKind 以降の NumericKind が使える）、result は結果の型。
// pow は Double だけで、Float・Int の pow は Compiler が CONVERT で Double にして計算し、Float に戻す（Kotlin の実装と同じ）
enum class MathFunction(val id: Int, val arity: Int, val firstKind: Int, val result: MathResult = MathResult.SAME) {
    ABS(0, 1, NumericKind.INT),
    MIN(1, 2, NumericKind.INT),
    MAX(2, 2, NumericKind.INT),
    SQRT(3, 1, NumericKind.FLOAT),
    POW(4, 2, NumericKind.DOUBLE),
    FLOOR(5, 1, NumericKind.FLOAT),
    CEIL(6, 1, NumericKind.FLOAT),
    ROUND(7, 1, NumericKind.FLOAT),
    ROUND_TO_INT(8, 1, NumericKind.FLOAT, MathResult.INT),
    COERCE_IN(9, 3, NumericKind.INT),

    // 三角関数・双曲線関数（第3.11c段階。角度はラジアン）。Float は Float のまま計算する（Kotlin の Float 版と同じく Double で計算して戻す）
    SIN(10, 1, NumericKind.FLOAT),
    COS(11, 1, NumericKind.FLOAT),
    TAN(12, 1, NumericKind.FLOAT),
    ASIN(13, 1, NumericKind.FLOAT),
    ACOS(14, 1, NumericKind.FLOAT),
    ATAN(15, 1, NumericKind.FLOAT),
    ATAN2(16, 2, NumericKind.FLOAT),
    SINH(17, 1, NumericKind.FLOAT),
    COSH(18, 1, NumericKind.FLOAT),
    TANH(19, 1, NumericKind.FLOAT),

    // 対数・指数・hypot・符号と、NaN・無限大の判定（第3.11d段階）。Float は Float のまま計算する（Kotlin の Float 版と同じ）
    LN(20, 1, NumericKind.FLOAT),
    LOG10(21, 1, NumericKind.FLOAT),
    EXP(22, 1, NumericKind.FLOAT),
    HYPOT(23, 2, NumericKind.FLOAT),
    SIGN(24, 1, NumericKind.FLOAT),
    IS_NAN(25, 1, NumericKind.FLOAT, MathResult.BOOLEAN),
    IS_INFINITE(26, 1, NumericKind.FLOAT, MathResult.BOOLEAN),

    // isClose(a, b, tolerance)（第3.11e段階）: |a - b| <= tolerance × max(1, |a|, |b|) を誤差なく判定する。tolerance の負数・NaN・Infinity は実行時エラー
    IS_CLOSE(27, 3, NumericKind.FLOAT, MathResult.BOOLEAN),
    ;

    fun operand(kind: Int) = id * NumericKind.COUNT + kind

    companion object {
        // operand の関数。負数・未知の番号・その関数に使えない数値の型は null（数値の型は operand % NumericKind.COUNT）
        fun byOperand(operand: Int): MathFunction? {
            if (operand < 0) return null
            val function = entries.firstOrNull { it.id == operand / NumericKind.COUNT } ?: return null
            return if (operand % NumericKind.COUNT >= function.firstKind) function else null
        }
    }
}

// LINALG 命令の関数（第3.11e段階）。id はバイトコードに書き込まれるため、一度決めたら変更・再利用しない。arity は取り出す値の数
// （VECTOR_OF・MATRIX_OF は operand の個数）。operand = id × 32 + 個数（個数を使うのは VECTOR_OF・MATRIX_OF だけで、それ以外は 0）
enum class LinalgFunction(val id: Int, val arity: Int) {
    // vector(x1, …, xn): Double n 個（1〜16）から Vector
    VECTOR_OF(0, -1),

    // vector(list): List<Double> の要素をコピーした Vector（要素数 1〜16 は実行時に確かめる）
    VECTOR_OF_LIST(1, 1),
    ADD(2, 2),
    SUBTRACT(3, 2),
    NEGATE(4, 1),

    // v * s・s * v・v / s
    TIMES(5, 2),
    TIMES_LEFT(6, 2),
    DIVIDE(7, 2),
    DOT(8, 2),
    LENGTH(9, 1),
    NORMALIZE(10, 1),

    // matrix(row1, …, rowr): Vector r 個（1〜8。長さが同じで 1〜8）から Matrix（行の Vector は共有する）
    MATRIX_OF(11, -1),
    MATRIX_ADD(12, 2),
    MATRIX_SUBTRACT(13, 2),

    // m * s・s * m
    MATRIX_TIMES(14, 2),
    MATRIX_TIMES_LEFT(15, 2),
    TRANSPOSE(16, 1),

    // matmul(a, b)・matmul(m, v)
    MATMUL(17, 2),
    MATMUL_VECTOR(18, 2),

    // m.columnCount（計算の単位は 0 で、確定の 1 単位だけ）
    COLUMN_COUNT(19, 1),
    ;

    fun operand(count: Int = 0) = id * COUNT_RANGE + count

    companion object {
        const val COUNT_RANGE = 32

        // operand の関数と個数。負数・未知の番号・その関数に使えない個数は null
        fun byOperand(operand: Int): Pair<LinalgFunction, Int>? {
            if (operand < 0) return null
            val function = entries.firstOrNull { it.id == operand / COUNT_RANGE } ?: return null
            val count = operand % COUNT_RANGE
            val valid = when (function) {
                VECTOR_OF -> count in 1..VmLimits.MAX_VECTOR_SIZE
                MATRIX_OF -> count in 1..VmLimits.MAX_MATRIX_SIZE
                else -> count == 0
            }
            return if (valid) function to count else null
        }
    }

    // 取り出す値の数
    fun arityOf(count: Int) = if (arity < 0) count else arity
}

// MATH 命令の結果の型（第3.11d段階）。SAME は引数と同じ数値の型、INT は Int（roundToInt）、BOOLEAN は Boolean（isNaN・isInfinite）。
// バイトコードには書き込まない
enum class MathResult { SAME, INT, BOOLEAN }

// position は実行時エラーの報告に使うソース上の位置
data class Instruction(val opcode: Opcode, val operand: Int, val position: SourcePosition)

// 定数プールの要素（同じ型・同じ値は1つにまとめる: A10）。Float・Double（第3.11a段階）はビット列で持ち、ビットが同じものだけをまとめる
sealed interface Constant {
    data class IntConstant(val value: Int) : Constant
    data class StringConstant(val value: String) : Constant
    data class FloatConstant(val bits: Int) : Constant
    data class DoubleConstant(val bits: Long) : Constant
}

// 組み込み関数。id はバイトコードに書き込まれるため、一度決めたら変更・再利用しない
enum class HostFunction(val id: Int) {
    // println(Int) / println(String)。引数を1つ取り出して表示する
    PRINTLN(0),
    WAIT_TICKS(1),

    // println()。空行を表示する
    PRINTLN_EMPTY(2),

    // 乱数（第3.11e段階。VM ごとの SplitMix64 の状態を使う）。random() は [0, 1) の Double、random(min, max) は [min, max) の Double、
    // randomInt(min, max) は [min, max) の Int、randomSeed(seed) は状態を seed にする（Unit）
    RANDOM(3),
    RANDOM_RANGE(4),
    RANDOM_INT(5),
    RANDOM_SEED(6),
    ;

    companion object {
        fun byId(id: Int): HostFunction? = entries.firstOrNull { it.id == id }
    }
}

// NEW_LIST で作るリストの形（第3.10a段階）。element は要素の型、mutable は変更できるリスト（mutableListOf）か、size は最初の要素の数
data class ListShape(val element: SnapshotVerifier.SlotType, val mutable: Boolean, val size: Int)

// ユーザー定義関数（第3.8c段階）。entry は先頭の命令位置、localCount は呼び出しフレームのローカル変数の数（引数を含む）、
// parameterCount は引数の数（第3.8d段階。ローカル変数の先頭 parameterCount 個が引数）
data class FunctionInfo(val entry: Int, val localCount: Int, val parameterCount: Int = 0)

// クラス（第3.9a段階）。番号（CompiledProgram.classes の位置）は宣言の順。name はオブジェクトの表示（Robot@1）に使う。
// fields はプロパティ（第3.9b段階。宣言の順）、constructor はプロパティを初期化する関数（CompiledProgram.functions の番号。
// プロパティの無いクラスは -1）。コンストラクタは NEW で作ったオブジェクトをローカル変数 0 に受け取り、それを返す
data class ClassInfo(val name: String, val fields: List<FieldInfo> = emptyList(), val constructor: Int = -1)

// プロパティ（第3.9b段階）。type は値の型（Unit は使えない）。name はプログラムのハッシュに含める
data class FieldInfo(val name: String, val type: SnapshotVerifier.SlotType)

// コンパイル結果。Minecraft に依存しない。命令はトップレベルの文（HALT で終わる）の後に、関数を定義の順に並べる
data class CompiledProgram(
    val instructions: List<Instruction>,
    val constants: List<Constant>,
    // トップレベルの変数（大域変数）の数
    val variableCount: Int,
    // コンパイル時に計算したスタックの最大の深さ（呼び出しフレームごとの深さ。VM のスタックは呼び出しの連鎖に沿って積み重なり、
    // その合計が MAX_STACK_DEPTH を超えないことも Compiler が確かめる）
    val maxStackDepth: Int,
    val functions: List<FunctionInfo> = emptyList(),
    val classes: List<ClassInfo> = emptyList(),
    // NEW_LIST で作るリストの形（第3.10a段階。同じ形は1つにまとめる）
    val lists: List<ListShape> = emptyList(),
)
