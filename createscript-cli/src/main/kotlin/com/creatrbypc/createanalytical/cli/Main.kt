package com.creatrbypc.createanalytical.cli

import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

// createscript コマンドの入口。標準出力・標準エラー出力は UTF-8 で書く（標準出力はまとめて書き、実行の診断を出す前と終了の前に書き出す）
fun main(args: Array<String>) {
    val out = PrintStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)), false, Charsets.UTF_8)
    val err = PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8)
    val code = CreateScriptCli(out, err).execute(args.toList())
    out.flush()
    err.flush()
    exitProcess(code)
}
