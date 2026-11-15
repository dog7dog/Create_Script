package com.creatrbypc.createanalytical.script.diagnostic

/**
 * The kinds of diagnostics. [id] (`CS_...`) is the stable identifier for hosts, logs and translation keys: an id is
 * never changed or reused for another meaning. New codes are added in later releases, so a `when` over this type
 * needs an `else` branch. `docs/language.md` and `docs/cli.md` explain some of them.
 *
 * @property id the stable identifier, for example `CS_SEM_UNDECLARED_VARIABLE`.
 */
public enum class DiagnosticCode(public val id: String) {
    LEX_SOURCE_TOO_LONG("CS_LEX_SOURCE_TOO_LONG"),
    LEX_INVALID_SURROGATE("CS_LEX_INVALID_SURROGATE"),
    LEX_FORBIDDEN_CONTROL_CHARACTER("CS_LEX_FORBIDDEN_CONTROL_CHARACTER"),
    LEX_BYTE_ORDER_MARK("CS_LEX_BYTE_ORDER_MARK"),
    LEX_BIDI_CONTROL_CHARACTER("CS_LEX_BIDI_CONTROL_CHARACTER"),
    LEX_LONE_CARRIAGE_RETURN("CS_LEX_LONE_CARRIAGE_RETURN"),
    LEX_INVALID_CHARACTER("CS_LEX_INVALID_CHARACTER"),
    LEX_IDENTIFIER_TOO_LONG("CS_LEX_IDENTIFIER_TOO_LONG"),
    LEX_UNDERSCORE_ONLY_IDENTIFIER("CS_LEX_UNDERSCORE_ONLY_IDENTIFIER"),
    LEX_BACKTICK_IDENTIFIER_UNSUPPORTED("CS_LEX_BACKTICK_IDENTIFIER_UNSUPPORTED"),
    LEX_LEADING_ZERO("CS_LEX_LEADING_ZERO"),
    LEX_UNSUPPORTED_NUMBER_FORMAT("CS_LEX_UNSUPPORTED_NUMBER_FORMAT"),
    LEX_INTEGER_TOO_MANY_DIGITS("CS_LEX_INTEGER_TOO_MANY_DIGITS"),
    LEX_UNTERMINATED_STRING("CS_LEX_UNTERMINATED_STRING"),
    LEX_UNSUPPORTED_ESCAPE("CS_LEX_UNSUPPORTED_ESCAPE"),
    LEX_STRING_TEMPLATE_UNSUPPORTED("CS_LEX_STRING_TEMPLATE_UNSUPPORTED"),
    LEX_STRING_CONTROL_CHARACTER("CS_LEX_STRING_CONTROL_CHARACTER"),
    LEX_STRING_TOO_LONG("CS_LEX_STRING_TOO_LONG"),
    LEX_RAW_STRING_UNSUPPORTED("CS_LEX_RAW_STRING_UNSUPPORTED"),
    LEX_CHAR_LITERAL_UNSUPPORTED("CS_LEX_CHAR_LITERAL_UNSUPPORTED"),
    LEX_BLOCK_COMMENT_UNSUPPORTED("CS_LEX_BLOCK_COMMENT_UNSUPPORTED"),
    LEX_TOO_MANY_TOKENS("CS_LEX_TOO_MANY_TOKENS"),

    // 浮動小数点数のリテラル（第3.11a段階）。指数の数字が無い（1e・1e+）、綴りが LexerLimits.MAX_FLOAT_LITERAL_LENGTH を超える（引数: 長さ、上限）、
    // 値が無限大に丸められる・0 でない値が 0 に丸められる（Float は f の付いたリテラル）。小数点の後に数字の無い 1. は整数と "."、
    // f 以外のサフィックス（1d・1.5L）は既存の CS_LEX_UNSUPPORTED_NUMBER_FORMAT、整数部の先頭の 0（01.5）は CS_LEX_LEADING_ZERO
    LEX_MALFORMED_EXPONENT("CS_LEX_MALFORMED_EXPONENT"),
    LEX_FLOAT_LITERAL_TOO_LONG("CS_LEX_FLOAT_LITERAL_TOO_LONG"),
    LEX_FLOAT_LITERAL_OUT_OF_RANGE("CS_LEX_FLOAT_LITERAL_OUT_OF_RANGE"),

