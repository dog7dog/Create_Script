package com.creatrbypc.createanalytical.project.registry

import com.creatrbypc.createanalytical.project.ProjectDiagnosticCode
import com.creatrbypc.createanalytical.project.registry.RegistryFixtures.entry
import com.creatrbypc.createanalytical.project.registry.RegistryFixtures.numbers
import com.creatrbypc.createanalytical.project.registry.RegistryFixtures.vectorMath
import com.creatrbypc.createanalytical.project.registry.RegistryFixtures.version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

// S8.3 Phase 1: index を受け入れる手順（署名 → 読み取り → 日時 → 以前の index との比較）
class IndexVerifierTest {
    private val signer = TestOnlySigner()
    private val verifier = IndexVerifier(RegistryKeys(listOf(signer.trusted)))
    private val now: Instant = RegistryFixtures.generated.plus(Duration.ofHours(1))

    private fun signed(entries: List<PackageEntry> = RegistryFixtures.entries, serial: Long = 1, generated: Instant = RegistryFixtures.generated): Pair<ByteArray, ByteArray> {
        val bytes = RegistryIndexes.write(RegistryFixtures.index(entries, serial, generated))
        return bytes to signer.signatureFile(bytes)
    }

    private fun accept(index: Pair<ByteArray, ByteArray>, previous: AcceptedIndex? = null, at: Instant = now): AcceptedIndex {
        val result = verifier.verify(index.first, index.second, previous, at)
        return result.accepted ?: throw AssertionError(result.diagnostics.toString())
    }

    private fun refused(index: Pair<ByteArray, ByteArray>, previous: AcceptedIndex? = null, at: Instant = now): List<ProjectDiagnosticCode> {
        val result = verifier.verify(index.first, index.second, previous, at)
        assertNull(result.accepted)
        return result.diagnostics.map { it.code }
    }

    @Test
    fun acceptsASignedIndex() {
        val first = accept(signed())
        assertEquals(RegistryFixtures.index(), first.index)
        assertEquals(RegistryIndexes.sha256(first.bytes), first.sha256)
        assertEquals(listOf("test-only-key-1"), first.keyIds)
        // 同じ index をもう一度: 受け入れ、変わっていないと分かる
        val again = verifier.verify(signed().first, signed().second, first, now)
        assertTrue(again.unchanged)
        // 次の通し番号
        val next = verifier.verify(signed(serial = 2, generated = RegistryFixtures.generated.plusSeconds(60)).first, signed(serial = 2, generated = RegistryFixtures.generated.plusSeconds(60)).second, first, now)
        assertNotNull(next.accepted)
        assertFalse(next.unchanged)
    }

