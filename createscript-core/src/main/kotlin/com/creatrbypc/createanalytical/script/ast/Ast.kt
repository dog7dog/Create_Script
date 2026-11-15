package com.creatrbypc.createanalytical.script.ast

import com.creatrbypc.createanalytical.script.InternalCreateScriptApi
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition

// CreateScript の抽象構文木。型や Int32 範囲の検証は Semantic 段階で行うため、ここでは構文の形だけを表す
// functions はトップレベルの関数定義（定義の順。第3.8c段階）、classes はトップレベルのクラス宣言（宣言の順。第3.9a段階）。
// どちらも statements からは除き、定義そのものは実行しない。imports はファイルの先頭の import（宣言の順。重複は除く。第8.0段階）
@InternalCreateScriptApi
public data class Program(
    val statements: List<Statement>,
    val functions: List<FunctionDeclaration> = emptyList(),
    val classes: List<ClassDeclaration> = emptyList(),
    val imports: List<ImportDeclaration> = emptyList(),
) {
    // v0.1.0-alpha（imports の無い 3 つの項目の Program）のバイナリとの互換（S8.1 の検証で追加）。v0.1.0-alpha の Core に対してコンパイルしたコードが呼ぶ
    // Program(List, List, List)・その既定値付きの合成コンストラクター・copy(List, List, List)・copy$default を JVM に残す。
    // ソースからは見えない（HIDDEN）ため、新しい呼び出しと曖昧にならない。equals・hashCode・toString・データクラスの copy は 4 つの項目のまま。
    // 互換用の copy は imports を引き継ぐ（古いコードが新しい Parser の結果を写しても、imports を失わない）
    @Deprecated("Binary compatibility with v0.1.0-alpha", level = DeprecationLevel.HIDDEN)
    public constructor(
        statements: List<Statement>,
        functions: List<FunctionDeclaration> = emptyList(),
        classes: List<ClassDeclaration> = emptyList(),
    ) : this(statements, functions, classes, emptyList())

    @Deprecated("Binary compatibility with v0.1.0-alpha", level = DeprecationLevel.HIDDEN)
    public fun copy(
        statements: List<Statement> = this.statements,
        functions: List<FunctionDeclaration> = this.functions,
        classes: List<ClassDeclaration> = this.classes,
    ): Program = Program(statements, functions, classes, imports)
}

// import モジュール名（第8.0段階）。ファイルの先頭（他の文・宣言より前）にだけ書ける。モジュール名は識別子 1 つ。
// position は import の位置、namePosition はモジュール名の位置
@InternalCreateScriptApi
public data class ImportDeclaration(val name: String, val namePosition: SourcePosition, val position: SourcePosition)

// 呼び出しフレームを作って実行する単位（第3.9b段階）。関数・メソッドと、プロパティを持つクラスのコンストラクタ（クラスの宣言で表す）
@InternalCreateScriptApi
public sealed interface CallableDeclaration

// class 名前 [{ メンバー }]（第3.9a段階。メンバーは第3.9b段階）。トップレベルでのみ宣言できる。
// properties はプロパティ（val / var。宣言の順で、インスタンスの生成時にこの順に初期化する）、methods はメソッド（定義の順）。
// position は class キーワード、namePosition はクラス名の位置
@InternalCreateScriptApi
public data class ClassDeclaration(
    val name: String,
    val namePosition: SourcePosition,
    val position: SourcePosition,
    val properties: List<VariableDeclaration> = emptyList(),
    val methods: List<FunctionDeclaration> = emptyList(),
) : CallableDeclaration

// fun 名前(引数: 型, …): 戻り値の型 { 本体 }（第3.8c段階。引数・戻り値は第3.8d段階）。トップレベルの関数と、クラスのメソッド（第3.9b段階）。
// returnType が null なら Unit。position は fun キーワード、namePosition は関数名の位置
@InternalCreateScriptApi
public data class FunctionDeclaration(
    val name: String,
    val namePosition: SourcePosition,
    val parameters: List<Parameter>,
    val returnType: TypeReference?,
    val body: Block,
    val position: SourcePosition,
) : CallableDeclaration

// 関数の引数（第3.8d段階）。型の注釈は必須。position は引数名の位置
@InternalCreateScriptApi
public data class Parameter(val name: String, val type: TypeReference, val position: SourcePosition)

@InternalCreateScriptApi
public sealed interface Statement {
    public val position: SourcePosition
}