    PARSE_EXPECTED_EXPRESSION("CS_PARSE_EXPECTED_EXPRESSION"),
    PARSE_EXPECTED_IDENTIFIER("CS_PARSE_EXPECTED_IDENTIFIER"),
    PARSE_EXPECTED_TYPE("CS_PARSE_EXPECTED_TYPE"),
    PARSE_EXPECTED_INITIALIZER("CS_PARSE_EXPECTED_INITIALIZER"),
    PARSE_EXPECTED_MEMBER_NAME("CS_PARSE_EXPECTED_MEMBER_NAME"),
    PARSE_EXPECTED_RIGHT_PAREN("CS_PARSE_EXPECTED_RIGHT_PAREN"),
    PARSE_EXPECTED_STATEMENT_END("CS_PARSE_EXPECTED_STATEMENT_END"),
    PARSE_EMPTY_STATEMENT("CS_PARSE_EMPTY_STATEMENT"),
    PARSE_LAMBDA_UNSUPPORTED("CS_PARSE_LAMBDA_UNSUPPORTED"),
    PARSE_UNSUPPORTED_OPERATOR("CS_PARSE_UNSUPPORTED_OPERATOR"),
    PARSE_UNSUPPORTED_KEYWORD("CS_PARSE_UNSUPPORTED_KEYWORD"),
    PARSE_INVALID_ASSIGNMENT_TARGET("CS_PARSE_INVALID_ASSIGNMENT_TARGET"),
    PARSE_NESTING_TOO_DEEP("CS_PARSE_NESTING_TOO_DEEP"),

    // if 文・while 文とブロック（第3.7c・3.7d段階）。if / while の後の "(" が無い、ブロックの "{" が無い（D4: {} 必須）・"}" が無い、対応する "{" の無い "}"、対応する if の無い else
    PARSE_EXPECTED_LEFT_PAREN("CS_PARSE_EXPECTED_LEFT_PAREN"),
    PARSE_EXPECTED_LEFT_BRACE("CS_PARSE_EXPECTED_LEFT_BRACE"),
    PARSE_EXPECTED_RIGHT_BRACE("CS_PARSE_EXPECTED_RIGHT_BRACE"),
    PARSE_UNEXPECTED_RIGHT_BRACE("CS_PARSE_UNEXPECTED_RIGHT_BRACE"),
    PARSE_UNEXPECTED_ELSE("CS_PARSE_UNEXPECTED_ELSE"),

    // 整数範囲の for（第3.8b段階）。"for (" の後に変数名が無い、変数名の後に in が無い、始点の後に ".." / until / downTo が無い
    // （コレクション・step 単独・..< などは未対応）
    PARSE_EXPECTED_LOOP_VARIABLE("CS_PARSE_EXPECTED_LOOP_VARIABLE"),
    PARSE_EXPECTED_IN("CS_PARSE_EXPECTED_IN"),
    PARSE_EXPECTED_RANGE("CS_PARSE_EXPECTED_RANGE"),
    PARSE_TOO_MANY_NODES("CS_PARSE_TOO_MANY_NODES"),

    // ユーザー定義関数（第3.8c段階）。ブロック・関数の中の fun は未対応。
    // 第3.8c段階の CS_PARSE_FUNCTION_PARAMETERS_UNSUPPORTED / CS_PARSE_FUNCTION_RETURN_TYPE_UNSUPPORTED / CS_PARSE_RETURN_VALUE_UNSUPPORTED は
    // 第3.8d段階で引数・戻り値に対応したため廃止（id は再利用しない）
    PARSE_NESTED_FUNCTION_UNSUPPORTED("CS_PARSE_NESTED_FUNCTION_UNSUPPORTED"),

