package com.creatrbypc.createanalytical.script.module

import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import com.creatrbypc.createanalytical.script.vm.VmSnapshot
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// 第8.0.1段階: import したモジュールだけを変えたとき、保存した実行状態が別のプログラムに復元されないこと
class ModuleRestoreTest {
    private val main = "import geometry\nvar i = 1\nwhile (i <= 6) {\n    println(geometry.value(i))\n    i = i + 1\n    waitTicks(1)\n}\n"
    private val v1 = "fun value(x: Int): Int {\n    return x * x\n}\n"

    // 1 文字だけ違う（同じ長さ。演算子だけが変わり、位置は同じ）
    private val v2 = "fun value(x: Int): Int {\n    return x + x\n}\n"

    // 定数だけが違う
    private val v3 = "fun value(x: Int): Int {\n    return x * 3\n}\n"
    private val v1Times2 = "fun value(x: Int): Int {\n    return x * 2\n}\n"

    // 引数の名前だけが違う（同じ長さ。命令は同じ）
    private val renamed = "fun value(y: Int): Int {\n    return y * y\n}\n"

    private fun compile(geometry: String): ModuleCompileResult =
        CreateScript.compile(main, { name -> if (name == "geometry") ModuleLookup.Found(geometry) else ModuleLookup.NotFound }, "main")

    // ホストが保存する、プログラムの識別（Minecraft の統合と同じ 2 つの 32 バイトのハッシュ）
    private class Identity(val sourceHash: ByteArray, val programHash: ByteArray)

    private fun identity(result: ModuleCompileResult) = Identity(result.sourceFingerprint, ProgramFingerprint.ofProgram(result.program!!))

    // ホストの復元の手順（ソースのハッシュ → コンパイル → プログラムのハッシュ → スナップショットの検証）。復元しなかった理由を返す
    private fun restore(saved: Identity, snapshot: VmSnapshot, current: ModuleCompileResult): Pair<DiagnosticCode?, List<String>> {
        if (!current.sourceFingerprint.contentEquals(saved.sourceHash)) return DiagnosticCode.RUN_RESTORE_SOURCE_CHANGED to emptyList()
        val program = current.program ?: return DiagnosticCode.RUN_RESTORE_COMPILE_FAILED to emptyList()
        if (!ProgramFingerprint.ofProgram(program).contentEquals(saved.programHash)) return DiagnosticCode.RUN_RESTORE_PROGRAM_CHANGED to emptyList()
        val printed = ArrayList<String>()
        val run = CreateScript.resume(program, snapshot, RunOptions(128, 0L)) { printed.add(it) } ?: return DiagnosticCode.RUN_RESTORE_INVALID_STATE to emptyList()
        while (!run.isFinished) run.tick()
        return null to printed
    }

    // 3 行を出力して待機している状態
    private fun savedState(program: CompiledProgram): VmSnapshot {
        val printed = ArrayList<String>()
        val run = CreateScript.start(program, RunOptions(128, 0L)) { printed.add(it) }
        while (printed.size < 3) run.tick()
        assertEquals(VmState.WAITING, run.state)
        return run.snapshot()!!
    }

    @Test
    fun sameFilesRestoreAndContinue() {
        val before = compile(v1)
        val snapshot = savedState(before.program!!)
        val (reason, printed) = restore(identity(before), snapshot, compile(v1))
        assertEquals(null, reason)
        assertEquals(listOf("16", "25", "36"), printed)
    }

    // main.cscr は同じで geometry.cscr だけを変えた。エントリーのソースのハッシュ（ofSource）は同じなので、それだけを比べるホストは変更に気づかない。
    // ソース全体のハッシュ（sourceFingerprint）とプログラムのハッシュの両方が変わり、どちらでも復元を止められる
    @Test
    fun changedModuleIsNotRestored() {
        val before = compile(v1)
        val saved = identity(before)
        val snapshot = savedState(before.program!!)
        for (changed in listOf(v2, v3)) {
            val after = compile(changed)
            assertFalse(after.sourceFingerprint.contentEquals(saved.sourceHash), changed)
            assertFalse(ProgramFingerprint.ofProgram(after.program!!).contentEquals(saved.programHash), changed)
            assertEquals(DiagnosticCode.RUN_RESTORE_SOURCE_CHANGED, restore(saved, snapshot, after).first, changed)
            // ソースのハッシュを確かめないホストでも、プログラムのハッシュで止まる
            val withoutSourceCheck = Identity(after.sourceFingerprint, saved.programHash)
            assertEquals(DiagnosticCode.RUN_RESTORE_PROGRAM_CHANGED, restore(withoutSourceCheck, snapshot, after).first, changed)
        }
        // 2 つの定数（* 2 と * 3）も区別する
        assertFalse(ProgramFingerprint.ofProgram(compile(v1Times2).program!!).contentEquals(ProgramFingerprint.ofProgram(compile(v3).program!!)))
    }

