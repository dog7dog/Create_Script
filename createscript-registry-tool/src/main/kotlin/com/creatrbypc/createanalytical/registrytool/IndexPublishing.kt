package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.project.JsonArray
import com.creatrbypc.createanalytical.project.JsonBoolean
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonParser
import com.creatrbypc.createanalytical.project.JsonString
import com.creatrbypc.createanalytical.project.registry.AcceptedIndex
import com.creatrbypc.createanalytical.project.registry.IndexHistory
import com.creatrbypc.createanalytical.project.registry.IndexSignature
import com.creatrbypc.createanalytical.project.registry.IndexSignatures
import com.creatrbypc.createanalytical.project.registry.IndexVerifier
import com.creatrbypc.createanalytical.project.registry.RegistryIndex
import com.creatrbypc.createanalytical.project.registry.RegistryIndexes
import com.creatrbypc.createanalytical.project.registry.RegistryKeys
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

// 公開済みの index（直前の Release の index.json と index.json.sig のバイト列）
class PublishedIndex(val index: ByteArray, val signature: ByteArray)

// index の生成・署名・公開前の確認・公開後の確認（S8.3 Phase 2。SIGNING.md）。
// 署名は、署名する人が手元で行う（秘密鍵は GitHub に置かない）。公開のワークフローは、署名済みの index を確かめて Release にするだけで、署名しない
object IndexPublishing {
    // この段階で署名に使える鍵は、テスト専用の鍵（鍵の ID が test-only- で始まるもの）だけ。本番の鍵は、鍵の生成の手順（SIGNING.md）を
    // 承認した後の段階で有効にする
    const val PRODUCTION_SIGNING_ENABLED = false

    // 直前に公開した index を受け入れる（署名と検査。期限切れでもよい: 間が空いた後の次の index を作るため）
    fun acceptPrevious(previous: PublishedIndex?, keys: RegistryKeys, now: Instant, report: Report): AcceptedIndex? {
        previous ?: return null
        val result = IndexVerifier(keys).verify(previous.index, previous.signature, null, now, allowExpired = true)
        result.diagnostics.forEach { report.error("the previously published index: ${it.format("index.json")}") }
        return result.accepted
    }

    class Built(val bytes: ByteArray, val index: RegistryIndex)

    // リポジトリから次の index を作る。serial は直前の index の次（無ければ 1）。generated は秒の単位に切り捨てる。
    // 直前の index との比較（巻き戻し・審査済みの SHA-256 と依存の変更・消えた版）に通らなければ作らない
    fun build(repository: LoadedRegistry, previous: AcceptedIndex?, generated: Instant, report: Report): Built? {
        if (!repository.ok) {
            repository.problems.forEach(report::error)
            return null
        }
        val serial = (previous?.index?.serial ?: 0) + 1
        val time = generated.truncatedTo(ChronoUnit.SECONDS)
        if (previous != null && time.isBefore(previous.index.generated)) {
            report.error("the time ${time} is before the previous index (${previous.index.generated})")
            return null
        }
        val result = RegistryIndexes.build(repository.config!!, repository.reserved!!, repository.entries, serial, time)
        result.diagnostics.forEach { report.error(it.format("index")) }
        val index = result.value ?: return null
        val bytes = RegistryIndexes.write(index)
        if (previous != null) {
            IndexHistory.compare(previous.index, previous.sha256, index, RegistryIndexes.sha256(bytes)).forEach { report.error(it.format("index")) }
            if (!report.ok) return null
            summarize(previous.index, index).forEach(report::notice)
        } else {
            report.notice("the first index: ${index.packages.size} packages, ${index.packages.values.sumOf { it.versions.size }} versions")
        }
        return Built(bytes, index)
    }

    // 直前の index からの変化（署名する人が、署名する前に読む）
    fun summarize(previous: RegistryIndex, current: RegistryIndex): List<String> {
        val lines = ArrayList<String>()
        lines += "serial ${previous.serial} -> ${current.serial}, expires ${current.expires}"
        for ((name, entry) in current.packages.toSortedMap()) {
            val before = previous.packages[name]
            if (before == null) {
                lines += "new package $name (${entry.source}) with ${entry.versions.keys.sorted().joinToString(", ")}"
                continue
            }
            for (version in (entry.versions.keys - before.versions.keys).sorted()) lines += "new version $name@$version sha256 ${entry.versions.getValue(version).sha256}"
            for ((version, value) in entry.versions.toSortedMap()) {
                if (value.yanked != null && before.versions[version]?.yanked == null) lines += "yanked $name@$version: ${value.yanked!!.reason}"
                if (value.yanked == null && before.versions[version]?.yanked != null) lines += "no longer yanked $name@$version"
            }
            if (entry.removed != null && before.removed == null) lines += "removed $name: ${entry.removed!!.reason}"
            if (entry.removed == null && before.removed != null) lines += "no longer removed $name"
            if (!entry.source.equals(before.source, ignoreCase = true)) lines += "source of $name: ${before.source} -> ${entry.source}"
        }
        return lines
    }

