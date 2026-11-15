package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.ProjectDiagnostic
import com.creatrbypc.createanalytical.project.ProjectDiagnosticCode
import com.creatrbypc.createanalytical.project.ProjectRules
import com.creatrbypc.createanalytical.project.registry.AcceptedIndex
import com.creatrbypc.createanalytical.project.registry.IndexSignatures
import com.creatrbypc.createanalytical.project.registry.IndexVerifier
import com.creatrbypc.createanalytical.project.registry.RegistryIndexes
import com.creatrbypc.createanalytical.project.registry.RegistryKeys
import com.creatrbypc.createanalytical.project.registry.RegistryLimits
import com.creatrbypc.createanalytical.project.registry.RegistryRules
import com.creatrbypc.createanalytical.project.registry.TrustedKey
import java.time.Instant

// どのレジストリを、どの鍵で信頼するか（S8.3 Phase 3）。repository は index を配布する GitHub のリポジトリ（持ち主/名前）
class RegistryTrust(val repository: String, keys: List<TrustedKey>) {
    val keys = RegistryKeys(keys)

    init {
        require(ProjectRules.isValidSource("github:$repository")) { "invalid repository: $repository" }
        // 公式のレジストリには、テスト専用の鍵を信頼させない（どの経路で作っても）
        require(!repository.equals(OFFICIAL_REPOSITORY, ignoreCase = true) || this.keys.keys.none { it.keyId.startsWith(TEST_KEY_PREFIX) }) {
            "a test key cannot be trusted by the official registry configuration"
        }
    }

    // 鍵が 1 つも無ければ、レジストリは使えない（署名を確かめられない index は使わない）
    val configured: Boolean get() = keys.keys.isNotEmpty()

    companion object {
        // テスト専用の鍵の ID の始まり（S8.3 Phase 1・2 と同じ）。本番の設定には入れられない
        const val TEST_KEY_PREFIX = "test-only-"

        // 公式の CreateScript-Registry（まだ作られていない。docs/registry.md）。index は、このリポジトリの最新のリリースのアセット
        const val OFFICIAL_REPOSITORY = "dog7dog/CreateScript-Registry"

        // 組み込みの本番の公開鍵。正式な鍵の生成と承認が済むまで空（空の間は、レジストリからのインストールを通信せずに拒否する）。
        // 鍵を替えるときは、新旧の鍵を並べたクライアントを出してから、古い鍵を外す（docs/registry.md の「Key rotation」）
        private val PRODUCTION_KEYS: List<TrustedKey> = emptyList()

        // 本番の設定。テスト専用の鍵が混ざっていれば作れない
        fun official(keys: List<TrustedKey> = PRODUCTION_KEYS): RegistryTrust {
            require(keys.none { it.keyId.startsWith(TEST_KEY_PREFIX) }) { "a test key cannot be trusted by the official registry configuration" }
            return RegistryTrust(OFFICIAL_REPOSITORY, keys)
        }
    }
}

// index と署名のファイルを得る相手。本番は GitHub の最新のリリース（GitHubIndexStore）。テストはメモリーの中・ローカルのサーバーを渡す
fun interface IndexStore {
    // name は index.json か index.json.sig。maxBytes を超えるものは受け取らない（FetchFailure）
    fun download(name: String, maxBytes: Long): ByteArray
}

class GitHubIndexStore(private val releases: GitHubReleases, private val repository: String) : IndexStore {
    override fun download(name: String, maxBytes: Long): ByteArray = releases.latestAsset(repository, name, maxBytes)
}

// index を得た結果
sealed interface RegistryLookup {
    // 使える index（署名・期限・履歴を確かめたもの）。fromCache は保存したものを使ったか。notes は利用者に示すこと
    class Available(val accepted: AcceptedIndex, val fromCache: Boolean, val notes: List<String>) : RegistryLookup

    // 使える index が無い（審査の状態を確かめられない）
    class Unavailable(val reason: Reason, val message: String) : RegistryLookup

    // 受け取った index を拒否した（署名・巻き戻しなど）。この index からは何もインストールしない
    class Refused(val message: String) : RegistryLookup

    enum class Reason { NOT_CONFIGURED, NO_INDEX, EXPIRED, NETWORK, CACHE_UNUSABLE }
}