    // クラス（第3.9a段階）。ブロック・関数の中の class、主コンストラクタ・継承・型引数（class A(…) / class A : B / class A<T>）は未対応。
    // CS_PARSE_CLASS_MEMBERS_UNSUPPORTED は、第3.9a段階では本体の中身すべて、プロパティ・メソッドに対応した第3.9b段階からは
    // それ以外のメンバー（init ブロック・修飾子・コンストラクタ・文など。位置はメンバーの先頭）
    PARSE_NESTED_CLASS_UNSUPPORTED("CS_PARSE_NESTED_CLASS_UNSUPPORTED"),
    PARSE_CLASS_HEADER_UNSUPPORTED("CS_PARSE_CLASS_HEADER_UNSUPPORTED"),
    PARSE_CLASS_MEMBERS_UNSUPPORTED("CS_PARSE_CLASS_MEMBERS_UNSUPPORTED"),

    // リスト（第3.10a段階）。添字の後の "]" が無い
    PARSE_EXPECTED_RIGHT_BRACKET("CS_PARSE_EXPECTED_RIGHT_BRACKET"),

    // ローカル import（第8.0段階）。import の後がモジュール名（識別子 1 つ）でない・モジュール名の後に "." / as などが続く（位置はそのトークン）、
    // ファイルの先頭以外（他の文・宣言の後、ブロックの中）の import（位置は import）、同じファイルの同じ import（警告。引数: モジュール名）、
    // import したモジュールの名前と同じ名前の宣言（変数・引数・ループ変数・関数・クラス・メンバー。引数: 名前）、
    // モジュール名.関数名(…) 以外の形で使ったモジュール名（値として使う・関数以外のメンバー。引数: モジュール名）
    PARSE_INVALID_IMPORT("CS_PARSE_INVALID_IMPORT"),
    PARSE_MISPLACED_IMPORT("CS_PARSE_MISPLACED_IMPORT"),
    PARSE_DUPLICATE_IMPORT("CS_PARSE_DUPLICATE_IMPORT"),
    PARSE_IMPORT_NAME_CONFLICT("CS_PARSE_IMPORT_NAME_CONFLICT"),
    PARSE_INVALID_MODULE_REFERENCE("CS_PARSE_INVALID_MODULE_REFERENCE"),

    SEM_UNDECLARED_VARIABLE("CS_SEM_UNDECLARED_VARIABLE"),
    SEM_DUPLICATE_DECLARATION("CS_SEM_DUPLICATE_DECLARATION"),
    SEM_BUILTIN_REDECLARATION("CS_SEM_BUILTIN_REDECLARATION"),
    SEM_VAL_REASSIGNMENT("CS_SEM_VAL_REASSIGNMENT"),
    SEM_UNKNOWN_TYPE("CS_SEM_UNKNOWN_TYPE"),
    SEM_TYPE_MISMATCH("CS_SEM_TYPE_MISMATCH"),
    SEM_INVALID_OPERAND_TYPE("CS_SEM_INVALID_OPERAND_TYPE"),

    // == != の両辺の型が違う（引数: 演算子、左辺の型、右辺の型）
    SEM_INCOMPARABLE_TYPES("CS_SEM_INCOMPARABLE_TYPES"),

    // if / while の条件が Boolean でない（引数: 条件の型）。Int などからの暗黙の変換は無い
    SEM_CONDITION_NOT_BOOLEAN("CS_SEM_CONDITION_NOT_BOOLEAN"),

