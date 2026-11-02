package com.creatrbypc.createanalytical.cli

import org.jline.keymap.KeyMap
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.Reference
import org.jline.reader.UserInterruptException
import org.jline.reader.Widget
import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.InfoCmp
import java.io.BufferedReader
import java.io.IOException
import java.io.PrintStream
import java.util.EnumMap
import java.util.logging.Level
import java.util.logging.Logger

// 対話モードで読んだもの（第3.12段階 S5.3）
sealed interface InputEvent {
    class Line(val text: String) : InputEvent

    // Ctrl+C（行編集のときだけ）。入力の途中なら、その入力を捨てる
    data object Cancel : InputEvent

    // 入力の終わり（Ctrl+D、パイプ・ファイルの終わり）
    data object End : InputEvent
}

// 対話モードの入力。プロンプト（">>> "・"... "）を表示してから 1 行を読む
interface LineInput : AutoCloseable {
    fun read(prompt: String): InputEvent
}

// 行編集なしの入力（標準入力がパイプ・ファイルのとき、端末で行編集を使えないとき、テスト）。S5.1 までと同じ動き
class PlainLineInput(private val reader: BufferedReader, private val out: PrintStream) : LineInput {
    override fun read(prompt: String): InputEvent {
        out.print(prompt)
        out.flush()
        val line = reader.readLine() ?: run {
            // 入力の終わりでは、プロンプトの後で改行する（JLine は自分で改行するため、ここだけで行う）
            out.println()
            return InputEvent.End
        }
        return InputEvent.Line(line)
    }

    override fun close() {}
}

// JLine の行編集（←→・Home/End・Ctrl+A/E・Backspace/Delete・↑↓の履歴など、JLine の emacs のキー操作）。
// 履歴はメモリの中だけに持ち、ファイルには書かない。入力の意味を変えないよう、次の JLine の機能は使わない:
//   - 履歴の展開（"!" と "^"。"x != 1" などが書き換えられるため）
//   - 貼り付けの一括入力（複数の行を 1 回の入力にまとめるため。貼り付けた行は 1 行ずつ読む）
//   - Tab の補完（Tab は、これまでどおり Tab の文字を入力する）
class TerminalLineInput(private val terminal: Terminal, private val out: PrintStream) : LineInput {
    private val reader: LineReader = LineReaderBuilder.builder()
        .terminal(SignalFreeTerminal(terminal))
        .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
        .option(LineReader.Option.BRACKETED_PASTE, false)
        .build()
        .also { reader ->
            val keys = reader.keyMaps.getValue(LineReader.MAIN)
            keys.bind(Reference(LineReader.SELF_INSERT), KeyMap.ctrl('I'))
            reader.widgets[CANCEL] = Widget { throw UserInterruptException(reader.buffer.toString()) }
            keys.bind(Reference(CANCEL), KeyMap.ctrl('C'))
            // ↑↓←→・Home/End・Delete。端末の種類（terminfo）のキーと、よく使われる形（ESC [ A・ESC O A など）の両方
            for ((widget, capability, sequences) in EDIT_KEYS) {
                val key = KeyMap.key(terminal, capability)
                if (!key.isNullOrEmpty()) keys.bind(Reference(widget), key)
                for (sequence in sequences) keys.bind(Reference(widget), sequence)
            }
        }

    override fun read(prompt: String): InputEvent {
        // 前の入力の実行結果（標準出力）を、プロンプトより前に出す
        out.flush()
        // 入力の間だけ、Ctrl+C を信号（SIGINT）ではなくキーとして読む（CANCEL）。SIGINT で受けると、JLine の exec の方式では
        // 端末の入力が捨てられたときに入力の終わりと区別できないことがあり、Ctrl+C で対話モードが終わってしまうため。
        // 入力の後は元に戻すので、実行中の Ctrl+C は、これまでどおり createscript を終了する（終了コード 130）
        val normal = terminal.attributes
        terminal.attributes = Attributes(normal).apply { setControlChar(Attributes.ControlChar.VINTR, 0) }
        return try {
            InputEvent.Line(reader.readLine(prompt))
        } catch (_: UserInterruptException) {
            InputEvent.Cancel
        } catch (_: EndOfFileException) {
            InputEvent.End
        } finally {
            terminal.attributes = normal
        }
    }

