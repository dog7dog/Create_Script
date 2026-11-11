package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.project.LockRoot
import com.creatrbypc.createanalytical.project.Lockfile
import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.registry.IndexSignatures
import com.creatrbypc.createanalytical.project.registry.IndexVerifier
import com.creatrbypc.createanalytical.project.registry.Owner
import com.creatrbypc.createanalytical.project.registry.RegistryIndexes
import com.creatrbypc.createanalytical.project.registry.RegistryKeys
import com.creatrbypc.createanalytical.project.registry.RegistryStatus
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.entry
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.library
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.tree
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.version
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.with
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64

// S8.3 Phase 2: index の生成・手元での署名・公開前の確認・公開後の確認。鍵はすべてテスト専用（実行のたびにメモリーの中で作る）
class PublishingTest {
    private val key = TestOnlyKey()
    private val numbersZip = library("numbers", "3.0.0")
    private val vectorZip = library("vector-math", "1.2.0", mapOf("numbers" to ("3.0.0" to "github:olga/numbers")))
    private val numbers = entry("numbers", Owner("olga", 2001), 900, mapOf("3.0.0" to version(GitHubReleases.sha256Hex(numbersZip), pullRequest = 3)))
    private val vector = entry("vector-math", Owner("alice", 1001), 555, mapOf("1.2.0" to version(GitHubReleases.sha256Hex(vectorZip), mapOf("numbers" to "3.0.0"))))
    private val generated: Instant = Instant.parse("2026-11-20T10:00:00Z")
    private val now: Instant = generated.plus(Duration.ofHours(2))

    private fun repo() = tree(listOf(numbers, vector), keys = listOf(key.trusted))

    private fun load(tree: RegistryTree) = LoadedRegistry.load(tree).also { assertTrue(it.ok, it.problems.toString()) }

    // 署名する人の手元: 作る → 署名する
    private fun buildAndSign(tree: RegistryTree, previous: PublishedIndex? = null, at: Instant = generated, signer: TestOnlyKey = key): PublishedIndex {
        val report = Report()
        val repository = load(tree)
        val accepted = IndexPublishing.acceptPrevious(previous, repository.keys!!.keys, now, report)
        val built = IndexPublishing.build(repository, accepted, at, report) ?: throw AssertionError(report.lines().toString())
        val signature = IndexPublishing.sign(built.bytes, signer.pair.private, signer.keyId, repository.keys!!.keys, null, report) ?: throw AssertionError(report.lines().toString())
        return PublishedIndex(built.bytes, signature)
    }

    private fun publishCheck(tree: RegistryTree, signed: PublishedIndex?, previous: PublishedIndex? = null, allowTestKeys: Boolean = true, at: Instant = now): Report {
        val withSigned = if (signed == null) tree else tree.with("signed/index.json", signed.index).with("signed/index.json.sig", signed.signature)
        val repository = load(withSigned)
        return IndexPublishing.publishCheck(repository, repository.signedIndex, repository.signedSignature, previous, at, allowTestKeys)
    }

    private fun assertRefused(report: Report, fragment: String) = assertTrue(report.errors.any { fragment in it }, "no error with '$fragment': ${report.lines()}")

    @Test
    fun signedIndexIsVerifiedLocallyAndPassesTheCheck() {
        val signed = buildAndSign(repo())
        // Phase 1 の検証（クライアントと同じ）で受け入れられる
        val accepted = IndexVerifier(RegistryKeys(listOf(key.trusted))).verify(signed.index, signed.signature, null, now).accepted
        assertNotNull(accepted)
        assertEquals(1L, accepted!!.index.serial)
        val report = publishCheck(repo(), signed)
        assertEquals(emptyList<String>(), report.errors)
        assertTrue(report.notices.single().startsWith("signed/index.json: serial 1, sha256 ${RegistryIndexes.sha256(signed.index)}, signed by test-only-key-1"))
        // 公開のワークフローでは、テスト専用の鍵を受け入れない
        assertRefused(publishCheck(repo(), signed, allowTestKeys = false), "a public index is never signed with a test key")
    }

