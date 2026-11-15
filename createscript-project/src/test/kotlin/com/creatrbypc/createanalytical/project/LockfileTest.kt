package com.creatrbypc.createanalytical.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

// S8.1: createscript.lock（lockVersion 1）の決まった形・読み取り・検査・マニフェストとの整合
class LockfileTest {
    private val shaA = "a".repeat(64)
    private val shaB = "0123456789abcdef".repeat(4)
    private val root = LockRoot("example-project", "0.1.0")
    private val vector = LockedPackage("vector-math", "1.2.0", "github:owner/vector-math", shaA, listOf("numbers"))
    private val numbers = LockedPackage("numbers", "3.0.0", "github:owner/numbers", shaB)
    private val manifest = Manifest("example-project", "0.1.0", "src/main.cscr", dependencies = listOf(Dependency("vector-math", "1.2.0", "github:owner/vector-math")))

    private val canonical = """
        {
          "lockVersion": 1,
          "root": {
            "name": "example-project",
            "version": "0.1.0"
          },
          "packages": [
            {
              "name": "numbers",
              "version": "3.0.0",
              "source": "github:owner/numbers",
              "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "dependencies": []
            },
            {
              "name": "vector-math",
              "version": "1.2.0",
              "source": "github:owner/vector-math",
              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "dependencies": [
                "numbers"
              ]
            }
          ]
        }

    """.trimIndent()

    private fun parse(text: String) = Lockfiles.parse(text.toByteArray(Charsets.UTF_8))

