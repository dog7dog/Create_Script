package com.creatrbypc.createanalytical.project.registry

import com.creatrbypc.createanalytical.project.CanonicalJson
import com.creatrbypc.createanalytical.project.JsonParser
import com.creatrbypc.createanalytical.project.JsonValue
import com.creatrbypc.createanalytical.project.JsonWriter
import com.creatrbypc.createanalytical.project.LockRoot
import com.creatrbypc.createanalytical.project.LockedPackage
import com.creatrbypc.createanalytical.project.Lockfile
import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.ProjectDiagnosticCode
import com.creatrbypc.createanalytical.project.ProjectRules
import com.creatrbypc.createanalytical.project.registry.RegistryFixtures.entry
import com.creatrbypc.createanalytical.project.registry.RegistryFixtures.index
import com.creatrbypc.createanalytical.project.registry.RegistryFixtures.numbers
import com.creatrbypc.createanalytical.project.registry.RegistryFixtures.sha
import com.creatrbypc.createanalytical.project.registry.RegistryFixtures.vectorMath
import com.creatrbypc.createanalytical.project.registry.RegistryFixtures.version
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

// S8.3 Phase 1: index.json の正規形・読み取り・パッケージの間の規則・リポジトリのファイルからの生成・S8.2 との互換性
class RegistryIndexTest {
    private val expected = "{\"expires\":\"2026-12-20T10:00:00Z\",\"generated\":\"2026-11-20T10:00:00Z\",\"indexVersion\":1,\"packages\":{" +
        "\"numbers\":{\"description\":\"Helpers for numbers\",\"license\":\"MIT\",\"repositoryId\":${numbers.repositoryId},\"source\":\"github:owner/numbers\",\"versions\":{" +
        "\"3.0.0\":{\"dependencies\":{},\"pullRequest\":42,\"sha256\":\"${sha('a')}\"}," +
        "\"3.1.0\":{\"dependencies\":{},\"pullRequest\":42,\"sha256\":\"${sha('b')}\"}}}," +
        "\"vector-math\":{\"description\":\"Helpers for vector-math\",\"license\":\"MIT\",\"repositoryId\":${vectorMath.repositoryId},\"source\":\"github:owner/vector-math\",\"versions\":{" +
        "\"1.2.0\":{\"dependencies\":{\"numbers\":\"3.0.0\"},\"pullRequest\":42,\"sha256\":\"${sha('c')}\"}}}},\"serial\":1}"

    private fun codes(bytes: ByteArray) = RegistryIndexes.parse(bytes).let { result ->
        assertNull(result.value)
        result.diagnostics.map { it.code }.distinct()
    }

    private fun codes(text: String) = codes(text.toByteArray(Charsets.UTF_8))

    // 正規形の JSON に変える（中身を書き換えた index を作るため）
    private fun canonical(text: String) = CanonicalJson.write(JsonParser.parse(text.toByteArray(Charsets.UTF_8), 1 shl 24).value!!).toString(Charsets.UTF_8)

    @Test
    fun buildsAndParsesTheCanonicalIndex() {
        val index = index()
        assertEquals(Instant.parse("2026-12-20T10:00:00Z"), index.expires)
        val bytes = RegistryIndexes.write(index)
        assertEquals(expected, bytes.toString(Charsets.UTF_8))
        assertEquals(index, RegistryIndexes.parse(bytes).value)
        // 同じ内容からは、入力の順によらず同じバイト列・同じ SHA-256
        val again = RegistryIndexes.write(index(listOf(vectorMath, numbers.copy(versions = numbers.versions.toList().reversed().toMap()))))
        assertArrayEquals(bytes, again)
        assertEquals(RegistryIndexes.sha256(bytes), RegistryIndexes.sha256(again))
        assertEquals(64, RegistryIndexes.sha256(bytes).length)
        // 取り下げ・削除も、書いて読み直すと同じ
        val withdrawn = index(
            listOf(
                numbers.copy(versions = numbers.versions + ("3.2.0" to version('d', yanked = Withdrawal("wrong results for negative numbers", LocalDate.of(2026, 11, 21))))),
                vectorMath,
                entry("old-colors", mapOf("1.0.0" to version('e')), removed = Withdrawal("the author asked for removal", LocalDate.of(2026, 11, 22))),
            ),
        )
        assertEquals(withdrawn, RegistryIndexes.parse(RegistryIndexes.write(withdrawn)).value)
    }

