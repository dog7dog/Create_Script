package com.creatrbypc.createanalytical.project.registry

import com.creatrbypc.createanalytical.project.ProjectDiagnosticCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.util.Base64

// S8.3 Phase 1: index の Ed25519 署名（Java 21 の標準 API）。鍵はすべてテスト専用で、実行のたびに作る（TestOnlySigner）
class IndexSignatureTest {
    private val signer = TestOnlySigner()
    private val keys = RegistryKeys(listOf(signer.trusted))
    private val index = RegistryIndexes.write(RegistryFixtures.index())

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun verifyCodes(indexBytes: ByteArray, signature: ByteArray?, keys: RegistryKeys = this.keys) = IndexSignatures.verify(indexBytes, signature, keys).let { result ->
        assertNull(result.value)
        result.diagnostics.map { it.code }
    }

    // RFC 8032 7.1 の TEST 1（公開されたテストベクトル。秘密鍵は使わない）: 32 バイトの公開鍵の変換と、JDK の Ed25519 の検証が RFC と一致する
    @Test
    fun rfc8032TestVector() {
        val key = Ed25519.publicKey(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"))
        val signature = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(Ed25519.verify(key, ByteArray(0), signature))
        assertFalse(Ed25519.verify(key, byteArrayOf(0), signature))
        assertFalse(Ed25519.verify(key, ByteArray(0), signature.copyOf(63)))
        assertThrows(IllegalArgumentException::class.java) { Ed25519.publicKey(ByteArray(31)) }
    }

    @Test
    fun acceptsAValidSignature() {
        val result = IndexSignatures.verify(index, signer.signatureFile(index), keys)
        assertEquals(listOf("test-only-key-1"), result.value, result.diagnostics.toString())
        // 署名のファイルの形
        val file = signer.signatureFile(index).toString(Charsets.UTF_8)
        assertTrue(file.matches(Regex("""\{"signatureVersion":1,"signatures":\[\{"keyId":"test-only-key-1","signature":"[A-Za-z0-9+/]{86}=="}]}""")), file)
    }

    @Test
    fun refusesMissingAndMalformedSignatures() {
        assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_MISSING), verifyCodes(index, null))
        assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_MISSING), verifyCodes(index, ByteArray(0)))
        val good = signer.signatureFile(index).toString(Charsets.UTF_8)
        val signature = Regex("\"signature\":\"([^\"]+)\"").find(good)!!.groupValues[1]
        val malformed = listOf(
            "not json",
            good.replace("\"signatureVersion\":1", "\"signatureVersion\":2"),
            good.replace(signature, signature.dropLast(4)),
            good.replace(signature, signature.replace("==", "")),
            good.replace(signature, signature.replace('A', '-')),
            good.replace("{\"keyId\"", "{\"extra\":1,\"keyId\""),
            good.replace("\"signatures\":[", "\"signatures\":[]").substringBefore("]") + "]}",
            good.replace("\"keyId\":\"test-only-key-1\"", "\"keyId\":\"Test Only\""),
            good.replace("{\"signatureVersion\"", "{ \"signatureVersion\""),
            good.replace("[{", "[{\"keyId\":\"test-only-key-1\",\"signature\":\"$signature\"},{"),
        )
        for (text in malformed) {
            val codes = verifyCodes(index, text.toByteArray())
            assertEquals(ProjectDiagnosticCode.SIGNATURE_MALFORMED, codes.first(), text)
        }
        // 余りのビットが 0 でない Base64（同じ 64 バイトの別の書き方）
        val last = signature[signature.length - 3]
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val other = alphabet[alphabet.indexOf(last) xor 1]
        val nonCanonical = signature.substring(0, signature.length - 3) + other + "=="
        assertTrue(Base64.getDecoder().decode(nonCanonical).contentEquals(Base64.getDecoder().decode(signature)), "the JDK decoder accepts both forms")
        assertEquals(ProjectDiagnosticCode.SIGNATURE_MALFORMED, verifyCodes(index, good.replace(signature, nonCanonical).toByteArray()).first())
    }

    @Test
    fun refusesUnknownKeysAndWrongSignatures() {
        val stranger = TestOnlySigner("test-only-stranger")
        // 知らない鍵の署名だけ
        val unknown = IndexSignatures.verify(index, stranger.signatureFile(index), keys)
        assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_UNKNOWN_KEY), unknown.diagnostics.map { it.code })
        assertEquals(listOf("test-only-stranger"), unknown.diagnostics.single().arguments)
        // 知らない鍵で、信頼する鍵の ID を名乗った署名
        val impostor = IndexSignature("test-only-key-1", stranger.signMessage(IndexSignatures.message("test-only-key-1", index)))
        assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_INVALID), verifyCodes(index, IndexSignatures.write(listOf(impostor))))
        // 署名を 1 ビット変えたもの
        val flipped = signer.sign(index).let { IndexSignature(it.keyId, it.signature.copyOf().also { s -> s[10] = (s[10].toInt() xor 1).toByte() }) }
        assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_INVALID), verifyCodes(index, IndexSignatures.write(listOf(flipped))))
        // S に群の位数 L を足した署名（同じ署名の別の表現）も拒否する
        val good = signer.sign(index).signature
        val s = BigInteger(1, good.copyOfRange(32, 64).reversedArray())
        val l = BigInteger.TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))
        val sPlusL = s.add(l).toByteArray().reversedArray().copyOf(32)
        if (s.add(l).bitLength() <= 256) {
            val malleable = good.copyOfRange(0, 32) + sPlusL
            assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_INVALID), verifyCodes(index, IndexSignatures.write(listOf(IndexSignature("test-only-key-1", malleable)))))
        }
        // 信頼する鍵の署名が 1 つでも合わなければ、合う署名があっても拒否する
        val other = TestOnlySigner("test-only-key-2")
        val both = RegistryKeys(listOf(signer.trusted, other.trusted))
        assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_INVALID), verifyCodes(index, IndexSignatures.write(listOf(signer.sign(index), flipped.copy(keyId = "test-only-key-2"))), both))
    }

    @Test
    fun refusesTamperedIndexes() {
        val signature = signer.signatureFile(index)
        val text = index.toString(Charsets.UTF_8)
        val tampered = listOf(
            text.replace(RegistryFixtures.sha('c'), RegistryFixtures.sha('f')),
            text.replace("\"serial\":1", "\"serial\":2"),
            text.replace("2026-12-20T10:00:00Z", "2027-01-20T10:00:00Z"),
            "$text\n",
            text.replace("{\"numbers\":\"3.0.0\"}", "{}"),
        )
        for (changed in tampered) assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_INVALID), verifyCodes(changed.toByteArray(), signature), changed)
        assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_INVALID), verifyCodes(index.copyOf(index.size - 1), signature))
    }

    // 署名の対象は、前置き（ドメイン）・鍵の ID・index のバイト列。index のバイト列だけへの署名や、別の鍵の ID の署名は通らない
    @Test
    fun domainSeparation() {
        val message = IndexSignatures.message("test-only-key-1", index)
        val prefix = "CreateScript-Registry index signature v1\u0000test-only-key-1\u0000".toByteArray(Charsets.US_ASCII)
        assertTrue(message.copyOfRange(0, prefix.size).contentEquals(prefix))
        assertTrue(message.copyOfRange(prefix.size, message.size).contentEquals(index))
        val bare = IndexSignature("test-only-key-1", signer.signMessage(index))
        assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_INVALID), verifyCodes(index, IndexSignatures.write(listOf(bare))))
        // 同じ秘密鍵でも、別の鍵の ID として署名したものは通らない
        val sameKeyOtherId = TrustedKey("test-only-key-9", signer.trusted.publicKey)
        val renamed = IndexSignature("test-only-key-9", signer.sign(index).signature)
        assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_INVALID), verifyCodes(index, IndexSignatures.write(listOf(renamed)), RegistryKeys(listOf(sameKeyOtherId))))
    }

    // 鍵の更新: 新旧の鍵の署名を並べた index は、どちらかの鍵だけを知るクライアントでも受け入れられる
    @Test
    fun keyRotation() {
        val next = TestOnlySigner("test-only-key-2")
        val file = IndexSignatures.write(listOf(signer.sign(index), next.sign(index)))
        assertEquals(listOf("test-only-key-1"), IndexSignatures.verify(index, file, RegistryKeys(listOf(signer.trusted))).value)
        assertEquals(listOf("test-only-key-2"), IndexSignatures.verify(index, file, RegistryKeys(listOf(next.trusted))).value)
        assertEquals(listOf("test-only-key-1", "test-only-key-2"), IndexSignatures.verify(index, file, RegistryKeys(listOf(signer.trusted, next.trusted))).value)
        // 古い鍵を外したクライアントは、古い鍵だけの署名を拒否する
        assertEquals(listOf(ProjectDiagnosticCode.SIGNATURE_UNKNOWN_KEY), verifyCodes(index, signer.signatureFile(index), RegistryKeys(listOf(next.trusted))))
        // 信頼する鍵の一覧の誤り
        assertThrows(IllegalArgumentException::class.java) { RegistryKeys(listOf(signer.trusted, TrustedKey("test-only-key-1", next.trusted.publicKey))) }
        assertThrows(IllegalArgumentException::class.java) { RegistryKeys(listOf(signer.trusted, TrustedKey("test-only-key-3", signer.trusted.publicKey))) }
        assertThrows(IllegalArgumentException::class.java) { TrustedKey("Bad Key", signer.trusted.publicKey) }
        // 署名は最大 4 つ
        val five = IndexSignatures.write((1..5).map { IndexSignature("test-only-k$it", ByteArray(64)) })
        assertEquals(listOf(ProjectDiagnosticCode.TOO_MANY_ENTRIES), IndexSignatures.parse(five).diagnostics.map { it.code })
    }
}
