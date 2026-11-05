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
        val violations = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().filterNot { it.trim().startsWith("//") }.filter { line -> forbidden.any { line.contains(it) } }.map { "${file.name}: $it" }
        }.toList()
        assertEquals(emptyList<String>(), violations)
    }
}