// position は val / var キーワードの位置、namePosition は変数名の位置
@InternalCreateScriptApi
public data class VariableDeclaration(
    val isMutable: Boolean,
    val name: String,
    val namePosition: SourcePosition,
    val type: TypeReference?,
    val initializer: Expression,
    override val position: SourcePosition,
) : Statement

// 代入先は変数名（NameReference）か、プロパティ（MemberAccessExpression: 第3.9b段階）か、リストの要素（IndexExpression: 第3.10a段階）。
// position は代入先の名前（要素は "["）の位置
@InternalCreateScriptApi
public data class Assignment(val target: Expression, val value: Expression, override val position: SourcePosition) : Statement

@InternalCreateScriptApi
public data class ExpressionStatement(val expression: Expression, override val position: SourcePosition) : Statement

// { } で囲んだ文の並び。1つのスコープになる（第3.7c段階）。position は "{" の位置
@InternalCreateScriptApi
public data class Block(val statements: List<Statement>, val position: SourcePosition)

// if の条件と、条件が true のときに実行するブロック。position は if キーワードの位置
@InternalCreateScriptApi
public data class IfBranch(val condition: Expression, val body: Block, val position: SourcePosition)

// if / else if / else。else if の連鎖は branches に平らに並べ、入れ子の深さを使わない（11.4）。if は文のみで、値を持たない
@InternalCreateScriptApi
public data class IfStatement(val branches: List<IfBranch>, val elseBlock: Block?, override val position: SourcePosition) : Statement

// while (条件) { 本体 }（第3.7d段階）。文のみ。本体のブロックは反復ごとに新しいスコープになる。position は while キーワードの位置
@InternalCreateScriptApi
public data class WhileStatement(val condition: Expression, val body: Block, override val position: SourcePosition) : Statement

// 整数範囲の for の範囲（第3.8b段階）。RANGE_TO は "..", UNTIL は "until"（終端を含まない）, DOWN_TO は "downTo"（降順）
@InternalCreateScriptApi
public enum class RangeKind { RANGE_TO, UNTIL, DOWN_TO }

// for (変数 in 始点 範囲 終点) { 本体 }（第3.8b段階）。文のみ。変数は Int の読み取り専用で、for の中だけで見える。
// position は for キーワード、variablePosition は変数名、rangePosition は範囲の演算子の位置
@InternalCreateScriptApi
public data class ForStatement(
    val variable: String,
    val variablePosition: SourcePosition,
    val start: Expression,
    val kind: RangeKind,
    val rangePosition: SourcePosition,
    val end: Expression,
    val body: Block,
    override val position: SourcePosition,
) : Statement

// break / continue（第3.8a段階）。最も内側の while を対象にする文（式としては使えない・ラベルなし）。position はキーワードの位置
@InternalCreateScriptApi
public data class BreakStatement(override val position: SourcePosition) : Statement

@InternalCreateScriptApi
public data class ContinueStatement(override val position: SourcePosition) : Statement

// return [値]（第3.8c段階。値は第3.8d段階）。関数を途中で終えて呼び出し元へ戻る。value が null なら Unit を返す。position はキーワードの位置
@InternalCreateScriptApi
public data class ReturnStatement(val value: Expression?, override val position: SourcePosition) : Statement

// 型注釈の名前（Int / String / Boolean かどうかは Semantic 段階で判定する）。arguments は型引数（List<Int> の Int: 第3.10a段階）
@InternalCreateScriptApi
public data class TypeReference(val name: String, val position: SourcePosition, val arguments: List<TypeReference> = emptyList())

@InternalCreateScriptApi
public sealed interface Expression {
    public val position: SourcePosition
}

// value は符号を含まない 0〜9,999,999,999。-2147483648 は UnaryExpression(MINUS, IntLiteral(2147483648)) で表す
@InternalCreateScriptApi
public data class IntLiteral(val value: Long, override val position: SourcePosition) : Expression

// 浮動小数点数のリテラル（第3.11a段階）。値は Lexer が丸めた値で、符号を含まない（-1.5 は UnaryExpression(MINUS, DoubleLiteral(1.5))）
@InternalCreateScriptApi
public data class FloatLiteral(val value: Float, override val position: SourcePosition) : Expression

@InternalCreateScriptApi
public data class DoubleLiteral(val value: Double, override val position: SourcePosition) : Expression

@InternalCreateScriptApi
public data class StringLiteral(val value: String, override val position: SourcePosition) : Expression

@InternalCreateScriptApi
public data class BooleanLiteral(val value: Boolean, override val position: SourcePosition) : Expression

