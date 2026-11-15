package com.creatrbypc.createanalytical.cli

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

// S8.2 Phase 4: createscript init は、.gitignore が無いときだけ /.createscript/ の 1 行で作る。既にあるもの（リンク・ディレクトリを含む）には触れない
class InitGitignoreTest {
    @TempDir
    lateinit var directory: Path

    private fun init(project: Path, write: (Path, String) -> FileIdentity = SafeFiles::writeNew): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = ProjectCommands(PrintStream(out, true, Charsets.UTF_8), PrintStream(ByteArrayOutputStream()), write).init(project.toString(), "app")
        return code to out.toString(Charsets.UTF_8)
    }

    @Test
    fun createsAGitignoreOnlyWhenThereIsNone() {
        val fresh = directory.resolve("fresh")
        val (code, out) = init(fresh)
        assertEquals(ExitCode.OK, code)
        assertEquals("/.createscript/\n", Files.readString(fresh.resolve(".gitignore")))
        assertTrue(out.contains("Created ${fresh.resolve(".gitignore")}"), out)
    }

    @Test
    fun keepsAnExistingGitignore() {
        val project = Files.createDirectories(directory.resolve("existing"))
        val contents = "build/\n# no .createscript here\n".toByteArray()
        val gitignore = Files.write(project.resolve(".gitignore"), contents)
        val before = Files.getLastModifiedTime(gitignore)
        val key = Files.readAttributes(gitignore, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        val (code, out) = init(project)
        assertEquals(ExitCode.OK, code)
        assertArrayEquals(contents, Files.readAllBytes(gitignore))
        assertEquals(before, Files.getLastModifiedTime(gitignore))
        assertEquals(key, Files.readAttributes(gitignore, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
        assertFalse(out.contains(".gitignore"), out)
    }

    // シンボリックリンク（たどれないものも）・ディレクトリの .gitignore は、たどらず、作らず、変えない
    @Test
    fun doesNotFollowOrReplaceOtherKindsOfGitignore() {
        val target = Files.writeString(directory.resolve("target.txt"), "outside\n")
        val linked = Files.createDirectories(directory.resolve("linked"))
        Files.createSymbolicLink(linked.resolve(".gitignore"), target)
        val broken = Files.createDirectories(directory.resolve("broken"))
        Files.createSymbolicLink(broken.resolve(".gitignore"), directory.resolve("missing.txt"))
        val dir = Files.createDirectories(directory.resolve("dir/.gitignore")).parent
        for (project in listOf(linked, broken, dir)) assertEquals(ExitCode.OK, init(project).first, "$project")
        assertTrue(Files.isSymbolicLink(linked.resolve(".gitignore")))
        assertEquals("outside\n", Files.readString(target))
        assertTrue(Files.isSymbolicLink(broken.resolve(".gitignore")))
        assertFalse(Files.exists(directory.resolve("missing.txt"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isDirectory(dir.resolve(".gitignore")))
    }

    // .gitignore の書き込みで失敗したら、今回作ったもの（.gitignore の前の 2 つのファイルとディレクトリ）をすべて取り消す
    @Test
    fun rollsBackWhenTheGitignoreCannotBeWritten() {
        val project = directory.resolve("new/app")
        val failing: (Path, String) -> FileIdentity = { path, text ->
            if (path.fileName.toString() == ".gitignore") throw IOException("disk full (simulated)")
            SafeFiles.writeNew(path, text)
        }
        val failure = assertThrows(ProjectCommands.Failure::class.java) { init(project, failing) }
        assertEquals(ExitCode.CANNOT_CREATE, failure.code)
        assertTrue(failure.message!!.contains("nothing was left behind"), failure.message)
        assertFalse(Files.exists(directory.resolve("new")))
    }
}
