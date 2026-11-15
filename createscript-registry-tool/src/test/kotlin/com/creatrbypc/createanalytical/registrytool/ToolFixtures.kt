package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.install.FetchFailure
import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.project.PackageArchives
import com.creatrbypc.createanalytical.project.registry.EntryVersion
import com.creatrbypc.createanalytical.project.registry.Owner
import com.creatrbypc.createanalytical.project.registry.PackageEntry
import com.creatrbypc.createanalytical.project.registry.RegistryConfig
import com.creatrbypc.createanalytical.project.registry.RegistryFiles
import com.creatrbypc.createanalytical.project.registry.RegistryRules
import com.creatrbypc.createanalytical.project.registry.ReservedNames
import com.creatrbypc.createanalytical.project.registry.Review
import com.creatrbypc.createanalytical.project.registry.TrustedKey
import java.net.URI
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Instant
import java.time.LocalDate

// GitHub の代わり（メモリーの中）。実際の GitHub には接続しない
class FakeGitHub : RegistryGitHub {
    class Repo(val info: GitHubReleases.RepositoryInfo)

    val repositories = HashMap<String, GitHubReleases.RepositoryInfo>()
    val assets = HashMap<String, ByteArray>()
    val digests = HashMap<String, String?>()
    val immutable = HashMap<String, Boolean?>()
    val calls = ArrayList<String>()

    private fun key(source: String) = source.lowercase()

    fun repository(source: String, id: Long, ownerLogin: String, ownerId: Long, ownerType: String = "User", fullName: String = source.removePrefix("github:")) {
        repositories[key(source)] = GitHubReleases.RepositoryInfo(id, fullName, ownerId, ownerLogin, ownerType)
    }

    // createscript pack と同じ正規形のアーカイブを、変更できない Release として公開する
    fun publish(source: String, version: String, bytes: ByteArray, digest: String? = GitHubReleases.sha256Hex(bytes), immutable: Boolean? = true) {
        val id = "${key(source)}@$version"
        assets[id] = bytes
        digests[id] = digest
        this.immutable[id] = immutable
    }

    override fun repository(source: String): GitHubReleases.RepositoryInfo {
        calls += "repository $source"
        return repositories[key(source)] ?: throw FetchFailure.NotFound(URI("https://api.github.com/repos/${source.removePrefix("github:")}"))
    }

    override fun release(source: String, version: String): GitHubReleases.ReleaseInfo {
        calls += "release $source@$version"
        val id = "${key(source)}@$version"
        if (id !in assets) throw FetchFailure.NotFound(URI("https://api.github.com/repos/${source.removePrefix("github:")}/releases/tags/v$version"))
        return GitHubReleases.ReleaseInfo(digests[id], immutable[id])
    }

    override fun download(source: String, version: String): ByteArray {
        calls += "download $source@$version"
        return assets["${key(source)}@$version"] ?: throw FetchFailure.NotFound(URI("https://github.com/${source.removePrefix("github:")}/releases/download/v$version/createscript-package.zip"))
    }
}

// テスト専用の鍵（実行のたびにメモリーの中で作る。ファイルに保存しない。鍵の ID は test-only- で始まる）
class TestOnlyKey(val keyId: String = "test-only-key-1") {
    val pair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val trusted = TrustedKey(keyId, pair.public.encoded.copyOfRange(pair.public.encoded.size - 32, pair.public.encoded.size))
}

object ToolFixtures {
    val today: LocalDate = LocalDate.of(2026, 11, 20)
    val now: Instant = Instant.parse("2026-11-20T12:00:00Z")

    val lead = Maintainer("dog7dog", 1)
    val maintainerB = Maintainer("bea", 2)
    val maintainerC = Maintainer("chen", 3)
    val maintainerD = Maintainer("dara", 4)
    val maintainers = Maintainers("dog7dog", 3, 2, listOf(lead, maintainerB, maintainerC, maintainerD))

    val alice = PullRequest(7, "alice", 1001)

    // ライブラリのパッケージアーカイブ（createscript pack の正規形）。dependencies は name → (version, source)
    fun library(name: String, version: String, dependencies: Map<String, Pair<String, String>> = emptyMap(), manifestName: String = name): ByteArray {
        val deps = dependencies.entries.joinToString(", ") { (n, vs) -> "\"$n\": {\"version\": \"${vs.first}\", \"source\": \"${vs.second}\"}" }
        val module = name.replace('-', '_')
        val manifest = """{"schemaVersion": 2, "kind": "library", "name": "$manifestName", "version": "$version", "license": "MIT", "modules": ["$module"], "dependencies": {$deps}}"""
        return PackageArchives.write(mapOf("createscript.json" to manifest.toByteArray(), "modules/$module.cscr" to "fun one(): Int {\n    return 1\n}\n".toByteArray(), "LICENSE" to "MIT License\n".toByteArray()))
    }

    fun version(sha256: String, dependencies: Map<String, String> = emptyMap(), pullRequest: Long = 7, reviewers: List<String> = listOf("bea", "chen")) =
        EntryVersion(sha256, dependencies, Review(pullRequest, reviewers, today))

    fun entry(name: String, owner: Owner, repositoryId: Long, versions: Map<String, EntryVersion>, source: String = "github:${owner.login}/$name") =
        PackageEntry(name, source, repositoryId, listOf(owner), "Helpers for $name", "MIT", null, versions)

    // レジストリの作業ツリー（ファイルはすべて正規形で書く）
    fun tree(
        entries: List<PackageEntry> = emptyList(),
        maintainers: Maintainers = this.maintainers,
        keys: List<TrustedKey> = emptyList(),
        signingKeyIds: List<String> = listOf("test-only-key-1", "test-only-key-2"),
        extra: Map<String, ByteArray> = emptyMap(),
    ): RegistryTree {
        val files = java.util.TreeMap<String, ByteArray>()
        files["registry.json"] = RegistryFiles.writeConfig(RegistryConfig(30, signingKeyIds)).toByteArray()
        files["reserved-names.json"] = RegistryFiles.writeReserved(ReservedNames(mapOf("createscript" to "the CreateScript project", "core" to "the CreateScript core"))).toByteArray()
        files["maintainers.json"] = Maintainers.write(maintainers).toByteArray()
        files["keys/trusted-keys.json"] = TrustedKeysFile.write(keys).toByteArray()
        files[CodeOwners.PATH] = codeOwners(maintainers)
        files["README.md"] = "registry\n".toByteArray()
        for (entry in entries) files[RegistryRules.entryPath(entry.name)] = RegistryFiles.writeEntry(entry).toByteArray()
        files.putAll(extra)
        return RegistryTree(files)
    }

    // すべてのメンテナーをコードオーナーにした CODEOWNERS（S8.3 Phase 5、P4-M1）
    fun codeOwners(maintainers: Maintainers): ByteArray =
        ("# code owners\n* " + (if (maintainers.maintainers.isEmpty()) listOf(maintainers.lead) else maintainers.maintainers.map { it.login }).joinToString(" ") { "@$it" } + "\n").toByteArray()

    fun RegistryTree.with(path: String, bytes: ByteArray?) = RegistryTree(java.util.TreeMap(files).also { if (bytes == null) it.remove(path) else it[path] = bytes })
}
