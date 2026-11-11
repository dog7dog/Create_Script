package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.install.TestRegistry.pkg
import com.creatrbypc.createanalytical.install.TestRegistry.version
import com.creatrbypc.createanalytical.project.registry.IndexSignatures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// S8.3 Phase 3: レジストリの index の取得・検証・保存（RegistryClient・RegistryCache）
class RegistryClientTest {
    @TempDir
    lateinit var directory: Path

    private val one = mapOf("numbers" to pkg("github:owner/numbers", mapOf("3.0.0" to version("a".repeat(64)))))
    private val two = one + mapOf("vector-math" to pkg("github:owner/vector-math", mapOf("1.2.0" to version("b".repeat(64), mapOf("numbers" to "3.0.0")))))

    private fun harness() = RegistryHarness(directory.resolve("cache"))

    private fun available(lookup: RegistryLookup): RegistryLookup.Available {
        assertTrue(lookup is RegistryLookup.Available, describe(lookup))
        return lookup as RegistryLookup.Available
    }

    private fun describe(lookup: RegistryLookup) = when (lookup) {
        is RegistryLookup.Available -> "available ${lookup.accepted.index.serial}"
        is RegistryLookup.Unavailable -> "unavailable ${lookup.reason}: ${lookup.message}"
        is RegistryLookup.Refused -> "refused: ${lookup.message}"
    }

    private fun refused(lookup: RegistryLookup, code: String) {
        assertTrue(lookup is RegistryLookup.Refused && lookup.message.contains(code), describe(lookup))
    }

    private fun registryDirectory() = directory.resolve("cache/registry/example/test-registry")