    // Core の CreateScript.resume はスナップショットの形だけを確かめ、どのプログラムの状態かは確かめない（単一ファイルと同じ。ハッシュの比較はホストの役目）。
    // 形の同じ別のプログラム（演算子だけが違う）には、ハッシュを比べなければ復元できてしまい、続きの出力が変わる
    @Test
    fun resumeAloneDoesNotCheckTheProgram() {
        val snapshot = savedState(compile(v1).program!!)
        val printed = ArrayList<String>()
        val run = CreateScript.resume(compile(v2).program!!, snapshot, RunOptions(128, 0L)) { printed.add(it) }
        assertNotNull(run)
        while (!run!!.isFinished) run.tick()
        assertEquals(listOf("8", "10", "12"), printed)
    }

    // 命令の変わらない変更（引数の名前）も、ソースが変わったので復元しない（安全側）
    @Test
    fun anySourceChangeIsDetected() {
        val before = compile(v1)
        val after = compile(renamed)
        assertArrayEquals(ProgramFingerprint.ofProgram(before.program!!), ProgramFingerprint.ofProgram(after.program!!))
        assertEquals(DiagnosticCode.RUN_RESTORE_SOURCE_CHANGED, restore(identity(before), savedState(before.program!!), after).first)
    }

    // import の無いプログラムのソースのハッシュは、これまでの ofSource と同じ
    @Test
    fun singleFileFingerprintIsUnchanged() {
        for (source in listOf("println(1)", "fun f(): Int {\n    return 1\n}\nprintln(f())\n", "")) {
            assertArrayEquals(ProgramFingerprint.ofSource(source), CreateScript.compile(source, { ModuleLookup.NotFound }, "main").sourceFingerprint)
            assertArrayEquals(ProgramFingerprint.ofSource(source), ProgramFingerprint.ofModules(source, emptyMap()))
        }
        // 既知の値（"println(1)" の UTF-8 の SHA-256。shasum -a 256 で計算したもの）
        val hex = CreateScript.compile("println(1)", { ModuleLookup.NotFound }).sourceFingerprint.joinToString("") { "%02x".format(it) }
        assertEquals("d27b29307bb84c52a21e039011320a46f97400726c04d878902e209b8b0481ab", hex)
    }

    // ソース全体のハッシュは決まった値で、名前の順に並べるため import の順には依存しない。名前・内容・ファイルの境目の違いは区別する
    @Test
    fun moduleFingerprintIsDeterministic() {
        val a = "fun f(): Int {\n    return 1\n}\n"
        val b = "fun g(): Int {\n    return 2\n}\n"
        val base = ProgramFingerprint.ofModules("x", mapOf("a" to a, "b" to b))
        assertArrayEquals(base, ProgramFingerprint.ofModules("x", linkedMapOf("b" to b, "a" to a)))
        assertEquals(ProgramFingerprint.SIZE, base.size)
        val different = listOf(
            ProgramFingerprint.ofModules("x", mapOf("a" to b, "b" to a)),
            ProgramFingerprint.ofModules("x", mapOf("a" to a, "c" to b)),
            ProgramFingerprint.ofModules("x", mapOf("a" to a)),
            ProgramFingerprint.ofModules("x", mapOf("a" to a, "b" to b + " ")),
            ProgramFingerprint.ofModules("x" + a, mapOf("a" to "", "b" to b)),
            ProgramFingerprint.ofSource("x"),
        )
        for (hash in different) assertFalse(base.contentEquals(hash))
        // import の順を変えても、ソースが同じならエントリーのソースが違うため別の値（エントリーのソースもハッシュに含む）
        val resolver = ModuleResolver { name -> mapOf("a" to a, "b" to b)[name]?.let { ModuleLookup.Found(it) } ?: ModuleLookup.NotFound }
        val ab = CreateScript.compile("import a\nimport b\nprintln(a.f() + b.g())\n", resolver)
        assertArrayEquals(ProgramFingerprint.ofModules("import a\nimport b\nprintln(a.f() + b.g())\n", mapOf("a" to a, "b" to b)), ab.sourceFingerprint)
        assertTrue(ab.modules.containsAll(listOf("a", "b")))
    }
}