    private fun check(text: String, against: Manifest = manifest): List<ProjectDiagnosticCode> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return Lockfiles.check(bytes, Lockfiles.parse(bytes), against).map { it.code }
    }

    // 入力の順に関係なく、同じ内容は同じバイト列になる
    @Test
    fun writesDeterministically() {
        val first = Lockfiles.write(Lockfile.of(root, listOf(vector, numbers)))
        val second = Lockfiles.write(Lockfile.of(root, listOf(numbers, vector.copy(dependencies = listOf("numbers")))))
        assertEquals(canonical, first)
        assertEquals(first, second)
        val many = (1..50).map { LockedPackage("p$it", "1.0.$it", "github:o/p$it", shaA, (1 until it).filter { d -> d % 7 == 0 }.map { d -> "p$d" }.reversed()) }
        val texts = (1..5).map { seed -> Lockfiles.write(Lockfile.of(root, many.shuffled(kotlin.random.Random(seed)))) }
        assertEquals(1, texts.distinct().size)
        // 読み直すと同じ内容
        assertEquals(Lockfile.of(root, listOf(vector, numbers)), parse(canonical).lockfile)
        assertEquals("{\n  \"lockVersion\": 1,\n  \"root\": {\n    \"name\": \"a\",\n    \"version\": \"1.0.0\"\n  },\n  \"packages\": []\n}\n", Lockfiles.write(Lockfile.of(LockRoot("a", "1.0.0"), emptyList())))
    }

    @Test
    fun checksAgainstTheManifest() {
        assertEquals(emptyList<ProjectDiagnosticCode>(), check(canonical))
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_ROOT_MISMATCH), check(canonical, manifest.copy(version = "0.2.0")))
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_ROOT_MISMATCH), check(canonical, manifest.copy(name = "renamed")))
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_VERSION_MISMATCH), check(canonical, manifest.copy(dependencies = listOf(Dependency("vector-math", "1.3.0", "github:owner/vector-math")))))
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_SOURCE_MISMATCH), check(canonical, manifest.copy(dependencies = listOf(Dependency("vector-math", "1.2.0", "github:fork/vector-math")))))
        // マニフェストの依存がロックファイルに無い。どこからも使われないパッケージ
        val extra = Dependency("geometry", "1.0.0", "github:owner/geometry")
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_MISSING_PACKAGE), check(canonical, manifest.copy(dependencies = listOf(extra, manifest.dependencies.single()))))
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_UNUSED_PACKAGE, ProjectDiagnosticCode.LOCK_UNUSED_PACKAGE), check(canonical, manifest.copy(dependencies = emptyList())))
        // 間接の依存（numbers）は、vector-math を通して使われる
        assertEquals(emptyList<ProjectDiagnosticCode>(), check(canonical))
    }

    // 正規形でないもの（字下げ・順・改行が違う）は、内容が同じでも報告する
    @Test
    fun detectsNonCanonicalFiles() {
        val variants = listOf(
            canonical.replace("\n", "\r\n"),
            canonical.removeSuffix("\n"),
            canonical + "\n",
            canonical.replace("  \"lockVersion\": 1,", "  \"lockVersion\":  1,"),
            canonical.replace("\"version\": \"0.1.0\"\n", "\"version\": \"0.1.0\" \n"),
        )
        for (text in variants) assertEquals(listOf(ProjectDiagnosticCode.LOCK_NOT_CANONICAL), check(text), text)
        val swapped = Lockfiles.write(Lockfile.of(root, listOf(numbers, vector))).let { text ->
            // パッケージの順を入れ替えたもの（名前の順でない）
            val start = text.indexOf("    {")
            val middle = text.indexOf("    {", start + 1)
            val end = text.lastIndexOf("  ]")
            text.substring(0, start) + text.substring(middle, end).removeSuffix("\n") + ",\n" + text.substring(start, middle).removeSuffix(",\n") + "\n" + text.substring(end)
        }
        assertNotNull(parse(swapped).lockfile)
        val diagnostic = Lockfiles.check(swapped.toByteArray(), parse(swapped), manifest).single()
        assertEquals(ProjectDiagnosticCode.LOCK_NOT_CANONICAL, diagnostic.code)
        assertEquals(9, diagnostic.line)
    }

    @Test
    fun checksPackages() {
        fun codes(text: String): List<ProjectDiagnosticCode> = parse(text).let { assertNull(it.lockfile, text); it.diagnostics.map { d -> d.code } }
        for (sha in listOf("A".repeat(64), "a".repeat(63), "a".repeat(65), "g".repeat(64), "", " " + "a".repeat(63))) {
            assertEquals(listOf(ProjectDiagnosticCode.INVALID_SHA256), codes(canonical.replace(shaA, sha)), sha)
        }
        assertEquals(listOf(ProjectDiagnosticCode.INVALID_VERSION), codes(canonical.replace("\"3.0.0\"", "\"3.0\"")))
        assertEquals(listOf(ProjectDiagnosticCode.INVALID_SOURCE), codes(canonical.replace("github:owner/numbers", "https://example.com/numbers.zip")))
        assertEquals(listOf(ProjectDiagnosticCode.INVALID_NAME), codes(canonical.replace("\"name\": \"numbers\"", "\"name\": \"Numbers\"").replace("\"numbers\"\n", "\"Numbers\"\n")).take(1))
        // 同じ名前のパッケージ・依存の重複・無いパッケージへの依存・循環
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_DUPLICATE_PACKAGE), codes(canonical.replace("\"name\": \"vector-math\"", "\"name\": \"numbers\"").replace("\"numbers\"\n", "\"x\"\n")).filter { it == ProjectDiagnosticCode.LOCK_DUPLICATE_PACKAGE })
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_DUPLICATE_DEPENDENCY), codes(canonical.replace("\"numbers\"\n      ]", "\"numbers\",\n        \"numbers\"\n      ]")))
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_UNKNOWN_DEPENDENCY), codes(canonical.replace("\"numbers\"\n      ]", "\"missing\"\n      ]")))
        val cyclic = canonical.replace("\"dependencies\": []", "\"dependencies\": [\"vector-math\"]")
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_DEPENDENCY_CYCLE), codes(cyclic))
        val self = canonical.replace("\"dependencies\": []", "\"dependencies\": [\"numbers\"]")
        assertEquals(listOf(ProjectDiagnosticCode.LOCK_DEPENDENCY_CYCLE), codes(self))
        // 版・知らない項目・重複キー・型
        assertEquals(listOf(ProjectDiagnosticCode.UNSUPPORTED_LOCK_VERSION), codes(canonical.replace("\"lockVersion\": 1", "\"lockVersion\": 2")))
        assertEquals(listOf(ProjectDiagnosticCode.UNKNOWN_FIELD), codes(canonical.replace("\"sha256\": \"$shaA\",", "\"sha256\": \"$shaA\",\n      \"url\": \"x\",")))
        assertEquals(listOf(ProjectDiagnosticCode.JSON_DUPLICATE_KEY), codes(canonical.replace("\"sha256\": \"$shaA\",", "\"sha256\": \"$shaA\",\n      \"sha256\": \"$shaB\",")))
        assertEquals(listOf(ProjectDiagnosticCode.WRONG_TYPE), codes(canonical.replace("\"dependencies\": []", "\"dependencies\": {}")))
        assertEquals(listOf(ProjectDiagnosticCode.MISSING_FIELD), codes(canonical.replace("\"source\": \"github:owner/numbers\",\n", "")))
        assertEquals(listOf(ProjectDiagnosticCode.FILE_TOO_LARGE), codes(canonical + " ".repeat(Lockfiles.MAX_BYTES)))
    }

    // 長い依存の連鎖も、再帰せずにたどる
    @Test
    fun handlesLongChains() {
        val chain = (1..Lockfiles.MAX_PACKAGES).map { LockedPackage("p$it", "1.0.0", "github:o/p$it", shaA, if (it < Lockfiles.MAX_PACKAGES) listOf("p${it + 1}") else emptyList()) }
        val lock = Lockfile.of(root, chain)
        val text = Lockfiles.write(lock)
        assertEquals(lock, parse(text).lockfile)
        val chainManifest = manifest.copy(dependencies = listOf(Dependency("p1", "1.0.0", "github:o/p1")))
        assertEquals(emptyList<ProjectDiagnosticCode>(), check(text, chainManifest))
        val cyclic = chain.dropLast(1) + chain.last().copy(dependencies = listOf("p1"))
        assertEquals("p1", Lockfiles.cycleMember(cyclic))
        assertThrows(IllegalArgumentException::class.java) { Lockfile.of(root, cyclic) }
    }

    @Test
    fun modelRejectsInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { Lockfile.of(root, listOf(numbers, numbers)) }
        assertThrows(IllegalArgumentException::class.java) { Lockfile.of(root, listOf(vector)) }
        assertThrows(IllegalArgumentException::class.java) { Lockfile.of(root, listOf(numbers.copy(sha256 = "A".repeat(64)))) }
        assertThrows(IllegalArgumentException::class.java) { Lockfile.of(root, listOf(numbers.copy(dependencies = listOf("numbers")))) }
    }
}
