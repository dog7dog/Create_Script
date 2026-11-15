package com.creatrbypc.createanalytical.install

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// S8.2 Phase 2: createscript-install はダウンロードしたものを実行しない（プロセス・クラスローダー・スクリプトエンジン・リフレクションを使わない）。
// Minecraft・NeoForge・Create などに依存しない
class InstallDependencyTest {
    @Test
    fun runsNoCodeAndUsesNoGameApis() {
        val forbidden = listOf(
            "ProcessBuilder", "Runtime.getRuntime", "ClassLoader", "javax.script", "java.lang.reflect", "kotlin.reflect", "Class.forName", "MethodHandles",
            "net.minecraft", "net.neoforged", "com.simibubi", "com.mojang", "thedarkcolour",
        )
        val root = File("src/main/kotlin/com/creatrbypc/createanalytical/install")
        assertTrue(root.isDirectory, "not found: ${root.absolutePath}")
        // S8.3 L-5: 例外は AnchoredFs.kt の 1 か所の ProcessBuilder だけ（固定の /bin/sh と、固定のスクリプトの定数。ダウンロードしたものは実行しない）
        val allowed = "AnchoredFs.kt:         val builder = ProcessBuilder(command).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)"
        val violations = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().filterNot { it.trim().startsWith("//") }.filter { line -> forbidden.any { line.contains(it) } }.map { "${file.name}: $it" }
        }.toList().filter { it != allowed }
        assertEquals(emptyList<String>(), violations)
        val helper = File(root, "AnchoredFs.kt").readText()
        assertEquals(1, Regex("ProcessBuilder\\(").findAll(helper).count())
        assertTrue(helper.contains("private const val SHELL = \"/bin/sh\""))
        assertTrue(helper.contains("command += listOf(SHELL, \"-c\", SCRIPT, "), "the helper runs only the constant script")
        assertTrue(helper.contains("private val SCRIPT = \"\"\""), "the script is a constant")
    }
}