    override fun close() = terminal.close()

    companion object {
        // Ctrl+C で入力を捨てる widget の名前
        private const val CANCEL = "createscript-cancel-input"

        // キーと操作。↑↓ は、入力中の文字にかかわらず 1 つ前・後の履歴にする（JLine の既定は、入力中の文字で始まる履歴を探す）
        private val EDIT_KEYS = listOf(
            Triple(LineReader.UP_LINE_OR_HISTORY, InfoCmp.Capability.key_up, listOf("\u001b[A", "\u001bOA")),
            Triple(LineReader.DOWN_LINE_OR_HISTORY, InfoCmp.Capability.key_down, listOf("\u001b[B", "\u001bOB")),
            Triple(LineReader.FORWARD_CHAR, InfoCmp.Capability.key_right, listOf("\u001b[C", "\u001bOC")),
            Triple(LineReader.BACKWARD_CHAR, InfoCmp.Capability.key_left, listOf("\u001b[D", "\u001bOD")),
            Triple(LineReader.BEGINNING_OF_LINE, InfoCmp.Capability.key_home, listOf("\u001b[H", "\u001bOH", "\u001b[1~")),
            Triple(LineReader.END_OF_LINE, InfoCmp.Capability.key_end, listOf("\u001b[F", "\u001bOF", "\u001b[4~")),
            Triple(LineReader.DELETE_CHAR, InfoCmp.Capability.key_dc, listOf("\u001b[3~")),
        )

        // JLine の中のログ（java.util.logging の org.jline。未知の端末の種類の警告など）は表示しない。Logger の設定は、
        // その Logger が参照されている間だけ保たれるため、ここで参照を持つ
        private val jlineLog: Logger = Logger.getLogger("org.jline")

        // 標準入力と標準出力がどちらも端末のとき（System.console() が null でないとき）、その端末を開く。null のときは行編集なしで
        // 読む。null になるのは、JLine の exec の方式（stty で端末を設定する）で開けないとき（TERM が dumb・未設定のとき、stty が
        // ないときなど）と、端末の種類（TERM）の情報がなく、カーソルを左へ動かす方法か ← のキーが分からないとき
        fun openSystem(out: PrintStream): TerminalLineInput? {
            if (System.console() == null) return null
            jlineLog.level = Level.OFF
            val terminal = try {
                // nativeSignals(false) と SignalFreeTerminal: JLine に OS の信号（SIGINT・SIGWINCH など）の処理を変えさせない
                TerminalBuilder.builder().system(true).provider("exec").dumb(false).nativeSignals(false).encoding(Charsets.UTF_8).build()
            } catch (_: IOException) {
                return null
            } catch (_: IllegalStateException) {
                return null
            }
            if (terminal.getStringCapability(InfoCmp.Capability.cursor_left) == null || terminal.getStringCapability(InfoCmp.Capability.key_left) == null) {
                terminal.close()
                return null
            }
            return TerminalLineInput(terminal, out)
        }
    }
}

// JLine の LineReader は、行を読む間、SIGINT・SIGWINCH・SIGCONT を受ける処理を端末に登録し、端末はそれを OS の信号の処理として
// 登録する（Java の sun.misc.Signal を使えるとき。jlink のランタイムには、その jdk.unsupported を入れていない）。ここでは登録を
// JLine の中だけで受け止め、OS の信号の処理は変えない。そのため、Ctrl+C 以外の信号は S5.2 までと同じに扱われる（実行中・入力中の
// SIGINT は Java の既定の処理で終了コード 130 で終わる）。端末の大きさは入力のたびに読み直すので、入力中に変えた大きさは次の入力から使う
private class SignalFreeTerminal(private val terminal: Terminal) : Terminal by terminal {
    private val handlers = EnumMap<Terminal.Signal, Terminal.SignalHandler>(Terminal.Signal::class.java)

    override fun handle(signal: Terminal.Signal, handler: Terminal.SignalHandler): Terminal.SignalHandler =
        handlers.put(signal, handler) ?: Terminal.SignalHandler.SIG_DFL
}