    @Test
    fun acceptsSavesAndReusesASignedIndex() {
        val h = harness()
        val index = h.publish(one, serial = 1)
        val first = available(h.client().lookup(offline = false))
        assertFalse(first.fromCache)
        assertEquals(1, first.accepted.index.serial)
        assertTrue(first.accepted.bytes.contentEquals(index))
        // 原本のバイト列・署名・履歴（serial と SHA-256）を保存する
        val saved = registryDirectory()
        val history = Files.readString(saved.resolve("trusted.json"))
        assertTrue(history.contains("\"serial\":1") && history.contains(first.accepted.sha256), history)
        assertTrue(Files.readAllBytes(saved.resolve(RegistryCache.indexFile(first.accepted.sha256))).contentEquals(index))
        // 作ったディレクトリは所有者だけのもの
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(saved)))
        // オフラインでは保存したものを使う（通信しない）
        h.store.network = false
        val offline = available(h.client().lookup(offline = true))
        assertTrue(offline.fromCache)
        assertEquals(first.accepted.sha256, offline.accepted.sha256)
        // S8.3 Phase 5（P4-M2）: オンラインで通信できなければ、保存した有効なものがあっても使わない（--offline を案内する）
        val failed = h.client().lookup(offline = false)
        assertTrue(failed is RegistryLookup.Unavailable && failed.reason == RegistryLookup.Reason.NETWORK, describe(failed))
        assertTrue((failed as RegistryLookup.Unavailable).message.contains("was not used") && failed.message.contains("--offline"), failed.message)
        assertTrue(offline.notes.single().startsWith("offline: using the registry index saved before"), offline.notes.toString())
        // 新しい index を受け入れて、履歴を進める。古いファイルは消す
        h.store.network = true
        h.publish(two, serial = 2, generated = TestRegistry.GENERATED.plusSeconds(60))
        val second = available(h.client().lookup(offline = false))
        assertEquals(2, second.accepted.index.serial)
        assertFalse(Files.exists(saved.resolve(RegistryCache.indexFile(first.accepted.sha256))))
        assertEquals(1, Files.list(saved).use { s -> s.filter { it.fileName.toString().startsWith("index-") }.count() })
    }

    // 2. 不正な署名・3. 未知の鍵・4. 署名が無い。どれも保存しない
    @Test
    fun refusesBadSignatures() {
        val h = harness()
        val index = TestRegistry.index(one)
        // 中身を変えた index
        val changed = TestRegistry.index(two)
        h.store.publish(changed, h.key.sign(index))
        refused(h.client().lookup(offline = false), "CS_REG_SIGNATURE_INVALID")
        // 署名を 1 バイト変えた
        val signature = h.key.signature(index)
        signature.signature[0] = (signature.signature[0].toInt() xor 1).toByte()
        h.store.publish(index, IndexSignatures.write(listOf(signature)))
        refused(h.client().lookup(offline = false), "CS_REG_SIGNATURE_INVALID")
        // 別の鍵の ID を名乗る署名
        h.store.publish(index, IndexSignatures.write(listOf(h.key.signature(index, "test-only-other-key"))))
        refused(h.client().lookup(offline = false), "CS_REG_SIGNATURE_UNKNOWN_KEY")
        // 信頼しない鍵（未知の鍵）
        val stranger = TestOnlyRegistryKey("test-only-stranger")
        h.store.publish(index, stranger.sign(index))
        refused(h.client().lookup(offline = false), "CS_REG_SIGNATURE_UNKNOWN_KEY")
        // 署名のファイルが無い・空
        h.store.publish(index, null)
        refused(h.client().lookup(offline = false), "CS_REG_SIGNATURE_MISSING")
        h.store.publish(index, ByteArray(0))
        refused(h.client().lookup(offline = false), "CS_REG_SIGNATURE_MISSING")
        // 何も保存していない（オフラインでは使える index が無い）
        assertFalse(Files.exists(registryDirectory().resolve("trusted.json")))
        val offline = h.client().lookup(offline = true)
        assertTrue(offline is RegistryLookup.Unavailable && offline.reason == RegistryLookup.Reason.NO_INDEX, describe(offline))
        // 鍵の更新: 信頼しない鍵の署名は無視し、信頼する鍵の署名があれば受け入れる
        h.store.publish(index, IndexSignatures.write(listOf(stranger.signature(index), h.key.signature(index))))
        available(h.client().lookup(offline = false))
    }

    // 5. 期限切れ（オンラインでは拒否、オフラインでは黙って使わない）
    @Test
    fun refusesExpiredIndexes() {
        val h = harness()
        h.publish(one, serial = 1)
        available(h.client().lookup(offline = false))
        h.now = TestRegistry.GENERATED.plus(Duration.ofDays(30))
        refused(h.client().lookup(offline = false), "CS_REG_INDEX_EXPIRED")
        val offline = h.client().lookup(offline = true)
        assertTrue(offline is RegistryLookup.Unavailable && offline.reason == RegistryLookup.Reason.EXPIRED && offline.message.contains("has expired"), describe(offline))
        h.store.network = false
        val unreachable = h.client().lookup(offline = false)
        assertTrue(unreachable is RegistryLookup.Unavailable && unreachable.reason == RegistryLookup.Reason.NETWORK && !unreachable.message.contains("--offline"), describe(unreachable))
        // 生成日時が 1 日を超えて未来
        h.now = TestRegistry.GENERATED.minus(Duration.ofDays(2))
        h.store.network = true
        refused(h.client().lookup(offline = false), "CS_REG_INDEX_NOT_YET_VALID")
    }

    // 6. serial の巻き戻し・7. 同じ serial で違う内容・11. 循環のある index
    @Test
    fun refusesRollbackReuseAndInvalidIndexes() {
        val h = harness()
        h.publish(one, serial = 5)
        available(h.client().lookup(offline = false))
        // 同じ serial で違う内容
        h.publish(two, serial = 5)
        refused(h.client().lookup(offline = false), "CS_REG_INDEX_SERIAL_REUSED")
        // 古い serial: 前のものが有効なら、それを使う（古いものは受け入れない）
        h.publish(two, serial = 4)
        val older = available(h.client().lookup(offline = false))
        assertEquals(5, older.accepted.index.serial)
        assertTrue(older.notes.any { it.contains("older index") }, older.notes.toString())
        // 前のものが期限切れなら、拒否する（古い serial の index 自体は、まだ期限内）
        h.now = TestRegistry.GENERATED.plus(Duration.ofDays(31))
        h.publish(two, serial = 4, generated = TestRegistry.GENERATED.plus(Duration.ofDays(5)))
        refused(h.client().lookup(offline = false), "CS_REG_INDEX_ROLLBACK")
        h.now = TestRegistry.NOW
        // 審査済みの SHA-256 を変えた新しい index
        h.publish(mapOf("numbers" to pkg("github:owner/numbers", mapOf("3.0.0" to version("c".repeat(64))))), serial = 6, generated = TestRegistry.GENERATED.plusSeconds(1))
        refused(h.client().lookup(offline = false), "CS_REG_SHA256_CHANGED")
        // 依存の循環（署名は正しいが、index の規則に反する）
        val cycle = mapOf(
            "numbers" to pkg("github:owner/numbers", mapOf("3.0.0" to version("a".repeat(64)), "3.1.0" to version("d".repeat(64), mapOf("loop" to "1.0.0")))),
            "loop" to pkg("github:owner/loop", mapOf("1.0.0" to version("e".repeat(64), mapOf("numbers" to "3.1.0")))),
        )
        h.publish(cycle, serial = 7, generated = TestRegistry.GENERATED.plusSeconds(2))
        refused(h.client().lookup(offline = false), "CS_REG_DEPENDENCY_CYCLE")
        // 保存した状態は serial 5 のまま
        assertTrue(Files.readString(registryDirectory().resolve("trusted.json")).contains("\"serial\":5"))
    }

    // 12. キャッシュの破損・改ざん
    @Test
    fun detectsDamagedAndTamperedCaches() {
        val h = harness()
        h.publish(one, serial = 3)
        val accepted = available(h.client().lookup(offline = false)).accepted
        val saved = registryDirectory()
        val indexFile = saved.resolve(RegistryCache.indexFile(accepted.sha256))
        // index のファイルを書き換えた: 使わない。オフラインでは使えない
        Files.write(indexFile, TestRegistry.index(two, serial = 9))
        val offline = h.client().lookup(offline = true)
        assertTrue(offline is RegistryLookup.Unavailable && offline.reason == RegistryLookup.Reason.CACHE_UNUSABLE && offline.message.contains("does not match its SHA-256"), describe(offline))
        // オンラインでは、履歴（serial・SHA-256）とは比べる: 古い serial は拒否する
        h.publish(one, serial = 2)
        val rollback = h.client().lookup(offline = false)
        assertTrue(rollback is RegistryLookup.Refused && rollback.message.contains("older (serial 2)"), describe(rollback))
        // 新しい index なら受け入れて、壊れたファイルを正しいもので置き換える
        h.publish(two, serial = 4, generated = TestRegistry.GENERATED.plusSeconds(5))
        available(h.client().lookup(offline = false))
        available(h.client().lookup(offline = true))
        // 履歴のファイルを書き換えた（serial を減らした）: 使わずに止まる（履歴を黙って捨てない）
        val historyPath = saved.resolve("trusted.json")
        val history = Files.readString(historyPath)
        Files.writeString(historyPath, history.replace("\"serial\":4", "\"serial\":1 "))
        val damaged = assertThrows(InstallFailure::class.java) { h.client().lookup(offline = false) }
        assertEquals(InstallFailure.Kind.UNSAFE, damaged.kind)
        assertTrue(damaged.message!!.contains("is damaged or was changed"), damaged.message)
        // 履歴がリンク
        Files.delete(historyPath)
        Files.createSymbolicLink(historyPath, directory.resolve("elsewhere.json"))
        assertTrue(assertThrows(InstallFailure::class.java) { h.client().lookup(offline = true) }.message!!.contains("symbolic link"))
        Files.delete(historyPath)
        Files.writeString(historyPath, history)
        available(h.client().lookup(offline = true))
        // 保存した index が、今の鍵で確かめられない（信頼する鍵が替わった）
        val replaced = RegistryClient(RegistryTrust(TestRegistry.REPOSITORY, listOf(TestOnlyRegistryKey("test-only-new-key").trusted)), h.store, h.cache()) { h.now }
        val unknown = replaced.lookup(offline = true)
        assertTrue(unknown is RegistryLookup.Unavailable && unknown.message.contains("CS_REG_SIGNATURE_UNKNOWN_KEY"), describe(unknown))
    }

    // キャッシュのパスのシンボリックリンク・ほかのユーザーが書き込めるディレクトリ
    @Test
    fun refusesLinkedOrSharedCacheDirectories() {
        val h = harness()
        h.publish(one, serial = 1)
        val elsewhere = Files.createDirectories(directory.resolve("elsewhere"))
        Files.createDirectories(directory.resolve("cache/registry/example"))
        Files.createSymbolicLink(directory.resolve("cache/registry/example/test-registry"), elsewhere)
        val linked = assertThrows(InstallFailure::class.java) { h.client().lookup(offline = false) }
        assertTrue(linked.message!!.contains("is a symbolic link"), linked.message)
        assertEquals(0, Files.list(elsewhere).use { it.count() })
        Files.delete(directory.resolve("cache/registry/example/test-registry"))
        available(h.client().lookup(offline = false))
        Files.setPosixFilePermissions(registryDirectory(), PosixFilePermissions.fromString("rwxrwxrwx"))
        val shared = assertThrows(InstallFailure::class.java) { h.client().lookup(offline = true) }
        assertTrue(shared.message!!.contains("can be written by other users"), shared.message)
        Files.setPosixFilePermissions(registryDirectory(), PosixFilePermissions.fromString("rwx------"))
        // ほかのユーザーのディレクトリ（所有者を読む手段をテストで差し替える）
        val foreign = RegistryCache(directory.resolve("cache"), TestRegistry.REPOSITORY, {}, LockWait(5_000, 5, 1_000), { DirectoryAttributes("someone-else", 0b111_000_000, false, true) })
        val other = assertThrows(InstallFailure::class.java) { RegistryClient(h.trust, h.store, foreign) { h.now }.lookup(offline = true) }
        assertTrue(other.message!!.contains("belongs to someone-else"), other.message)
    }

    // 14. 同時に動く複数の更新（スレッド）。どの結果も受け入れたもので、最後の状態は一番新しい index
    @Test
    fun concurrentUpdatesKeepAConsistentHistory() {
        val h = harness()
        // 履歴の規則（パッケージは消えない）を満たすように、各 serial は前のものをすべて含む
        val cumulative = ArrayList<Pair<ByteArray, ByteArray>>()
        var packages = one
        for (serial in 1L..8L) {
            packages = packages + mapOf("extra$serial" to pkg("github:owner/extra$serial", mapOf("1.0.0" to version("f".repeat(64)))))
            val index = TestRegistry.index(packages, serial, TestRegistry.GENERATED.plusSeconds(serial))
            cumulative += index to h.key.sign(index)
        }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = cumulative.map { (index, signature) ->
            pool.submit<RegistryLookup> {
                start.await()
                val store = IndexStore { name, _ -> if (name == "index.json") index else signature }
                RegistryClient(h.trust, store, h.cache()) { h.now }.lookup(offline = false)
            }
        }
        start.countDown()
        val lookups = results.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        for (lookup in lookups) available(lookup)
        assertTrue(Files.readString(registryDirectory().resolve("trusted.json")).contains("\"serial\":8"))
        assertEquals(8, available(h.client().lookup(offline = true)).accepted.index.serial)
        // 止まった更新が無いので、一時ファイルは残らない
        val names = Files.list(registryDirectory()).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertTrue(names.none { it.startsWith(".tmp-") }, names.toString())
    }

    // 15. 更新の途中で止まった（プロセスの停止の代わり）: 前の正しい状態のまま。次の更新が片付ける
    @Test
    fun anInterruptedUpdateKeepsThePreviousState() {
        val h = harness()
        h.publish(one, serial = 1)
        available(h.client().lookup(offline = false))
        for (step in listOf("files-written", "history-written")) {
            h.publish(two, serial = 2, generated = TestRegistry.GENERATED.plusSeconds(1))
            assertThrows(SimulatedCrash::class.java) { h.client(hooks = { if (it == step) throw SimulatedCrash(it) }).lookup(offline = false) }
            assertEquals(1, available(h.client().lookup(offline = true)).accepted.index.serial, step)
        }
        // 履歴を置き換えた直後に止まった: 新しい状態
        assertThrows(SimulatedCrash::class.java) { h.client(hooks = { if (it == "history-replaced") throw SimulatedCrash(it) }).lookup(offline = false) }
        assertEquals(2, available(h.client().lookup(offline = true)).accepted.index.serial)
        // 次の更新で、一時ファイルと古いファイルが消える
        h.publish(two + mapOf("more" to pkg("github:owner/more", mapOf("1.0.0" to version("9".repeat(64))))), serial = 3, generated = TestRegistry.GENERATED.plusSeconds(2))
        available(h.client().lookup(offline = false))
        val names = Files.list(registryDirectory()).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(4, names.size, names.toString())
        assertTrue(names.containsAll(listOf("lock", "trusted.json")), names.toString())
    }

    // 15. 実際のプロセスを SIGKILL で止める（macOS・Linux。別の JVM が保存の途中で止まる）
    @Test
    fun aKilledProcessKeepsThePreviousState() {
        assumeTrue(!System.getProperty("os.name").lowercase().startsWith("windows"))
        val h = harness()
        h.publish(one, serial = 1)
        available(h.client().lookup(offline = false))
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        for ((step, expected) in listOf("files-written" to 1L, "history-written" to 1L, "history-replaced" to 2L)) {
            val index = TestRegistry.index(two, 2, TestRegistry.GENERATED.plusSeconds(1))
            val indexPath = Files.write(directory.resolve("next-index.json"), index)
            val signaturePath = Files.write(directory.resolve("next-index.json.sig"), h.key.sign(index))
            val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), RegistryKillTarget::class.java.name, directory.resolve("cache").toString(), step, indexPath.toString(), signaturePath.toString())
                .redirectErrorStream(true).start()
            val line = process.inputStream.bufferedReader().readLine()
            assertEquals("READY $step", line)
            process.destroyForcibly()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            // 止まったプロセスのロックは外れている。状態は前のものか新しいもののどちらか
            assertEquals(expected, available(h.client().lookup(offline = true)).accepted.index.serial, step)
            if (expected == 2L) break
        }
    }

    // 19・20. 本番の設定: 鍵が無ければ、通信もキャッシュも使わずに拒否する。テスト専用の鍵は本番の設定に入らない
    @Test
    fun productionConfiguration() {
        val official = RegistryTrust.official()
        assertFalse(official.configured)
        assertEquals(RegistryTrust.OFFICIAL_REPOSITORY, official.repository)
        assertTrue(official.keys.keys.none { it.keyId.startsWith(RegistryTrust.TEST_KEY_PREFIX) })
        val key = TestOnlyRegistryKey()
        assertThrows(IllegalArgumentException::class.java) { RegistryTrust.official(listOf(key.trusted)) }
        assertThrows(IllegalArgumentException::class.java) { RegistryTrust(RegistryTrust.OFFICIAL_REPOSITORY, listOf(key.trusted)) }
        assertThrows(IllegalArgumentException::class.java) { RegistryTrust("dog7dog/createscript-registry", listOf(key.trusted)) }
        // 鍵の無い設定は、通信もキャッシュの作成もしない
        val store = MemoryIndexStore()
        val cacheRoot = directory.resolve("production-cache")
        val lookup = RegistryClient(official, store, RegistryCache(cacheRoot, RegistryTrust.OFFICIAL_REPOSITORY)).lookup(offline = false)
        assertTrue(lookup is RegistryLookup.Unavailable && lookup.reason == RegistryLookup.Reason.NOT_CONFIGURED, describe(lookup))
        assertTrue(store.downloads.isEmpty())
        assertFalse(Files.exists(cacheRoot))
        // テスト専用の鍵で署名して保存した index は、別の鍵（本番のつもりの鍵）の設定からは使えない
        val h = harness()
        h.publish(one, serial = 1)
        available(h.client().lookup(offline = false))
        val production = RegistryTrust(TestRegistry.REPOSITORY, listOf(TestOnlyRegistryKey("registry-key-1").trusted))
        val other = RegistryClient(production, h.store, h.cache()) { h.now }
        assertTrue(other.lookup(offline = true) is RegistryLookup.Unavailable)
        refused(other.lookup(offline = false), "CS_REG_SIGNATURE_UNKNOWN_KEY")
        // クライアントとキャッシュのレジストリは同じ
        assertThrows(IllegalArgumentException::class.java) { RegistryClient(official, store, h.cache()) }
    }

    // 取得: HTTP の規則（ホスト・リダイレクト・大きさ）は GitHubReleases と同じ。本番の URL へは接続しない
    @Test
    fun downloadsTheLatestReleaseAssetsWithTheSameRules() {
        LocalServer().use { server ->
            val index = TestRegistry.index(one)
            server.route("/example/test-registry/releases/latest/download/index.json") { LocalServer.respond(it, 302, ByteArray(0), mapOf("Location" to "${server.base}/assets/index.json")) }
            server.route("/assets/index.json") { LocalServer.respond(it, 200, index) }
            server.route("/example/test-registry/releases/latest/download/index.json.sig") { LocalServer.respond(it, 302, ByteArray(0), mapOf("Location" to "http://localhost:${server.port}/assets/sig")) }
            val limits = NetworkLimits(maxAttempts = 1)
            val endpoints = Endpoints(server.base, server.base, setOf("127.0.0.1"), setOf(server.port), https = false, ipLiterals = true)
            GitHubReleases(endpoints, limits, null, {}, "createscript-test/1").use { releases ->
                val store = GitHubIndexStore(releases, TestRegistry.REPOSITORY)
                assertTrue(store.download("index.json", 1 shl 20).contentEquals(index))
                // 大きさの上限
                assertThrows(FetchFailure.TooLarge::class.java) { store.download("index.json", 10) }
                // 許していないホストへのリダイレクト
                assertThrows(FetchFailure.Forbidden::class.java) { store.download("index.json.sig", 4096) }
                // 取得元の名前は組み込みの設定から（不正な名前は使わない）
                assertThrows(IllegalArgumentException::class.java) { releases.latestAsset("../x", "index.json", 10) }
                assertThrows(IllegalArgumentException::class.java) { releases.latestAsset(TestRegistry.REPOSITORY, "../index.json", 10) }
            }
        }
        // 本番の接続先は HTTPS の github.com だけ（URL は作るだけで、接続しない）
        assertEquals(null, Endpoints.GITHUB.problem(java.net.URI("https://github.com/dog7dog/CreateScript-Registry/releases/latest/download/index.json")))
        assertTrue(Endpoints.GITHUB.problem(java.net.URI("http://github.com/x")) != null)
    }
}

// SIGKILL の試験で、別の JVM として動かすもの: 保存の途中の step で止まって待つ（親が SIGKILL で止める）
object RegistryKillTarget {
    @JvmStatic
    fun main(args: Array<String>) {
        val (root, step, indexPath, signaturePath) = args
        val cache = RegistryCache(Path.of(root), TestRegistry.REPOSITORY, { reached ->
            if (reached == step) {
                println("READY $step")
                System.out.flush()
                Thread.sleep(120_000)
            }
        }, LockWait(5_000, 5, 1_000))
        val index = Files.readAllBytes(Path.of(indexPath))
        cache.withLock { cache.store(2, TestRegistry.GENERATED.plusSeconds(1), index, Files.readAllBytes(Path.of(signaturePath))) }
    }
}