    @Test
    fun refusesUnsignedAndWronglySignedIndexes() {
        val signed = buildAndSign(repo())
        assertRefused(publishCheck(repo(), PublishedIndex(signed.index, ByteArray(0))), "CS_REG_SIGNATURE_MISSING")
        val unsigned = repo().with("signed/index.json", signed.index)
        val repository = load(unsigned)
        assertRefused(IndexPublishing.publishCheck(repository, repository.signedIndex, repository.signedSignature, null, now, true), "CS_REG_SIGNATURE_MISSING")
        assertRefused(publishCheck(repo(), null), "there is no signed/index.json")
        // 信頼していない鍵の署名・信頼する鍵の ID を名乗る別の鍵の署名
        val stranger = TestOnlyKey("test-only-key-2")
        val strangerSigned = IndexSignatures.write(listOf(com.creatrbypc.createanalytical.project.registry.IndexSignature("test-only-key-2", sign(stranger, "test-only-key-2", signed.index))))
        assertRefused(publishCheck(repo(), PublishedIndex(signed.index, strangerSigned)), "CS_REG_SIGNATURE_UNKNOWN_KEY")
        val impostor = IndexSignatures.write(listOf(com.creatrbypc.createanalytical.project.registry.IndexSignature("test-only-key-1", sign(stranger, "test-only-key-1", signed.index))))
        assertRefused(publishCheck(repo(), PublishedIndex(signed.index, impostor)), "CS_REG_SIGNATURE_INVALID")
        // 署名した後に書き換えた index
        val tampered = signed.index.toString(Charsets.UTF_8).replace("\"serial\":1", "\"serial\":2").toByteArray()
        assertRefused(publishCheck(repo(), PublishedIndex(tampered, signed.signature)), "CS_REG_SIGNATURE_INVALID")
    }

    private fun sign(signer: TestOnlyKey, keyId: String, index: ByteArray): ByteArray = java.security.Signature.getInstance("Ed25519").run {
        initSign(signer.pair.private)
        update(IndexSignatures.message(keyId, index))
        sign()
    }

    @Test
    fun serialsOnlyGrow() {
        val first = buildAndSign(repo())
        // 次の index（版を足した）
        val more = tree(listOf(numbers.copy(versions = numbers.versions + ("3.1.0" to version("d".repeat(64), pullRequest = 4))), vector), keys = listOf(key.trusted))
        val second = buildAndSign(more, previous = first, at = generated.plusSeconds(60))
        assertEquals(emptyList<String>(), publishCheck(more, second, previous = first).errors)
        // 巻き戻し: 公開済みが 2 なのに、1 を公開しようとする
        assertRefused(publishCheck(repo(), first, previous = second), "CS_REG_INDEX_ROLLBACK")
        // 同じ通し番号で違う内容（2 の後に、別の内容の 2）
        val otherSecond = buildAndSign(tree(listOf(numbers, vector, entry("colors", Owner("olga", 2001), 901, mapOf("1.0.0" to version("e".repeat(64))))), keys = listOf(key.trusted)), previous = first, at = generated.plusSeconds(60))
        assertRefused(publishCheck(more, otherSecond, previous = second), "CS_REG_INDEX_SERIAL_REUSED")
        // 同じ index をもう一度
        assertRefused(publishCheck(more, second, previous = second), "is already published")
        // 通し番号を飛ばす（3 を作ってから、2 を公開済みとして 4 を作ったように見せることはできない: 公開済みの次だけ）
        assertRefused(publishCheck(more, second, previous = null), "the serial must be 1")
    }

    @Test
    fun reviewedValuesCannotChangeInTheNextIndex() {
        val first = buildAndSign(repo())
        val changed = tree(listOf(numbers.copy(versions = mapOf("3.0.0" to version("f".repeat(64), pullRequest = 3))), vector), keys = listOf(key.trusted))
        // 署名する人の手元で止まる
        val report = Report()
        val repository = load(changed)
        val previous = IndexPublishing.acceptPrevious(first, repository.keys!!.keys, now, report)
        assertNull(IndexPublishing.build(repository, previous, generated.plusSeconds(60), report))
        assertRefused(report, "CS_REG_SHA256_CHANGED")
        // 手で作って署名しても、公開の前の確認で止まる
        val forged = com.creatrbypc.createanalytical.project.registry.RegistryIndexes.build(repository.config!!, repository.reserved!!, repository.entries, 2, generated.plusSeconds(60)).value!!
        val bytes = RegistryIndexes.write(forged)
        val signature = IndexPublishing.sign(bytes, key.pair.private, key.keyId, repository.keys!!.keys, null, Report())!!
        assertRefused(publishCheck(changed, PublishedIndex(bytes, signature), previous = first), "CS_REG_SHA256_CHANGED")
    }

    @Test
    fun theSignedIndexMustBeTheReviewedRepository() {
        // 別の内容から作った index を、このリポジトリの index として公開しようとする
        val other = buildAndSign(tree(listOf(numbers), keys = listOf(key.trusted)))
        assertRefused(publishCheck(repo(), other), "is not the index of this repository's packages")
        // レジストリが閉じている（メンテナーが足りない）
        val closed = tree(listOf(numbers, vector), keys = listOf(key.trusted), maintainers = ToolFixtures.maintainers.copy(maintainers = listOf(ToolFixtures.lead)))
        assertRefused(publishCheck(closed, buildAndSign(repo())), "the registry is closed")
        // 信頼する鍵が無い
        assertRefused(publishCheck(tree(listOf(numbers, vector)), buildAndSign(repo())), "has no keys")
    }

