package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.registry.IndexPackage
import com.creatrbypc.createanalytical.project.registry.IndexSignature
import com.creatrbypc.createanalytical.project.registry.IndexSignatures
import com.creatrbypc.createanalytical.project.registry.IndexVersion
import com.creatrbypc.createanalytical.project.registry.RegistryIndex
import com.creatrbypc.createanalytical.project.registry.RegistryIndexes
import com.creatrbypc.createanalytical.project.registry.TrustedKey
import com.creatrbypc.createanalytical.project.registry.Withdrawal
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

// テスト専用の署名の鍵（S8.3 Phase 3）。実行のたびにメモリーの中で作り、保存しない。鍵の ID は test-only- で始まる
internal class TestOnlyRegistryKey(val keyId: String = "test-only-client-key") {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val trusted = TrustedKey(keyId, pair.public.encoded.copyOfRange(pair.public.encoded.size - 32, pair.public.encoded.size))

    fun signature(index: ByteArray, claimedKeyId: String = keyId): IndexSignature = IndexSignature(
        claimedKeyId,
        Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(IndexSignatures.message(claimedKeyId, index))
            sign()
        },
    )

    fun sign(index: ByteArray): ByteArray = IndexSignatures.write(listOf(signature(index)))
}

// テスト用のレジストリ（公式のものではないリポジトリの名前と、テスト専用の鍵）
internal object TestRegistry {
    const val REPOSITORY = "example/test-registry"
    val NOW: Instant = Instant.parse("2026-11-20T12:00:00Z")
    val GENERATED: Instant = Instant.parse("2026-11-20T10:00:00Z")

    fun version(sha256: String, dependencies: Map<String, String> = emptyMap(), yanked: Withdrawal? = null, pullRequest: Long = 42) =
        IndexVersion(sha256, dependencies, pullRequest, yanked)

    fun pkg(source: String, versions: Map<String, IndexVersion>, removed: Withdrawal? = null, repositoryId: Long = 1000L + (source.hashCode().toLong() and 0xffff)) =
        IndexPackage(source, repositoryId, "a test package", "MIT", removed, versions)

    // 正規形の index.json のバイト列（RegistryIndexes.write は、パッケージの間の規則を確かめずに書く。規則に反する index も作れる）
    fun index(packages: Map<String, IndexPackage>, serial: Long = 1, generated: Instant = GENERATED, validity: Duration = Duration.ofDays(30)): ByteArray =
        RegistryIndexes.write(RegistryIndex(serial, generated, generated.plus(validity), packages))
}

// メモリーの中の index の配布（GitHub の最新のリリースの代わり）
internal class MemoryIndexStore : IndexStore {
    var index: ByteArray? = null
    var signature: ByteArray? = null
    var network = true
    val downloads = ArrayList<String>()

    fun publish(index: ByteArray, signature: ByteArray?) {
        this.index = index
        this.signature = signature
    }

    override fun download(name: String, maxBytes: Long): ByteArray {
        if (!network) throw FetchFailure.Network("the network is not available (test)")
        downloads += name
        val bytes = (if (name == "index.json") index else signature) ?: throw FetchFailure.NotFound(URI("https://github.com/${TestRegistry.REPOSITORY}/releases/latest/download/$name"))
        if (bytes.size > maxBytes) throw FetchFailure.TooLarge(maxBytes)
        return bytes
    }
}

// 時計を進められるクライアントの組み立て
internal class RegistryHarness(val cacheRoot: java.nio.file.Path, val key: TestOnlyRegistryKey = TestOnlyRegistryKey()) {
    val store = MemoryIndexStore()
    var now: Instant = TestRegistry.NOW
    val trust = RegistryTrust(TestRegistry.REPOSITORY, listOf(key.trusted))

    fun cache(hooks: (String) -> Unit = {}) = RegistryCache(cacheRoot, TestRegistry.REPOSITORY, hooks, LockWait(5_000, 5, 1_000))

    fun client(trust: RegistryTrust = this.trust, hooks: (String) -> Unit = {}) = RegistryClient(trust, store, cache(hooks)) { now }

    fun publish(packages: Map<String, IndexPackage>, serial: Long, generated: Instant = TestRegistry.GENERATED): ByteArray {
        val index = TestRegistry.index(packages, serial, generated)
        store.publish(index, key.sign(index))
        return index
    }
}

internal fun withdrawal(reason: String) = Withdrawal(reason, LocalDate.parse("2026-11-19"))
