package com.creatrbypc.createanalytical.registrytool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

// S8.3 Phase 6・7: 雛形のワークフローの静的な検査（src/test/workflows/wfcheck.py）と、run の中身を模擬の gh・curl とローカルの git で動かす
// 試験（src/test/workflows/harness.py）。GitHub には接続しない。python3（PyYAML）・jq・openssl・git・bash が無い環境では、スキップする
class WorkflowHarnessTest {
    @TempDir
    lateinit var temp: Path

    private val repository: Path = Paths.get("..").toAbsolutePath().normalize()
    private val scripts: Path = Paths.get("src/test/workflows").toAbsolutePath()

    private fun available(vararg command: String): Boolean = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (error: java.io.IOException) {
        false
    }

    private fun assumeTools() {
        assumeTrue(!System.getProperty("os.name").lowercase().startsWith("windows"), "needs bash")
        assumeTrue(available("python3", "-I", "-c", "import yaml"), "needs python3 with PyYAML")
        for (tool in listOf(listOf("jq", "--version"), listOf("openssl", "version"), listOf("git", "--version"), listOf("bash", "--version"))) {
            assumeTrue(available(*tool.toTypedArray()), "needs ${tool.first()}")
        }
    }

    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).directory(repository.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor(10, TimeUnit.MINUTES))
        return process.exitValue() to output
    }

    @Test
    fun workflowsPassTheStaticChecks() {
        assumeTools()
        val (code, output) = run(
            "python3", "-I", scripts.resolve("wfcheck.py").toString(),
            "registry-template/.github/workflows/validate.yml", "registry-template/.github/workflows/publish.yml",
        )
        println(output)
        assertEquals(0, code, output)
    }

    @Test
    fun workflowScriptsBehaveAsSpecified() {
        assumeTools()
        // ツールは、このテストのクラスパスから動かす（installDist に依存しない）
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val launcher = temp.resolve("createscript-registry-tool")
        Files.writeString(launcher, "#!/bin/sh\nexec '$java' -cp '${System.getProperty("java.class.path")}' com.creatrbypc.createanalytical.registrytool.MainKt \"\$@\"\n")
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"))
        val work = Files.createDirectories(temp.resolve("work"))
        val (code, output) = run("python3", "-I", scripts.resolve("harness.py").toString(), "--repo", repository.toString(), "--tool", launcher.toString(), "--work", work.toString())
        println(output.lines().last { it.startsWith("HARNESS") })
        assertEquals(0, code, output)
    }
}