    @Test
    fun signingRules() {
        val repository = load(repo())
        val bytes = IndexPublishing.build(repository, null, generated, Report())!!.bytes
        // テスト専用でない鍵の ID では署名しない（本番の署名はまだ有効でない）
        val report = Report()
        assertNull(IndexPublishing.sign(bytes, key.pair.private, "createscript-registry-key-1", repository.keys!!.keys, null, report))
        assertRefused(report, "only test keys")
        // 信頼する鍵に無い ID・違う秘密鍵
        assertRefused(Report().also { IndexPublishing.sign(bytes, key.pair.private, "test-only-key-9", repository.keys!!.keys, null, it) }, "is not in the trusted keys")
        assertRefused(Report().also { IndexPublishing.sign(bytes, TestOnlyKey().pair.private, key.keyId, repository.keys!!.keys, null, it) }, "does not match the trusted public key")
        // 正規形でない・壊れた index には署名しない
        assertRefused(Report().also { IndexPublishing.sign("{bad".toByteArray(), key.pair.private, key.keyId, repository.keys!!.keys, null, it) }, "CS_PKG_JSON_SYNTAX")
        assertRefused(Report().also { IndexPublishing.sign(bytes + " ".toByteArray(), key.pair.private, key.keyId, repository.keys!!.keys, null, it) }, "CS_REG_NOT_CANONICAL")
        // 鍵の更新: 2 つ目の鍵の署名を、既存の署名のファイルに足す
        val second = TestOnlyKey("test-only-key-2")
        val both = load(tree(listOf(numbers, vector), keys = listOf(key.trusted, second.trusted))).keys!!.keys
        val first = IndexPublishing.sign(bytes, key.pair.private, key.keyId, both, null, Report())!!
        val combined = IndexPublishing.sign(bytes, second.pair.private, second.keyId, both, first, Report())!!
        assertEquals(listOf("test-only-key-1", "test-only-key-2"), IndexSignatures.verify(bytes, combined, both).value)
        // 秘密鍵の読み取り（PKCS#8 の DER と PEM）
        val der = key.pair.private.encoded
        val pem = IndexPublishing.PEM_BEGIN + "\n" + Base64.getMimeEncoder().encodeToString(der) + "\n" + IndexPublishing.PEM_END + "\n"
        assertArrayEquals(der, IndexPublishing.readPrivateKey(der).encoded)
        assertArrayEquals(der, IndexPublishing.readPrivateKey(pem.toByteArray()).encoded)
        assertThrows(ToolFailure::class.java) { IndexPublishing.readPrivateKey("not a key".toByteArray()) }
    }

    @Test
    fun checksThePublishedRelease() {
        val signed = buildAndSign(repo())
        fun release(tag: String = "index-1", immutable: String = "true", draft: String = "false", indexDigest: String = RegistryIndexes.sha256(signed.index), extra: String = "") =
            """{"tag_name": "$tag", "draft": $draft, "immutable": $immutable, "assets": [{"name": "index.json", "digest": "sha256:$indexDigest"}, {"name": "index.json.sig", "digest": "sha256:${RegistryIndexes.sha256(signed.signature)}"}$extra]}""".toByteArray()
        assertEquals(emptyList<String>(), IndexPublishing.checkRelease(release(), signed.index, signed.signature).errors)
        assertRefused(IndexPublishing.checkRelease(release(immutable = "false"), signed.index, signed.signature), "the release is not immutable")
        assertRefused(IndexPublishing.checkRelease(release(immutable = "null"), signed.index, signed.signature), "the release is not immutable")
        assertRefused(IndexPublishing.checkRelease(release(draft = "true"), signed.index, signed.signature), "draft")
        assertRefused(IndexPublishing.checkRelease(release(tag = "index-2"), signed.index, signed.signature), "not index-1")
        assertRefused(IndexPublishing.checkRelease(release(indexDigest = "0".repeat(64)), signed.index, signed.signature), "the GitHub digest of index.json")
        assertRefused(IndexPublishing.checkRelease(release(extra = """, {"name": "notes.txt", "digest": "sha256:${"1".repeat(64)}"}"""), signed.index, signed.signature), "only index.json and index.json.sig")
    }

    // S8.2・Phase 1 との互換性: 作った index は Phase 1 の読み取りと同じで、各版は S8.2 のロックファイルになる
    @Test
    fun compatibleWithPhase1AndS82() {
        val signed = buildAndSign(repo())
        val index = RegistryIndexes.parse(signed.index).value!!
        assertArrayEquals(signed.index, RegistryIndexes.write(index))
        val closure = index.lockedClosure("vector-math", "1.2.0")!!
        val lockfile = Lockfile.of(LockRoot("app", "0.1.0"), closure)
        assertEquals(lockfile, Lockfiles.parse(Lockfiles.write(lockfile).toByteArray()).lockfile)
        for (locked in closure) assertEquals(RegistryStatus.REVIEWED, index.status(locked))
        assertEquals("github:alice/vector-math", closure.single { it.name == "vector-math" }.source)
    }
}
