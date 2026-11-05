package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.Dependency
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonParser
import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.Manifests
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

// S8.2 Phase 3: インストール・依存の解決・同時実行の防止（復旧は RecoveryTest）
class InstallTest {
    @TempDir
    lateinit var temp: Path

    private val store = MemoryReleases()
    private lateinit var root: Path
    private lateinit var cache: Path
    private val vectorMath = Packages.source("vector-math")
    private val numbers = Packages.source("numbers")
    private val spec get() = PackageSpec(vectorMath, "1.2.0")

    @BeforeEach
    fun setUp() {
        root = createProject(temp.resolve("app"))
        cache = temp.resolve("cache")
    }

    private fun installer(dir: Path = root, hooks: InstallHooks = InstallHooks.NONE, limits: InstallLimits = InstallLimits(), cacheDir: Path = cache) =
        Installer(dir, PackageFetcher(store, ArchiveCache(cacheDir)), limits, hooks)

    private fun publishBasic() {
        store.publish(numbers, "3.0.0", Packages.library("numbers", "3.0.0"))
        store.publish(vectorMath, "1.2.0", Packages.library("vector-math", "1.2.0", mapOf("numbers" to ("3.0.0" to numbers))))
    }

    private fun failure(kind: InstallFailure.Kind, block: () -> Unit): InstallFailure {
        val failure = assertThrows(InstallFailure::class.java) { block() }
        assertEquals(kind, failure.kind, failure.message)
        return failure
    }

    private fun manifestWith(dependencies: String) =
        """{"schemaVersion": 1, "name": "app", "version": "0.1.0", "entry": "src/main.cscr", "dependencies": {$dependencies}}"""

    private fun dep(name: String, version: String, source: String = Packages.source(name)) = "\"$name\": {\"version\": \"$version\", \"source\": \"$source\"}"

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    // ---- 正常系 ----

    @Test
    fun installsAPackageAndItsDependencies() {
        publishBasic()
        val result = installer().install(spec, offline = false)
        assertEquals(listOf("numbers", "vector-math"), result.packages.map { it.name })
        assertTrue(result.packages.all { it.installed && !it.fetch.locked && it.fetch.origin == PackageFetcher.Origin.DOWNLOAD })
        assertTrue(result.packages.all { it.fetch.digest == PackageFetcher.DigestCheck.Matched && it.fetch.immutable == true })
        assertEquals(Dependency("vector-math", "1.2.0", vectorMath), result.added)
        assertTrue(result.manifestChanged && result.lockChanged)
        assertEquals("vector-math", result.packages[0].requiredBy)
        assertEquals(Manifests.FILE_NAME, result.packages[1].requiredBy)
        // 取得は、指定したもの → その依存（名前の順の深さ優先）の順
        assertEquals(listOf("$vectorMath@1.2.0", "$numbers@3.0.0"), store.downloads)
        // マニフェストとロックファイル（ロックファイルは lock check と同じ検査に通る）
        val manifest = Manifests.parse(Files.readAllBytes(root.resolve("createscript.json"))).manifest!!
        assertEquals(listOf(Dependency("vector-math", "1.2.0", vectorMath)), manifest.dependencies)
        val lockBytes = Files.readAllBytes(root.resolve("createscript.lock"))
        val lock = Lockfiles.parse(lockBytes)
        assertEquals(emptyList<Any>(), Lockfiles.check(lockBytes, lock, manifest))
        assertEquals(listOf("numbers", "vector-math"), lock.lockfile!!.packages.map { it.name })
        assertEquals(listOf("numbers"), lock.lockfile!!.packages[1].dependencies)
        assertEquals(GitHubReleases.sha256Hex(store.assets["$numbers@3.0.0"]!!), lock.lockfile!!.packages[0].sha256)
        // 置いたファイル（アーカイブの中身と同じ）と印
        val directory = root.resolve(".createscript/packages/vector-math/1.2.0")
        assertEquals(setOf(".createscript-package.json", "LICENSE", "createscript.json", "modules"), Files.list(directory).use { s -> s.map { it.fileName.toString() }.toList().toSet() })
        assertEquals("fun one(): Int {\n    return 1\n}\n", Files.readString(directory.resolve("modules/vector_math.cscr")))
        val marker = Marker.parse(Files.readAllBytes(directory.resolve(".createscript-package.json")))!!
        assertEquals(GitHubReleases.sha256Hex(store.assets["$vectorMath@1.2.0"]!!), marker.sha256)
        // 作業用のファイル・ジャーナルは残らない
        assertEquals(listOf("install.lock", "packages"), Files.list(root.resolve(".createscript")).use { s -> s.map { it.fileName.toString() }.sorted().toList() })
        assertEquals(emptyList<String>(), result.warnings)
    }