@InternalCreateScriptApi
public data class NameReference(val name: String, override val position: SourcePosition) : Expression

// this（第3.9b段階）。メソッドの本体・プロパティの初期化式の中で、そのインスタンスを表す
@InternalCreateScriptApi
public data class ThisExpression(override val position: SourcePosition) : Expression

// NOT は "!"。前置の "!!x" は NOT を2つ重ねたもの（!(!x)）として表す
@InternalCreateScriptApi
public enum class UnaryOperator { PLUS, MINUS, NOT }

// position は演算子の位置
@InternalCreateScriptApi
public data class UnaryExpression(val operator: UnaryOperator, val operand: Expression, override val position: SourcePosition) : Expression

// 四則演算・剰余と、比較（== != < <= > >=）
@InternalCreateScriptApi
public enum class BinaryOperator { ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER, EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL }

// position は演算子の位置
@InternalCreateScriptApi
public data class BinaryExpression(
    val operator: BinaryOperator,
    val left: Expression,
    val right: Expression,
    override val position: SourcePosition,
) : Expression

@InternalCreateScriptApi
public enum class LogicalOperator { AND, OR }

// && と ||。右辺は必要なときだけ評価する（短絡評価）ため、BinaryExpression と分ける。position は演算子の位置
@InternalCreateScriptApi
public data class LogicalExpression(
    val operator: LogicalOperator,
    val left: Expression,
    val right: Expression,
    override val position: SourcePosition,
) : Expression

// println(1) は callee = NameReference、gear.start() は callee = MemberAccessExpression。position は callee の位置。
// typeArguments は名前の後の型引数（listOf<Int>() の Int: 第3.10a段階）
@InternalCreateScriptApi
public data class CallExpression(
    val callee: Expression,
    val arguments: List<Expression>,
    override val position: SourcePosition,
    val typeArguments: List<TypeReference> = emptyList(),
) : Expression

// position はメンバー名の位置
@InternalCreateScriptApi
public data class MemberAccessExpression(val receiver: Expression, val name: String, override val position: SourcePosition) : Expression

// リストの要素 list[index]（第3.10a段階）。position は "[" の位置
@InternalCreateScriptApi
public data class IndexExpression(val receiver: Expression, val index: Expression, override val position: SourcePosition) : Expression

// ラムダの引数（第9.0段階）。type は型の注釈（省略すると呼び出し先の文脈から推論する: 第9.3段階）
@InternalCreateScriptApi
public data class LambdaParameter(val name: String, val type: TypeReference?, val position: SourcePosition)

// ラムダ "{" [ 引数 { "," 引数 } "->" ] 文の並び "}"（第9.0段階）。hasArrow が false で引数が 1 つ求められる文脈では、暗黙の引数 it を使う。
// 最後の文が式の文なら、その値がラムダの結果になる。ラムダはその場で展開する（インライン）ため、値として保存・返却はできない。position は "{" の位置
@InternalCreateScriptApi
public data class LambdaExpression(
    val parameters: List<LambdaParameter>,
    val hasArrow: Boolean,
    val body: List<Statement>,
    override val position: SourcePosition,
) : Expression

// 関数の参照 ::名前（第9.3段階）。トップレベルの関数（モジュールの関数を含む）だけ。position は "::" の位置
@InternalCreateScriptApi
public data class FunctionReference(val name: String, val namePosition: SourcePosition, override val position: SourcePosition) : Expression

// パイプライン 左 |> 右（第9.1段階）。右は 名前・名前(引数…)・名前 { ラムダ }。左を最初の引数（リストの操作は受け手）にした呼び出しになる。position は "|>" の位置
@InternalCreateScriptApi
public data class PipeExpression(val left: Expression, val right: Expression, override val position: SourcePosition) : Expression

// エラーフォールバック 左 ?! 右（第9.2段階）。左の評価中に回復できる実行時エラーが起きたら、それまでの途中の値を捨てて右を評価する。
// 右結合（a ?! b ?! c は a ?! (b ?! c)）。position は "?!" の位置
@InternalCreateScriptApi
public data class FallbackExpression(val left: Expression, val right: Expression, override val position: SourcePosition) : Expression

// 関数の型 (A, B) -> R（第9.3段階）を表す TypeReference の名前。arguments は引数の型の後に結果の型。ソースの型の名前（識別子）とは重ならない
@InternalCreateScriptApi
public const val FUNCTION_TYPE_NAME: String = "->"