    // PEM の種類の名前（PKCS#8 の秘密鍵）。区切りの行は、この名前から作る
    const val PEM_LABEL = "PRIVATE KEY"
    val PEM_BEGIN = "-----BEGIN $PEM_LABEL-----"
    val PEM_END = "-----END $PEM_LABEL-----"

    // PKCS#8 の Ed25519 の秘密鍵（DER か、PEM）
    fun readPrivateKey(bytes: ByteArray): PrivateKey {
        val text = bytes.toString(Charsets.US_ASCII)
        val der = if (text.contains(PEM_BEGIN)) {
            try {
                Base64.getMimeDecoder().decode(text.substringAfter(PEM_BEGIN).substringBefore(PEM_END))
            } catch (error: IllegalArgumentException) {
                throw ToolFailure("the PEM file is not valid Base64")
            }
        } else {
            bytes
        }
        return try {
            KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(der))
        } catch (error: GeneralSecurityException) {
            throw ToolFailure("the key is not a PKCS#8 Ed25519 private key")
        }
    }

    // 署名する。署名するのは、正規形で検査を通る index だけ。署名した後、信頼する公開鍵の一覧（keys）で確かめる（違う鍵で署名した誤りを、公開の前に見つける）。
    // existing は、鍵を替える間に、ほかの鍵の署名を並べるときの既存の署名のファイル
    fun sign(indexBytes: ByteArray, key: PrivateKey, keyId: String, keys: RegistryKeys, existing: ByteArray?, report: Report): ByteArray? {
        if (!PRODUCTION_SIGNING_ENABLED && !keyId.startsWith(TrustedKeysFile.TEST_KEY_PREFIX)) {
            report.error("signing with the key $keyId is refused: in this phase only test keys (key IDs starting with ${TrustedKeysFile.TEST_KEY_PREFIX}) may sign; production signing starts after the key ceremony in SIGNING.md is approved")
            return null
        }
        val parsed = RegistryIndexes.parse(indexBytes)
        if (parsed.value == null) {
            parsed.diagnostics.forEach { report.error(it.format("index.json")) }
            return null
        }
        if (keys.find(keyId) == null) {
            report.error("the key $keyId is not in the trusted keys; add its public key first")
            return null
        }
        val others = existing?.let { bytes ->
            val result = IndexSignatures.parse(bytes)
            result.value ?: run {
                result.diagnostics.forEach { report.error(it.format("index.json.sig")) }
                return null
            }
        }.orEmpty().filter { it.keyId != keyId }
        val signature = try {
            Signature.getInstance("Ed25519").run {
                initSign(key)
                update(IndexSignatures.message(keyId, indexBytes))
                sign()
            }
        } catch (error: GeneralSecurityException) {
            throw ToolFailure("cannot sign: ${error.message}")
        }
        val file = IndexSignatures.write((others + IndexSignature(keyId, signature)).sortedBy { it.keyId })
        val check = IndexSignatures.verify(indexBytes, IndexSignatures.write(listOf(IndexSignature(keyId, signature))), RegistryKeys(listOfNotNull(keys.find(keyId))))
        if (check.value == null) {
            report.error("the signature does not match the trusted public key $keyId (was the right private key used?)")
            return null
        }
        return file
    }

    // 公開の前の確認（公開のワークフローと、署名する人の手元。publish の PR の検証も同じ）:
    // - レジストリが開いている（メンテナーがそろっている）
    // - 信頼する鍵がある。テスト専用の鍵は、allowTestKeys のとき（ローカルのテスト）だけ
    // - 署名がある・正しい。直前の公開済みの index に対して、通し番号の巻き戻し・再利用・審査済みの SHA-256 と依存の変更・消えた版が無い
    // - 通し番号は、直前の次（初めてなら 1）
    // - index は、このリポジトリ（審査を経た main）から作り直したものとバイト列まで同じ（署名した index が、審査した内容と違うことを防ぐ）
    fun publishCheck(repository: LoadedRegistry, indexBytes: ByteArray?, signatureBytes: ByteArray?, previous: PublishedIndex?, now: Instant, allowTestKeys: Boolean): Report {
        val report = Report()
        if (!repository.ok) {
            repository.problems.forEach(report::error)
            return report
        }
        val maintainers = repository.maintainers!!
        if (!maintainers.open) report.error(maintainers.closedReason())
        val keys = repository.keys!!
        if (keys.keys.keys.isEmpty()) report.error("keys/trusted-keys.json has no keys, so no index can be published")
        if (keys.hasTestKeys && !allowTestKeys) report.error("keys/trusted-keys.json has test keys (${TrustedKeysFile.TEST_KEY_PREFIX}…); a public index is never signed with a test key")
        if (indexBytes == null) report.error("there is no signed/index.json to publish")
        if (!report.ok || indexBytes == null) return report
        val previousAccepted = acceptPrevious(previous, keys.keys, now, report)
        if (!report.ok) return report
        val verified = IndexVerifier(keys.keys).verify(indexBytes, signatureBytes, previousAccepted, now)
        verified.diagnostics.forEach { report.error(it.format("signed/index.json")) }
        val accepted = verified.accepted ?: return report
        val expected = (previousAccepted?.index?.serial ?: 0) + 1
        if (accepted.index.serial != expected) {
            report.error(if (verified.unchanged) "this index (serial ${accepted.index.serial}) is already published" else "the serial must be $expected (the one after the published index), not ${accepted.index.serial}")
            return report
        }
        val rebuilt = RegistryIndexes.build(repository.config!!, repository.reserved!!, repository.entries, accepted.index.serial, accepted.index.generated)
        val rebuiltBytes = rebuilt.value?.let(RegistryIndexes::write)
        if (rebuiltBytes == null || !rebuiltBytes.contentEquals(indexBytes)) {
            report.error("signed/index.json is not the index of this repository's packages (rebuild it from this commit and sign it again)")
            return report
        }
        report.notice("signed/index.json: serial ${accepted.index.serial}, sha256 ${accepted.sha256}, signed by ${accepted.keyIds.joinToString(", ")}")
        return report
    }

    fun releaseTag(serial: Long) = "index-$serial"

    // 公開した後の確認。GitHub の API の Release の応答（GET /repos/…/releases/tags/index-<serial>）で、タグ・下書きでないこと・
    // 変更できない（immutable）こと・2 つのアセットの digest が手元のファイルの SHA-256 と同じことを確かめる
    fun checkRelease(releaseJson: ByteArray, indexBytes: ByteArray, signatureBytes: ByteArray): Report {
        val report = Report()
        val release = JsonParser.parse(releaseJson, 4 * 1024 * 1024).value as? JsonObject
        if (release == null) {
            report.error("cannot read the GitHub API response for the release")
            return report
        }
        val index = RegistryIndexes.parse(indexBytes).value
        if (index == null) {
            report.error("signed/index.json cannot be read")
            return report
        }
        val tag = (release.members["tag_name"] as? JsonString)?.value
        if (tag != releaseTag(index.serial)) report.error("the release tag is $tag, not ${releaseTag(index.serial)}")
        if ((release.members["draft"] as? JsonBoolean)?.value != false) report.error("the release is a draft (or the response does not say)")
        if ((release.members["immutable"] as? JsonBoolean)?.value != true) {
            report.error("the release is not immutable: enable immutable releases for the repository, delete this release and publish again (see SIGNING.md)")
        }
        val assets = (release.members["assets"] as? JsonArray)?.elements?.filterIsInstance<JsonObject>().orEmpty()
        for ((name, bytes) in listOf("index.json" to indexBytes, "index.json.sig" to signatureBytes)) {
            val matching = assets.filter { (it.members["name"] as? JsonString)?.value == name }
            val digest = (matching.singleOrNull()?.members?.get("digest") as? JsonString)?.value
            when {
                matching.size != 1 -> report.error("the release must have exactly one asset $name")
                digest != "sha256:${RegistryIndexes.sha256(bytes)}" -> report.error("the GitHub digest of $name is $digest, not the SHA-256 of the signed file")
            }
        }
        if (assets.size != 2) report.error("the release must have only index.json and index.json.sig")
        return report
    }
}