    // while の外の break / continue（第3.8a段階。位置はキーワード）
    SEM_BREAK_OUTSIDE_LOOP("CS_SEM_BREAK_OUTSIDE_LOOP"),
    SEM_CONTINUE_OUTSIDE_LOOP("CS_SEM_CONTINUE_OUTSIDE_LOOP"),
    SEM_INTEGER_OUT_OF_RANGE("CS_SEM_INTEGER_OUT_OF_RANGE"),
    SEM_UNIT_VALUE_NOT_ALLOWED("CS_SEM_UNIT_VALUE_NOT_ALLOWED"),
    SEM_MEMBER_ACCESS_UNSUPPORTED("CS_SEM_MEMBER_ACCESS_UNSUPPORTED"),
    SEM_UNKNOWN_FUNCTION("CS_SEM_UNKNOWN_FUNCTION"),
    SEM_NOT_CALLABLE("CS_SEM_NOT_CALLABLE"),
    SEM_INVALID_CALLEE("CS_SEM_INVALID_CALLEE"),
    SEM_FUNCTION_AS_VALUE("CS_SEM_FUNCTION_AS_VALUE"),
    SEM_WRONG_ARGUMENT_COUNT("CS_SEM_WRONG_ARGUMENT_COUNT"),
    SEM_ARGUMENT_TYPE_MISMATCH("CS_SEM_ARGUMENT_TYPE_MISMATCH"),
    SEM_UNUSED_EXPRESSION("CS_SEM_UNUSED_EXPRESSION"),

    // ユーザー定義関数（第3.8c段階）。同名の関数の重複定義（引数: 関数名）、変数名と関数名の衝突（引数: 名前。位置は変数名）、
    // 関数の外の return、関数が使う変数がその呼び出しより前に宣言されていない（引数: 関数名、変数名。位置は呼び出し）。
    // 第3.8c段階の CS_SEM_RECURSIVE_CALL（再帰の禁止）は第3.8e段階で再帰に対応したため廃止（id は再利用しない）
    SEM_DUPLICATE_FUNCTION("CS_SEM_DUPLICATE_FUNCTION"),
    SEM_FUNCTION_NAME_CONFLICT("CS_SEM_FUNCTION_NAME_CONFLICT"),
    SEM_RETURN_OUTSIDE_FUNCTION("CS_SEM_RETURN_OUTSIDE_FUNCTION"),
    SEM_CALL_BEFORE_VARIABLE_DECLARATION("CS_SEM_CALL_BEFORE_VARIABLE_DECLARATION"),

    // 戻り値（第3.8d段階）。値を返す関数での値の無い return（引数: 関数名、戻り値の型。位置は return）、
    // 値を返す関数の本体の終わりに return 無しで到達しうる（引数: 関数名、戻り値の型。位置は関数名）。
    // 戻り値・引数の型の不一致は既存の CS_SEM_TYPE_MISMATCH・CS_SEM_ARGUMENT_TYPE_MISMATCH、Unit 型の引数は CS_SEM_UNIT_VALUE_NOT_ALLOWED
    SEM_RETURN_WITHOUT_VALUE("CS_SEM_RETURN_WITHOUT_VALUE"),
    SEM_MISSING_RETURN("CS_SEM_MISSING_RETURN"),

    // クラス（第3.9a段階）。同名のクラスの重複宣言（引数: クラス名。位置は 2 つ目の名前）、クラス名と関数名・組み込みの型名・変数名
    // （引数・ループ変数を含む）の衝突（引数: 名前。位置は関数名・変数名、組み込みの型名との衝突はクラス名）、クラス名を値として使う（引数: クラス名）。
    // 未定義のクラス名の型は既存の CS_SEM_UNKNOWN_TYPE、未定義のクラスの生成は CS_SEM_UNKNOWN_FUNCTION、別のクラスどうしの == は CS_SEM_INCOMPARABLE_TYPES
    SEM_DUPLICATE_CLASS("CS_SEM_DUPLICATE_CLASS"),
    SEM_CLASS_NAME_CONFLICT("CS_SEM_CLASS_NAME_CONFLICT"),
    SEM_CLASS_AS_VALUE("CS_SEM_CLASS_AS_VALUE"),

