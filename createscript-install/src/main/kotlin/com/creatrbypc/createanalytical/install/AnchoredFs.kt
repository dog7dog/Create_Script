package com.creatrbypc.createanalytical.install

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// S8.3 L-5 の修正（案 A: 作業ディレクトリを固定した子プロセス。macOS・Linux）。
//
// Java 21 の標準のファイル API は、どの操作もパスを名前でたどる。確かめた後に、途中のディレクトリをリンクに差し替えられると、
// 作成・付け替え・削除はリンクの先で行われる（macOS には SecureDirectoryStream も、開いたディレクトリを基準にする手段も無い）。
// そこで、プロジェクトの中のファイルを変える操作は、すべてこの子プロセス（/bin/sh と OS の標準のコマンド）で行う:
//
// 1. 子プロセスはプロジェクトのルートを作業ディレクトリとして起動し、そこが始めに記録したルート（デバイス番号・i-node）であることを確かめる。
// 2. 対象のディレクトリまで `cd -P` で 1 段ずつ下り、`.` から `..` を段の数だけたどった先がルートであること（今いる場所がプロジェクトの中であること）、
//    分かっているときは、そのディレクトリ自身の デバイス番号・i-node を確かめる。作業ディレクトリは i-node で保持されるので、
//    その後に途中のディレクトリが差し替えられても、以降の操作は確かめたディレクトリに対して行われる。
// 3. 操作は、そのディレクトリの中の 1 要素の名前だけで行う（途中の要素が無いので、たどられる途中のディレクトリが無い）。
//    最後の要素のリンクはたどらない: 作成は noclobber（O_EXCL。既存の名前・リンクには作らない）、付け替えは link（既存の名前を置き換えない）か
//    mv -h / mv -T（宛先のリンクの先へ入らない）、削除は unlink / rmdir（リンクそのものを消す）、権限は chmod -h（macOS）か記述子（Linux）。
//    中身は、作った記述子が、その名前の i-node・リンク数 1・大きさ 0 の通常のファイルであることを確かめてから書く。
//
// 名前・数値は、引数（argv）としてだけ渡し、スクリプトの文字列には埋め込まない（スクリプトは固定の定数で、入力を評価しない）。名前は厳格に確かめる。
// Windows（/bin/sh が無い）では使わない（SafeIo の既存の処理のまま）。
internal class AnchoredFs private constructor(
    val root: Path,
    private val rootDevice: Long,
    private val rootInode: Long,
    private val flavor: String,
    private val timeoutMillis: Long,
) {
    // 子プロセスが報告した、ファイル・ディレクトリの識別子（デバイス番号:i-node）と種類（f: 通常のファイル、d: ディレクトリ、l: リンク、o: その他）
    class Entry(val id: String, val kind: Char, val links: Long, val size: Long)

    companion object {
        private const val SHELL = "/bin/sh"
        private val NAME = Regex("[A-Za-z0-9._@+-][A-Za-z0-9._@+ -]{0,254}")
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L

        // macOS・Linux（/bin/sh があり、ルートの i-node を読めるとき）だけ。Windows などでは null（既存の処理を使う）
        fun open(root: Path, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): AnchoredFs? {
            val os = System.getProperty("os.name").lowercase()
            val flavor = when {
                os.contains("mac") || os.contains("darwin") || os.contains("bsd") -> "bsd"
                os.contains("linux") -> "gnu"
                else -> return null
            }
            if (!Files.isExecutable(Path.of(SHELL))) return null
            val attributes = SafeIo.attributes(root)
            if (attributes == null || attributes.isSymbolicLink || !attributes.isDirectory) throw InstallFailure(InstallFailure.Kind.NO_PROJECT, "not a directory: $root")
            val device = SafeIo.unixLong(root, "dev") ?: return null
            val inode = SafeIo.unixLong(root, "ino") ?: return null
            val fs = AnchoredFs(root, device, inode, flavor, timeoutMillis)
            // 子プロセスと必要なコマンドが使えること（使えなければ、何も変えずに止まる。弱い方式に切り替えない）
            val answer = fs.session(root, null, "check").finish()
            if (answer != "OK") throw InstallFailure(InstallFailure.Kind.UNSAFE, "createscript install cannot use its file helper ($answer); nothing was changed")
            return fs
        }

        fun idOf(device: Long, inode: Long) = "$device:$inode"

        // 子プロセスのスクリプト。引数: flavor ルートのデバイス番号 ルートの i-node 段の数 段の名前… 期待する識別子（- なら無し） 操作 操作の引数…
        // 出力は 1 行ずつ（OK / ERR 理由 / 各段階の報告）。標準入力は、書き込みの段階の合図と中身だけ
        private val SCRIPT = """
            fail() { printf 'ERR %s\n' "${'$'}1"; exit 3; }
            flavor=${'$'}1; rdev=${'$'}2; rino=${'$'}3; n=${'$'}4; shift 4
            case ${'$'}rdev${'$'}rino${'$'}n in *[!0-9]*|'') fail args;; esac
            if [ "${'$'}flavor" = bsd ]; then
              st() { stat -f '%d %i %l %z %HT' "${'$'}1"; }
              stf() { stat -L -f '%i %l %z %HT' "${'$'}1"; }
              stm() { stat -f '%i %Mp%Lp' "${'$'}1"; }
            else
              st() { stat -c '%d %i %h %s %F' -- "${'$'}1"; }
              stf() { stat -L -c '%i %h %s %F' -- "${'$'}1"; }
              stm() { stat -c '%i %a' -- "${'$'}1"; }
            fi
            # 開いた記述子（/dev/fd/N）の stat は、macOS で多数のプロセスが同時に動くと、まれに一時的に失敗する（記述子は開いているのに EBADF）。
            # 読めなかったときだけ、上限つき（5 回）で読み直す。読めた値は呼び出し側が常に照合し、値が合わないときは読み直さない
            again() { t=0; while :; do L=${'$'}("${'$'}@" 2>/dev/null) && return 0; t=${'$'}((t+1)); [ "${'$'}t" -lt 5 ] || return 1; done; }
            # 記述子への操作（chmod /dev/fd/N）も同じく一時的に失敗しうる。確かめた記述子への同じ操作なので、上限つきで試し直す
            againdo() { t=0; while :; do "${'$'}@" 2>/dev/null && return 0; t=${'$'}((t+1)); [ "${'$'}t" -lt 5 ] || return 1; done; }
            octal() { o=${'$'}1; while :; do case ${'$'}o in 0?*) o=${'$'}{o#0};; *) break;; esac; done; echo "${'$'}o"; }
            kind() { case ${'$'}1 in [Rr]egular*) echo f;; [Dd]irectory*) echo d;; [Ss]ymbolic*) echo l;; *) echo o;; esac; }
            look() { L=${'$'}(st "${'$'}1" 2>/dev/null) || return 1; read -r ld li ll lz lt <<EOF
            ${'$'}L
            EOF
            lk=${'$'}(kind "${'$'}lt"); return 0; }
            lookfd() { again stf "${'$'}1" || return 1; read -r fi fl fz ft <<EOF
            ${'$'}L
            EOF
            fk=${'$'}(kind "${'$'}ft"); return 0; }
            modename() { again stm "${'$'}1" || return 1; read -r mi mp <<EOF
            ${'$'}L
            EOF
            mp=${'$'}(octal "${'$'}mp"); return 0; }
            name() { case ${'$'}1 in ''|.|..|*/*|-*) fail name;; esac; }
            num() { case ${'$'}1 in *[!0-9]*|'') fail args;; esac; }
            look . || fail root
            [ "${'$'}ld ${'$'}li" = "${'$'}rdev ${'$'}rino" ] || fail root
            k=0; anc=.
            while [ "${'$'}k" -lt "${'$'}n" ]; do
              name "${'$'}1"
              cd -P "./${'$'}1" 2>/dev/null || fail enter
              shift; k=${'$'}((k+1)); anc="${'$'}anc/.."
            done
            look "${'$'}anc" || fail outside
            [ "${'$'}ld ${'$'}li" = "${'$'}rdev ${'$'}rino" ] || fail outside
            expected=${'$'}1; shift
            if [ "${'$'}expected" != - ]; then look . || fail moved; [ "${'$'}ld:${'$'}li" = "${'$'}expected" ] || fail moved; fi
            op=${'$'}1; shift
            info() { if look "./${'$'}1"; then printf 'OK %s %s %s %s %s\n' "${'$'}ld:${'$'}li" "${'$'}lk" "${'$'}ll" "${'$'}lz" x; else printf 'MISSING\n'; fi; }
            waitgo() { read -r go || exit 4; [ "${'$'}go" = go ] || exit 4; }
            case ${'$'}op in
            check)
              for c in stat link unlink mv mkdir rmdir chmod head; do command -v "${'$'}c" >/dev/null 2>&1 || fail "missing ${'$'}c"; done
              printf 'OK\n';;
            stat) name "${'$'}1"; info "${'$'}1";;
            mkdir) name "${'$'}1"; num "${'$'}2"
              mkdir -m "${'$'}2" "./${'$'}1" 2>/dev/null || { if look "./${'$'}1"; then fail exists; else fail create; fi; }
              info "${'$'}1";;
            write) tmp=${'$'}1; final=${'$'}2; mask=${'$'}3; size=${'$'}4; mode=${'$'}5; how=${'$'}6
              name "${'$'}tmp"; name "${'$'}final"; num "${'$'}mask"; num "${'$'}size"; num "${'$'}mode"
              umask "${'$'}mask"; set -C
              command exec 3>"./${'$'}tmp" 2>/dev/null || fail create
              set +C
              lookfd /dev/fd/3 || fail verify
              look "./${'$'}tmp" || fail verify
              [ "${'$'}fi" = "${'$'}li" ] && [ "${'$'}fl" = 1 ] && [ "${'$'}fz" = 0 ] && [ "${'$'}fk" = f ] && [ "${'$'}lk" = f ] || fail verify
              ino=${'$'}li; dev=${'$'}ld
              printf 'CREATED %s:%s\n' "${'$'}dev" "${'$'}ino"; waitgo
              tagged=".part-${'$'}ino-${'$'}size-${'$'}final"
              name "${'$'}tagged"
              link "./${'$'}tmp" "./${'$'}tagged" 2>/dev/null || fail tag
              unlink "./${'$'}tmp" 2>/dev/null || fail tag
              printf 'TAGGED %s\n' "${'$'}tagged"; waitgo
              if [ "${'$'}size" -gt 0 ]; then head -c "${'$'}size" >&3 || fail write; fi
              lookfd /dev/fd/3 || fail write
              [ "${'$'}fi" = "${'$'}ino" ] && [ "${'$'}fz" = "${'$'}size" ] || fail write
              exec 3>&-
              printf 'WRITTEN\n'; waitgo
              look "./${'$'}tagged" || fail publish
              [ "${'$'}li" = "${'$'}ino" ] && [ "${'$'}lk" = f ] || fail publish
              if [ "${'$'}how" = replace ]; then
                if [ "${'$'}flavor" = bsd ]; then mv -f -h "./${'$'}tagged" "./${'$'}final" 2>/dev/null || fail publish
                else mv -f -T "./${'$'}tagged" "./${'$'}final" 2>/dev/null || fail publish; fi
              else
                link "./${'$'}tagged" "./${'$'}final" 2>/dev/null || { if look "./${'$'}final"; then unlink "./${'$'}tagged" 2>/dev/null; fail exists; else fail publish; fi; }
                unlink "./${'$'}tagged" 2>/dev/null || fail publish
              fi
              look "./${'$'}final" || fail publish
              [ "${'$'}li" = "${'$'}ino" ] || fail publish
              printf 'PUBLISHED\n'; waitgo
              # 権限は、開いた記述子が公開したファイル（i-node）であることを確かめてから、記述子を通して変える（名前で chmod すると、
              # 確かめた後に名前を外のファイルへのリンクに差し替えられたとき、外のファイルを変えうる）。違えば変えずに replaced で失敗する。
              # 変えられなかったとき・変わったことを確かめられなかったときは chmod で失敗する（0400 のまま成功にしない。そのファイルは、
              # 名前を付けた直後に止まったものと同じく、書き終えた 0400 のファイルとして扱われる）
              command exec 3<"./${'$'}final" 2>/dev/null || { if look "./${'$'}final" && [ "${'$'}li" = "${'$'}ino" ]; then fail chmod; else fail replaced; fi; }
              lookfd /dev/fd/3 || fail chmod
              [ "${'$'}fi" = "${'$'}ino" ] && [ "${'$'}fk" = f ] || fail replaced
              againdo chmod "${'$'}mode" /dev/fd/3 || fail chmod
              # 変わったことは名前で（リンクをたどらずに）読み直して確かめる（macOS の /dev/fd/N の stat は、ファイルの権限ではなく
              # 記述子の開き方から作った値を返すため）。その名前が今も自分のファイル（i-node）でなければ replaced
              modename "./${'$'}final" || fail chmod
              [ "${'$'}mi" = "${'$'}ino" ] || fail replaced
              [ "${'$'}mp" = "${'$'}(octal "${'$'}mode")" ] || fail chmod
              exec 3<&-
              info "${'$'}final";;
            link) name "${'$'}1"; name "${'$'}2"
              look "./${'$'}1" || fail missing
              [ "${'$'}ld:${'$'}li" = "${'$'}3" ] && [ "${'$'}lk" = f ] || fail changed
              link "./${'$'}1" "./${'$'}2" 2>/dev/null || { if look "./${'$'}2"; then fail exists; else fail link; fi; }
              look "./${'$'}2" && [ "${'$'}ld:${'$'}li" = "${'$'}3" ] || fail link
              info "${'$'}2";;
            rename) name "${'$'}1"; name "${'$'}2"
              look "./${'$'}1" || fail missing
              [ "${'$'}ld:${'$'}li" = "${'$'}3" ] || fail changed
              if [ "${'$'}4" != replace ]; then if [ -e "./${'$'}2" ] || [ -h "./${'$'}2" ]; then fail exists; fi; fi
              if [ "${'$'}flavor" = bsd ]; then mv -f -h "./${'$'}1" "./${'$'}2" 2>/dev/null || fail rename
              else mv -f -T "./${'$'}1" "./${'$'}2" 2>/dev/null || fail rename; fi
              look "./${'$'}2" && [ "${'$'}ld:${'$'}li" = "${'$'}3" ] || fail rename
              info "${'$'}2";;
            remove) name "${'$'}1"
              look "./${'$'}1" || { printf 'MISSING\n'; exit 0; }
              if [ "${'$'}2" != - ] && [ "${'$'}ld:${'$'}li" != "${'$'}2" ]; then fail changed; fi
              if [ "${'$'}lk" = d ]; then rmdir "./${'$'}1" 2>/dev/null || { if look "./${'$'}1"; then fail notempty; else printf 'MISSING\n'; exit 0; fi; }
              else unlink "./${'$'}1" 2>/dev/null || fail remove; fi
              printf 'OK\n';;
            *) fail op;;
            esac
        """.trimIndent()
    }

    // ---- 子プロセスとのやり取り ----

    inner class Session(private val process: Process) {
        private val lines = LinkedBlockingQueue<String>()
        private val input: OutputStream = process.outputStream

        init {
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).useLines { seq -> seq.forEach { lines.put(it) } }
                } catch (error: IOException) {
                    // 終了した
                }
                lines.put(EOF)
            }
            reader.isDaemon = true
            reader.start()
        }

        // 次の 1 行（時間内に答えなければ子プロセスを止めて失敗）
        fun next(): String {
            val line = lines.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            if (line == null) {
                kill()
                throw InstallFailure(InstallFailure.Kind.UNSAFE, "the file helper of createscript install did not answer within ${timeoutMillis / 1000} seconds; it was stopped")
            }
            if (line == EOF) {
                process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                throw InstallFailure(InstallFailure.Kind.UNSAFE, "the file helper of createscript install stopped unexpectedly")
            }
            return line
        }

        fun send(bytes: ByteArray) {
            try {
                input.write(bytes)
                input.flush()
            } catch (error: IOException) {
                // 子プロセスが先に止まった（次の行で理由が分かる）
            }
        }

        fun go(content: ByteArray? = null) = send(if (content == null) GO else GO + content)

        // 最後の行を読み、子プロセスの終了を待つ
        fun finish(): String {
            val line = next()
            close()
            return line
        }

        fun close() {
            try {
                input.close()
            } catch (error: IOException) {
                // 閉じている
            }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) kill()
        }

        fun kill() {
            process.destroyForcibly()
            try {
                process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private val GO = "go\n".toByteArray()
    private val EOF = "\u0000EOF"

    // dir（ルートの中のディレクトリ）を作業ディレクトリにして、操作 op を始める。expectedId は dir 自身の識別子（分からなければ null）
    fun session(dir: Path, expectedId: String?, op: String, vararg args: String): Session {
        val components = components(dir)
        val command = ArrayList<String>()
        command += listOf(SHELL, "-c", SCRIPT, "createscript-install-helper", flavor, rootDevice.toString(), rootInode.toString(), components.size.toString())
        command += components
        command += expectedId ?: "-"
        command += op
        command += args
        val builder = ProcessBuilder(command).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().clear()
        builder.environment()["PATH"] = "/usr/bin:/bin"
        builder.environment()["LC_ALL"] = "C"
        val process = try {
            builder.start()
        } catch (error: IOException) {
            throw InstallFailure(InstallFailure.Kind.UNSAFE, "cannot start the file helper of createscript install ($SHELL)")
        }
        return Session(process)
    }

    // ルートからの各段の名前（ルートそのものは空）。ルートの外・.. を含むものは拒否
    fun components(dir: Path): List<String> {
        if (!dir.startsWith(root)) throw InstallFailure(InstallFailure.Kind.UNSAFE, "$dir is not inside the project")
        return root.relativize(dir).map { it.toString() }.filter { it.isNotEmpty() }.onEach(::checkName)
    }

    fun checkName(name: String) {
        if (!NAME.matches(name) || name == "." || name == ".." || name.startsWith("-")) throw InstallFailure(InstallFailure.Kind.UNSAFE, "createscript install does not handle the file name \"$name\"")
    }

    fun owns(path: Path): Boolean = path.startsWith(root) && path != root

    // 子プロセスの答え（ERR を例外にする。exists は FileAlreadyExistsException、missing は NoSuchFileException）
    private fun answer(line: String, path: Path): String {
        if (line.startsWith("ERR ")) {
            when (val reason = line.removePrefix("ERR ")) {
                "exists" -> throw FileAlreadyExistsException(path.toString())
                "missing" -> throw NoSuchFileException(path.toString())
                "root", "enter", "outside", "moved" -> throw InstallFailure(
                    InstallFailure.Kind.UNSAFE,
                    "another program replaced or moved a directory on the way to ${path.parent} during the installation (L-5: check \"$reason\"); createscript install did not change anything there and stopped",
                )
                // 公開した後、権限を変える前に、名前が別のものに差し替えられた（差し替えたものの権限は変えていない）
                "replaced" -> throw InstallFailure(InstallFailure.Kind.UNSAFE, "${path.fileName} was replaced while it was published (L-5); createscript install did not change it and stopped")
                else -> throw IOException("$reason: $path")
            }
        }
        return line
    }

    private fun entry(line: String, path: Path): Entry? {
        val answered = answer(line, path)
        if (answered == "MISSING") return null
        val parts = answered.split(' ')
        if (parts.size < 5 || parts[0] != "OK") throw IOException("unexpected answer from the file helper: $answered")
        return Entry(parts[1], parts[2].single(), parts[3].toLong(), parts[4].toLong())
    }

    // ---- 操作 ----

    // path の状態（リンクをたどらない）。無ければ null
    fun stat(path: Path, parentId: String? = null): Entry? {
        checkName(path.fileName.toString())
        return entry(session(path.parent, parentId, "stat", path.fileName.toString()).finish(), path)
    }

    // 新しいディレクトリ（既にあれば FileAlreadyExistsException）。作ったディレクトリの識別子を返す
    fun mkdir(path: Path, mode: Int = 0b111_101_101, parentId: String? = null): String {
        checkName(path.fileName.toString())
        val made = entry(session(path.parent, parentId, "mkdir", path.fileName.toString(), Integer.toOctalString(mode)).finish(), path)
        if (made == null || made.kind != 'd') throw InstallFailure(InstallFailure.Kind.UNSAFE, "${path.fileName} was replaced while it was created (L-5)")
        return made.id
    }

    // 新しいファイルを、書き終えてから最終の名前で公開する（S8.2 N-3 の手順: .part-<UUID> → .part-<i-node>-<大きさ>-<名前> → 中身 → 最終の名前）。
    // replace なら最終の名前を置き換える（mv -h / -T。宛先のリンクの先へは入らない）、そうでなければ既にあれば FileAlreadyExistsException。
    // mask は作るときの umask（書いている間の権限）、mode は公開した後の権限。onStep は各段階（テストの中断に使う。SafeIo.writeNew と同じ名前）。
    // durable は、書き終えた中身をディスクに書き出す手順（パスで開いて force。差し替えられていても、ほかのファイルを変えない）
    fun write(
        path: Path,
        bytes: ByteArray,
        replace: Boolean,
        mask: Int = 0b011_111_111,
        mode: Int = 0b110_100_100,
        onStep: (String) -> Unit = {},
        durable: (Path) -> Unit = {},
        parentId: String? = null,
    ): String {
        val final = path.fileName.toString()
        checkName(final)
        val temporary = "${SafeIo.PART_PREFIX}${java.util.UUID.randomUUID()}"
        val session = session(
            path.parent, parentId, "write", temporary, final, Integer.toOctalString(mask), bytes.size.toString(), Integer.toOctalString(mode), if (replace) "replace" else "new",
        )
        var completed = false
        try {
            val created = answer(session.next(), path)
            if (!created.startsWith("CREATED ")) throw IOException("unexpected answer from the file helper: $created")
            val id = created.removePrefix("CREATED ")
            onStep("creating")
            session.go()
            val tagged = answer(session.next(), path)
            if (!tagged.startsWith("TAGGED ")) throw IOException("unexpected answer from the file helper: $tagged")
            onStep("writing:$final")
            session.go(bytes)
            val written = answer(session.next(), path)
            if (written != "WRITTEN") throw IOException("unexpected answer from the file helper: $written")
            durable(path.resolveSibling(tagged.removePrefix("TAGGED ")))
            onStep("written:$final")
            session.go()
            val published = answer(session.next(), path)
            if (published != "PUBLISHED") throw IOException("unexpected answer from the file helper: $published")
            // 名前を付けた直後（権限はまだ書いている間の 0400: S8.2 N-3 と同じ順）
            onStep("renamed:$final")
            session.go()
            val entry = entry(session.finish(), path)
            if (entry == null || entry.id != id) throw InstallFailure(InstallFailure.Kind.UNSAFE, "$final was replaced while it was published (L-5)")
            completed = true
            return id
        } finally {
            if (!completed) session.kill()
        }
    }

    // 同じディレクトリの中で、source（識別子が id の通常のファイル）に target の名前を足す（置き換えない）
    fun link(source: Path, target: Path, id: String) {
        sameDirectory(source, target)
        entry(session(source.parent, null, "link", source.fileName.toString(), target.fileName.toString(), id).finish(), target)
    }

    // 同じディレクトリの中の付け替え（ファイル・ディレクトリ）。replace でなければ、target が既にあれば FileAlreadyExistsException
    fun rename(source: Path, target: Path, id: String, replace: Boolean) {
        sameDirectory(source, target)
        entry(session(source.parent, null, "rename", source.fileName.toString(), target.fileName.toString(), id, if (replace) "replace" else "new").finish(), target)
    }

    // 名前を消す（ファイルは unlink、ディレクトリは rmdir: 空でなければ DirectoryNotEmptyException）。id があれば、その識別子のものだけ。
    // 無ければ false
    fun remove(path: Path, id: String?): Boolean {
        checkName(path.fileName.toString())
        val line = session(path.parent, null, "remove", path.fileName.toString(), id ?: "-").finish()
        if (line == "ERR notempty") throw java.nio.file.DirectoryNotEmptyException(path.toString())
        if (line == "ERR changed") throw IOException("changed: $path")
        return answer(line, path) == "OK"
    }

    private fun sameDirectory(source: Path, target: Path) {
        if (source.parent != target.parent) throw InstallFailure(InstallFailure.Kind.UNSAFE, "internal error: $source and $target are not in the same directory")
        checkName(source.fileName.toString())
        checkName(target.fileName.toString())
    }

    // 補助: 標準入力・出力を使わない読み取り（テスト用）
    @Suppress("unused")
    private fun drain(stream: InputStream) = stream.readAllBytes()
}
