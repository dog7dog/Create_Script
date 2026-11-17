package com.creatrbypc.createanalytical.docscheck

import java.io.File

// A5b: checks that the Japanese documents in docs/ja/ keep in step with their English sources, and that the relative
// links and anchors of the repository's Markdown files lead somewhere (docs/README.md, "Japanese versions").
// Run by DocsTranslationTest of createscript-cli. Uses only the JDK and the Kotlin standard library.
//
// For each pair (English source, Japanese version) it checks:
// - the same sequence of heading levels;
// - the same code blocks, in the same order, character for character (indentation of the fence removed), apart from
//   comments, which can be translated: `#` comments in `bash` blocks and `//` comments in `kotlin` blocks (outside strings);
// - the same tables with the same number of rows, in the same order (a Japanese table marked with a line
//   `<!-- ja-only -->` before it is an addition and is not compared);
// - the same set of link targets: a link to a document that has a Japanese version points to that version, and an
//   anchor points to the heading at the same position; other targets stay the same;
// - the same set of `CS_...` ids;
// - every number of the English text also in the Japanese text (the Japanese text can add numbers);
// - a source line at the top: the English path, a link to it, and the commit (7 to 40 hex digits).
// Whether the translation means the same is for a person to review.
object DocsCheck {
    class Pair(val english: String, val japanese: String, val needsSource: Boolean = true)

    val PAIRS: List<Pair> = listOf(
        Pair("README.md", "README.ja.md", needsSource = false),
        Pair("docs/README.md", "docs/ja/README.md"),
        Pair("docs/language.md", "docs/ja/language.md"),
        Pair("docs/cli.md", "docs/ja/cli.md"),
        Pair("docs/samples.md", "docs/ja/samples.md"),
        Pair("docs/packages.md", "docs/ja/packages.md"),
        Pair("docs/registry.md", "docs/ja/registry.md"),
        Pair("docs/api/api-surface.md", "docs/ja/api/api-surface.md"),
        Pair("docs/api/embedding.md", "docs/ja/api/embedding.md"),
        Pair("docs/api/project-files.md", "docs/ja/api/project-files.md"),
        Pair("docs/api/compatibility.md", "docs/ja/api/compatibility.md"),
    )

    // Records keep the paths that were valid when they were written: their broken links are reported, not failed
    val RECORDS: List<String> = listOf("docs/audits/", "CHANGELOG.md")

    const val JA_ONLY = "<!-- ja-only -->"
    const val SOURCE_MARK = "原文（英語）"

    class Heading(val level: Int, val text: String, val slug: String)

    class Link(val target: String, val line: Int)

    class Doc(
        val headings: List<Heading>,
        val codeBlocks: List<String>,
        val tables: List<Int>,
        val links: List<Link>,
        val allLinks: List<Link>,
        val ids: Set<String>,
        val numbers: Set<String>,
        val lines: List<String>,
    )

    private val LINK = Regex("""(?<!!)\[[^\]\n]*\]\(([^)\s]+)\)""")
    private val ID = Regex("""CS_[A-Z0-9_]+""")
    private val NUMBER = Regex("""(?<![A-Za-z0-9_.])\d+(?:[.,]\d+)*""")
    private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val SOURCE = Regex("""\[([^\]]+)\]\(([^)]+)\)（コミット `([0-9a-f]{7,40})`""")

    fun read(file: File): Doc = parse(file.readText().replace("\r\n", "\n"))