    // 署名は、index の JSON を読む前に確かめる（署名の無い・不正な index は、中身が正しくても、壊れていても同じく拒否）
    @Test
    fun signatureComesFirst() {
        val (bytes, signature) = signed()
        assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_MISSING), refused(bytes to ByteArray(0)))
        assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_INVALID), refused("{not json".toByteArray() to signature))
        val brokenButSigned = "{not json".toByteArray()
        assertEquals(listOf(ProjectDiagnosticCode.JSON_SYNTAX), refused(brokenButSigned to signer.signatureFile(brokenButSigned)))
        assertEquals(listOf(ProjectDiagnosticCode.FILE_TOO_LARGE), refused(ByteArray(RegistryLimits.MAX_INDEX_BYTES + 1) to signature))
        assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_UNKNOWN_KEY), refused(bytes to TestOnlySigner("test-only-stranger").signatureFile(bytes)))
    }

    @Test
    fun datesAndExpiry() {
        val index = signed()
        // 期限ちょうど・期限の後は拒否
        val expires = RegistryFixtures.generated.plus(Duration.ofDays(30))
        assertNotNull(accept(index, at = expires.minusSeconds(1)))
        assertEquals(listOf(ProjectDiagnosticCode.INDEX_EXPIRED), refused(index, at = expires))
        // オフラインでキャッシュを表示に使うときだけ、期限切れを許す
        assertNotNull(verifier.verify(index.first, index.second, null, expires.plusSeconds(1), allowExpired = true).accepted)
        // 生成日時が、時計のずれ（1 日）を超えて未来
        assertNotNull(accept(index, at = RegistryFixtures.generated.minus(Duration.ofHours(23))))
        assertEquals(listOf(ProjectDiagnosticCode.INDEX_NOT_YET_VALID), refused(index, at = RegistryFixtures.generated.minus(Duration.ofDays(1)).minusSeconds(1)))
    }

    @Test
    fun refusesRollbacks() {
        val second = accept(signed(serial = 5, generated = RegistryFixtures.generated.plusSeconds(600)))
        // 通し番号の巻き戻し（古い署名済みの index を出し直す攻撃）
        assertEquals(listOf(ProjectDiagnosticCode.INDEX_ROLLBACK), refused(signed(serial = 4, generated = RegistryFixtures.generated.plusSeconds(900)), second))
        assertEquals(listOf(ProjectDiagnosticCode.INDEX_ROLLBACK), refused(signed(serial = 1), second))
        // 同じ通し番号で、違う内容
        val other = signed(RegistryFixtures.entries + entry("colors", mapOf("1.0.0" to version('d'))), serial = 5, generated = RegistryFixtures.generated.plusSeconds(600))
        assertEquals(listOf(ProjectDiagnosticCode.INDEX_SERIAL_REUSED), refused(other, second))
        // 通し番号は進んだが、生成日時が戻った
        assertEquals(listOf(ProjectDiagnosticCode.INDEX_TIME_ROLLBACK), refused(signed(serial = 6, generated = RegistryFixtures.generated), second))
    }

    // 審査済みの SHA-256・依存は変わらない。パッケージ・版は消えない。リポジトリの ID は変わらない
    @Test
    fun reviewedValuesAreImmutable() {
        val first = accept(signed())
        val later = RegistryFixtures.generated.plusSeconds(3600)
        fun next(entries: List<PackageEntry>) = refused(signed(entries, serial = 2, generated = later), first)
        val changedSha = numbers.copy(versions = numbers.versions + ("3.0.0" to version('f')))
        assertEquals(listOf(ProjectDiagnosticCode.SHA256_CHANGED), next(listOf(changedSha, vectorMath)))
        val changedDependencies = vectorMath.copy(versions = mapOf("1.2.0" to version('c', mapOf("numbers" to "3.1.0"))))
        assertEquals(listOf(ProjectDiagnosticCode.DEPENDENCIES_CHANGED), next(listOf(numbers, changedDependencies)))
        assertEquals(listOf(ProjectDiagnosticCode.VERSION_DISAPPEARED), next(listOf(numbers.copy(versions = numbers.versions - "3.1.0"), vectorMath)))
        assertEquals(listOf(ProjectDiagnosticCode.PACKAGE_DISAPPEARED), next(listOf(numbers)))
        assertEquals(listOf(ProjectDiagnosticCode.REPOSITORY_CHANGED), next(listOf(numbers.copy(repositoryId = 999), vectorMath)))
        // 消してから、別の中身で登録し直す（2 回に分けても、間の index を受け入れていれば見つかる）
        val removedVersion = accept(signed(listOf(numbers.copy(versions = numbers.versions - "3.1.0"), vectorMath), serial = 2, generated = later), previous = null)
        assertEquals(listOf(ProjectDiagnosticCode.VERSION_DISAPPEARED), refused(signed(listOf(numbers.copy(versions = numbers.versions - "3.1.0"), vectorMath), serial = 2, generated = later), first))
        val readded = numbers.copy(versions = numbers.versions - "3.1.0" + ("3.1.0" to version('e')))
        assertEquals(listOf(ProjectDiagnosticCode.SHA256_CHANGED), refused(signed(listOf(readded, vectorMath), serial = 3, generated = later.plusSeconds(1)), first))
        assertNotNull(removedVersion)
        // 許される変更: 版の追加・パッケージの追加・取り下げ・削除・説明・取得元の名前（同じリポジトリの ID）
        val allowed = listOf(
            numbers.copy(
                description = "Number helpers",
                source = "github:owner/numbers-renamed",
                versions = numbers.versions + ("3.2.0" to version('d')) + ("3.0.0" to version('a', yanked = Withdrawal("broken", LocalDate.of(2026, 11, 21)))),
            ),
            vectorMath.copy(removed = Withdrawal("the author asked for removal", LocalDate.of(2026, 11, 22))),
            entry("colors", mapOf("1.0.0" to version('e'))),
        )
        assertNotNull(accept(signed(allowed, serial = 2, generated = later), first))
    }

    // リポジトリのファイル同士の比較（PR の前後）
    @Test
    fun comparesRepositoryFiles() {
        assertEquals(emptyList<Any>(), IndexHistory.compareEntries(RegistryFixtures.entries, RegistryFixtures.entries + entry("colors", mapOf("1.0.0" to version('d')))))
        val changed = numbers.copy(versions = numbers.versions + ("3.0.0" to version('f')))
        val diagnostic = IndexHistory.compareEntries(RegistryFixtures.entries, listOf(changed, vectorMath)).single()
        assertEquals(ProjectDiagnosticCode.SHA256_CHANGED, diagnostic.code)
        assertEquals(listOf("numbers@3.0.0", RegistryFixtures.sha('a'), RegistryFixtures.sha('f')), diagnostic.arguments)
    }
}
