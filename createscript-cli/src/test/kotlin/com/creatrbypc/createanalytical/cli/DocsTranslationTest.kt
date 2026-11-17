package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.docscheck.DocsCheck
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// A5b: the Japanese documents keep in step with their English sources, and the relative links and anchors of the
// Markdown files lead somewhere (docs/README.md, "Japanese versions"). The checks are in test-support/docs-check.
class DocsTranslationTest {
    private val root = File(System.getProperty("createscript.repo.root") ?: error("createscript.repo.root is not set"))

    private fun markdownFiles(): List<String> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" && it.name != "node_modules" }
        .filter { it.isFile && it.name.endsWith(".md") }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .sorted()
        .toList()

    @Test
    fun japaneseDocumentsMatchTheirSources() {
        val problems = DocsCheck.PAIRS.flatMap { DocsCheck.compare(root, it) }
        assertTrue(problems.isEmpty(), "The Japanese documents differ from their sources:\n" + problems.joinToString("\n"))
    }

    @Test
    fun relativeLinksAndAnchorsExist() {
        val files = markdownFiles()
        assertTrue(files.size > 20, "too few Markdown files found under $root: ${files.size}")
        val (current, records) = DocsCheck.brokenLinks(root, files)
        if (records.isNotEmpty()) println("Links in records that lead to old paths (kept as they are):\n" + records.joinToString("\n"))
        assertTrue(current.isEmpty(), "Broken relative links or anchors:\n" + current.joinToString("\n"))
    }

    @Test
    fun theCheckFindsDifferences() {
        val english = DocsCheck.parse("# Title\n\nSee [a](#title) and `CS_X_Y`, 42 items.\n\n| a |\n|---|\n| 1 |\n\n```kotlin\nval x = 1\n```\n")
        val japanese = DocsCheck.parse("# 題\n\n<!-- ja-only -->\n| 追加 |\n|---|\n| 補足 |\n\n42 個。`CS_X_Y`。[a](#題)\n\n| a |\n|---|\n| 1 |\n\n```kotlin\nval x = 1\n```\n")
        assertTrue(english.headings.map { it.level } == japanese.headings.map { it.level })
        assertTrue(english.tables == japanese.tables, "a ja-only table is not compared")
        assertTrue(english.codeBlocks == japanese.codeBlocks && english.ids == japanese.ids)
        assertTrue(japanese.numbers.containsAll(english.numbers))
        assertTrue(DocsCheck.slug("対応環境") == "対応環境" && DocsCheck.slug("Modules (local `import`)") == "modules-local-import")
        val changed = DocsCheck.parse("# 題\n\n```kotlin\nval x = 2\n```\n")
        assertTrue(changed.codeBlocks != english.codeBlocks)
        // comments in code blocks can be translated; the code cannot
        val shell = DocsCheck.parse("```bash\n./gradlew build   # run all tests\necho \"# not a comment\"\n```\n")
        val shellJa = DocsCheck.parse("```bash\n./gradlew build   # すべてのテスト\necho \"# not a comment\"\n```\n")
        val shellChanged = DocsCheck.parse("```bash\n./gradlew test   # すべてのテスト\necho \"# not a comment\"\n```\n")
        assertTrue(shell.codeBlocks == shellJa.codeBlocks)
        assertTrue(shell.codeBlocks != shellChanged.codeBlocks)
        val stringComment = DocsCheck.parse("```kotlin\nprintln(\"// kept\")   // note\n```\n")
        assertTrue(stringComment.codeBlocks.single() == "println(\"// kept\")")
    }
}