// レジストリの index を得る（S8.3 Phase 3。docs/registry.md の「The client」）。
// - 信頼する鍵が無ければ、通信もキャッシュの読み書きもせずに Unavailable(NOT_CONFIGURED)
// - オンライン: index と署名をダウンロードし、IndexVerifier で確かめる（署名 → 中身 → 期限 → 前に受け入れたものとの比較）。
//   受け入れたものだけを保存する。前より古い index（serial が小さい）が来て、前のものがまだ有効なら、前のものを使う（同時に動く別の createscript が先に新しいものを保存した場合も、これになる）
// - 通信できなければ、保存した有効な index を（知らせて）使う。期限切れなら使わない
// - オフライン: 保存した index だけ。期限切れ・壊れたものは使わない
class RegistryClient(
    val trust: RegistryTrust,
    private val store: IndexStore?,
    private val cache: RegistryCache,
    private val clock: () -> Instant = Instant::now,
) {
    init {
        require(cache.repository.equals(trust.repository, ignoreCase = true)) { "the cache belongs to another registry" }
    }

    private val verifier = IndexVerifier(trust.keys)

    private fun describe(diagnostics: List<ProjectDiagnostic>, file: String = RegistryIndexes.FILE_NAME) = diagnostics.joinToString("; ") { it.format(file) }

    // 保存した index（確かめたもの。期限切れも返す）か、使えない理由
    private class Cached(val accepted: AcceptedIndex?, val history: RegistryCache.History?, val problem: String?)

    private fun readCache(now: Instant): Cached {
        return when (val state = cache.read()) {
            RegistryCache.State.Empty -> Cached(null, null, null)
            is RegistryCache.State.Present -> {
                if (state.index == null || state.signature == null) return Cached(null, state.history, "the saved registry index cannot be used: ${state.problem}")
                val result = verifier.verify(state.index, state.signature, null, now, allowExpired = true)
                val accepted = result.accepted
                    ?: return Cached(null, state.history, "the saved registry index cannot be used: ${describe(result.diagnostics)}")
                if (accepted.index.serial != state.history.serial || accepted.sha256 != state.history.indexSha256) {
                    return Cached(null, state.history, "the saved registry index does not match ${RegistryCache.HISTORY}")
                }
                Cached(accepted, state.history, null)
            }
        }
    }

    private fun expired(accepted: AcceptedIndex, now: Instant) = !now.isBefore(accepted.index.expires)

    private fun summary(accepted: AcceptedIndex) =
        "serial ${accepted.index.serial}, generated ${RegistryRules.formatTimestamp(accepted.index.generated)}, expires ${RegistryRules.formatTimestamp(accepted.index.expires)}"

    fun lookup(offline: Boolean): RegistryLookup {
        if (!trust.configured) {
            return RegistryLookup.Unavailable(
                RegistryLookup.Reason.NOT_CONFIGURED,
                "the official CreateScript-Registry has not been published yet: no registry signing key is built into this createscript",
            )
        }
        if (offline || store == null) return fromCache(clock(), null)
        val downloaded = try {
            download()
        } catch (failure: FetchFailure) {
            // S8.3 Phase 5（P4-M2）: オンラインでは、取得できなかったときに保存した index へ黙って戻らない（新しい削除・取り下げを隠されないため）。
            // 保存した index を使うのは、利用者が --offline を明示したときだけ
            return networkFailure(clock(), "the registry index could not be downloaded (${failure.message})")
        }
        return cache.withLock { accept(downloaded.first, downloaded.second, clock()) }
    }

    // 2 つのファイルの間に新しい index が公開されると、署名が合わない。その場合だけ、もう一度だけ両方を取り直す
    private fun download(): Pair<ByteArray, ByteArray> {
        var attempt = 0
        while (true) {
            val index = store!!.download(RegistryIndexes.FILE_NAME, RegistryLimits.MAX_INDEX_BYTES.toLong())
            // 署名のファイルが無い index は、署名の無い index として拒否する（通信の失敗として扱わない）
            val signature = try {
                store.download(IndexSignatures.FILE_NAME, RegistryLimits.MAX_SIGNATURE_BYTES.toLong())
            } catch (failure: FetchFailure.NotFound) {
                ByteArray(0)
            }
            val signed = IndexSignatures.verify(index, signature, trust.keys)
            if (signed.value != null || attempt++ >= 1 || signed.diagnostics.none { it.code == ProjectDiagnosticCode.SIGNATURE_INVALID }) return index to signature
        }
    }

    private fun networkFailure(now: Instant, problem: String): RegistryLookup {
        val saved = try {
            cache.withLock { readCache(now) }.accepted?.takeIf { !expired(it, now) }
        } catch (failure: InstallFailure) {
            null
        }
        val hint = if (saved == null) "" else
            "; the registry index saved before (${summary(saved)}) was not used, because it may not include packages removed or versions yanked since then. " +
                "To use it anyway, run the command again with --offline"
        return RegistryLookup.Unavailable(RegistryLookup.Reason.NETWORK, problem + hint)
    }

    private fun fromCache(now: Instant, networkProblem: String?): RegistryLookup {
        val cached = cache.withLock { readCache(now) }
        val accepted = cached.accepted
        if (accepted == null) {
            val reason = if (networkProblem != null) RegistryLookup.Reason.NETWORK else if (cached.problem != null) RegistryLookup.Reason.CACHE_UNUSABLE else RegistryLookup.Reason.NO_INDEX
            val message = listOfNotNull(
                networkProblem,
                cached.problem,
                if (cached.problem == null && cached.history == null) "no registry index has been downloaded yet" + if (networkProblem == null) " (run createscript install without --offline once)" else "" else null,
            ).joinToString("; ")
            return RegistryLookup.Unavailable(reason, message)
        }
        if (expired(accepted, now)) {
            return RegistryLookup.Unavailable(
                if (networkProblem != null) RegistryLookup.Reason.NETWORK else RegistryLookup.Reason.EXPIRED,
                listOfNotNull(networkProblem, "the saved registry index (${summary(accepted)}) has expired and is not used" + if (networkProblem == null) "; run createscript install without --offline to update it" else "").joinToString("; "),
            )
        }
        val notes = ArrayList<String>()
        notes += (if (networkProblem != null) "$networkProblem; " else "") +
            "offline: using the registry index saved before (${summary(accepted)}); packages removed and versions yanked after ${RegistryRules.formatTimestamp(accepted.index.generated)} are not known"
        return RegistryLookup.Available(accepted, true, notes)
    }

    // ロックを持って呼ぶ
    private fun accept(index: ByteArray, signature: ByteArray, now: Instant): RegistryLookup {
        val cached = readCache(now)
        val previous = cached.accepted
        val notes = ArrayList<String>()
        cached.problem?.let { notes += it }
        val result = verifier.verify(index, signature, previous, now)
        val accepted = result.accepted
        if (accepted == null) {
            // 前より古い（同時に動いた別の createscript が新しいものを保存した・古い index を送られた）。前のものが有効なら、それを使う
            if (previous != null && !expired(previous, now) && result.diagnostics.any { it.code == ProjectDiagnosticCode.INDEX_ROLLBACK }) {
                notes += "the registry sent an older index than the one accepted before; the one accepted before is used (${summary(previous)})"
                return RegistryLookup.Available(previous, true, notes)
            }
            return RegistryLookup.Refused("the registry index was refused: ${describe(result.diagnostics)}")
        }
        // 保存した index のファイルが使えなくても、履歴（serial・SHA-256・生成日時）とは比べる
        val history = cached.history
        if (previous == null && history != null) {
            val problem = when {
                accepted.index.serial < history.serial -> "it is older (serial ${accepted.index.serial}) than the index accepted before (serial ${history.serial})"
                accepted.index.serial == history.serial && accepted.sha256 != history.indexSha256 -> "it has the serial ${history.serial} of the index accepted before, but other contents"
                accepted.index.generated.isBefore(history.generated) -> "it was generated before the index accepted before"
                else -> null
            }
            if (problem != null) return RegistryLookup.Refused("the registry index was refused: $problem")
        }
        val signatureSha256 = SafeIo.sha256Hex(signature)
        if (history == null || previous == null || history.indexSha256 != accepted.sha256 || history.signatureSha256 != signatureSha256) {
            try {
                cache.store(accepted.index.serial, accepted.index.generated, accepted.bytes, signature)
            } catch (failure: InstallFailure) {
                notes += "the registry index was not saved: ${failure.message}"
            } catch (error: java.io.IOException) {
                notes += "the registry index was not saved: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        return RegistryLookup.Available(accepted, false, notes)
    }
}