    // プロパティ・メソッド（第3.9b段階）。同じクラスの中のプロパティ・メソッドの名前の重複（引数: クラス名、名前。位置は 2 つ目の名前）、
    // 存在しないプロパティ・メソッド（引数: クラス名、名前。位置はメンバー名）、初期化式からの初期化前のプロパティ（自分自身・後に宣言したもの）の
    // 読み取り（引数: プロパティ名）、クラスの外の this、初期化式からのトップレベルの変数の参照（引数: 変数名）、
    // 型の注釈の無いプロパティの型が自分自身に依存して決まらない（引数: クラス名、プロパティ名。位置は依存が循環した参照）、
    // プロパティの初期化で同じクラスの生成が必ず繰り返される（引数: クラス名。位置はクラス名）。
    // val の再代入・型の不一致・引数の個数と型・戻り値は既存の CS_SEM_VAL_REASSIGNMENT・CS_SEM_TYPE_MISMATCH・CS_SEM_WRONG_ARGUMENT_COUNT・
    // CS_SEM_ARGUMENT_TYPE_MISMATCH・CS_SEM_RETURN_WITHOUT_VALUE・CS_SEM_MISSING_RETURN、() の無いメソッドは CS_SEM_FUNCTION_AS_VALUE、
    // プロパティの呼び出しは CS_SEM_NOT_CALLABLE、クラス以外の値のメンバーは CS_SEM_MEMBER_ACCESS_UNSUPPORTED
    SEM_DUPLICATE_MEMBER("CS_SEM_DUPLICATE_MEMBER"),
    SEM_UNKNOWN_PROPERTY("CS_SEM_UNKNOWN_PROPERTY"),
    SEM_UNKNOWN_METHOD("CS_SEM_UNKNOWN_METHOD"),
    SEM_UNINITIALIZED_PROPERTY("CS_SEM_UNINITIALIZED_PROPERTY"),
    SEM_THIS_OUTSIDE_CLASS("CS_SEM_THIS_OUTSIDE_CLASS"),
    SEM_TOP_LEVEL_VARIABLE_IN_INITIALIZER("CS_SEM_TOP_LEVEL_VARIABLE_IN_INITIALIZER"),
    SEM_RECURSIVE_PROPERTY_TYPE("CS_SEM_RECURSIVE_PROPERTY_TYPE"),
    SEM_CYCLIC_CONSTRUCTION("CS_SEM_CYCLIC_CONSTRUCTION"),

    // リスト（第3.10a段階）。読み取り専用のリスト（List）の変更（add・removeAt・clear・要素への代入。引数: 型。位置はメンバー名・"["）、
    // 型を決められない空のリスト（引数: 関数名。listOf<Int>() のように型引数が必要）、型引数の数の誤り（引数: 型・関数の名前、型引数の数）、
    // 要素の型の混在・不一致は既存の CS_SEM_TYPE_MISMATCH・CS_SEM_ARGUMENT_TYPE_MISMATCH（入れ子のリストは第3.10b段階で使えるようになり、専用の診断は削除）、
    // Int 以外の添字は CS_SEM_ARGUMENT_TYPE_MISMATCH、リスト以外の添字は CS_SEM_INVALID_OPERAND_TYPE、size への代入は CS_SEM_VAL_REASSIGNMENT
    SEM_READ_ONLY_LIST("CS_SEM_READ_ONLY_LIST"),
    SEM_LIST_TYPE_REQUIRED("CS_SEM_LIST_TYPE_REQUIRED"),
    SEM_WRONG_TYPE_ARGUMENT_COUNT("CS_SEM_WRONG_TYPE_ARGUMENT_COUNT"),

    COMP_TOO_MANY_INSTRUCTIONS("CS_COMP_TOO_MANY_INSTRUCTIONS"),
    COMP_TOO_MANY_CONSTANTS("CS_COMP_TOO_MANY_CONSTANTS"),
    COMP_TOO_MANY_VARIABLES("CS_COMP_TOO_MANY_VARIABLES"),
    COMP_STACK_TOO_DEEP("CS_COMP_STACK_TOO_DEEP"),

    // 関数の呼び出しの連鎖が VmLimits.MAX_CALL_DEPTH を超える（第3.8c段階。引数: 上限。位置は連鎖の先頭の関数名）
    COMP_CALL_TOO_DEEP("CS_COMP_CALL_TOO_DEEP"),