    @Test
    fun refusesIndexesThatAreNotCanonical() {
        assertTrue(RegistryIndexes.parse(expected.toByteArray()).value != null)
        val variants = listOf(
            expected.replace(",\"serial\":1}", ", \"serial\":1}"),
            expected + "\n",
            expected.replace("{\"expires\":\"2026-12-20T10:00:00Z\",\"generated\":\"2026-11-20T10:00:00Z\",", "{\"generated\":\"2026-11-20T10:00:00Z\",\"expires\":\"2026-12-20T10:00:00Z\","),
            expected.replace("Helpers for numbers", "Helpers for numb\\u0065rs"),
            expected.replace("Helpers for numbers", "Helpers for numbers\\/"),
            expected.replace("\"serial\":1", "\"serial\":1.0"),
        )
        for (text in variants) {
            val result = codes(text)
            assertTrue(result == listOf(ProjectDiagnosticCode.REGISTRY_NOT_CANONICAL) || result == listOf(ProjectDiagnosticCode.JSON_NUMBER_NOT_INTEGER), "$text: $result")
        }
        // 2^53 以上の整数は、正規形で書けない
        assertEquals(listOf(ProjectDiagnosticCode.REGISTRY_NOT_CANONICAL), codes(expected.replace("\"serial\":1", "\"serial\":9007199254740992")))
    }

    @Test
    fun rejectsInvalidValuesInTheIndex() {
        val cases = linkedMapOf(
            expected.replace("\"indexVersion\":1", "\"indexVersion\":2") to ProjectDiagnosticCode.UNSUPPORTED_INDEX_VERSION,
            expected.replace("\"serial\":1", "\"serial\":0") to ProjectDiagnosticCode.INVALID_ID,
            expected.replace("\"serial\":1", "\"serial\":-3") to ProjectDiagnosticCode.INVALID_ID,
            expected.replace("2026-11-20T10:00:00Z", "2026-11-20T10:00:00.5Z") to ProjectDiagnosticCode.INVALID_DATE,
            expected.replace("2026-11-20T10:00:00Z", "2026-11-20T19:00:00+09:00") to ProjectDiagnosticCode.INVALID_DATE,
            expected.replace("2026-11-20T10:00:00Z", "2026-11-31T10:00:00Z") to ProjectDiagnosticCode.INVALID_DATE,
            expected.replace("2026-11-20T10:00:00Z", "2026-12-31T23:59:60Z") to ProjectDiagnosticCode.INVALID_DATE,
            expected.replace("2026-12-20T10:00:00Z", "2026-11-20T10:00:00Z") to ProjectDiagnosticCode.INVALID_VALIDITY,
            expected.replace("2026-12-20T10:00:00Z", "2026-11-19T10:00:00Z") to ProjectDiagnosticCode.INVALID_VALIDITY,
            expected.replace("2026-12-20T10:00:00Z", "2027-02-18T10:00:01Z") to ProjectDiagnosticCode.INVALID_VALIDITY,
            canonical(expected.replace("\"numbers\":{\"description\"", "\"Numbers\":{\"description\"").replace("{\"numbers\":\"3.0.0\"}", "{\"Numbers\":\"3.0.0\"}")) to ProjectDiagnosticCode.INVALID_NAME,
            canonical(expected.replace("\"3.1.0\":", "\"3.1.0+x\":")) to ProjectDiagnosticCode.VERSION_BUILD_METADATA,
            canonical(expected.replace("\"3.1.0\":", "\"3.1\":")) to ProjectDiagnosticCode.INVALID_VERSION,
            expected.replace(sha('b'), sha('B')) to ProjectDiagnosticCode.INVALID_SHA256,
            expected.replace("github:owner/numbers", "github:owner/numbers/extra") to ProjectDiagnosticCode.INVALID_SOURCE,
            expected.replace("\"pullRequest\":42,\"sha256\":\"${sha('b')}\"", "\"pullRequest\":0,\"sha256\":\"${sha('b')}\"") to ProjectDiagnosticCode.INVALID_ID,
            canonical(expected.replace("\"pullRequest\":42,\"sha256\":\"${sha('b')}\"", "\"pullRequest\":42,\"reviewed\":true,\"sha256\":\"${sha('b')}\"")) to ProjectDiagnosticCode.UNKNOWN_FIELD,
            canonical(expected.replace("\"serial\":1", "\"serial\":1,\"mirror\":\"https://example.com\"")) to ProjectDiagnosticCode.UNKNOWN_FIELD,
            expected.replace("\"pullRequest\":42,\"sha256\":\"${sha('b')}\"", "\"sha256\":\"${sha('b')}\"") to ProjectDiagnosticCode.MISSING_FIELD,
            canonical(expected.replace("\"license\":\"MIT\",\"repositoryId\":${numbers.repositoryId}", "\"license\":\"MIT\",\"removed\":null,\"repositoryId\":${numbers.repositoryId}")) to ProjectDiagnosticCode.WRONG_TYPE,
            expected.replace("Helpers for numbers", "Helpers ‮for numbers") to ProjectDiagnosticCode.INVALID_TEXT,
            expected.replace("Helpers for numbers", "Helpers for numéros") to ProjectDiagnosticCode.INVALID_TEXT,
        )
        for ((text, code) in cases) assertEquals(listOf(code), codes(text), text)
    }

