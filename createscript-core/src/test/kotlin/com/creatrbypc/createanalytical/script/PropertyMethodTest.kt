package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.vm.ClassInfo
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.Console
import com.creatrbypc.createanalytical.script.vm.Constant
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.FieldInfo
import com.creatrbypc.createanalytical.script.vm.FunctionInfo
import com.creatrbypc.createanalytical.script.vm.Instruction
import com.creatrbypc.createanalytical.script.vm.ObjectSnapshot
import com.creatrbypc.createanalytical.script.vm.Opcode
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import com.creatrbypc.createanalytical.script.vm.SnapshotProblem
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier.SlotType
import com.creatrbypc.createanalytical.script.vm.Value
import com.creatrbypc.createanalytical.script.vm.VmLimits
import com.creatrbypc.createanalytical.script.vm.VmSnapshot
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

// 第3.9b段階のプロパティ・メソッド・this を、ソースから CS_VM・Scheduler・SnapshotVerifier まで通して確かめる
class PropertyMethodTest {
    private fun compile(source: String): CompiledProgram {
        val result = Compiler.compileSource(source)
        return result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
    }

    private fun codes(source: String): List<DiagnosticCode> {
        val result = Compiler.compileSource(source)
        assertNull(result.program, "program must be null when there are errors: $source")
        return result.diagnostics.map { it.code }
    }

