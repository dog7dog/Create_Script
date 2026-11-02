package com.creatrbypc.createanalytical.cli

import org.jline.terminal.Attributes
import org.jline.terminal.Size
import org.jline.terminal.TerminalBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

// 第3.12段階 S5.3: 対話モードの行編集（JLine）。実際の端末の代わりに JLine の端末（xterm、80 桁）にキーを送る。
// この端末は、送ったキーを JLine が読む前にまとめて受け取るため、端末の設定は信号・エコーなしで始める（Ctrl+C がキーとして届く）。
// 実際の端末で入力の間だけ Ctrl+C をキーにする切り替えは、擬似端末でキーを送る手動の確認で確かめる（README の 5.2）
class LineInputTest {
    private class Session(val code: Int, val out: String, val err: String, val screen: String) {
        // 標準出力の行（最初の 2 行は案内）
        val results: List<String>
            get() = out.lines().drop(2).filter { it.isNotEmpty() }
    }

    private fun repl(keys: String): Session {
        val screen = ByteArrayOutputStream()
        val terminal = TerminalBuilder.builder()
            .system(false)
            .streams(ByteArrayInputStream(keys.toByteArray(Charsets.UTF_8)), screen)
            .type("xterm-256color")
            .size(Size(80, 24))
            .attributes(Attributes())
            .encoding(Charsets.UTF_8)
            .build()
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o ->
            PrintStream(err, true, Charsets.UTF_8).use { e ->
                TerminalLineInput(terminal, o).use { Repl(it, o, e).start() }
            }
        }
        return Session(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8), screen.toString(Charsets.UTF_8))
    }

    // ↑↓ で履歴を移動する
    @Test
    fun historyUpAndDown() {
        assertEquals(listOf("2", "5", "5", "2", "5"), repl("1 + 1\r2 + 3\r$UP\r$UP$UP$UP\r$UP$UP$UP$DOWN\r$CTRL_D").results)
    }

    // ↑ は、入力中の文字にかかわらず 1 つ前の履歴を出す（"1" で始まる履歴を探さない）
    @Test
    fun upIgnoresTypedText() {
        assertEquals(listOf("2", "5", "5"), repl("1 + 1\r2 + 3\r1$UP\r$CTRL_D").results)
    }

    // ←→ でカーソルを移動して、途中に文字を入れる。ESC [ の形（CSI）のキーも同じ
    @Test
    fun cursorLeftAndRight() {
        assertEquals(listOf("4", "6", "4"), repl("13$LEFT+\r1+$LEFT$LEFT$RIGHT${RIGHT}5\r13$CSI_LEFT+\r$CTRL_D").results)
    }

    // Home/End と Ctrl+A/E
    @Test
    fun homeAndEnd() {
        assertEquals(listOf("120", "120", "120"), repl("2${HOME}1${END}0\r2${CSI_HOME}1${CSI_END}0\r2${CTRL_A}1${CTRL_E}0\r$CTRL_D").results)
    }

    // Backspace と Delete
    @Test
    fun backspaceAndDelete() {
        assertEquals(listOf("10", "23"), repl("100$BACKSPACE\r123$HOME$DELETE\r$CTRL_D").results)
    }

    // 複数行の入力は "... " で続ける
    @Test
    fun multiLineInput() {
        val session = repl("println(\r7\r)\rfun f(a: Int): Int {\r\treturn a * 3\r}\rf(5)\r$CTRL_D")
        assertEquals(listOf("7", "15"), session.results)
        assertTrue(session.screen.contains("... "), session.screen)
        // Tab は補完でなく、Tab の文字を入力する
        assertTrue(session.screen.contains("...     return a * 3"), session.screen)
    }

    // Ctrl+C は入力中の行（複数行の入力の途中も）を捨て、対話モードを続ける
    @Test
    fun ctrlCCancelsInput() {
        val session = repl("1 + ${CTRL_C}println(\r99\r${CTRL_C}2 + 2\r$CTRL_D")
        assertEquals(ExitCode.OK, session.code)
        assertEquals(listOf("4"), session.results)
        assertEquals("", session.err)
    }

    // Ctrl+D は、空の行では対話モードを終え、文字のある行では次の文字を消す
    @Test
    fun ctrlDQuitsOnEmptyLine() {
        val session = repl("45$LEFT$CTRL_D\r$CTRL_D")
        assertEquals(ExitCode.OK, session.code)
        assertEquals(listOf("4"), session.results)
        // 入力の途中の Ctrl+D では、その入力を実行しない
        assertEquals(listOf<String>(), repl("println(\r1\r$CTRL_D").results)
    }

    // 変数の保持・式の値の表示・エラーの後の復帰
    @Test
    fun keepsSessionState() {
        val session = repl("val x = 10\rx * 2\rx + \"a\"\rx\r$CTRL_D")
        assertEquals(listOf("20", "10"), session.results)
        assertTrue(session.err.contains("CS_SEM_INVALID_OPERAND_TYPE"), session.err)
    }

    // "!" は履歴の展開に使わない（"!!" を前の入力に置き換えない。置き換えると "!!t" は "val t = truet" になる）
    @Test
    fun noHistoryExpansion() {
        assertEquals(listOf("true", "true"), repl("val t = true\r!!t\r1 != 2\r$CTRL_D").results)
    }

    // 日本語（UTF-8）の入力・表示・編集
    @Test
    fun japaneseText() {
        val session = repl("println(\"日本語\")\r\"あいう\"$LEFT${LEFT}え\r\"漢字x\"$LEFT$BACKSPACE$LEFT$BACKSPACE\r$UP$UP\r$CTRL_D")
        assertEquals(listOf("日本語", "あいえう", "字", "あいえう"), session.results)
    }

    private companion object {
        const val UP = "\u001bOA"
        const val DOWN = "\u001bOB"
        const val RIGHT = "\u001bOC"
        const val LEFT = "\u001bOD"
        const val HOME = "\u001bOH"
        const val END = "\u001bOF"
        const val CSI_LEFT = "\u001b[D"
        const val CSI_HOME = "\u001b[H"
        const val CSI_END = "\u001b[F"
        const val DELETE = "\u001b[3~"
        const val BACKSPACE = "\u007f"
        const val CTRL_A = "\u0001"
        const val CTRL_C = "\u0003"
        const val CTRL_D = "\u0004"
        const val CTRL_E = "\u0005"
    }
}