    // 関数の引数の数が VmLimits.MAX_PARAMETERS を超える（第3.8d段階。引数: 上限。位置は上限を超えた最初の引数名）
    COMP_TOO_MANY_PARAMETERS("CS_COMP_TOO_MANY_PARAMETERS"),

    // クラスのプロパティの数が VmLimits.MAX_PROPERTIES を超える（第3.9b段階。引数: 上限。位置は上限を超えた最初のプロパティ名）
    COMP_TOO_MANY_PROPERTIES("CS_COMP_TOO_MANY_PROPERTIES"),

    // Compiler が出したバイトコードが SnapshotVerifier の検証に通らない（Compiler の内部エラー。実行可能なプログラムを返さない）
    COMP_VERIFICATION_FAILED("CS_COMP_VERIFICATION_FAILED"),

    RUN_INTEGER_OVERFLOW("CS_RUN_INTEGER_OVERFLOW"),
    RUN_DIVISION_BY_ZERO("CS_RUN_DIVISION_BY_ZERO"),
    RUN_NEGATIVE_WAIT("CS_RUN_NEGATIVE_WAIT"),
    RUN_STACK_OVERFLOW("CS_RUN_STACK_OVERFLOW"),

    // 関数の呼び出しの深さが VmLimits.MAX_CALL_DEPTH を超えた（第3.8e段階。無限・深すぎる再帰。引数: 上限。位置は呼び出し）
    RUN_CALL_DEPTH_EXCEEDED("CS_RUN_CALL_DEPTH_EXCEEDED"),

    // 同時に存在できるオブジェクトの数が VmLimits.MAX_OBJECTS を超えた（第3.9a段階。どこからも参照されないオブジェクトを回収しても空きが無い。
    // 引数: 上限。位置はインスタンスの生成）
    RUN_TOO_MANY_OBJECTS("CS_RUN_TOO_MANY_OBJECTS"),

    // 初期化前のプロパティを読んだ（第3.9b段階。初期化式から this を渡した関数・メソッドが、まだ初期化していないプロパティを読むなど。
    // 位置はプロパティの読み取り）
    RUN_UNINITIALIZED_PROPERTY("CS_RUN_UNINITIALIZED_PROPERTY"),

    // リスト（第3.10a段階）。範囲外の添字（負数・要素数以上・空のリストの removeAt。引数: 添字、要素数。位置は "[" か removeAt）、
    // 1 つのリストの要素数が VmLimits.MAX_LIST_SIZE を超える（引数: 上限）、VM 全体のリストの要素数が回収しても VmLimits.MAX_LIST_ELEMENTS を超える（引数: 上限）
    RUN_INDEX_OUT_OF_BOUNDS("CS_RUN_INDEX_OUT_OF_BOUNDS"),
    RUN_LIST_TOO_LARGE("CS_RUN_LIST_TOO_LARGE"),
    RUN_TOO_MANY_LIST_ELEMENTS("CS_RUN_TOO_MANY_LIST_ELEMENTS"),

    // 数学関数（第3.11b段階。Kotlin では例外になるもの）。最小値が最大値より大きい coerceIn（位置は coerceIn）、
    // NaN の roundToInt（位置は roundToInt）。引数は無い
    RUN_EMPTY_COERCE_RANGE("CS_RUN_EMPTY_COERCE_RANGE"),
    RUN_ROUND_NAN("CS_RUN_ROUND_NAN"),

    // 乱数・近似比較（第3.11e段階）。空・NaN・Infinity を含む乱数の範囲（位置は random / randomInt）、負数・NaN・Infinity の許容誤差（位置は isClose）。引数は無い
    RUN_INVALID_RANDOM_RANGE("CS_RUN_INVALID_RANDOM_RANGE"),
    RUN_INVALID_TOLERANCE("CS_RUN_INVALID_TOLERANCE"),