    // 待機は1 tick ずつ数え、それ以外は予算 1000 ずつ実行する
    private fun runToEnd(vm: CsVm, maxTicks: Int = 100_000): Int {
        var ticks = 0
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.advance(1000)
            check(++ticks < maxTicks) { "did not finish" }
        }
        return ticks
    }

    private fun run(source: String): CsVm = CsVm(compile(source)).also { runToEnd(it) }

    private fun output(source: String): List<String> {
        val vm = run(source)
        assertEquals(VmState.HALTED, vm.state, "error ${vm.error} in $source")
        return vm.console.history
    }

    private fun runtimeError(source: String): DiagnosticCode? {
        val vm = run(source)
        assertEquals(VmState.ERROR, vm.state, source)
        return vm.error?.code
    }

    // ---- 1・2. val / var の読み取りと var への代入 ----

    @Test
    fun propertiesAreReadAndAssigned() {
        val source = "class Robot {\n  val name = \"R2\"\n  var speed = 4\n  val active: Boolean = true\n}\nval r = Robot()\n" +
            "println(r.name)\nprintln(r.speed)\nprintln(r.active)\nr.speed = r.speed * 2\nprintln(r.speed)"
        assertEquals(listOf("R2", "4", "true", "8"), output(source))
        // 生成は NEW の後にコンストラクタを呼ぶ。コンストラクタは宣言の順に初期化して this を返す
        val program = compile(source)
        assertEquals(listOf(Opcode.NEW, Opcode.CALL, Opcode.STORE), program.instructions.take(3).map { it.opcode })
        val constructor = program.functions.single()
        assertEquals(FunctionInfo(constructor.entry, 1, 1), constructor)
        assertEquals(
            listOf(Opcode.LOAD_LOCAL, Opcode.PUSH_CONST, Opcode.SET_FIELD, Opcode.LOAD_LOCAL, Opcode.PUSH_CONST, Opcode.SET_FIELD, Opcode.LOAD_LOCAL, Opcode.PUSH_BOOL, Opcode.SET_FIELD, Opcode.LOAD_LOCAL, Opcode.RETURN),
            program.instructions.drop(constructor.entry).map { it.opcode },
        )
        assertEquals(listOf(ClassInfo("Robot", listOf(FieldInfo("name", SlotType.STRING), FieldInfo("speed", SlotType.INT), FieldInfo("active", SlotType.BOOLEAN)), 0)), program.classes)
        // 指示書の例
        val counter = "class Counter {\n    var count = 0\n\n    fun increment() {\n        count = count + 1\n    }\n\n    fun getCount(): Int {\n        return count\n    }\n}\n\n" +
            "val counter = Counter()\ncounter.increment()\ncounter.increment()\nprintln(counter.getCount())"
        assertEquals(listOf("2"), output(counter))
        // クラス型のプロパティ（後で宣言したクラスも使える）と、プロパティを通した読み書き
        assertEquals(
            listOf("Robot@2", "7", "true"),
            output("class Box {\n  var item = Robot()\n}\nclass Robot {\n  var power = 1\n}\nval box = Box()\nprintln(box.item)\nbox.item.power = 7\nprintln(box.item.power)\nval r = box.item\nprintln(r == box.item)"),
        )
    }

    // ---- 3. val への再代入・型の不一致の拒否 ----

    @Test
    fun invalidAssignmentsAreRejected() {
        assertEquals(listOf(DiagnosticCode.SEM_VAL_REASSIGNMENT), codes("class A { val x = 1 }\nval a = A()\na.x = 2"))
        assertEquals(listOf(DiagnosticCode.SEM_VAL_REASSIGNMENT), codes("class A {\n  val x = 1\n  fun f() { x = 2 }\n}"))
        assertEquals(listOf(DiagnosticCode.SEM_VAL_REASSIGNMENT), codes("class A {\n  val x = 1\n  fun f() { this.x = 2 }\n}"))
        // 位置は代入先のプロパティ名
        assertEquals(SourcePosition(36, 3, 3), Compiler.compileSource("class A { val x = 1 }\nval a = A()\na.x = 2").diagnostics.single().position)
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("class A { var x = 1 }\nval a = A()\na.x = \"s\""))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("class A { val x: Int = \"s\" }"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("class A { var r = Robot() }\nclass Robot\nclass Gear\nval a = A()\na.r = Gear()"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("class A { val x = println() }"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("class A { val x: Unit = 1 }"))
        assertEquals(listOf(DiagnosticCode.SEM_UNKNOWN_TYPE), codes("class A { val x: Gear = 1 }"))
    }

    // ---- 4. インスタンスごとの状態 ----

    @Test
    fun instancesHaveIndependentState() {
        val source = "class Counter {\n  var count = 0\n  fun increment() { count = count + 1 }\n}\nval a = Counter()\nval b = Counter()\n" +
            "a.increment()\na.increment()\nb.increment()\nprintln(a.count)\nprintln(b.count)\nval c = a\nc.increment()\nprintln(a.count)\nprintln(Counter().count)"
        assertEquals(listOf("2", "1", "3", "0"), output(source))
    }

    // ---- 5・6. メソッドの呼び出し・戻り値・引数と型検査 ----

    @Test
    fun methodsTakeArgumentsAndReturnValues() {
        val calc = "class Calc {\n  var total = 0\n  fun add(n: Int): Int {\n    total = total + n\n    return total\n  }\n" +
            "  fun isBig(limit: Int): Boolean { return total > limit }\n  fun label(): String { return \"calc\" }\n}\nval c = Calc()\n"
        assertEquals(listOf("5", "12", "true", "calc"), output(calc + "println(c.add(5))\nprintln(c.add(7))\nprintln(c.isBig(10))\nprintln(c.label())"))
        // 引数の個数・型、戻り値の型、値を返さない経路、存在しないメソッド・プロパティ、() の無いメソッド、プロパティの呼び出し
        val cases = mapOf(
            "c.add()" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "c.add(1, 2)" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "c.add(true)" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "val s: String = c.add(1)" to DiagnosticCode.SEM_TYPE_MISMATCH,
            "val u = c.label() + 1" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "c.sub(1)" to DiagnosticCode.SEM_UNKNOWN_METHOD,
            "println(c.foo)" to DiagnosticCode.SEM_UNKNOWN_PROPERTY,
            "c.foo = 1" to DiagnosticCode.SEM_UNKNOWN_PROPERTY,
            "println(c.add)" to DiagnosticCode.SEM_FUNCTION_AS_VALUE,
            "c.total()" to DiagnosticCode.SEM_NOT_CALLABLE,
        )
        for ((statement, code) in cases) assertEquals(listOf(code), codes(calc + statement), statement)
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("class A { fun f(): Int { return \"s\" } }"))
        assertEquals(listOf(DiagnosticCode.SEM_MISSING_RETURN), codes("class A { fun f(): Int { println(1) } }"))
        assertEquals(listOf(DiagnosticCode.SEM_RETURN_WITHOUT_VALUE), codes("class A { fun f(): Int { return } }"))
        // メソッドの中の名前だけの呼び出し・参照
        assertEquals(listOf(DiagnosticCode.SEM_NOT_CALLABLE), codes("class A {\n  val x = 1\n  fun f() { x() }\n}"))
        assertEquals(listOf(DiagnosticCode.SEM_FUNCTION_AS_VALUE), codes("class A {\n  fun g() { }\n  fun f() { println(g) }\n}"))
        assertEquals(listOf(DiagnosticCode.SEM_UNKNOWN_FUNCTION), codes("class A {\n  fun f() { g() }\n}"))
        // クラス以外の値のメンバーは未対応
        assertEquals(listOf(DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED), codes(calc + "println(c.total.size)"))
        // Unit を返すメソッドの結果は値として使えない
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("class A { fun f() { } }\nval x = A().f()"))
    }

    // メンバーの名前の重複・衝突。メソッドはトップレベルの関数と同じ名前でもよく、クラスの中ではメソッドを優先する
    @Test
    fun memberNamesAreChecked() {
        assertEquals(listOf(DiagnosticCode.SEM_DUPLICATE_MEMBER), codes("class A {\n  val x = 1\n  var x = 2\n}"))
        assertEquals(listOf(DiagnosticCode.SEM_DUPLICATE_MEMBER), codes("class A {\n  fun f() { }\n  fun f() { }\n}"))
        assertEquals(listOf(DiagnosticCode.SEM_DUPLICATE_MEMBER), codes("class A {\n  fun f() { }\n  val f = 1\n}"))
        // 2 つ目の名前（ソースの順）を報告する
        val duplicate = Compiler.compileSource("class A {\n  fun f() { }\n  val f = 1\n}").diagnostics.single()
        assertEquals(SourcePosition(30, 3, 7), duplicate.position)
        assertEquals(listOf(DiagnosticArgument.StringArgument("A"), DiagnosticArgument.StringArgument("f")), duplicate.arguments)
        assertEquals(listOf(DiagnosticCode.SEM_BUILTIN_REDECLARATION), codes("class A { val println = 1 }"))
        assertEquals(listOf(DiagnosticCode.SEM_BUILTIN_REDECLARATION), codes("class A { fun waitTicks() { } }"))
        assertEquals(listOf(DiagnosticCode.SEM_CLASS_NAME_CONFLICT), codes("class A { fun A() { } }"))
        assertEquals(listOf(DiagnosticCode.SEM_CLASS_NAME_CONFLICT), codes("class B\nclass A { val B = 1 }"))
        assertEquals(listOf(DiagnosticCode.SEM_FUNCTION_NAME_CONFLICT), codes("fun g() { }\nclass A { val g = 1 }"))
        // メソッドと同じ名前のローカル変数・引数は使えない（名前だけの参照がメソッドを値として使うことと区別できない）
        assertEquals(listOf(DiagnosticCode.SEM_FUNCTION_NAME_CONFLICT), codes("class A {\n  fun f() { }\n  fun g() { val f = 1 }\n}"))
        assertEquals(listOf(DiagnosticCode.SEM_FUNCTION_NAME_CONFLICT), codes("class A {\n  fun f() { }\n  fun g(f: Int) { }\n}"))
        // 別のクラスのメンバーと同じ名前はよい。引数・ローカル変数はプロパティを隠す（this.名前 で区別する）
        val source = "fun size(): Int { return 1 }\nclass A {\n  var x = 10\n  fun size(): Int { return 2 }\n  fun both(): Int { return size() * 10 + x }\n" +
            "  fun shadow(x: Int): Int { return x + this.x }\n}\nclass B { fun size(): Int { return 3 } }\nval x = 5\nprintln(A().both())\nprintln(size())\nprintln(B().size())\nprintln(A().shadow(1))\nprintln(x)"
        assertEquals(listOf("30", "1", "3", "11", "5"), output(source))
    }

    // ---- 7. this ----

    @Test
    fun thisRefersToTheReceiver() {
        val source = "class Point {\n  var x = 0\n  var y = 0\n  fun moveTo(x: Int, y: Int): Point {\n    this.x = x\n    this.y = y\n    return this\n  }\n" +
            "  fun same(other: Point): Boolean { return this == other }\n}\nval p = Point()\nval q = p.moveTo(3, 4)\nprintln(p.x)\nprintln(q.y)\n" +
            "println(p.same(q))\nprintln(p.same(Point()))\nprintln(p.moveTo(1, 2).moveTo(5, 6).x)\nprintln(this == p)"
        assertEquals(listOf(DiagnosticCode.SEM_THIS_OUTSIDE_CLASS), codes(source))
        assertEquals(listOf("3", "4", "true", "false", "5"), output(source.substringBeforeLast("\n")))
        assertEquals(listOf(DiagnosticCode.SEM_THIS_OUTSIDE_CLASS), codes("fun f() { println(this) }"))
        assertEquals(listOf(DiagnosticCode.SEM_INVALID_CALLEE), codes("class A { fun f() { this() } }"))
        // 初期化式の this は初期化中のインスタンス（自分を指すプロパティ）
        assertEquals(listOf("true", "Node@1"), output("class Node { var next: Node = this }\nval n = Node()\nprintln(n.next == n)\nprintln(n.next.next)"))
    }

    // ---- 8. メソッドの再帰 ----

    @Test
    fun methodsRecurse() {
        val source = "class Math {\n  var calls = 0\n  fun fact(n: Int): Int {\n    calls = calls + 1\n    if (n <= 1) { return 1 }\n    return n * fact(n - 1)\n  }\n" +
            "  fun even(n: Int): Boolean {\n    if (n == 0) { return true }\n    return this.odd(n - 1)\n  }\n  fun odd(n: Int): Boolean {\n    if (n == 0) { return false }\n    return even(n - 1)\n  }\n" +
            "  fun down(n: Int): Int {\n    if (n == 0) { return 0 }\n    return down(n - 1)\n  }\n}\nval m = Math()\n"
        assertEquals(listOf("120", "5", "true", "false", "0"), output(source + "println(m.fact(5))\nprintln(m.calls)\nprintln(m.even(10))\nprintln(m.odd(4))\nprintln(m.down(15))"))
        // 呼び出しの深さ 16 は関数と同じ（down(15) は 16 段、down(16) は 17 段）
        val vm = run(source + "println(m.down(16))")
        assertEquals(DiagnosticCode.RUN_CALL_DEPTH_EXCEEDED, vm.error?.code)
        assertEquals(listOf(DiagnosticArgument.IntArgument(VmLimits.MAX_CALL_DEPTH)), vm.error?.arguments)
    }

    // ---- 9. メソッド・初期化式の中の waitTicks ----

    @Test
    fun methodsAndInitializersWait() {
        val source = "class Slow {\n  var steps = 0\n  fun work(n: Int): Int {\n    for (i in 0 until n) {\n      waitTicks(2)\n      steps = steps + 1\n    }\n    return steps\n  }\n}\n" +
            "val s = Slow()\nprintln(s.work(3))\nprintln(s.steps)"
        val vm = CsVm(compile(source))
        assertTrue(runToEnd(vm) >= 6)
        assertEquals(listOf("3", "3"), vm.console.history)
        assertEquals(listOf("3"), output(sensor + "println(s.c)"))
    }

    // 初期化式の途中（関数の中）で待つクラス
    private val sensor = "class Sensor {\n  val a = 1\n  val b = slowValue()\n  val c = a + b\n}\nfun slowValue(): Int {\n  waitTicks(5)\n  return 2\n}\nval s = Sensor()\n"

    // ---- 10・11. 回収: プロパティが参照するオブジェクトは残り、外から参照されない循環は回収する ----

    @Test
    fun objectsReferencedFromPropertiesSurvive() {
        // 指示書の例。box が参照する Robot は、大量の生成で回収が走っても残る
        val box = "class Box {\n  var item = Robot()\n}\n\nclass Robot {}\n\nval box = Box()\nval first = box.item\nfor (i in 0 until 600) {\n  val t = Box()\n}\nprintln(box.item == first)\nprintln(box.item)"
        assertEquals(listOf("true", "Robot@2"), output(box))
        // 大域変数からは先頭しか参照しない 101 個の連結を、プロパティをたどって残す
        val chain = "class Node {\n  var next: Node = this\n  var value = 0\n}\nvar head = Node()\nfor (i in 1..100) {\n  val n = Node()\n  n.value = i\n  n.next = head\n  head = n\n}\n" +
            "for (i in 0 until 1000) {\n  val garbage = Node()\n}\nvar sum = 0\nvar cur = head\nfor (i in 0 until 101) {\n  sum = sum + cur.value\n  cur = cur.next\n}\nprintln(sum)\nprintln(cur == cur.next)"
        assertEquals(listOf("5050", "true"), output(chain))
    }

    @Test
    fun unreachableCyclesAreCollected() {
        // 互いに参照し合う 2 つ組を 1000 組作って捨てる（回収できなければ 256 個で止まる）
        val pairs = "class Node { var next: Node = this }\nval keep = Node()\nfor (i in 0 until 1000) {\n  val a = Node()\n  val b = Node()\n  a.next = b\n  b.next = a\n}\nprintln(keep == keep.next)\nprintln(Node())"
        assertEquals(listOf("true", "Node@2002"), output(pairs))
        // 120 個の輪を 3 回作り直す（前の輪は輪の中の参照しか残らない。作り直す間は 2 つの輪が生きている）
        val ring = "class Node { var next: Node = this }\nfun ring(n: Int): Node {\n  val first = Node()\n  var last = first\n  for (i in 1 until n) {\n    val node = Node()\n    last.next = node\n    last = node\n  }\n" +
            "  last.next = first\n  return first\n}\nvar r = ring(120)\nr = ring(120)\nr = ring(120)\nvar count = 1\nvar cur = r.next\nwhile (cur != r) {\n  count = count + 1\n  cur = cur.next\n}\nwaitTicks(1)\nprintln(count)"
        val program = compile(ring)
        val vm = CsVm(program)
        while (vm.state != VmState.WAITING) vm.advance(1000)
        // 待機の時点のヒープは上限以内で、検証に通る
        val snapshot = vm.snapshot()!!
        assertTrue(snapshot.objects.size <= VmLimits.MAX_OBJECTS)
        assertNull(SnapshotVerifier.verify(program, snapshot))
        runToEnd(vm)
        assertEquals(listOf("120"), vm.console.history)
        // 輪が生きている間は回収されず、上限を超えると止まる
        assertEquals(DiagnosticCode.RUN_TOO_MANY_OBJECTS, runtimeError(ring.replace("var r = ring(120)", "val big = ring(120)\nvar r = ring(120)")))
    }

    // ---- 未初期化のプロパティ（初期化の順序） ----

    @Test
    fun uninitializedPropertiesAreRejected() {
        // 初期化式から、初期化中のインスタンスの自分自身・後のプロパティは読めない（名前だけ・this.名前）
        assertEquals(listOf(DiagnosticCode.SEM_UNINITIALIZED_PROPERTY), codes("class A {\n  val x = y\n  val y = 1\n}"))
        assertEquals(listOf(DiagnosticCode.SEM_UNINITIALIZED_PROPERTY), codes("class A { val x = this.x }"))
        assertEquals(listOf(DiagnosticCode.SEM_UNINITIALIZED_PROPERTY), codes("class A { val x: Int = x + 1 }"))
        assertEquals(SourcePosition(20, 2, 11), Compiler.compileSource("class A {\n  val x = y\n  val y = 1\n}").diagnostics.single().position)
        // 先に宣言したプロパティは読める
        assertEquals(listOf("1", "2", "3"), output("class A {\n  val x = 1\n  val y = x + 1\n  val z = this.y + 1\n}\nval a = A()\nprintln(a.x)\nprintln(a.y)\nprintln(a.z)"))
        // this を渡した先・自分のメソッド・自分を指すプロパティを通して未初期化のプロパティを読むと、実行時エラーで安全に止まる
        val passed = "class A {\n  val b = peek(this)\n  val c = 5\n}\nfun peek(a: A): Int { return a.c }\nprintln(\"start\")\nval a = A()\nprintln(\"end\")"
        val vm = run(passed)
        assertEquals(DiagnosticCode.RUN_UNINITIALIZED_PROPERTY, vm.error?.code)
        assertEquals(5, vm.error?.position?.line)
        assertEquals(listOf("start"), vm.console.history)
        assertEquals(DiagnosticCode.RUN_UNINITIALIZED_PROPERTY, runtimeError("class A {\n  val x = getY()\n  val y = 1\n  fun getY(): Int { return y }\n}\nprintln(A().x)"))
        assertEquals(DiagnosticCode.RUN_UNINITIALIZED_PROPERTY, runtimeError("class A {\n  val me = this\n  val v = me.w\n  val w = 1\n}\nval a = A()"))
        // 初期化済みのプロパティなら、this を渡した先でも読める。代入は後の初期化式で上書きされる
        assertEquals(listOf("5", "5", "3"), output("class A {\n  val c = 5\n  val b = peek(this)\n  var d = poke(this)\n  var e = 3\n}\nfun peek(a: A): Int { return a.c }\nfun poke(a: A): Int {\n  a.e = 9\n  return a.c\n}\nval a = A()\nprintln(a.b)\nprintln(a.d)\nprintln(a.e)"))
    }

    // 初期化式から読めるもの・読めないもの
    @Test
    fun initializerScopeIsChecked() {
        // トップレベルの変数は直接は読めない（関数を通せば読めるが、生成がその変数の宣言より後でなければならない）
        assertEquals(listOf(DiagnosticCode.SEM_TOP_LEVEL_VARIABLE_IN_INITIALIZER), codes("val g = 1\nclass A { val x = g }"))
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("class A { val x = nope }"))
        assertEquals(listOf("7"), output("class A { val x = getG() }\nfun getG(): Int { return g }\nval g = 7\nprintln(A().x)"))
        assertEquals(listOf(DiagnosticCode.SEM_CALL_BEFORE_VARIABLE_DECLARATION), codes("class A { val x = getG() }\nfun getG(): Int { return g }\nval a = A()\nval g = 7"))
        assertEquals(listOf(DiagnosticCode.SEM_CALL_BEFORE_VARIABLE_DECLARATION), codes("class A { fun f(): Int { return g } }\nval a = A()\nprintln(a.f())\nval g = 1"))
        // メソッドの本体からはトップレベルの変数を読み書きできる
        assertEquals(listOf("3"), output("var total = 1\nclass A { fun add(n: Int) { total = total + n } }\nA().add(2)\nprintln(total)"))
        // 初期化式から他のクラスの生成・メソッド・他のインスタンスのプロパティを使える。型の注釈の無いプロパティの型は、後で宣言したクラスの初期化式から決まる
        assertEquals(listOf("s", "4"), output("class A {\n  val x = B().y\n  val n = B().twice(2)\n}\nclass B {\n  val y = \"s\"\n  fun twice(k: Int): Int { return k * 2 }\n}\nprintln(A().x)\nprintln(A().n)"))
    }

    // 型の注釈の無いプロパティの型の依存の循環と、生成の循環
    @Test
    fun cyclesInInitializationAreDetected() {
        assertEquals(listOf(DiagnosticCode.SEM_RECURSIVE_PROPERTY_TYPE), codes("class A { val x = make().x }\nfun make(): A { return A() }"))
        assertEquals(
            listOf(DiagnosticCode.SEM_RECURSIVE_PROPERTY_TYPE),
            codes("class A { val x = makeB().y }\nclass B { val y = makeA().x }\nfun makeA(): A { return A() }\nfun makeB(): B { return B() }"),
        )
        // 型の注釈があれば型は決まるが、生成は終わらず、実行時に呼び出しの深さの上限で安全に止まる
        assertEquals(DiagnosticCode.RUN_CALL_DEPTH_EXCEEDED, runtimeError("class A { val x: Int = make().x }\nfun make(): A { return A() }\nprintln(A().x)"))
        // 初期化式で必ず同じクラスの生成に戻る場合はコンパイル時に拒否する（位置はクラス名）
        assertEquals(listOf(DiagnosticCode.SEM_CYCLIC_CONSTRUCTION), codes("class Node { val next = Node() }"))
        assertEquals(listOf(DiagnosticCode.SEM_CYCLIC_CONSTRUCTION, DiagnosticCode.SEM_CYCLIC_CONSTRUCTION), codes("class A { var b = B() }\nclass B { var a = A() }"))
        assertEquals(listOf(DiagnosticCode.SEM_CYCLIC_CONSTRUCTION), codes("class A { var c = C() }\nclass B { var b = B() }\nclass C { var d = 1 }\nval a = A()"))
        assertEquals(SourcePosition(6, 1, 7), Compiler.compileSource("class Node { val next = Node() }").diagnostics.single().position)
        // && || の右辺の生成は評価されないことがあるため対象外。関数を通した生成の循環は実行時に止まる
        assertEquals(listOf("false"), output("class A { val ok = false && A() == A() }\nprintln(A().ok)"))
        assertEquals(DiagnosticCode.RUN_CALL_DEPTH_EXCEEDED, runtimeError("class A { val b = makeA() }\nfun makeA(): A { return A() }\nprintln(A())"))
    }

    // ---- 上限 ----

    @Test
    fun limitsAreEnforced() {
        val sixteen = (0 until 16).joinToString("\n") { "  var p$it = $it" }
        assertEquals(listOf("15"), output("class Big {\n$sixteen\n}\nprintln(Big().p15)"))
        val tooMany = Compiler.compileSource("class Big {\n$sixteen\n  var p16 = 16\n}")
        assertEquals(listOf(DiagnosticCode.COMP_TOO_MANY_PROPERTIES), tooMany.diagnostics.map { it.code })
        assertEquals(listOf(DiagnosticArgument.IntArgument(VmLimits.MAX_PROPERTIES)), tooMany.diagnostics.single().arguments)
        assertEquals(18, tooMany.diagnostics.single().position.line)
        // メソッドの引数は this の分だけ少ない 15 個まで
        fun method(count: Int) = "class A { fun f(" + (0 until count).joinToString(", ") { "a$it: Int" } + "): Int { return a${count - 1} } }\nprintln(A().f(" + (0 until count).joinToString(", ") + "))"
        assertEquals(listOf("14"), output(method(15)))
        val parameters = Compiler.compileSource(method(16))
        assertEquals(listOf(DiagnosticCode.COMP_TOO_MANY_PARAMETERS), parameters.diagnostics.map { it.code })
        assertEquals(listOf(DiagnosticArgument.IntArgument(VmLimits.MAX_PARAMETERS - 1)), parameters.diagnostics.single().arguments)
        // 再帰に入らない生成・メソッドの連鎖も、呼び出しの深さ 16 を超えればコンパイル時に拒否する
        val chain = (0 until 17).joinToString("\n") { "class C$it { val next = ${if (it == 16) "1" else "C${it + 1}()"} }" }
        assertEquals(listOf(DiagnosticCode.COMP_CALL_TOO_DEEP), codes("$chain\nprintln(C0())"))
    }

    // ---- 15. 不正なバイトコード ----

    private val origin = SourcePosition(0, 1, 1)

    private fun op(opcode: Opcode, operand: Int = 0) = Instruction(opcode, operand, origin)

    @Test
    fun invalidFieldBytecodeIsRejected() {
        val box = ClassInfo("Box", listOf(FieldInfo("n", SlotType.INT), FieldInfo("other", SlotType.OBJECT(1))))
        val two = listOf(box, ClassInfo("Robot"))
        fun program(vararg instructions: Instruction, classes: List<ClassInfo> = two, functions: List<FunctionInfo> = emptyList()) =
            CompiledProgram(instructions.toList(), listOf(Constant.IntConstant(1)), 1, 3, functions, classes)
        val valid = program(op(Opcode.NEW, 0), op(Opcode.STORE), op(Opcode.LOAD), op(Opcode.PUSH_CONST), op(Opcode.SET_FIELD, 0), op(Opcode.LOAD), op(Opcode.GET_FIELD, 0), op(Opcode.POP), op(Opcode.HALT))
        assertNotNull(SnapshotVerifier.framesOf(valid))
        val cases = mapOf(
            "get from int" to program(op(Opcode.PUSH_CONST), op(Opcode.GET_FIELD, 0), op(Opcode.POP), op(Opcode.HALT)),
            "get out of range" to program(op(Opcode.NEW, 0), op(Opcode.GET_FIELD, 2), op(Opcode.POP), op(Opcode.HALT)),
            "get from class without fields" to program(op(Opcode.NEW, 1), op(Opcode.GET_FIELD, 0), op(Opcode.POP), op(Opcode.HALT)),
            "set wrong type" to program(op(Opcode.NEW, 0), op(Opcode.PUSH_BOOL, 1), op(Opcode.SET_FIELD, 0), op(Opcode.HALT)),
            "set wrong class" to program(op(Opcode.NEW, 0), op(Opcode.NEW, 0), op(Opcode.SET_FIELD, 1), op(Opcode.HALT)),
            "set on int" to program(op(Opcode.PUSH_CONST), op(Opcode.PUSH_CONST), op(Opcode.SET_FIELD, 0), op(Opcode.HALT)),
            "set without value" to program(op(Opcode.NEW, 0), op(Opcode.SET_FIELD, 0), op(Opcode.HALT)),
            "field type unit" to program(op(Opcode.HALT), classes = listOf(ClassInfo("A", listOf(FieldInfo("u", SlotType.UNIT))))),
            "field class out of range" to program(op(Opcode.HALT), classes = listOf(ClassInfo("A", listOf(FieldInfo("o", SlotType.OBJECT(3)))))),
            "too many fields" to program(op(Opcode.HALT), classes = listOf(ClassInfo("A", List(VmLimits.MAX_PROPERTIES + 1) { FieldInfo("f$it", SlotType.INT) }))),
            "constructor out of range" to program(op(Opcode.HALT), classes = listOf(box.copy(constructor = 0), ClassInfo("Robot"))),
            "shared constructor" to program(
                op(Opcode.HALT), op(Opcode.LOAD_LOCAL), op(Opcode.RETURN, 1),
                classes = listOf(box.copy(constructor = 0), ClassInfo("Robot", constructor = 0)),
                functions = listOf(FunctionInfo(1, 1, 1)),
            ),
        )
        for ((label, program) in cases) assertNull(SnapshotVerifier.framesOf(program), label)
        // 検証しないで実行しても（念のため）、VM はクラッシュせずに止まる
        fun failure(program: CompiledProgram): DiagnosticCode? = CsVm(program).also { it.run(10) }.error?.code
        assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, failure(cases.getValue("get from int")))
        assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, failure(cases.getValue("get out of range")))
        assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, failure(cases.getValue("set wrong type")))
        assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, failure(cases.getValue("set wrong class")))
        // 初期化しないで読む（検証は型だけを見るため通る）と、実行時に CS_RUN_UNINITIALIZED_PROPERTY
        val unset = program(op(Opcode.NEW, 0), op(Opcode.GET_FIELD, 0), op(Opcode.POP), op(Opcode.HALT))
        assertNotNull(SnapshotVerifier.framesOf(unset))
        assertEquals(DiagnosticCode.RUN_UNINITIALIZED_PROPERTY, failure(unset))
    }

    // ---- 12・16. プロパティ・メソッドの実行中の保存と復元、不正なスナップショット ----

    // box.item が Robot を参照し、box.n が 3 のまま待機する
    private val boxProgram = compile("class Box {\n  var item = Robot()\n  var n = 3\n}\nclass Robot\nval box = Box()\nwaitTicks(100)\nbox.n = box.n + 1\nprintln(box.n)\nprintln(box.item)")

    private fun waitingSnapshot(program: CompiledProgram): VmSnapshot {
        val vm = CsVm(program)
        while (vm.state != VmState.WAITING) vm.advance(1)
        return vm.snapshot()!!
    }

    private fun finish(vm: CsVm): List<String> {
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.advance(1)
        return vm.console.history
    }

    @Test
    fun propertiesSurviveSaveAndRestore() {
        val waiting = waitingSnapshot(boxProgram)
        assertEquals(listOf(ObjectSnapshot(1, 0, listOf(Value.ObjectRef(2), Value.IntValue(3))), ObjectSnapshot(2, 1)), waiting.objects)
        var vm = CsVm.restore(boxProgram, waiting)!!
        repeat(20) { vm = CsVm.restore(boxProgram, vm.snapshot()!!)!!.also { it.countWaitTick() } }
        assertEquals(listOf("4", "Robot@2"), finish(vm))
        // メソッドの実行中（this はフレームのローカル変数 0）と、初期化の途中（プロパティの一部が未初期化）の保存
        val inMethod = compile("class Slow {\n  var steps = 0\n  fun work(n: Int): Int {\n    waitTicks(3)\n    steps = steps + n\n    return steps\n  }\n}\nval s = Slow()\nprintln(s.work(2) + s.work(5))")
        val methodSnapshot = waitingSnapshot(inMethod)
        assertEquals(Value.ObjectRef(1), methodSnapshot.callStack.single().locals[0])
        assertEquals(listOf("9"), finish(CsVm.restore(inMethod, methodSnapshot)!!))
        val constructing = compile(sensor + "println(s.c)")
        val partial = waitingSnapshot(constructing)
        assertEquals(listOf(Value.IntValue(1), null, null), partial.objects.single().fields)
        assertEquals(2, partial.callStack.size)
        assertEquals(listOf("3"), finish(CsVm.restore(constructing, partial)!!))
    }

    @Test
    fun invalidPropertySnapshotsAreRejected() {
        val waiting = waitingSnapshot(boxProgram)
        fun withBox(vararg fields: Value?) = waiting.copy(objects = listOf(ObjectSnapshot(1, 0, fields.toList()), ObjectSnapshot(2, 1)))
        fun problem(snapshot: VmSnapshot) = SnapshotVerifier.verify(boxProgram, snapshot)
        // プロパティの数・型・参照の先・クラス、Unit、プロパティの無いクラスの値
        val invalid = listOf(
            withBox(Value.ObjectRef(2)),
            withBox(Value.ObjectRef(2), Value.IntValue(3), Value.IntValue(4)),
            withBox(Value.IntValue(1), Value.IntValue(3)),
            withBox(Value.ObjectRef(9), Value.IntValue(3)),
            withBox(Value.ObjectRef(1), Value.IntValue(3)),
            withBox(Value.ObjectRef(2), Value.BoolValue(true)),
            withBox(Value.ObjectRef(2), Value.UnitValue),
            withBox(Value.ObjectRef(2), Value.StringValue(0)),
            // 初期化中でないオブジェクトの未初期化のプロパティ
            withBox(null, Value.IntValue(3)),
            withBox(Value.ObjectRef(2), null),
            waiting.copy(objects = listOf(waiting.objects[0], ObjectSnapshot(2, 1, listOf(Value.IntValue(1))))),
        )
        for (snapshot in invalid) assertEquals(SnapshotProblem.INVALID_OBJECTS, problem(snapshot), "${snapshot.objects}")
        // どこからも参照されない、プロパティのそろったオブジェクトはあってもよい
        assertNull(problem(waiting.copy(objects = waiting.objects + ObjectSnapshot(5, 0, listOf(Value.ObjectRef(2), Value.IntValue(0))), nextObjectId = 6)))
        // 初期化の途中: コンストラクタが初期化を終えたプロパティ（先頭の a）は未初期化に戻れない。後のプロパティは代入済みでもよい
        val constructing = compile(sensor + "println(s.c)")
        val partial = waitingSnapshot(constructing)
        fun withSensor(vararg fields: Value?) = partial.copy(objects = listOf(ObjectSnapshot(1, 0, fields.toList())))
        assertNull(SnapshotVerifier.verify(constructing, partial))
        assertEquals(SnapshotProblem.INVALID_OBJECTS, SnapshotVerifier.verify(constructing, withSensor(null, null, null)))
        assertNull(SnapshotVerifier.verify(constructing, withSensor(Value.IntValue(1), Value.IntValue(2), null)))
        // 未初期化のプロパティを持てるのは、コンストラクタのフレームが初期化しているオブジェクトだけ（同じクラスの別のオブジェクトは持てない）
        val other = partial.copy(objects = partial.objects + ObjectSnapshot(2, 0, listOf(Value.IntValue(1), null, null)), nextObjectId = 3)
        assertEquals(SnapshotProblem.INVALID_OBJECTS, SnapshotVerifier.verify(constructing, other))
        assertNull(SnapshotVerifier.verify(constructing, other.copy(objects = partial.objects + ObjectSnapshot(2, 0, listOf(Value.IntValue(1), Value.IntValue(2), Value.IntValue(3))))))
    }

    // 1歩ずつ進め、各境界のスナップショットから復元して最後まで実行した結果が、中断しない実行と一致する
    private fun restoreAtEveryBoundary(program: CompiledProgram, source: String) {
        fun step(vm: CsVm) {
            if (vm.state == VmState.WAITING) vm.countWaitTick() else vm.advance(1)
        }
        val expected = finish(CsVm(program))
        val original = CsVm(program)
        var cut = 0
        while ((original.state == VmState.RUNNING || original.state == VmState.WAITING) && cut < 3000) {
            val snapshot = original.snapshot()!!
            val restored = CsVm.restore(program, snapshot)
            assertNotNull(restored, "rejected at $cut: ${SnapshotVerifier.verify(program, snapshot)} in $source")
            assertEquals(snapshot, restored!!.snapshot())
            assertEquals(expected, finish(restored), "cut $cut of $source")
            step(original)
            cut++
        }
    }

    @Test
    fun everyBoundaryRestores() {
        val sources = listOf(
            sensor + "println(s.c)\nprintln(s)",
            "class Node {\n  var next: Node = this\n  var value = 0\n  fun push(v: Int): Node {\n    val n = Node()\n    n.value = v\n    n.next = this\n    waitTicks(1)\n    return n\n  }\n" +
                "  fun sum(): Int {\n    if (next == this) { return value }\n    return value + next.sum()\n  }\n}\nvar head = Node()\nfor (i in 1..4) { head = head.push(i) }\nprintln(head.sum())",
            "class Counter {\n  var count = 0\n  fun increment() {\n    count = count + 1\n  }\n\n  fun getCount(): Int {\n    return count\n  }\n}\nval counter = Counter()\ncounter.increment()\ncounter.increment()\nprintln(counter.getCount())",
        )
        for (source in sources) restoreAtEveryBoundary(compile(source), source)
    }

    // ---- 17. 予算と公平性 ----

    @Test
    fun methodsAndConstructionStayWithinBudgetAndFair() {
        val calls = compile("class Counter {\n  var count = 0\n  fun increment(): Int {\n    count = count + 1\n    return count\n  }\n}\nval c = Counter()\nwhile (true) { c.increment() }")
        val churn = compile("class Node {\n  var next: Node = this\n  var other = Leaf()\n}\nclass Leaf\nvar n = Node()\nwhile (true) {\n  val m = Node()\n  m.next = n\n  n = Node()\n}")
        val vm = CsVm(calls)
        assertEquals(16, vm.run(16))
        val scheduler = Scheduler<Int>(4096)
        repeat(600) { scheduler.register(it, CsVm(if (it % 2 == 0) calls else churn)) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        scheduler.tick { fast }
        val totals = IntArray(600)
        repeat(60) {
            val result = scheduler.tick { fast }
            assertTrue(result.budgetUsed <= 4096)
            assertTrue(result.executed.values.all { it <= PowerLevel.FAST.instructionsPerTick })
            assertTrue(result.finished.isEmpty(), "${result.finished}")
            result.executed.forEach { (key, count) -> totals[key] += count }
            assertTrue(totals.max() - totals.min() <= 1, "unfair: ${totals.min()}..${totals.max()}")
        }
    }

    // ---- 互換性 ----

    // 第3.9a段階のクラス（プロパティ・メソッドの無いクラス）だけのプログラムの命令とハッシュは変えない
    @Test
    fun classOnlyProgramsAreUnchanged() {
        val expected = mapOf(
            "class Robot {\n}\n\nval robot = Robot()\nprintln(robot)" to "e34e42570c4a0c924384ba411b103f85fb20b00144f33b539affa980f62f2bac",
            "class Robot\nclass Gear\nfun same(r: Robot): Robot { return r }\nfun keep(n: Int, r: Robot): Robot {\n  if (n == 0) { return r }\n  waitTicks(20)\n  return keep(n - 1, r)\n}\n" +
                "val a = Robot()\nval b = a\nprintln(same(a) == a)\nprintln(keep(3, a) == b)\nprintln(Gear())" to "b4f253f566de408f325587d6c5ce30ce7669fddb5640d482d0fe55fd97c374e4",
        )
        for ((source, hash) in expected) assertEquals(hash, ProgramFingerprint.ofProgram(compile(source)).joinToString("") { "%02x".format(it) }, source)
        // プロパティの名前・型・コンストラクタはハッシュに含める
        val program = compile("class A { var x = 1 }\nprintln(A().x)")
        val base = ProgramFingerprint.ofProgram(program)
        val renamed = program.copy(classes = listOf(program.classes[0].copy(fields = listOf(FieldInfo("y", SlotType.INT)))))
        val retyped = program.copy(classes = listOf(program.classes[0].copy(fields = listOf(FieldInfo("x", SlotType.BOOLEAN)))))
        for (changed in listOf(renamed, retyped)) assertTrue(!base.contentEquals(ProgramFingerprint.ofProgram(changed)))
    }

    // ---- 性能（参考値） ----

    @Test
    fun measurePropertiesAndMethods() {
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        fun perTick(label: String, program: CompiledProgram, count: Int, create: () -> CsVm = { CsVm(program) }) {
            val scheduler = Scheduler<Int>(4096)
            repeat(count) { scheduler.register(it, create()) }
            repeat(200) { scheduler.tick { fast } }
            val start = System.nanoTime()
            repeat(500) { scheduler.tick { fast } }
            println("[3.9b measure] $label: ${"%.3f".format((System.nanoTime() - start) / 1_000_000.0 / 500)} ms per tick")
        }
        perTick(
            "scheduler tick with 1000 VMs calling methods (budget 4096)",
            compile("class Counter {\n  var count = 0\n  fun increment(): Int {\n    count = count + 1\n    return count\n  }\n}\nval c = Counter()\nwhile (true) {\n  c.increment()\n  if (c.count > 1000) { c.count = 0 }\n}"),
            1000,
        )
        perTick(
            "scheduler tick with 1000 VMs constructing objects with properties",
            compile("class Node {\n  var next: Node = this\n  var value = 0\n}\nvar n = Node()\nwhile (true) {\n  val m = Node()\n  m.next = n\n  n = Node()\n}"),
            1000,
        )
        // 最悪の形: 16 個のオブジェクトの参照のプロパティを持つ 254 個を連結したまま、プロパティの無い Leaf を作り直す（生成のたびに回収が走り、
        // 254 個 × 16 本の参照をたどる）。連結を作り終えた 1 台の状態を保存し、各 VM はそこから復元して測る
        val fields = (0 until 16).joinToString("\n") { "  var p$it: Node = this" }
        val links = (0 until 16).joinToString("\n") { "  n.p$it = head" }
        val worst = compile("class Node {\n$fields\n}\nclass Leaf\nvar head = Node()\nfor (i in 1 until 254) {\n  val n = Node()\n$links\n  head = n\n}\nvar t = Leaf()\nwhile (true) { t = Leaf() }")
        val probe = CsVm(worst)
        while (probe.state == VmState.RUNNING && probe.snapshot()!!.objects.size < VmLimits.MAX_OBJECTS) probe.advance(1000)
        assertEquals(VmState.RUNNING, probe.state, "${probe.error}")
        val steady = probe.snapshot()!!
        perTick("worst case: 256 VMs collecting on every creation (254 live objects x 16 references each)", worst, 256) { CsVm.restore(worst, steady)!! }
        // 上限近くのヒープ（プロパティ 16 個のオブジェクト 254 個）を持つスナップショットの検証・復元
        val held = compile("class Node {\n$fields\n}\nvar head = Node()\nfor (i in 1 until 254) {\n  val n = Node()\n$links\n  head = n\n}\nwaitTicks(1)\nprintln(head)")
        val vm = CsVm(held)
        while (vm.state != VmState.WAITING) vm.advance(10_000)
        val snapshot = vm.snapshot()!!
        assertEquals(254, snapshot.objects.size)
        repeat(200) { assertNotNull(CsVm.restore(held, snapshot)) }
        val restoreStart = System.nanoTime()
        repeat(1000) { CsVm.restore(held, snapshot) }
        println("[3.9b measure] verify + restore with 254 objects x 16 properties: ${"%.3f".format((System.nanoTime() - restoreStart) / 1_000_000.0 / 1000)} ms per VM")
    }

    // ---- 無作為に作ったプロパティ・メソッドのプログラムと、Kotlin で書いた参照の実行器の比較 ----

    // 変数 c0〜c2 は Cell。Cell は value（Int）と link（Cell。初期値は自分）を持つ
    private sealed interface Stmt

    private data class Create(val target: String) : Stmt
    private data class Copy(val target: String, val source: String) : Stmt
    private data class SetValue(val target: String, val value: Int) : Stmt
    private data class Add(val target: String, val value: Int) : Stmt
    private data class Link(val target: String, val source: String) : Stmt
    private data class Follow(val target: String, val source: String) : Stmt
    private data class PrintValue(val target: String, val throughLink: Boolean) : Stmt
    private data class PrintObject(val target: String) : Stmt
    private data class CompareLink(val target: String, val source: String) : Stmt
    private data class Churn(val target: String, val count: Int) : Stmt
    private data class Wait(val ticks: Int) : Stmt
    private data class Loop(val count: Int, val body: List<Stmt>) : Stmt

    private class Cell(val id: Int) {
        var value = 0
        var link: Cell = this
    }

    private class Reference {
        val output = ArrayList<String>()
        val values = HashMap<String, Cell>()
        var next = 1
        var waited = 0

        // 変数から link をたどって届くオブジェクトの数の最大（VM のヒープの上限を超えない範囲の生成だけを比べる）
        var maxLive = 0

        fun create(): Cell {
            val live = HashSet<Int>()
            for (root in values.values) {
                var cell = root
                while (live.add(cell.id)) cell = cell.link
            }
            maxLive = maxOf(maxLive, live.size)
            return Cell(next++)
        }

        fun run(statements: List<Stmt>) {
            for (statement in statements) {
                when (statement) {
                    is Create -> values[statement.target] = create()
                    is Copy -> values[statement.target] = values.getValue(statement.source)
                    is SetValue -> values.getValue(statement.target).value = statement.value
                    is Add -> values.getValue(statement.target).value += statement.value
                    is Link -> values.getValue(statement.target).link = values.getValue(statement.source)
                    is Follow -> values[statement.target] = values.getValue(statement.source).link
                    is PrintValue -> output.add(values.getValue(statement.target).let { if (statement.throughLink) it.link else it }.value.toString())
                    is PrintObject -> output.add("Cell@${values.getValue(statement.target).id}")
                    is CompareLink -> output.add((values.getValue(statement.target) == values.getValue(statement.source).link).toString())
                    // 作ったオブジェクトは捨てる（link は target を指す）
                    is Churn -> repeat(statement.count) { create() }
                    is Wait -> waited += statement.ticks
                    is Loop -> repeat(statement.count) { run(statement.body) }
                }
            }
        }
    }

    private val cells = listOf("c0", "c1", "c2")

    private fun generate(random: Random, depth: Int): List<Stmt> = List(random.nextInt(1, 6)) {
        val target = cells.random(random)
        val source = cells.random(random)
        when (random.nextInt(if (depth < 2) 13 else 12)) {
            0 -> Create(target)
            1 -> Copy(target, source)
            2 -> SetValue(target, random.nextInt(-5, 10))
            3, 4 -> Add(target, random.nextInt(-3, 4))
            5 -> Link(target, source)
            6 -> Follow(target, source)
            7 -> PrintValue(target, random.nextBoolean())
            8 -> PrintObject(target)
            9 -> CompareLink(target, source)
            10 -> Churn(target, random.nextInt(0, 4))
            11 -> Wait(random.nextInt(0, 3))
            else -> Loop(random.nextInt(0, 4), generate(random, depth + 1))
        }
    }

    private fun render(statements: List<Stmt>, indent: String): String = statements.joinToString("") { statement ->
        indent + when (statement) {
            is Create -> "${statement.target} = Cell()\n"
            is Copy -> "${statement.target} = ${statement.source}\n"
            is SetValue -> "${statement.target}.value = ${statement.value}\n"
            is Add -> "${statement.target}.add(${statement.value})\n"
            is Link -> "${statement.target}.link = ${statement.source}\n"
            is Follow -> "${statement.target} = ${statement.source}.link\n"
            is PrintValue -> "println(${statement.target}${if (statement.throughLink) ".link" else ""}.value)\n"
            is PrintObject -> "println(${statement.target})\n"
            is CompareLink -> "println(${statement.target} == ${statement.source}.link)\n"
            is Churn -> "churn(${statement.target}, ${statement.count})\n"
            is Wait -> "waitTicks(${statement.ticks})\n"
            is Loop -> "for (i in 0 until ${statement.count}) {\n" + render(statement.body, "$indent  ") + "$indent}\n"
        }
    }

    // add はメソッドの中で value を書き換えて結果を返す（文としての呼び出しの結果は捨てる）。churn は作ったオブジェクトを捨てる
    private val prelude = "class Cell {\n  var value = 0\n  var link: Cell = this\n  fun add(n: Int): Int {\n    value = value + n\n    return this.value\n  }\n}\n" +
        "fun churn(target: Cell, n: Int) {\n  for (k in 0 until n) {\n    val g = Cell()\n    g.link = target\n  }\n}\n"

    @Test
    fun randomPropertyProgramsMatchReference() {
        val random = Random(391)
        var compared = 0
        var restored = 0
        var splitCollections = 0
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        repeat(300) { index ->
            val statements = generate(random, 0)
            // 先に 250 個作って捨て（最後の 1 個は pad が参照する）、ヒープを満杯近くにしてから始める（以後の生成で回収が走る）
            val source = prelude + "for (k in 0 until 250) { val pad = Cell() }\n" + cells.joinToString("") { "var $it = Cell()\n" } + render(statements, "") + "println(c0.value + c1.value + c2.value)"
            if (source.length > 4096) return@repeat
            val reference = Reference()
            reference.next = 251
            cells.forEach { reference.values[it] = reference.create() }
            reference.run(statements)
            if (reference.maxLive > 200) return@repeat
            reference.output.add(cells.sumOf { reference.values.getValue(it).value }.toString())
            val program = compile(source)
            val vm = CsVm(program)
            val ticks = runToEnd(vm)
            assertEquals(VmState.HALTED, vm.state, "${vm.error} in $source")
            assertEquals(reference.output.takeLast(Console.MAX_LINES), vm.console.history, source)
            assertTrue(ticks >= reference.waited, source)
            // Scheduler の小さな回収の予算（1 台 13 単位 / tick。回収は多数の tick に分かれる）で実行しても、出力は同じ
            val scheduled = CsVm(program)
            val scheduler = Scheduler<Int>(4096, 97, 13)
            scheduler.register(0, scheduled)
            var collectingTicks = 0
            var schedulerTicks = 0
            while (scheduled.state == VmState.RUNNING || scheduled.state == VmState.WAITING) {
                val result = scheduler.tick { fast }
                if ((result.collected[0] ?: 0) > 0) collectingTicks++
                check(++schedulerTicks < 1_000_000) { "did not finish: $source" }
            }
            assertEquals(vm.console.history, scheduled.console.history, source)
            if (collectingTicks > 1) splitCollections++
            compared++
            if (index % 20 == 0) {
                restored++
                restoreAtEveryBoundary(program, source)
            }
        }
        assertTrue(compared >= 280, "compared $compared")
        assertTrue(splitCollections > 0, "no program collected garbage")
        println("[3.9b random] $compared programs compared, $restored restored at every boundary, $splitCollections collected over several ticks")
    }
}