    // 再実行は何も変えない（ファイルの中身も、ファイルそのもの（fileKey）も同じ）。ロックファイルがあればキャッシュから使い、通信しない
    @Test
    fun runningAgainChangesNothing() {
        publishBasic()
        installer().install(spec, false)
        val before = snapshot(root)
        store.network = false
        val again = installer().install(spec, false)
        assertFalse(again.manifestChanged || again.lockChanged)
        assertNull(again.added)
        assertTrue(again.packages.none { it.installed })
        assertTrue(again.packages.all { it.fetch.origin == PackageFetcher.Origin.CACHE && it.fetch.locked })
        assertEquals(before, snapshot(root))
        installer().install(null, false)
        installer().install(null, true)
        assertEquals(before, snapshot(root))
    }

    // ロックファイルとキャッシュだけで、通信せずに同じものを置き直す（パッケージを消したプロジェクト、別の場所に写したプロジェクト）
    @Test
    fun reproducesOfflineFromTheLockAndTheCache() {
        publishBasic()
        installer().install(spec, false)
        val reference = contents(root)
        deleteTree(root.resolve(".createscript/packages"))
        store.network = false
        val result = installer().install(null, offline = true)
        assertTrue(result.packages.all { it.installed && it.fetch.origin == PackageFetcher.Origin.CACHE })
        assertFalse(result.manifestChanged || result.lockChanged)
        assertEquals(reference, contents(root))
        val clone = createProject(temp.resolve("clone"))
        Files.copy(root.resolve("createscript.json"), clone.resolve("createscript.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Files.copy(root.resolve("createscript.lock"), clone.resolve("createscript.lock"))
        installer(clone).install(null, true)
        assertEquals(reference, contents(clone))
        // キャッシュが無ければ（オフライン）、何も変えずに NETWORK
        val bare = createProject(temp.resolve("bare"))
        Files.copy(root.resolve("createscript.json"), bare.resolve("createscript.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Files.copy(root.resolve("createscript.lock"), bare.resolve("createscript.lock"))
        val before = snapshot(bare)
        assertTrue(failure(InstallFailure.Kind.NETWORK) { installer(bare, cacheDir = temp.resolve("empty-cache")).install(null, true) }.message!!.contains("not in the cache"))
        assertUnchanged(before, bare)
        // 書き換えられたキャッシュは使わない（オフラインなので失敗し、理由を示す）
        val entry = cache.resolve("archives/sha256/${GitHubReleases.sha256Hex(store.assets["$numbers@3.0.0"]!!)}.zip")
        Files.write(entry, Files.readAllBytes(entry) + byteArrayOf(0))
        deleteTree(clone.resolve(".createscript/packages"))
        val cloneBefore = snapshot(clone)
        val tampered = failure(InstallFailure.Kind.NETWORK) { installer(clone).install(null, true) }
        assertTrue(tampered.message!!.contains("not in the cache; cache not used:") && tampered.message!!.contains("does not match its SHA-256"), tampered.message)
        assertUnchanged(cloneBefore, clone)
        // ロックファイルの無い依存は、オフラインでは記録する SHA-256 が無いので取得できない
        val noLock = createProject(temp.resolve("nolock"), manifestWith(dep("numbers", "3.0.0")))
        assertThrows(InstallFailure::class.java) { installer(noLock).install(null, true) }
    }

    // ロックファイルの既知の SHA-256 と違うアーカイブは、必ず拒否する（キャッシュにも、ダウンロードにも）
    @Test
    fun refusesAHashMismatchWithTheLock() {
        publishBasic()
        installer().install(spec, false)
        store.publish(numbers, "3.0.0", Packages.library("numbers", "3.0.0", body = "fun one(): Int {\n    return 2\n}\n"))
        deleteTree(root.resolve(".createscript/packages"))
        deleteTree(cache)
        val before = snapshot(root)
        val failure = failure(InstallFailure.Kind.INVALID) { installer().install(null, false) }
        assertTrue(failure.message!!.contains("does not match createscript.lock"), failure.message)
        assertUnchanged(before, root)
        // 指定し直しても同じ（install github:…@v… も、ロックファイルの SHA-256 を使う）
        assertThrows(InstallFailure::class.java) { installer().install(PackageSpec(numbers, "3.0.0"), false) }
        assertUnchanged(before, root)
        // 拒否したアーカイブはキャッシュに入れない
        assertFalse(Files.exists(cache.resolve("archives/sha256/${GitHubReleases.sha256Hex(store.assets["$numbers@3.0.0"]!!)}.zip")))
    }

    @Test
    fun refusesInvalidArchives() {
        val cases = mapOf(
            "not a ZIP" to "not a zip file".toByteArray(),
            "another name" to Packages.library("other", "3.0.0"),
            "another version" to Packages.library("numbers", "3.0.1"),
        )
        for ((label, bytes) in cases) {
            store.publish(numbers, "3.0.0", bytes)
            val project = createProject(temp.resolve("p-${label.replace(' ', '-')}"), manifestWith(dep("numbers", "3.0.0")))
            val before = snapshot(project)
            val failure = failure(InstallFailure.Kind.INVALID) { installer(project).install(null, false) }
            assertTrue(failure.message!!.contains("not a valid package"), "$label: ${failure.message}")
            assertUnchanged(before, project)
        }
        // 見つからない Release
        val missing = createProject(temp.resolve("missing"), manifestWith(dep("absent", "1.0.0")))
        val before = snapshot(missing)
        assertTrue(failure(InstallFailure.Kind.INVALID) { installer(missing).install(null, false) }.message!!.contains("not found"))
        assertUnchanged(before, missing)
    }

    @Test
    fun refusesDependencyCycles() {
        val alpha = Packages.source("alpha")
        val beta = Packages.source("beta")
        store.publish(alpha, "1.0.0", Packages.library("alpha", "1.0.0", mapOf("beta" to ("1.0.0" to beta))))
        store.publish(beta, "1.0.0", Packages.library("beta", "1.0.0", mapOf("alpha" to ("1.0.0" to alpha))))
        val before = snapshot(root)
        val failure = failure(InstallFailure.Kind.INVALID) { installer().install(PackageSpec(alpha, "1.0.0"), false) }
        assertTrue(failure.message!!.contains("dependency cycle: alpha -> beta -> alpha"), failure.message)
        assertUnchanged(before, root)
        // プロジェクト自身への依存
        store.publish(Packages.source("gamma"), "1.0.0", Packages.library("gamma", "1.0.0", mapOf("app" to ("0.1.0" to Packages.source("app")))))
        assertTrue(failure(InstallFailure.Kind.INVALID) { installer().install(PackageSpec(Packages.source("gamma"), "1.0.0"), false) }.message!!.contains("which is this project"))
        assertUnchanged(before, root)
    }

    @Test
    fun refusesConflicts() {
        val gamma = Packages.source("gamma")
        store.publish(gamma, "1.0.0", Packages.library("gamma", "1.0.0"))
        store.publish(gamma, "2.0.0", Packages.library("gamma", "2.0.0"))
        store.publish("github:other/gamma", "1.0.0", Packages.library("gamma", "1.0.0"))
        fun publishPair(alphaNeeds: Pair<String, String>, betaNeeds: Pair<String, Pair<String, String>>) {
            store.publish(Packages.source("alpha"), "1.0.0", Packages.library("alpha", "1.0.0", mapOf("gamma" to alphaNeeds)))
            store.publish(Packages.source("beta"), "1.0.0", Packages.library("beta", "1.0.0", mapOf(betaNeeds)))
        }
        val cases = listOf(
            Triple("version", { publishPair("1.0.0" to gamma, "gamma" to ("2.0.0" to gamma)) }, "conflicting requirements for gamma"),
            Triple("source", { publishPair("1.0.0" to gamma, "gamma" to ("1.0.0" to "github:other/gamma")) }, "conflicting requirements for gamma"),
            Triple("two names", { publishPair("1.0.0" to gamma, "delta" to ("1.0.0" to gamma)) }, "is required as two packages"),
        )
        for ((label, setUp, expected) in cases) {
            setUp()
            val project = createProject(temp.resolve("c-${label.replace(' ', '-')}"), manifestWith(dep("alpha", "1.0.0") + ", " + dep("beta", "1.0.0")))
            val before = snapshot(project)
            val failure = failure(InstallFailure.Kind.INVALID) { installer(project).install(null, false) }
            assertTrue(failure.message!!.contains(expected), "$label: ${failure.message}")
            assertUnchanged(before, project)
        }
        // 指定したパッケージと、マニフェストの既存の依存の衝突（取得元が違えば置き換えない）
        publishBasic()
        val project = createProject(temp.resolve("c-spec"), manifestWith(dep("vector-math", "1.2.0", "github:other/vector-math")))
        val before = snapshot(project)
        assertTrue(failure(InstallFailure.Kind.INVALID) { installer(project).install(spec, false) }.message!!.contains("already has the dependency vector-math"))
        assertUnchanged(before, project)
    }

    @Test
    fun enforcesTheLimits() {
        assertEquals(InstallLimits(256, 32, 128L * 1024 * 1024), InstallLimits())
        publishBasic()
        val before = snapshot(root)
        val cases = mapOf(
            InstallLimits(maxPackages = 1) to "more than 1 packages",
            InstallLimits(maxDepth = 1) to "nested deeper than 1 levels",
            InstallLimits(maxTotalBytes = 100) to "larger than 100 bytes",
        )
        for ((limits, expected) in cases) {
            val failure = failure(InstallFailure.Kind.INVALID) { installer(limits = limits).install(spec, false) }
            assertTrue(failure.message!!.contains(expected), failure.message)
            assertUnchanged(before, root)
        }
        // ちょうど上限は通る
        installer(limits = InstallLimits(maxPackages = 2, maxDepth = 2)).install(spec, false)
    }

    // マニフェストは dependencies の 1 項目だけを足す・変える（ほかの項目の値と順序はそのまま）。版を変えても、前の版のディレクトリは消さない
    @Test
    fun keepsTheOtherFieldsOfTheManifest() {
        publishBasic()
        val zeta = Packages.source("zeta")
        store.publish(zeta, "1.0.0", Packages.library("zeta", "1.0.0"))
        val manifest = """{"name": "app", "license": "MIT", "schemaVersion": 1, "entry": "src/main.cscr", "dependencies": {${dep("zeta", "1.0.0")}, ${dep("numbers", "3.0.0")}}, "version": "0.1.0"}"""
        val project = createProject(temp.resolve("ordered"), manifest)
        installer(project).install(spec, false)
        fun keys(): Pair<List<String>, List<String>> {
            val json = JsonParser.parse(Files.readAllBytes(project.resolve("createscript.json")), 65536).value as JsonObject
            return json.members.keys.toList() to (json.members["dependencies"] as JsonObject).members.keys.toList()
        }
        assertEquals(listOf("name", "license", "schemaVersion", "entry", "dependencies", "version") to listOf("zeta", "numbers", "vector-math"), keys())
        store.publish(vectorMath, "1.3.0", Packages.library("vector-math", "1.3.0", mapOf("numbers" to ("3.0.0" to numbers))))
        val updated = installer(project).install(PackageSpec(vectorMath, "1.3.0"), false)
        assertEquals(Dependency("vector-math", "1.3.0", vectorMath), updated.added)
        assertEquals(listOf("name", "license", "schemaVersion", "entry", "dependencies", "version") to listOf("zeta", "numbers", "vector-math"), keys())
        val lock = Lockfiles.parse(Files.readAllBytes(project.resolve("createscript.lock"))).lockfile!!
        assertEquals(listOf("numbers" to "3.0.0", "vector-math" to "1.3.0", "zeta" to "1.0.0"), lock.packages.map { it.name to it.version })
        assertTrue(Files.isDirectory(project.resolve(".createscript/packages/vector-math/1.2.0")))
        assertTrue(Files.isDirectory(project.resolve(".createscript/packages/vector-math/1.3.0")))
        // 手で依存を外すと、ロックファイルからも外れる（ディレクトリは消さない）
        Files.writeString(project.resolve("createscript.json"), manifestWith(dep("zeta", "1.0.0")))
        installer(project).install(null, false)
        assertEquals(listOf("zeta"), Lockfiles.parse(Files.readAllBytes(project.resolve("createscript.lock"))).lockfile!!.packages.map { it.name })
        assertTrue(Files.isDirectory(project.resolve(".createscript/packages/numbers/3.0.0")))
    }

    // 壊れたロックファイル・マニフェストは書き換えない
    @Test
    fun refusesInvalidProjectFiles() {
        publishBasic()
        Files.writeString(root.resolve("createscript.lock"), "{")
        val before = snapshot(root)
        assertTrue(failure(InstallFailure.Kind.INVALID) { installer().install(spec, false) }.message!!.contains("createscript.lock is not valid"))
        assertUnchanged(before, root)
        Files.delete(root.resolve("createscript.lock"))
        Files.writeString(root.resolve("createscript.json"), """{"schemaVersion": 3}""")
        val before2 = snapshot(root)
        assertTrue(failure(InstallFailure.Kind.INVALID) { installer().install(spec, false) }.message!!.contains("createscript.json is not valid"))
        assertUnchanged(before2, root)
        assertEquals(InstallFailure.Kind.NO_PROJECT, assertThrows(InstallFailure::class.java) { installer(temp.resolve("nothing")).install(null, false) }.kind)
    }

    // ---- シンボリックリンク・既にあるディレクトリ ----

    @Test
    fun refusesSymbolicLinks() {
        publishBasic()
        val outside = Files.createDirectories(temp.resolve("outside"))
        val cases = mapOf<String, (Path) -> Unit>(
            ".createscript" to { p -> Files.createSymbolicLink(p.resolve(".createscript"), outside) },
            "packages" to { p -> Files.createDirectories(p.resolve(".createscript")); Files.createSymbolicLink(p.resolve(".createscript/packages"), outside) },
            "packages/numbers" to { p -> Files.createDirectories(p.resolve(".createscript/packages")); Files.createSymbolicLink(p.resolve(".createscript/packages/numbers"), outside) },
            "packages/numbers/3.0.0" to { p -> Files.createDirectories(p.resolve(".createscript/packages/numbers")); Files.createSymbolicLink(p.resolve(".createscript/packages/numbers/3.0.0"), outside) },
            "createscript.json" to { p ->
                Files.move(p.resolve("createscript.json"), p.resolve("real.json"))
                Files.createSymbolicLink(p.resolve("createscript.json"), p.resolve("real.json"))
            },
            "createscript.lock" to { p -> Files.writeString(p.resolve("real.lock"), "{}"); Files.createSymbolicLink(p.resolve("createscript.lock"), p.resolve("real.lock")) },
            "install.lock" to { p -> Files.createDirectories(p.resolve(".createscript")); Files.createSymbolicLink(p.resolve(".createscript/install.lock"), outside.resolve("lock")) },
            "transaction.json" to { p -> Files.createDirectories(p.resolve(".createscript")); Files.createSymbolicLink(p.resolve(".createscript/transaction.json"), outside.resolve("journal")) },
        )
        for ((label, setUp) in cases) {
            val project = createProject(temp.resolve("link-${label.replace('/', '-')}"))
            setUp(project)
            val before = snapshot(project)
            val failure = failure(InstallFailure.Kind.UNSAFE) { installer(project).install(spec, false) }
            assertTrue(failure.message!!.contains("symbolic link") || failure.message!!.contains("not a regular file"), "$label: ${failure.message}")
            assertUnchanged(before, project)
            assertEquals(0, Files.list(outside).use { it.count() }, label)
        }
    }

    // 既にあるディレクトリは、印と中身が完全に一致するときだけ使う。違えば触らずに止まる
    @Test
    fun refusesChangedPackageDirectories() {
        publishBasic()
        installer().install(spec, false)
        val module = root.resolve(".createscript/packages/numbers/3.0.0/modules/numbers.cscr")
        val changes = listOf<Pair<String, () -> Unit>>(
            "changed file" to { Files.writeString(module, "fun one(): Int {\n    return 3\n}\n") },
            "extra file" to { Files.writeString(module.resolveSibling("extra.cscr"), "") },
            "missing file" to { Files.delete(root.resolve(".createscript/packages/numbers/3.0.0/LICENSE")) },
            "damaged marker" to { Files.writeString(root.resolve(".createscript/packages/numbers/3.0.0/.createscript-package.json"), "{}") },
        )
        val original = snapshot(root)
        for ((label, change) in changes) {
            change()
            val before = snapshot(root)
            val failure = failure(InstallFailure.Kind.UNSAFE) { installer().install(null, false) }
            assertTrue(failure.message!!.contains("Remove it by hand"), "$label: ${failure.message}")
            assertEquals(before, snapshot(root), label)
            // 元に戻す
            deleteTree(root.resolve(".createscript/packages/numbers/3.0.0"))
            val restored = installer().install(null, true)
            assertEquals(emptyList<String>(), restored.warnings, label)
            assertEquals(original.keys, snapshot(root).keys, label)
        }
    }

    // ---- 同時インストール ----

    @Test
    fun refusesAConcurrentInstallInAnotherProcess() {
        publishBasic()
        Files.createDirectories(root.resolve(".createscript"))
        val javaBinary = ProcessHandle.current().info().command().orElseThrow()
        val classpath = listOf(LockHolder::class.java, Unit::class.java)
            .joinToString(java.io.File.pathSeparator) { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
        val process = ProcessBuilder(javaBinary, "-cp", classpath, LockHolder::class.java.name, root.resolve(".createscript/install.lock").toString()).start()
        try {
            assertEquals("locked", process.inputStream.bufferedReader().readLine())
            val before = snapshot(root)
            assertTrue(failure(InstallFailure.Kind.BUSY) { installer().install(spec, false) }.message!!.contains("another createscript install is running"))
            assertEquals(before, snapshot(root))
        } finally {
            process.outputStream.close()
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
        }
        assertEquals(2, installer().install(spec, false).packages.size)
    }

    @Test
    fun refusesAConcurrentInstallInTheSameProcess() {
        publishBasic()
        var inner: InstallFailure? = null
        installer(hooks = { step -> if (step == "resolved" && inner == null) inner = assertThrows(InstallFailure::class.java) { installer().install(spec, false) } }).install(spec, false)
        assertEquals(InstallFailure.Kind.BUSY, inner!!.kind)
    }

    // ---- 通信の中断（ローカルの HTTP サーバー） ----

    @Test
    fun anInterruptedDownloadChangesNothing() {
        val archive = Packages.library("solo", "1.0.0")
        val download = LocalServer()
        val api = LocalServer()
        try {
            val asset = "/owner/solo/releases/download/v1.0.0/createscript-package.zip"
            // 1000 バイトと言って 10 バイトで切る
            download.route(asset) { exchange ->
                exchange.sendResponseHeaders(200, archive.size.toLong())
                exchange.responseBody.write(archive, 0, 10)
                exchange.responseBody.flush()
                exchange.close()
            }
            api.route("/repos/owner/solo/releases/tags/v1.0.0", LocalServer.ok("""{"immutable": true, "assets": [{"name": "createscript-package.zip", "digest": "sha256:${GitHubReleases.sha256Hex(archive)}"}]}""".toByteArray()))
            val limits = NetworkLimits(maxDownloadBytes = 1024 * 1024, maxApiBytes = 65536, connectTimeout = java.time.Duration.ofSeconds(2), totalTimeout = java.time.Duration.ofSeconds(5), maxAttempts = 2)
            val releases = GitHubReleases(testEndpoints(download, api), limits, null, {}, "test")
            val fetcher = PackageFetcher(releases, ArchiveCache(cache))
            val before = snapshot(root)
            val failure = failure(InstallFailure.Kind.NETWORK) { Installer(root, fetcher, InstallLimits(), InstallHooks.NONE).install(PackageSpec("github:owner/solo", "1.0.0"), false) }
            assertTrue(failure.message!!.contains("github:owner/solo 1.0.0"), failure.message)
            assertEquals(2, download.requestsTo(asset).size)
            assertUnchanged(before, root)
            assertFalse(Files.exists(cache.resolve("archives/sha256/${GitHubReleases.sha256Hex(archive)}.zip")))
            // 通信が戻れば入る
            download.route(asset, LocalServer.ok(archive))
            val result = Installer(root, fetcher, InstallLimits(), InstallHooks.NONE).install(PackageSpec("github:owner/solo", "1.0.0"), false)
            assertEquals(PackageFetcher.DigestCheck.Matched, result.packages.single().fetch.digest)
            releases.close()
        } finally {
            download.close()
            api.close()
        }
    }

    @Test
    fun parsesPackageSpecs() {
        assertEquals("github:owner/repo@v1.2.0", PackageSpec.parse("github:owner/repo@v1.2.0").toString())
        assertEquals("1.2.0-beta.1+b.5", PackageSpec.parse("github:owner/repo@1.2.0-beta.1+b.5")!!.version)
        for (bad in listOf("github:owner/repo", "github:owner/repo@", "github:owner/repo@v1.2", "github:owner/repo@^1.2.0", "github:o/../r@v1.0.0", "owner/repo@v1.0.0", "@v1.0.0")) {
            assertNull(PackageSpec.parse(bad), bad)
        }
    }

    // ジャーナルを書く前に止まったインストールは、プロジェクトのファイルを変えておらず、作業用のディレクトリを警告する
    @ParameterizedTest
    @ValueSource(strings = ["start", "resolved", "staged"])
    fun aCrashBeforeTheJournalChangesNothing(step: String) {
        publishBasic()
        val before = snapshot(root)
        assertThrows(SimulatedCrash::class.java) { installer(hooks = { if (it == step) throw SimulatedCrash(it) }).install(spec, false) }
        val outside = { m: Map<String, String> -> m.filterKeys { !it.startsWith(".createscript") } }
        assertEquals(outside(before), outside(snapshot(root)))
        assertFalse(Files.exists(root.resolve(".createscript/transaction.json")))
        assertFalse(Files.exists(root.resolve(".createscript/packages")))
        val result = installer().install(spec, false)
        assertEquals(if (step == "staged") 1 else 0, result.warnings.count { it.contains("stopped before it changed anything") })
        assertEquals(2, result.packages.count { it.installed })
    }
}