    fun parse(text: String): Doc {
        val lines = text.split('\n')
        val headings = ArrayList<Heading>()
        val code = ArrayList<String>()
        val tables = ArrayList<Int>()
        val links = ArrayList<Link>()
        val allLinks = ArrayList<Link>()
        val prose = StringBuilder()
        val seen = HashMap<String, Int>()
        var i = 0
        var jaOnly = false
        var inSource = false
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                val indent = line.length - trimmed.length
                val language = trimmed.removePrefix("```").trim()
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    val l = lines[i]
                    body.add(if (l.length >= indent && l.substring(0, indent).isBlank()) l.substring(indent) else l.trimStart())
                    i++
                }
                code.add(withoutComments(language, body).joinToString("\n"))
                i++
                continue
            }
            if (line.trim() == JA_ONLY) {
                jaOnly = true
                i++
                continue
            }
            if (line.isBlank()) jaOnly = false
            inSource = line.contains(SOURCE_MARK)
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                val s = slug(heading.groupValues[2])
                val n = seen.getOrDefault(s, 0)
                seen[s] = n + 1
                headings.add(Heading(heading.groupValues[1].length, heading.groupValues[2], if (n == 0) s else "$s-$n"))
            }
            if (trimmed.startsWith("|")) {
                var rows = 0
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    val row = lines[i].trim()
                    if (!row.matches(Regex("""\|[\s:|-]+\|?"""))) rows++
                    prose.append(lines[i]).append('\n')
                    collectLinks(lines[i], i + 1, allLinks)
                    if (!jaOnly) collectLinks(lines[i], i + 1, links)
                    i++
                }
                if (!jaOnly) tables.add(rows)
                jaOnly = false
                continue
            }
            collectLinks(line, i + 1, allLinks)
            if (!inSource) {
                if (!jaOnly) collectLinks(line, i + 1, links)
                prose.append(line).append('\n')
            }
            i++
        }
        val withoutTargets = LINK.replace(prose) { m -> m.value.substringBefore("](") + "]" }
        val ids = ID.findAll(text).map { it.value }.toSet()
        val numbers = NUMBER.findAll(withoutTargets).map { it.value }.toSet()
        return Doc(headings, code, tables, links, allLinks, ids, numbers, lines)
    }

    // A code block without the comments that a translation may change; the code itself must stay the same
    private fun withoutComments(language: String, lines: List<String>): List<String> {
        val marker = when (language) {
            "bash", "sh", "shell" -> "#"
            "kotlin" -> "//"
            else -> return lines
        }
        return lines.map { line ->
            var quotes = 0
            var cut = -1
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '"' && (i == 0 || line[i - 1] != '\\')) quotes++
                if (quotes % 2 == 0 && line.startsWith(marker, i) && (i == 0 || line[i - 1].isWhitespace())) {
                    cut = i
                    break
                }
                i++
            }
            (if (cut >= 0) line.substring(0, cut) else line).trimEnd()
        }
    }

    private fun collectLinks(line: String, number: Int, into: MutableList<Link>) {
        val outsideCode = line.replace(Regex("`[^`]*`"), "``")
        for (m in LINK.findAll(outsideCode)) into.add(Link(m.groupValues[1], number))
    }

    // The anchor that GitHub makes for a heading: lowercase, without characters other than letters, marks, numbers,
    // connector punctuation, spaces and hyphens, spaces made hyphens
    fun slug(heading: String): String {
        val text = heading.replace("`", "").replace(Regex("""\[([^\]]*)\]\([^)]*\)"""), "$1").lowercase()
        val out = StringBuilder()
        for (c in text) {
            val type = Character.getType(c).toByte()
            val keep = Character.isLetterOrDigit(c) || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK ||
                type == Character.ENCLOSING_MARK || type == Character.CONNECTOR_PUNCTUATION || type == Character.LETTER_NUMBER ||
                type == Character.OTHER_NUMBER || c == '-' || c == ' '
            if (keep) out.append(if (c == ' ') '-' else c)
        }
        return out.toString()
    }

    private fun resolve(root: File, from: String, target: String): kotlin.Pair<String, String> {
        val path = target.substringBefore('#')
        val anchor = if ('#' in target) target.substringAfter('#') else ""
        if (path.isEmpty()) return from to anchor
        val top = root.absoluteFile.normalize()
        val file = File(File(top, from).parentFile, path).normalize()
        val relative = file.relativeTo(top).invariantSeparatorsPath
        return (relative + if (path.endsWith("/")) "/" else "") to anchor
    }

    // Problems of one pair, empty when it matches
    fun compare(root: File, pair: Pair): List<String> {
        val problems = ArrayList<String>()
        val enFile = File(root, pair.english)
        val jaFile = File(root, pair.japanese)
        if (!enFile.isFile) return listOf("${pair.english} is missing")
        if (!jaFile.isFile) return listOf("${pair.japanese} is missing (the Japanese version of ${pair.english})")
        val en = read(enFile)
        val ja = read(jaFile)
        val name = pair.japanese

        if (en.headings.map { it.level } != ja.headings.map { it.level }) {
            problems.add("$name: headings ${ja.headings.map { it.level }} differ from ${en.headings.map { it.level }} of ${pair.english}")
        }
        if (en.codeBlocks.size != ja.codeBlocks.size) {
            problems.add("$name: ${ja.codeBlocks.size} code blocks, ${pair.english} has ${en.codeBlocks.size}")
        } else {
            en.codeBlocks.zip(ja.codeBlocks).forEachIndexed { index, (e, j) ->
                if (e != j) problems.add("$name: code block ${index + 1} differs from ${pair.english}")
            }
        }
        if (en.tables != ja.tables) problems.add("$name: table rows ${ja.tables} differ from ${en.tables} of ${pair.english}")
        if (en.ids != ja.ids) {
            problems.add("$name: CS ids missing ${(en.ids - ja.ids).sorted()}, extra ${(ja.ids - en.ids).sorted()}")
        }
        val missingNumbers = (en.numbers - ja.numbers).sorted()
        if (missingNumbers.isNotEmpty()) problems.add("$name: numbers of ${pair.english} missing: $missingNumbers")

        val japaneseOf = PAIRS.associate { it.english to it.japanese }
        val external = Regex("""^[a-z][a-z0-9+.-]*:""")
        val expected = en.links.map { link ->
            if (external.containsMatchIn(link.target)) return@map link.target
            val (path, anchor) = resolve(root, pair.english, link.target)
            // the link that switches language points back to the English version
            if (path == pair.japanese) return@map "${pair.english}#$anchor"
            val translated = japaneseOf[path]
            val targetPath = translated ?: path
            val targetAnchor = if (anchor.isEmpty()) "" else if (translated != null || path == pair.english) mapAnchor(root, path, translated ?: name, anchor) ?: "?$anchor" else anchor
            (if (path == pair.english) name else targetPath) + "#" + targetAnchor
        }.toSet()
        val actual = ja.links.map { link ->
            if (external.containsMatchIn(link.target)) return@map link.target
            val (path, anchor) = resolve(root, name, link.target)
            "$path#$anchor"
        }.toSet()
        (expected - actual).sorted().forEach { problems.add("$name: missing a link to $it (from ${pair.english})") }
        (actual - expected).sorted().forEach { problems.add("$name: link to $it is not in ${pair.english}") }

        if (pair.needsSource) {
            val head = ja.lines.take(15)
            val sourceLine = head.firstOrNull { it.contains(SOURCE_MARK) }
            val match = sourceLine?.let { SOURCE.find(it) }
            if (match == null) {
                problems.add("$name: no source line (\"$SOURCE_MARK\" with a link to ${pair.english} and （コミット `…`）) in the first 15 lines")
            } else {
                if (match.groupValues[1] != pair.english) problems.add("$name: the source line names ${match.groupValues[1]}, not ${pair.english}")
                if (resolve(root, name, match.groupValues[2]).first != pair.english) problems.add("$name: the source link does not lead to ${pair.english}")
            }
        }
        return problems
    }

    // The anchor in the Japanese document for an anchor of the English one: the heading at the same position
    private fun mapAnchor(root: File, englishPath: String, japanesePath: String, anchor: String): String? {
        if (!File(root, englishPath).isFile || !File(root, japanesePath).isFile) return null
        val en = read(File(root, englishPath)).headings
        val ja = read(File(root, japanesePath)).headings
        val index = en.indexOfFirst { it.slug == anchor }
        if (index < 0 || index >= ja.size) return null
        return ja[index].slug
    }

    // Relative links and anchors that lead nowhere: (in current documents, in records)
    fun brokenLinks(root: File, markdownFiles: List<String>): kotlin.Pair<List<String>, List<String>> {
        val current = ArrayList<String>()
        val records = ArrayList<String>()
        val anchorCache = HashMap<String, Set<String>>()
        for (file in markdownFiles) {
            val doc = read(File(root, file))
            for (link in doc.allLinks) {
                if (Regex("""^[a-z][a-z0-9+.-]*:""").containsMatchIn(link.target)) continue
                val (path, anchor) = resolve(root, file, link.target)
                val target = File(root, path)
                val problem = when {
                    !target.exists() -> "missing file"
                    anchor.isNotEmpty() && path.endsWith(".md") &&
                        anchor !in anchorCache.getOrPut(path) { read(target).headings.map { it.slug }.toSet() } -> "missing anchor"
                    else -> null
                }
                if (problem != null) {
                    val text = "$file:${link.line}: (${link.target}) $problem"
                    if (RECORDS.any { file.startsWith(it) }) records.add(text) else current.add(text)
                }
            }
        }
        return current to records
    }
}
