package com.creatrbypc.createanalytical.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// CreateScript 言語コア（script パッケージ）が Minecraft・NeoForge・Create などに依存していないことを確認する
class ScriptDependencyTest {
    private val forbiddenPrefixes = listOf(
        "import net.minecraft", "import net.neoforged", "import com.simibubi", "import com.mojang",
        "import thedarkcolour", "import dev.engine_room", "import net.createmod", "import com.tterrag",
    )

    @Test
    fun scriptCoreHasNoGameImports() {
        val root = File("src/main/kotlin/com/creatrbypc/createanalytical/script")
        assertTrue(root.isDirectory, "script package not found: ${root.absolutePath}")
        val sources = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        val violations = sources.flatMap { file ->
            file.readLines().filter { line -> forbiddenPrefixes.any { line.trim().startsWith(it) } }.map { "${file.name}: $it" }
        }
        assertEquals(emptyList<String>(), violations)
    }

    // 同じ入力（プログラム・ホストの乱数の種・tick）から同じ結果になるよう、言語コアは時刻と JDK・Kotlin の乱数を使わない（第3.11e段階。乱数は VM の SplitMix64）
    @Test
    fun scriptCoreIsDeterministic() {
        val forbidden = listOf("java.util.Random", "ThreadLocalRandom", "SplittableRandom", "SecureRandom", "kotlin.random", "System.nanoTime", "currentTimeMillis", "Instant.now")
        val root = File("src/main/kotlin/com/creatrbypc/createanalytical/script")
        val violations = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().filterNot { it.trim().startsWith("//") }.filter { line -> forbidden.any { line.contains(it) } }.map { "${file.name}: $it" }
        }.toList()
        assertEquals(emptyList<String>(), violations)
    }
}
