package com.creatrbypc.createanalytical.script.vm

import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// 第3.12段階 S4: Console の履歴と、外部への通知のための記録
class ConsoleTest {
    // print 1 回で履歴は正確に 1 行増える（記録の有無にかかわらず）
    @Test
    fun printAddsExactlyOneLine() {
        for (capturing in listOf(false, true)) {
            val console = Console()
            if (capturing) console.startCapture()
            for (i in 1..Console.MAX_LINES) {
                console.print("line $i")
                assertEquals((1..i).map { "line $it" }, console.history, "capturing=$capturing")
            }
        }
    }

    // 32 行を超えても履歴は最大 32 行（古い行から押し出す）。記録には全行が順番どおりに残り、取り出すと空になる
    @Test
    fun historyIsCappedWhileCaptureKeepsAllLines() {
        val console = Console()
        console.startCapture()
        for (i in 0 until 100) {
            console.print("$i")
            assertEquals(minOf(i + 1, Console.MAX_LINES), console.history.size)
        }
        assertEquals((68 until 100).map { "$it" }, console.history)
        assertEquals((0 until 100).map { "$it" }, console.drainCaptured())
        assertEquals(emptyList<String>(), console.drainCaptured())
    }

    // 記録していない Console は取り出しても空（Minecraft 側の VM は記録しない）
    @Test
    fun withoutCaptureNothingIsRecorded() {
        val console = Console()
        console.print("a")
        assertEquals(emptyList<String>(), console.drainCaptured())
        assertEquals(listOf("a"), console.history)
    }

    // VM の println 1 回で履歴は 1 行だけ増え、コールバックには全行が順番どおりに 1 回ずつ通知される（1 命令/tick で毎 tick 確かめる）
    @Test
    fun eachPrintlnAddsOneHistoryLineAndOneNotification() {
        val printed = ArrayList<String>()
        val run = CreateScript.start(
            CreateScript.compile("for (i in 0 until 50) { println(i) }\nprintln()\nprintln(\"end\")").program!!,
            RunOptions(instructionsPerTick = 1, randomSeed = 0L),
        ) { printed.add(it) }
        var previous = 0
        while (!run.isFinished) {
            run.tick()
            // 1 命令/tick なので、1 tick の通知は多くても 1 行
            assertEquals(true, printed.size - previous <= 1)
            assertEquals(printed.takeLast(Console.MAX_LINES), run.console)
            previous = printed.size
        }
        val expected = (0 until 50).map { "$it" } + listOf("", "end")
        assertEquals(expected, printed)
        assertEquals(expected.takeLast(Console.MAX_LINES), run.console)
    }
}
