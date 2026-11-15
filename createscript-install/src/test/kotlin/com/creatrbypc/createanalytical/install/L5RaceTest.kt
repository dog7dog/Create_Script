package com.creatrbypc.createanalytical.install

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// S8.2 L-5（S8.3 Phase 4・5・8）: 同じユーザーの権限の攻撃者のスレッドが、インストール中に置き場所の途中のディレクトリをシンボリックリンクに
// 差し替える、実際の競合の試験。時間がかかる（約 3 分）ので、環境変数 CREATESCRIPT_L5_RACE=1 のときだけ動く（既定ではスキップ）。
// 回数は CREATESCRIPT_L5_RACE_ITERATIONS（方式ごと。既定 420）。JUnit の一時ディレクトリの中だけを使い、利用者のファイルには触れない。
// 結果は「AUDIT-L5」の行に出す。この試験が通ることは、L-5 が解決したことを意味しない（守る性質は、誤った成功と既存のファイルの変更が 0 であること）
class L5RaceTest {
    private companion object {
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    @TempDir
    lateinit var temp: Path

    private val store = MemoryReleases()

    @Test
    fun race() {
        assumeTrue(System.getenv("CREATESCRIPT_L5_RACE") == "1", "set CREATESCRIPT_L5_RACE=1 to run the L-5 race test")
        val iterations = System.getenv("CREATESCRIPT_L5_RACE_ITERATIONS")?.toInt() ?: 420
        store.publish(Packages.source("numbers"), "3.0.0", Packages.library("numbers", "3.0.0"))
        store.publish(Packages.source("vector-math"), "1.2.0", Packages.library("vector-math", "1.2.0", mapOf("numbers" to ("3.0.0" to Packages.source("numbers")))))
        val outside = Files.createDirectories(temp.resolve("outside"))
        fun resetOutside() {
            outside.toFile().deleteRecursively()
            Files.createDirectories(outside.resolve("numbers"))
            Files.createDirectories(outside.resolve("txn"))
            Files.writeString(outside.resolve("precious.txt"), "user data")
            Files.writeString(outside.resolve("numbers/notes.txt"), "more user data")
            // 別のプロジェクトを模したもの（root の方式で、プロジェクトのディレクトリの代わりに差し込む）。既存のマニフェスト・ロックファイルが変わらないことを見る
            Files.createDirectories(outside.resolve("decoy/src"))
            Files.writeString(outside.resolve("decoy/createscript.json"), """{"schemaVersion": 1, "name": "app", "version": "0.1.0", "entry": "src/main.cscr"}""")
            Files.writeString(outside.resolve("decoy/createscript.lock"), "another project's lock file\n")
            Files.writeString(outside.resolve("decoy/src/main.cscr"), "println(\"other\")\n")
            // おとり（S8.3 L-5 の対策の確認）: 作業用のディレクトリ・パッケージと同じ名前の利用者のファイル。インストーラーが、差し替えられたディレクトリの中の
            // 同じ名前のものを消す・置き換えると、内容か fileKey が変わり、userFilesChanged に数える
            val bait = outside.resolve("bait")
            for (name in listOf("journal.tmp", "createscript.json.old", "createscript.json.new", "createscript.lock.old", "createscript.lock.new")) {
                Files.createDirectories(bait)
                Files.writeString(bait.resolve(name), "user file $name")
            }
            for (name in listOf("numbers", "vector-math")) {
                val dir = Files.createDirectories(bait.resolve("staging/$name/3.0.0/modules"))
                Files.writeString(dir.resolve("../createscript.json"), "user manifest")
                Files.writeString(dir.resolve("../LICENSE"), "user license")
                Files.writeString(dir.resolve("../.createscript-package.json"), "user marker")
                Files.writeString(dir.resolve("numbers.cscr"), "user module")
                Files.writeString(dir.resolve("../../keep.txt"), "user notes")
            }
        }
        resetOutside()
        val baseline = snapshot(outside).mapValues { (_, v) -> v.substringBeforeLast(':') }
        val modes = (
            System.getenv("CREATESCRIPT_L5_RACE_MODES")
                ?: "packages-name,packages,transaction,state,root,bait-transaction,bait-staging,once:packages-name,once:packages,once:transaction,once:state,once:root,once:bait-transaction,once:bait-staging"
            ).split(',')
        val random = java.util.Random(20261111)
        var totalFalseSuccess = 0
        var totalChanged = 0
        println("AUDIT-L5 os=${System.getProperty("os.name")} ${System.getProperty("os.arch")} java=${System.getProperty("java.version")} iterations/mode=$iterations")
        for (mode in modes) {
            var outsideWrites = 0
            var outsideFiles = 0
            var outsideContentFiles = 0
            var userFilesChanged = 0
            var successes = 0
            var falseSuccess = 0
            val failureKinds = java.util.TreeMap<String, Int>()
            val kinds = java.util.TreeMap<String, Int>()
            val swaps = AtomicLong()
            val once = mode.startsWith("once:")
            val kind = mode.removePrefix("once:")
            for (i in 0 until iterations) {
                val root = createProject(temp.resolve("${mode.replace(':', '_')}-$i"))
                val delayNanos = random.nextInt(40_000_000).toLong()
                val state = root.resolve(".createscript")
                val stop = AtomicBoolean(false)
                val attacker = Thread {
                    val start = System.nanoTime()
                    var done = false
                    while (!stop.get() && !done) {
                        if (once && System.nanoTime() - start < delayNanos) {
                            Thread.onSpinWait()
                            continue
                        }
                        try {
                            val targets: List<Pair<Path, Path>> = when (kind) {
                                "state" -> listOf(state to outside.resolve("txn"))
                                "root" -> listOf(root to outside.resolve("decoy"))
                                "packages-name" -> listOf(state.resolve("packages/numbers") to outside.resolve("numbers"))
                                "packages" -> listOf(state.resolve("packages") to outside)
                                "bait-staging" -> (if (Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS)) Files.list(state).use { s -> s.filter { it.fileName.toString().startsWith("txn-") }.toList() } else emptyList())
                                    .map { it.resolve("staging") to outside.resolve("bait/staging") }
                                "bait-transaction" -> (if (Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS)) Files.list(state).use { s -> s.filter { it.fileName.toString().startsWith("txn-") }.toList() } else emptyList())
                                    .map { it to outside.resolve("bait") }
                                else -> (if (Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS)) Files.list(state).use { s -> s.filter { it.fileName.toString().startsWith("txn-") }.toList() } else emptyList())
                                    .map { it to outside.resolve("txn") }
                            }
                            for ((target, destination) in targets) {
                                val parked = target.resolveSibling(target.fileName.toString() + ".parked")
                                val real = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                                if (real) Files.move(target, parked)
                                try {
                                    Files.createSymbolicLink(target, destination)
                                    swaps.incrementAndGet()
                                    if (once) done = true
                                    repeat(if (once) 20_000 else 200) { Thread.onSpinWait() }
                                    Files.deleteIfExists(target)
                                } finally {
                                    if (real) Files.move(parked, target)
                                }
                            }
                        } catch (error: Exception) {
                            // 競合で失敗した差し替えは続ける
                        }
                    }
                }
                attacker.start()
                val result = try {
                    Installer(root, PackageFetcher(store, ArchiveCache(temp.resolve("cache")))).install(PackageSpec(Packages.source("vector-math"), "1.2.0"), false)
                } catch (failure: InstallFailure) {
                    failureKinds.merge(failure.kind.name, 1, Int::plus)
                    null
                } catch (error: Exception) {
                    failureKinds.merge(error.javaClass.simpleName, 1, Int::plus)
                    null
                }
                stop.set(true)
                attacker.join()
                val now = snapshot(outside).mapValues { (_, v) -> v.substringBeforeLast(':') }
                if (now != baseline) {
                    outsideWrites++
                    outsideFiles += now.filter { (k, v) -> k !in baseline && v.startsWith("file:") }.size
                    // 外に現れたものの名前の形（UUID・数字は * にまとめる）
                    now.keys.filter { it !in baseline }.forEach { kinds.merge(it.replace(Regex("[0-9a-f]{8}-[0-9a-f-]{27}|[0-9]+"), "*"), 1, Int::plus) }
                    // 中身のある新しいファイル（空の SHA-256 でないもの）
                    outsideContentFiles += now.filter { (k, v) -> k !in baseline && v.startsWith("file:") && v != "file:$EMPTY_SHA256" }.size
                    if (baseline.any { (k, v) -> now[k] != v }) userFilesChanged++
                    resetOutside()
                }
                if (result != null) {
                    successes++
                    val owner = Owner.probe(state)
                    val ok = result.packages.all { p ->
                        val directory = root.toRealPath().resolve(".createscript/packages/${p.name}/${p.version}")
                        SafeIo.chain(root.toRealPath(), directory) == SafeIo.Chain.Real &&
                            (PackageDirectories.inspect(directory, owner, keepContents = true) as? PackageState.Valid)?.let { PackageDirectories.matchesArchive(it, p.sha256) } == true
                    } && Files.isRegularFile(root.resolve("createscript.lock"), LinkOption.NOFOLLOW_LINKS)
                    if (!ok) falseSuccess++
                }
            }
            totalFalseSuccess += falseSuccess
            totalChanged += userFilesChanged
            println(
                "AUDIT-L5 mode=$mode trials=$iterations success=$successes failed=${iterations - successes} falseSuccess=$falseSuccess " +
                    "outsideWrites=$outsideWrites outsideFiles=$outsideFiles outsideContentFiles=$outsideContentFiles userFilesChanged=$userFilesChanged swaps=${swaps.get()} failureKinds=$failureKinds outsideNames=$kinds",
            )
        }
        // 守る性質（L-5 は未解決なので、外への書き込みの回数は記録するだけ）
        assertEquals(0, totalFalseSuccess, "an installation reported success although its packages were not in place")
        assertEquals(0, totalChanged, "an existing file outside the project was changed")
    }
}