    // 重複キー・不正な Unicode・BOM・大きさ（JSON の段階で拒否する）
    @Test
    fun strictJsonAndUnicode() {
        assertEquals(listOf(ProjectDiagnosticCode.JSON_DUPLICATE_KEY), codes(expected.replace("\"serial\":1", "\"serial\":1,\"serial\":1")))
        assertEquals(listOf(ProjectDiagnosticCode.JSON_DUPLICATE_KEY), codes(expected.replace("\"3.0.0\":{\"dependencies\"", "\"3.0.0\":{\"dependencies\":{},\"dependencies\"")))
        assertEquals(listOf(ProjectDiagnosticCode.JSON_SYNTAX), codes(expected.replace("Helpers for numbers", "Helpers \\ud800 numbers")))
        assertEquals(listOf(ProjectDiagnosticCode.JSON_SYNTAX), codes(expected.replace("Helpers for numbers", "Helpers \\udc00 numbers")))
        assertEquals(listOf(ProjectDiagnosticCode.BYTE_ORDER_MARK), codes("﻿" + expected))
        // 正しくない UTF-8: 余計な長さの符号化（C0 AF）・サロゲートの符号化（ED A0 80）・途中で切れたもの
        for (bad in listOf(byteArrayOf(0xC0.toByte(), 0xAF.toByte()), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), byteArrayOf(0xE3.toByte(), 0x81.toByte()))) {
            val bytes = expected.replace("Helpers for numbers", "Helpers XX").toByteArray()
            val at = String(bytes, Charsets.US_ASCII).indexOf("XX")
            val changed = bytes.copyOfRange(0, at) + bad + bytes.copyOfRange(at + 2, bytes.size)
            assertEquals(listOf(ProjectDiagnosticCode.NOT_UTF8), codes(changed))
        }
        assertEquals(listOf(ProjectDiagnosticCode.FILE_TOO_LARGE), codes(ByteArray(RegistryLimits.MAX_INDEX_BYTES + 1) { ' '.code.toByte() }))
    }

    // 件数の上限（パッケージ・パッケージごとの版・全体の版・依存）
    @Test
    fun countLimits() {
        fun indexWith(packages: List<Pair<String, JsonValue>>) = CanonicalJson.write(
            JsonWriter.obj(
                "indexVersion" to JsonWriter.integer(1), "serial" to JsonWriter.integer(1),
                "generated" to JsonWriter.string("2026-11-20T10:00:00Z"), "expires" to JsonWriter.string("2026-12-20T10:00:00Z"),
                "packages" to JsonWriter.obj(*packages.toTypedArray()),
            ),
        )
        fun pkg(versions: Int, dependencies: Int = 0) = JsonWriter.obj(
            "source" to JsonWriter.string("github:owner/x"), "repositoryId" to JsonWriter.integer(1), "description" to JsonWriter.string("x"), "license" to JsonWriter.string("MIT"),
            "versions" to JsonWriter.obj(*(0 until versions).map { v -> "1.0.$v" to JsonWriter.obj(
                "sha256" to JsonWriter.string(sha('a')), "pullRequest" to JsonWriter.integer(1),
                "dependencies" to JsonWriter.obj(*(0 until dependencies).map { "d$it" to JsonWriter.string("1.0.0") }.toTypedArray()),
            ) }.toTypedArray()),
        )
        val tooManyPackages = indexWith((0..RegistryLimits.MAX_PACKAGES).map { "p$it" to JsonWriter.obj() })
        assertEquals(listOf(ProjectDiagnosticCode.TOO_MANY_ENTRIES), codes(tooManyPackages))
        assertEquals(listOf(ProjectDiagnosticCode.TOO_MANY_ENTRIES), codes(indexWith(listOf("p" to pkg(RegistryLimits.MAX_VERSIONS_PER_PACKAGE + 1)))))
        assertEquals(listOf(ProjectDiagnosticCode.TOO_MANY_ENTRIES), codes(indexWith(listOf("p" to pkg(1, RegistryLimits.MAX_DEPENDENCIES + 1)))))
        val perPackage = RegistryLimits.MAX_VERSIONS_PER_PACKAGE
        // 全体の版の数は、版の中身を読む前に数える（中身のある版を 200,001 個並べると、先に大きさの上限を超える）
        val minimal = JsonWriter.obj("versions" to JsonWriter.obj(*(0 until perPackage).map { "1.0.$it" to JsonWriter.obj() }.toTypedArray()))
        val tooManyVersions = indexWith((0..RegistryLimits.MAX_TOTAL_VERSIONS / perPackage).map { "p$it" to minimal })
        assertTrue(tooManyVersions.size < RegistryLimits.MAX_INDEX_BYTES)
        assertEquals(listOf(ProjectDiagnosticCode.TOO_MANY_ENTRIES), codes(tooManyVersions))
        assertEquals(listOf(ProjectDiagnosticCode.NO_VERSIONS), codes(indexWith(listOf("p" to pkg(0)))))
    }

    // ---- パッケージの間の規則（index の読み取りでも、リポジトリのファイルからの生成でも同じ） ----

    private fun buildCodes(entries: List<PackageEntry>) = RegistryFixtures.build(entries).let { result ->
        assertNull(result.value)
        result.diagnostics.map { it.code }.distinct()
    }

    @Test
    fun dependenciesMustBeReviewedPackages() {
        // 審査済みのパッケージは、レジストリにある名前・版にだけ依存する
        assertEquals(listOf(ProjectDiagnosticCode.UNKNOWN_DEPENDENCY), buildCodes(listOf(numbers, vectorMath.copy(versions = mapOf("1.2.0" to version('c', mapOf("numbers" to "9.9.9")))))))
        assertEquals(listOf(ProjectDiagnosticCode.UNKNOWN_DEPENDENCY), buildCodes(listOf(vectorMath)))
        assertEquals(listOf(ProjectDiagnosticCode.DEPENDS_ON_ITSELF), buildCodes(listOf(numbers.copy(versions = mapOf("3.0.0" to version('a', mapOf("numbers" to "3.0.0")))))))
        // 循環（名前・版の単位）
        val a = entry("alpha", mapOf("1.0.0" to version('a', mapOf("beta" to "1.0.0"))))
        val b = entry("beta", mapOf("1.0.0" to version('b', mapOf("gamma" to "1.0.0"))))
        val c = entry("gamma", mapOf("1.0.0" to version('c', mapOf("alpha" to "1.0.0"))))
        assertEquals(listOf(ProjectDiagnosticCode.DEPENDENCY_CYCLE), buildCodes(listOf(a, b, c)))
        // 名前をたどると戻るが、版が違うものは循環ではない。ただし、1 つの版の依存に同じ名前の 2 つの版があれば、S8.2 のロックファイルに入らない
        val a2 = entry("alpha", mapOf("1.0.0" to version('a', mapOf("beta" to "1.0.0")), "2.0.0" to version('d')))
        val b2 = entry("beta", mapOf("1.0.0" to version('b', mapOf("alpha" to "2.0.0"))))
        assertEquals(listOf(ProjectDiagnosticCode.DEPENDENCY_CONFLICT), buildCodes(listOf(a2, b2)))
        val n2 = numbers
        val left = entry("left", mapOf("1.0.0" to version('c', mapOf("numbers" to "3.0.0"))))
        val right = entry("right", mapOf("1.0.0" to version('d', mapOf("numbers" to "3.1.0"))))
        val top = entry("top", mapOf("1.0.0" to version('e', mapOf("left" to "1.0.0", "right" to "1.0.0"))))
        val conflict = RegistryFixtures.build(listOf(n2, left, right, top)).diagnostics.single()
        assertEquals(ProjectDiagnosticCode.DEPENDENCY_CONFLICT, conflict.code)
        assertEquals(listOf("top@1.0.0", "numbers", "3.0.0", "3.1.0"), conflict.arguments)
        // 同じ index を署名済みとして受け取っても、読み取りで同じエラーになる
        val forged = RegistryIndex(1, RegistryFixtures.generated, RegistryFixtures.generated.plusSeconds(3600), listOf(n2, left, right, top).associate { it.name to it.toIndexPackage() })
        assertEquals(listOf(ProjectDiagnosticCode.DEPENDENCY_CONFLICT), codes(RegistryIndexes.write(forged)))
    }

    @Test
    fun yankedAndRemoved() {
        val yanked = numbers.copy(versions = mapOf("3.0.0" to version('a', yanked = Withdrawal("broken", LocalDate.of(2026, 11, 21))), "3.1.0" to version('b')))
        // 取り下げた版に依存し続けてよい（ロックファイルで固定した利用者を壊さない）
        assertNotNull(RegistryFixtures.build(listOf(yanked, vectorMath)).value)
        // 削除したパッケージに依存できるのは、取り下げた版か、削除したパッケージだけ
        val removed = numbers.copy(removed = Withdrawal("malicious code", LocalDate.of(2026, 11, 22)))
        assertEquals(listOf(ProjectDiagnosticCode.DEPENDENCY_ON_REMOVED), buildCodes(listOf(removed, vectorMath)))
        val yankedDependent = vectorMath.copy(versions = mapOf("1.2.0" to version('c', mapOf("numbers" to "3.0.0"), Withdrawal("depends on a removed package", LocalDate.of(2026, 11, 22)))))
        val index = RegistryFixtures.index(listOf(removed, yankedDependent))
        assertEquals(RegistryStatus.REMOVED, index.status("numbers", "3.0.0", "github:owner/numbers", sha('a')))
        assertEquals(RegistryStatus.REMOVED, index.status("numbers", "4.0.0", "github:owner/numbers", sha('f')))
        assertEquals(RegistryStatus.YANKED, index.status("vector-math", "1.2.0", "github:owner/vector-math", sha('c')))
        assertNotNull(RegistryFixtures.build(listOf(removed, vectorMath.copy(removed = Withdrawal("depends on a removed package", LocalDate.of(2026, 11, 22))))).value)
    }

    @Test
    fun namesSourcesAndRepositories() {
        // 予約名と、予約名に似た名前
        assertEquals(listOf(ProjectDiagnosticCode.RESERVED_PACKAGE_NAME), buildCodes(listOf(entry("core", mapOf("1.0.0" to version('a'))))))
        assertEquals(listOf(ProjectDiagnosticCode.RESERVED_PACKAGE_NAME), buildCodes(listOf(entry("create-script", mapOf("1.0.0" to version('a'))))))
        assertEquals(listOf(ProjectDiagnosticCode.RESERVED_PACKAGE_NAME), buildCodes(listOf(entry("st-d", mapOf("1.0.0" to version('a'))))))
        // ほかのパッケージと形が同じ名前（ハイフン・0/o・1/l）
        val similar = RegistryFixtures.build(listOf(numbers, vectorMath, entry("vect0rmath", mapOf("1.0.0" to version('d'))))).diagnostics.single()
        assertEquals(ProjectDiagnosticCode.SIMILAR_NAME, similar.code)
        assertEquals(listOf("vector-math", "vect0rmath"), similar.arguments)
        // 同じ名前の 2 つのファイル・同じ取得元（大文字・小文字を区別しない）・同じリポジトリの ID
        assertEquals(listOf(ProjectDiagnosticCode.DUPLICATE_PACKAGE), buildCodes(listOf(numbers, numbers)))
        assertEquals(listOf(ProjectDiagnosticCode.DUPLICATE_SOURCE), buildCodes(listOf(numbers, entry("other", mapOf("1.0.0" to version('d')), source = "github:Owner/Numbers"))))
        assertEquals(listOf(ProjectDiagnosticCode.DUPLICATE_REPOSITORY), buildCodes(listOf(numbers, entry("other", mapOf("1.0.0" to version('d')), repositoryId = numbers.repositoryId))))
        // 通し番号・生成日時
        assertEquals(listOf(ProjectDiagnosticCode.INVALID_ID), RegistryFixtures.build(serial = 0).diagnostics.map { it.code })
        assertEquals(listOf(ProjectDiagnosticCode.INVALID_DATE), RegistryFixtures.build(generated = RegistryFixtures.generated.plusMillis(1)).diagnostics.map { it.code })
    }

    @Test
    fun closureLimit() {
        val count = RegistryLimits.MAX_CLOSURE + 1
        val chain = (0 until count).map { i ->
            val name = "p" + i.toString().padStart(5, '0').map { 'a' + (it - '0') }.joinToString("")
            val next = if (i + 1 < count) "p" + (i + 1).toString().padStart(5, '0').map { 'a' + (it - '0') }.joinToString("") else null
            entry(name, mapOf("1.0.0" to version('a', if (next == null) emptyMap() else mapOf(next to "1.0.0"))), repositoryId = i + 1L, source = "github:owner/$name")
        }
        val codes = RegistryIndexes.build(RegistryFixtures.config, ReservedNames(emptyMap()), chain, 1, RegistryFixtures.generated).diagnostics.map { it.code }.distinct()
        assertEquals(listOf(ProjectDiagnosticCode.TOO_MANY_ENTRIES), codes)
        assertNotNull(RegistryIndexes.build(RegistryFixtures.config, ReservedNames(emptyMap()), chain.drop(1), 1, RegistryFixtures.generated).value)
    }

    // ---- S8.2 との互換性 ----

    @Test
    fun compatibleWithS82LockFiles() {
        val index = index()
        // 1 つの版と依存は、そのまま S8.2 のロックファイルになる（name・version・source・sha256・dependencies は同じ規則）
        val closure = index.lockedClosure("vector-math", "1.2.0")!!
        assertEquals(
            listOf(
                LockedPackage("numbers", "3.0.0", "github:owner/numbers", sha('a')),
                LockedPackage("vector-math", "1.2.0", "github:owner/vector-math", sha('c'), listOf("numbers")),
            ),
            closure,
        )
        val lockfile = Lockfile.of(LockRoot("app", "0.1.0"), closure)
        val parsed = Lockfiles.parse(Lockfiles.write(lockfile).toByteArray()).lockfile!!
        assertEquals(lockfile, parsed)
        assertNull(index.lockedClosure("vector-math", "9.9.9"))
        // ロックファイルのパッケージの審査の状態
        for (locked in parsed.packages) assertEquals(RegistryStatus.REVIEWED, index.status(locked))
        assertEquals(RegistryStatus.SHA256_MISMATCH, index.status(parsed.packages[0].copy(sha256 = sha('f'))))
        assertEquals(RegistryStatus.VERSION_NOT_REVIEWED, index.status(parsed.packages[0].copy(version = "3.0.1")))
        assertEquals(RegistryStatus.NOT_IN_REGISTRY, index.status("tool", "0.3.0", "github:bob/tool", sha('f')))
        assertEquals(RegistryStatus.NAME_USED_BY_ANOTHER_SOURCE, index.status("numbers", "3.0.0", "github:mallory/numbers", sha('a')))
        assertEquals(RegistryStatus.NAME_MISMATCH, index.status("numberz", "3.0.0", "github:owner/numbers", sha('a')))
        // GitHub の名前は大文字・小文字を区別しない
        assertEquals(RegistryStatus.REVIEWED, index.status("numbers", "3.0.0", "github:OWNER/Numbers", sha('a')))
    }

    // レジストリの名前・版・取得元・SHA-256 は、S8.2 と同じ規則（レジストリだけの追加: 版のビルドメタデータを使わない）
    @Test
    fun sameValueRulesAsS82() {
        for (name in listOf("a", "vector-math", "x9", "con", "Vector", "a--b", "-a", "a".repeat(65), "com1")) {
            val accepted = RegistryFixtures.build(listOf(entry(name, mapOf("1.0.0" to version('a'))))).let { it.value != null || it.diagnostics.none { d -> d.code == ProjectDiagnosticCode.INVALID_NAME || d.code == ProjectDiagnosticCode.RESERVED_NAME } }
            assertEquals(ProjectRules.isValidName(name), accepted, name)
        }
        for (v in listOf("1.0.0", "1.0.0-rc.1", "0.0.1", "1.0", "01.0.0", "1.0.0+b")) {
            val accepted = RegistryFixtures.build(listOf(entry("pkg", mapOf(v to version('a'))))).value != null
            assertEquals(ProjectRules.isValidVersion(v) && '+' !in v, accepted, v)
        }
    }
}
