package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.script.module.ModuleLookup
import com.creatrbypc.createanalytical.script.module.ModuleResolver
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// .cscr ファイルの読み込み（実行するファイルとモジュールで共通）。CliLimits.MAX_SOURCE_BYTES を超えるファイルは読み切らずに拒否する
internal object SourceFiles {
    sealed interface Read {
        class Text(val text: String) : Read

        data object TooLarge : Read

        data object NotUtf8 : Read

        data object Failed : Read
    }

    fun read(path: Path): Read {
        val bytes = try {
            Files.newInputStream(path).use { it.readNBytes(CliLimits.MAX_SOURCE_BYTES + 1) }
        } catch (error: IOException) {
            return Read.Failed
        }
        if (bytes.size > CliLimits.MAX_SOURCE_BYTES) return Read.TooLarge
        return try {
            Read.Text(
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            )
        } catch (error: CharacterCodingException) {
            Read.NotUtf8
        }
    }
}

// import のモジュールを、プロジェクトのルートの直下のファイルから探す（第8.0段階）。モジュール名 name のファイルは root/name.cscr。
// - 名前は識別子（英字・数字・_）だけ。"/"・".."・絶対パスになる名前は受け付けない（Core の Parser も識別子だけを渡す）
// - ファイル名は大文字・小文字まで一致するものだけ。大文字・小文字だけが違うファイルが複数あれば曖昧（ファイルシステムによって同じファイルになるため）
// - シンボリックリンクは、たどった先がルートの中の通常のファイルのときだけ使う
// - サブディレクトリ・検索パス・ネットワークは使わない。探す場所は 1 つだけなので、結果はディレクトリの一覧の順に依存しない
// root は toRealPath したもの、shownRoot は診断に表示するルート（利用者が指定した形。null ならファイル名だけ）、
// entryModule は実行するファイル自身のモジュール名（ルートの直下にあるときだけ。import すると循環になる）
internal class ProjectModules(private val root: Path, private val shownRoot: String?, entry: String?) : ModuleResolver {
    val entryModule: String? = entry?.takeIf { NAME.matches(it) }

    fun displayName(module: String): String = shownRoot?.let { Paths.get(it, module + CliLimits.EXTENSION).toString() } ?: (module + CliLimits.EXTENSION)

    override fun resolve(name: String): ModuleLookup {
        if (!NAME.matches(name)) return ModuleLookup.OutsideProject
        val fileName = name + CliLimits.EXTENSION
        val similar = try {
            Files.list(root).use { entries -> entries.map { it.fileName.toString() }.filter { it.equals(fileName, ignoreCase = true) }.toList() }
        } catch (error: IOException) {
            return ModuleLookup.Unreadable("cannot list the project root")
        }
        if (similar.size > 1) return ModuleLookup.Ambiguous
        if (similar.singleOrNull() != fileName) return ModuleLookup.NotFound
        val path = root.resolve(fileName)
        val real = try {
            path.toRealPath()
        } catch (error: IOException) {
            // たどれないシンボリックリンク
            return ModuleLookup.Unreadable("broken symbolic link")
        }
        if (!real.startsWith(root)) return ModuleLookup.OutsideProject
        if (!Files.isRegularFile(real)) return ModuleLookup.Unreadable("not a regular file")
        return when (val read = SourceFiles.read(real)) {
            is SourceFiles.Read.Text -> ModuleLookup.Found(read.text)
            SourceFiles.Read.TooLarge -> ModuleLookup.Unreadable("file too large (at most ${CliLimits.MAX_SOURCE_LENGTH} characters)")
            SourceFiles.Read.NotUtf8 -> ModuleLookup.Unreadable("not valid UTF-8")
            SourceFiles.Read.Failed -> ModuleLookup.Unreadable("cannot read the file")
        }
    }

    private companion object {
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