    // Vector・Matrix（第3.11e段階）。次元の違う演算（引数: Vector は左右の次元、Matrix を含むものは左の行数・列数と右の行数・列数（Vector は次元だけ）。
    // 位置は演算子・関数の名前）、vector(list) の要素数・matrix の列数が 0 か上限より多い（引数: 要素数、上限）、長さ 0 の normalize（引数は無い）、
    // 行の長さがそろっていない matrix（引数: 最初の行の長さ、そろっていない行の番号、その長さ）
    RUN_DIMENSION_MISMATCH("CS_RUN_DIMENSION_MISMATCH"),
    RUN_INVALID_DIMENSION("CS_RUN_INVALID_DIMENSION"),
    RUN_ZERO_VECTOR("CS_RUN_ZERO_VECTOR"),
    RUN_RAGGED_MATRIX("CS_RUN_RAGGED_MATRIX"),
    RUN_INVALID_BYTECODE("CS_RUN_INVALID_BYTECODE"),

    // 保存した実行状態を復元しなかった理由（D14）。いずれも状態は INTERRUPTED になり、最初から実行し直さない
    RUN_RESTORE_CORRUPTED("CS_RUN_RESTORE_CORRUPTED"),
    RUN_RESTORE_UNSUPPORTED_VERSION("CS_RUN_RESTORE_UNSUPPORTED_VERSION"),
    RUN_RESTORE_MOVED("CS_RUN_RESTORE_MOVED"),
    RUN_RESTORE_NO_PROGRAM("CS_RUN_RESTORE_NO_PROGRAM"),
    RUN_RESTORE_SOURCE_CHANGED("CS_RUN_RESTORE_SOURCE_CHANGED"),
    RUN_RESTORE_COMPILE_FAILED("CS_RUN_RESTORE_COMPILE_FAILED"),
    RUN_RESTORE_PROGRAM_CHANGED("CS_RUN_RESTORE_PROGRAM_CHANGED"),
    RUN_RESTORE_INVALID_STATE("CS_RUN_RESTORE_INVALID_STATE"),

    // モジュールの解決（第8.0段階。位置は import のモジュール名）。引数はモジュール名（循環は "a -> b -> a" の形の経路、読めないものは理由も）。
    // 見つからない、名前が曖昧（大文字・小文字だけが違うファイルが複数ある）、プロジェクトのルートの外を指す（シンボリックリンクなど）、読めない、
    // 循環する import、モジュールの数が ModuleLimits.MAX_MODULES を超える（引数: モジュール名、上限）、組み込み関数と同じモジュール名、
    // モジュールを使えないコンパイル（モジュールの解決方法を渡さない CreateScript.compile(source)・対話モード）
    MODULE_NOT_FOUND("CS_MODULE_NOT_FOUND"),
    MODULE_AMBIGUOUS("CS_MODULE_AMBIGUOUS"),
    MODULE_OUTSIDE_PROJECT("CS_MODULE_OUTSIDE_PROJECT"),
    MODULE_UNREADABLE("CS_MODULE_UNREADABLE"),
    MODULE_CYCLE("CS_MODULE_CYCLE"),
    MODULE_TOO_MANY("CS_MODULE_TOO_MANY"),
    MODULE_BUILTIN_NAME("CS_MODULE_BUILTIN_NAME"),
    MODULE_IMPORT_UNAVAILABLE("CS_MODULE_IMPORT_UNAVAILABLE"),

    // すべてのファイルの長さ・トークン数の合計が CompileOptions.maxTotalSourceLength・maxTotalTokens を超える
    // （第8.0.1段階。位置は超えたモジュールの import。引数: モジュール名、"characters" か "tokens"、上限）
    MODULE_PROGRAM_TOO_LARGE("CS_MODULE_PROGRAM_TOO_LARGE"),

    // 型の注釈の無いプロパティの型を決めるための、別のプロパティの初期化式の解析の入れ子が CompileOptions.maxPropertyTypeChain を超える
    // （第8.0.1段階。独立版だけ。位置はプロパティ名、引数: プロパティ名、上限。型の注釈を書けば解消する）
    SEM_PROPERTY_TYPE_CHAIN_TOO_DEEP("CS_SEM_PROPERTY_TYPE_CHAIN_TOO_DEEP"),

