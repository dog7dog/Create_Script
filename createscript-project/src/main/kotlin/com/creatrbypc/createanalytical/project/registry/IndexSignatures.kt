package com.creatrbypc.createanalytical.project.registry

import com.creatrbypc.createanalytical.project.InternalCreateScriptProjectApi
import com.creatrbypc.createanalytical.project.CanonicalJson
import com.creatrbypc.createanalytical.project.FieldReader
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonString
import com.creatrbypc.createanalytical.project.JsonWriter
import com.creatrbypc.createanalytical.project.ProjectDiagnostic
import com.creatrbypc.createanalytical.project.ProjectDiagnosticCode
import com.creatrbypc.createanalytical.project.ProjectDiagnostics
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

// Ed25519（RFC 8032 の PureEdDSA）の検証。Java 21 の標準の API（SunEC、モジュール jdk.crypto.ec）だけを使う
internal object Ed25519 {
    const val PUBLIC_KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64

    // RFC 8410 の SubjectPublicKeyInfo（Ed25519）の、32 バイトの鍵の前に付く部分
    private val X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    // 32 バイトの公開鍵（RFC 8032 の形）から。使えない鍵なら IllegalArgumentException
    fun publicKey(raw: ByteArray): PublicKey {
        require(raw.size == PUBLIC_KEY_SIZE) { "an Ed25519 public key has $PUBLIC_KEY_SIZE bytes, not ${raw.size}" }
        return try {
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))
        } catch (error: GeneralSecurityException) {
            throw IllegalArgumentException("not a usable Ed25519 public key", error)
        }
    }

    // 署名が合うか。形が正しくない署名・使えない鍵も false（例外を投げない）
    fun verify(key: PublicKey, message: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != SIGNATURE_SIZE) return false
        return try {
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (error: GeneralSecurityException) {
            false
        }
    }
}

// 信頼する公開鍵。keyId は鍵ごとに決めた名前で、鍵を替えるとき（新しい鍵の ID を加え、古い鍵の ID を外す）に使う。
// 本番の公開鍵は Phase 2 で決めて CLI に埋め込む（このクラスには、まだ本番の鍵は無い）
@InternalCreateScriptProjectApi
public class TrustedKey(public val keyId: String, publicKey: ByteArray) {
    public val publicKey: ByteArray = publicKey.copyOf()
    internal val key: PublicKey

    init {
        require(RegistryRules.isValidKeyId(keyId)) { "invalid key ID: $keyId" }
        key = Ed25519.publicKey(this.publicKey)
    }
}

@InternalCreateScriptProjectApi
public class RegistryKeys(keys: List<TrustedKey>) {
    public val keys: List<TrustedKey> = keys.toList()

    init {
        require(this.keys.map { it.keyId }.distinct().size == this.keys.size) { "duplicate key IDs" }
        require(this.keys.map { it.publicKey.toList() }.distinct().size == this.keys.size) { "the same key under two key IDs" }
    }

    public fun find(keyId: String): TrustedKey? = keys.firstOrNull { it.keyId == keyId }
}

@InternalCreateScriptProjectApi
public data class IndexSignature(val keyId: String, val signature: ByteArray) {
    override fun equals(other: Any?): Boolean = other is IndexSignature && other.keyId == keyId && other.signature.contentEquals(signature)

    override fun hashCode(): Int = keyId.hashCode() * 31 + signature.contentHashCode()
}

// index.json.sig（signatureVersion 1）: {"signatureVersion":1,"signatures":[{"keyId":"…","signature":"<Base64>"}]}。CanonicalJson の正規形。
// 署名の対象は、ドメインを分ける前置き・鍵の ID・index.json のバイト列そのもの（message）。鍵を替える間は、新旧の鍵の署名を並べられる（最大 MAX_SIGNATURES）
@InternalCreateScriptProjectApi
public object IndexSignatures {
    public const val FILE_NAME: String = "index.json.sig"
    internal const val SIGNATURE_VERSION = 1L
    public const val MAX_SIGNATURES: Int = 4

    // ドメインを分ける前置き。同じ鍵で別の用途のデータに署名しても、index の署名として通らない
    public const val DOMAIN: String = "CreateScript-Registry index signature v1"

    private val FIELDS = setOf("signatureVersion", "signatures")
    private val ENTRY_FIELDS = setOf("keyId", "signature")

    // 署名するバイト列: DOMAIN（ASCII）・0x00・鍵の ID（ASCII）・0x00・index.json のバイト列
    public fun message(keyId: String, indexBytes: ByteArray): ByteArray {
        require(RegistryRules.isValidKeyId(keyId)) { "invalid key ID: $keyId" }
        return DOMAIN.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + keyId.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + indexBytes
    }

    public fun write(signatures: List<IndexSignature>): ByteArray = CanonicalJson.write(
        JsonWriter.obj(
            "signatureVersion" to JsonWriter.integer(SIGNATURE_VERSION),
            "signatures" to JsonWriter.array(
                signatures.map { JsonWriter.obj("keyId" to JsonWriter.string(it.keyId), "signature" to JsonWriter.string(Base64.getEncoder().encodeToString(it.signature))) },
            ),
        ),
    )

