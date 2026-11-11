package com.creatrbypc.createanalytical.project.registry

import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.time.Instant
import java.time.LocalDate

// テスト専用の署名者。鍵はテストを実行するたびにメモリーの中で作り、ファイルに保存しない（本番の鍵ではない。本番の鍵の生成・保管は Phase 2）。
// 鍵の ID は、テスト専用であることが分かるように test-only- で始める
class TestOnlySigner(val keyId: String = "test-only-key-1") {
    init {
        require(keyId.startsWith("test-only-"))
    }

    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    val trusted = TrustedKey(keyId, raw(pair.public))

    fun signMessage(message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(pair.private)
        update(message)
        sign()
    }

    fun sign(indexBytes: ByteArray) = IndexSignature(keyId, signMessage(IndexSignatures.message(keyId, indexBytes)))

    fun signatureFile(indexBytes: ByteArray): ByteArray = IndexSignatures.write(listOf(sign(indexBytes)))

    companion object {
        // X.509 の形の公開鍵の末尾 32 バイトが、RFC 8032 の公開鍵
        fun raw(key: PublicKey): ByteArray = key.encoded.copyOfRange(key.encoded.size - 32, key.encoded.size)
    }
}

object RegistryFixtures {
    val config = RegistryConfig(30, listOf("test-only-key-1"))
    val reserved = ReservedNames(mapOf("createscript" to "the CreateScript project", "core" to "the CreateScript core", "std" to "a future standard library"))
    val generated: Instant = Instant.parse("2026-11-20T10:00:00Z")
    val reviewDate: LocalDate = LocalDate.of(2026, 11, 20)

    fun sha(c: Char) = c.toString().repeat(64)

    fun version(c: Char, dependencies: Map<String, String> = emptyMap(), yanked: Withdrawal? = null, pullRequest: Long = 42) =
        EntryVersion(sha(c), dependencies, Review(pullRequest, listOf("dog7dog"), reviewDate), yanked)

    fun entry(name: String, versions: Map<String, EntryVersion>, repositoryId: Long = 100_000L + kotlin.math.abs(name.hashCode().toLong()), source: String = "github:owner/$name", removed: Withdrawal? = null) =
        PackageEntry(name, source, repositoryId, listOf(Owner("owner", 1001)), "Helpers for $name", "MIT", removed, versions)

    val numbers = entry("numbers", mapOf("3.0.0" to version('a'), "3.1.0" to version('b')))
    val vectorMath = entry("vector-math", mapOf("1.2.0" to version('c', mapOf("numbers" to "3.0.0"))))
    val entries = listOf(numbers, vectorMath)

    fun build(entries: List<PackageEntry> = this.entries, serial: Long = 1, generated: Instant = this.generated): RegistryResult<RegistryIndex> =
        RegistryIndexes.build(config, reserved, entries, serial, generated)

    fun index(entries: List<PackageEntry> = this.entries, serial: Long = 1, generated: Instant = this.generated): RegistryIndex =
        build(entries, serial, generated).let { it.value ?: throw AssertionError(it.diagnostics.toString()) }
}