    // バイトコードの検証の処理回数が CompileOptions.maxVerificationSteps を超える（第8.0.1段階。独立版だけ。位置は 1 行 1 列、引数: 上限）
    COMP_PROGRAM_TOO_COMPLEX("CS_COMP_PROGRAM_TOO_COMPLEX"),

    // モジュールのファイルに書けないもの（第8.0段階。モジュールに書けるのは import と関数だけ）。トップレベルの文（位置は文の先頭）・クラス（位置は class）
    MODULE_TOP_LEVEL_STATEMENT("CS_MODULE_TOP_LEVEL_STATEMENT"),
    MODULE_CLASS_UNSUPPORTED("CS_MODULE_CLASS_UNSUPPORTED"),

    // import できない範囲のモジュール（S8.2 Phase 4。ModuleAccess を渡すコンパイルだけ。位置は import のモジュール名、引数: モジュール名）。
    // 例: 直接の依存ではないパッケージのモジュール、パッケージの公開しないモジュール。末尾に足す（既存の値の順序を変えない）
    MODULE_NOT_VISIBLE("CS_MODULE_NOT_VISIBLE"),

    // 第9.0〜9.3段階（リストの関数・ラムダ・パイプライン・エラーフォールバック・型推論）。末尾に足す（既存の値の順序を変えない）
    // ラムダ・関数の参照・関数の値を書けない場所（ラムダは呼び出しの引数・val の初期化式・関数の値の呼び出しにだけ書ける）。引数は無い
    SEM_LAMBDA_NOT_ALLOWED("CS_SEM_LAMBDA_NOT_ALLOWED"),

    // ラムダの引数の数が、呼び出し先が渡す引数の数と違う（引数: 関数名、求められる数、ラムダの引数の数）
    SEM_LAMBDA_PARAMETER_COUNT("CS_SEM_LAMBDA_PARAMETER_COUNT"),

    // 文脈から型を推論できないラムダの引数（val f = { x -> … } など。引数: 引数名）
    SEM_LAMBDA_PARAMETER_TYPE_REQUIRED("CS_SEM_LAMBDA_PARAMETER_TYPE_REQUIRED"),

    // ラムダの中の return（ラムダはその場で展開するため、関数から戻る return と区別できない）
    SEM_RETURN_IN_LAMBDA("CS_SEM_RETURN_IN_LAMBDA"),

    // プロパティの初期化式の中のラムダ（初期化中のインスタンスの状態を保存データで確かめる規則を保つため）
    SEM_LAMBDA_IN_INITIALIZER("CS_SEM_LAMBDA_IN_INITIALIZER"),

    // 関数の値（ラムダ・関数の参照を入れた val）を、宣言した関数・メソッド（またはトップレベル）の外で使った（引数: 名前）
    SEM_FUNCTION_VALUE_OUTSIDE_SCOPE("CS_SEM_FUNCTION_VALUE_OUTSIDE_SCOPE"),

    // |> の右が 名前・名前(引数…)・名前 { ラムダ } のどれでもない
    SEM_INVALID_PIPE_TARGET("CS_SEM_INVALID_PIPE_TARGET"),

    // group のキーの型が Int・String・Boolean 以外（引数: 型）
    SEM_GROUP_KEY_TYPE("CS_SEM_GROUP_KEY_TYPE"),

    // 関数の型を書けない場所（val の型の注釈以外。関数の引数・戻り値・プロパティ・リストの要素など）
    SEM_FUNCTION_TYPE_NOT_ALLOWED("CS_SEM_FUNCTION_TYPE_NOT_ALLOWED"),

    // 関数の値の展開（インライン）が多すぎる・深すぎる（引数: 上限）
    COMP_TOO_MANY_INLINE_EXPANSIONS("CS_COMP_TOO_MANY_INLINE_EXPANSIONS"),
}