    public fun parse(bytes: ByteArray): RegistryResult<List<IndexSignature>> {
        val diagnostics = ProjectDiagnostics()
        val reader = RegistryReader(diagnostics)
        val (root, fields) = reader.document(bytes, RegistryLimits.MAX_SIGNATURE_BYTES, FILE_NAME, "signatureVersion", SIGNATURE_VERSION, ProjectDiagnosticCode.UNSUPPORTED_SIGNATURE_VERSION)
            ?: return RegistryResult(null, diagnostics.list)
        fields.allowOnly(FIELDS)
        val result = ArrayList<IndexSignature>()
        fields.array("signatures")?.let { list ->
            if (list.elements.size !in 1..MAX_SIGNATURES) {
                diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, list, "signatures", "1..$MAX_SIGNATURES")
                return RegistryResult(null, diagnostics.list)
            }
            for ((index, element) in list.elements.withIndex()) {
                val path = "signatures[$index]"
                val obj = element as? JsonObject
                if (obj == null) {
                    diagnostics.add(ProjectDiagnosticCode.WRONG_TYPE, element, path, "object")
                    continue
                }
                val entry = FieldReader(diagnostics, obj, path)
                entry.allowOnly(ENTRY_FIELDS)
                val keyId = reader.keyId(entry.string("keyId"), "$path.keyId")
                val signature = entry.string("signature")?.let { decode(it, diagnostics) }
                if (keyId != null && result.any { it.keyId == keyId }) diagnostics.add(ProjectDiagnosticCode.DUPLICATE_VALUE, obj, "signatures", keyId)
                if (keyId != null && signature != null) result.add(IndexSignature(keyId, signature))
            }
        }
        if (diagnostics.isEmpty && !CanonicalJson.write(root).contentEquals(bytes)) {
            diagnostics.add(ProjectDiagnosticCode.REGISTRY_NOT_CANONICAL, root, "index.json.sig must be canonical JSON (no spaces, sorted keys)")
        }
        return if (diagnostics.isEmpty) RegistryResult(result, emptyList()) else RegistryResult(null, diagnostics.list)
    }

    // 標準の Base64（パディングあり）で 64 バイト。別の書き方で同じバイト列になるもの（余りのビットが 0 でないもの）も拒否する
    private fun decode(value: JsonString, diagnostics: ProjectDiagnostics): ByteArray? {
        val bytes = try {
            Base64.getDecoder().decode(value.value)
        } catch (error: IllegalArgumentException) {
            null
        }
        if (bytes == null || bytes.size != Ed25519.SIGNATURE_SIZE || Base64.getEncoder().encodeToString(bytes) != value.value) {
            diagnostics.add(ProjectDiagnosticCode.SIGNATURE_MALFORMED, value, "a signature must be ${Ed25519.SIGNATURE_SIZE} bytes in standard Base64 with padding")
            return null
        }
        return bytes
    }

    // index.json のバイト列と index.json.sig のバイト列（無ければ null）を、信頼する鍵で確かめる。成功すれば、確かめた鍵の ID（空でない）。
    // - 署名が無い・署名のファイルが正しくない → 拒否
    // - 信頼する鍵の署名が 1 つも無い（知らない鍵の署名だけ）→ 拒否
    // - 信頼する鍵の署名が 1 つでも合わない → 拒否（合う署名がほかにあっても）
    // 知らない鍵の署名は、信頼する鍵の署名が合っていれば無視する（鍵を替える間に、新しい鍵を知らないクライアントがあるため）
    public fun verify(indexBytes: ByteArray, signatureBytes: ByteArray?, keys: RegistryKeys): RegistryResult<List<String>> {
        if (signatureBytes == null || signatureBytes.isEmpty()) return failure(ProjectDiagnosticCode.SIGNATURE_MISSING)
        val parsed = parse(signatureBytes)
        val signatures = parsed.value ?: return RegistryResult(null, listOf(ProjectDiagnostic(ProjectDiagnosticCode.SIGNATURE_MALFORMED, 1, 1, listOf("index.json.sig could not be read"))) + parsed.diagnostics)
        val trusted = signatures.mapNotNull { signature -> keys.find(signature.keyId)?.let { signature to it } }
        if (trusted.isEmpty()) return failure(ProjectDiagnosticCode.SIGNATURE_UNKNOWN_KEY, signatures.joinToString(", ") { it.keyId })
        for ((signature, key) in trusted) {
            if (!Ed25519.verify(key.key, message(key.keyId, indexBytes), signature.signature)) return failure(ProjectDiagnosticCode.SIGNATURE_INVALID, key.keyId)
        }
        return RegistryResult(trusted.map { it.second.keyId }, emptyList())
    }

    private fun failure(code: ProjectDiagnosticCode, vararg arguments: String) = RegistryResult<List<String>>(null, listOf(ProjectDiagnostic(code, 1, 1, arguments.toList())))
}
