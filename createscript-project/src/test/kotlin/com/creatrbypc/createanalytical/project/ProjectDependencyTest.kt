package com.creatrbypc.createanalytical.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// S8.1: createscript-project はファイル・ネットワーク・プロセス・リフレクションに触れず、Minecraft・NeoForge・Create などに依存しない
class ProjectDependencyTest {
    @Test
    fun usesNoFilesNetworkOrGameApis() {
        val forbidden = listOf(
            "java.io.File", "java.nio.file", "kotlin.io.path", "java.net", "javax.net", "ProcessBuilder", "Runtime.getRuntime", "java.lang.reflect", "kotlin.reflect",
            "Class.forName", "net.minecraft", "net.neoforged", "com.simibubi", "com.mojang", "thedarkcolour",
        )
        val root = File("src/main/kotlin/com/creatrbypc/createanalytical/project")
        assertTrue(root.isDirectory, "not found: ${root.absolutePath}")
        val violations = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().filterNot { it.trim().startsWith("//") }.filter { line -> forbidden.any { line.contains(it) } }.map { "${file.name}: $it" }
        }.toList()
        assertEquals(emptyList<String>(), violations)
    }
}
