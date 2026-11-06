package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.ArchiveCache
import com.creatrbypc.createanalytical.install.FetchFailure
import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.install.Installer
import com.creatrbypc.createanalytical.install.PackageFetcher
import com.creatrbypc.createanalytical.install.PackageSpec
import com.creatrbypc.createanalytical.install.ReleaseStore
import com.creatrbypc.createanalytical.project.PackageArchives
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

// S8.2 Phase 4: createscript run のプロジェクトモード（インストール済みのパッケージ・import できる範囲・名前の衝突・実行の前の確認）
class ProjectRunTest {
    @TempDir
    lateinit var directory: Path

    private class Result(val code: Int, val out: String, val err: String)

    // メモリーの中の Release（照合は本物の PackageFetcher が行う）
    private class Releases : ReleaseStore {
        val assets = HashMap<String, ByteArray>()

        override fun download(source: String, version: String) = assets["$source@$version"] ?: throw FetchFailure.NotFound(URI("https://github.com/x"))

        override fun releaseInfo(source: String, version: String) = GitHubReleases.ReleaseInfo(assets["$source@$version"]?.let(GitHubReleases::sha256Hex), true)
    }

    private val releases = Releases()
    private lateinit var app: Path
    private lateinit var cache: Path

    private fun library(name: String, version: String, modules: Map<String, String>, public: List<String>, dependencies: Map<String, String> = emptyMap()) {
        val deps = dependencies.entries.joinToString(", ") { (n, v) -> "\"$n\": {\"version\": \"$v\", \"source\": \"github:owner/$n\"}" }
        val manifest = """{"schemaVersion": 2, "kind": "library", "name": "$name", "version": "$version", "modules": [${public.joinToString(", ") { "\"$it\"" }}], "dependencies": {$deps}}"""
        val files = modules.map { (module, source) -> "modules/$module.cscr" to source.toByteArray() }.toMap() + ("createscript.json" to manifest.toByteArray())
        releases.assets["github:owner/$name@$version"] = PackageArchives.write(files)
    }

    @BeforeEach
    fun setUp() {
        cache = directory.resolve("cache")
        library("numbers", "3.0.0", mapOf("numbers" to "fun zero(): Int {\n    return 0\n}\n"), listOf("numbers"))
        library(
            "vector-math", "1.2.0",
            mapOf(
                "vector_math" to "import vm_internal\nimport numbers\nfun length2(x: Int, y: Int): Int {\n    return vm_internal.square(x) + vm_internal.square(y) + numbers.zero()\n}\n",
                "vm_internal" to "fun square(x: Int): Int {\n    return x * x\n}\n",
            ),
            listOf("vector_math"), mapOf("numbers" to "3.0.0"),
        )
        app = project("app", "import vector_math\nprintln(vector_math.length2(3, 4))\n")
        install(app, "github:owner/vector-math@v1.2.0")
    }

    private fun project(name: String, main: String): Path {
        val root = Files.createDirectories(directory.resolve(name).resolve("src"))
        Files.writeString(root.parent.resolve("createscript.json"), """{"schemaVersion": 1, "name": "$name", "version": "0.1.0", "entry": "src/main.cscr"}""")
        Files.writeString(root.resolve("main.cscr"), main)
        return root.parent
    }

    private fun install(root: Path, spec: String? = null) {
        Installer(root, PackageFetcher(releases, ArchiveCache(cache))).install(spec?.let { PackageSpec.parse(it)!! }, false)
    }

    private fun cli(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o -> PrintStream(err, true, Charsets.UTF_8).use { e -> CreateScriptCli(o, e).execute(args.toList()) } }
        return Result(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun run(root: Path = app) = cli("run", root.toString())

    private fun writeMain(source: String, root: Path = app) = Files.writeString(root.resolve("src/main.cscr"), source)

    // ---- import できる範囲 ----

    // 直接の依存の公開モジュールを import できる。パッケージの中からは、自分の公開しないモジュール（vm_internal）と、自分の直接の依存（numbers）を import できる
    @Test
    fun importsPublicModulesOfDirectDependencies() {
        val result = run()
        assertEquals(ExitCode.OK, result.code, result.err)
        assertEquals("25\n", result.out)
        assertEquals("", result.err)
        // ローカルのモジュール（エントリーのファイルのディレクトリ）と一緒に使える
        Files.writeString(app.resolve("src/helpers.cscr"), "import vector_math\nfun twice(x: Int): Int {\n    return vector_math.length2(x, 0) * 2\n}\n")
        writeMain("import helpers\nimport vector_math\nprintln(helpers.twice(3))\nprintln(vector_math.length2(1, 1))\n")
        val combined = run()
        assertEquals("18\n2\n", combined.out, combined.err)
    }

    @Test
    fun refusesPrivateModulesFromOutside() {
        writeMain("import vm_internal\nprintln(vm_internal.square(2))\n")
        val result = run()
        assertEquals(ExitCode.COMPILE_ERROR, result.code)
        assertEquals("${app.resolve("src/main.cscr")}:1:8: error CS_MODULE_NOT_VISIBLE (\"vm_internal\")\n", result.err)
        assertEquals("", result.out)
    }

    // 推移的な依存（numbers は vector-math の依存）は、直接 import できない。直接の依存に足せば import できる
    @Test
    fun refusesTransitiveDependencies() {
        writeMain("import numbers\nprintln(numbers.zero())\n")
        val refused = run()
        assertEquals(ExitCode.COMPILE_ERROR, refused.code)
        assertTrue(refused.err.contains("error CS_MODULE_NOT_VISIBLE (\"numbers\")"), refused.err)
        install(app, "github:owner/numbers@v3.0.0")
        assertEquals("0\n", run().out)
    }

    // パッケージからも、直接の依存でないパッケージ・プロジェクトのローカルのモジュールは import できない（診断はパッケージのファイルの位置）
    @Test
    fun packagesSeeOnlyTheirOwnDependencies() {
        library("other", "1.0.0", mapOf("other" to "fun one(): Int {\n    return 1\n}\n"), listOf("other"))
        library("sneaky", "1.0.0", mapOf("sneaky" to "import other\nimport helpers\nfun f(): Int {\n    return other.one() + helpers.h()\n}\n"), listOf("sneaky"))
        val root = project("p2", "import sneaky\nimport other\nprintln(sneaky.f() + other.one())\n")
        Files.writeString(root.resolve("src/helpers.cscr"), "fun h(): Int {\n    return 1\n}\n")
        install(root, "github:owner/other@v1.0.0")
        install(root, "github:owner/sneaky@v1.0.0")
        val result = run(root)
        assertEquals(ExitCode.COMPILE_ERROR, result.code)
        val file = root.resolve(".createscript/packages/sneaky/1.0.0/modules/sneaky.cscr")
        assertEquals("$file:1:8: error CS_MODULE_NOT_VISIBLE (\"other\")\n$file:2:8: error CS_MODULE_NOT_VISIBLE (\"helpers\")\n", result.err)
    }

    // ---- 名前の衝突（名前空間: S8.2 の H-1） ----

    // 同じスコープ（プロジェクトから見えるもの）に同じ名前が 2 つあるときだけ曖昧
    @Test
    fun refusesNamesThatAreAmbiguousInOneScope() {
        // ローカルのモジュールと、直接の依存の公開モジュール（大文字・小文字だけが違うファイルも同じ名前）
        for (file in listOf("vector_math.cscr", "Vector_Math.cscr")) {
            Files.writeString(app.resolve("src/$file"), "fun length2(x: Int, y: Int): Int {\n    return 0\n}\n")
            val local = run()
            assertEquals(ExitCode.COMPILE_ERROR, local.code, file)
            assertTrue(local.err.contains("main.cscr:1:8: error CS_MODULE_AMBIGUOUS (\"vector_math\")"), local.err)
            Files.delete(app.resolve("src/$file"))
        }
        // 2 つの直接の依存が、同じ名前の公開モジュールを持つ
        library("copycat", "1.0.0", mapOf("vector_math" to "fun length2(x: Int, y: Int): Int {\n    return 1\n}\n"), listOf("vector_math"))
        install(app, "github:owner/copycat@v1.0.0")
        val packages = run()
        assertEquals(ExitCode.COMPILE_ERROR, packages.code)
        assertTrue(packages.err.contains("main.cscr:1:8: error CS_MODULE_AMBIGUOUS (\"vector_math\")"), packages.err)
        assertEquals("", packages.out)
        // パッケージの中: 自分のモジュールと、自分の直接の依存の公開モジュールが同じ名前
        val other = project("other", "import mixer\nprintln(mixer.m())\n")
        library("shared", "1.0.0", mapOf("util" to "fun v(): Int {\n    return 1\n}\n"), listOf("util"))
        library("mixer", "1.0.0", mapOf("mixer" to "import util\nfun m(): Int {\n    return util.v()\n}\n", "util" to "fun v(): Int {\n    return 2\n}\n"), listOf("mixer"), mapOf("shared" to "1.0.0"))
        install(other, "github:owner/mixer@v1.0.0")
        val inside = run(other)
        assertEquals(ExitCode.COMPILE_ERROR, inside.code)
        assertTrue(inside.err.contains("${other.resolve(".createscript/packages/mixer/1.0.0/modules/mixer.cscr")}:1:8: error CS_MODULE_AMBIGUOUS (\"util\")"), inside.err)
    }

    // 別のパッケージの、同じ名前の公開しないモジュールが共存する。ローカルのモジュールとパッケージの公開しないモジュールも共存する
    @Test
    fun samePrivateModuleNamesCoexist() {
        library("alpha", "1.0.0", mapOf("alpha" to "import util\nfun a(): Int {\n    return util.v()\n}\n", "util" to "fun v(): Int {\n    return 1\n}\n"), listOf("alpha"))
        library("beta", "1.0.0", mapOf("beta" to "import util\nfun b(): Int {\n    return util.v()\n}\n", "util" to "fun v(): Int {\n    return 20\n}\n"), listOf("beta"))
        val root = project("two", "import alpha\nimport beta\nimport util\nimport vm_internal\nimport vector_math\nprintln(alpha.a() + beta.b() + util.v())\nprintln(vm_internal.square(3))\nprintln(vector_math.length2(3, 4))\n")
        Files.writeString(root.resolve("src/util.cscr"), "fun v(): Int {\n    return 300\n}\n")
        Files.writeString(root.resolve("src/vm_internal.cscr"), "fun square(x: Int): Int {\n    return -1\n}\n")
        install(root, "github:owner/alpha@v1.0.0")
        install(root, "github:owner/beta@v1.0.0")
        install(root, "github:owner/vector-math@v1.2.0")
        val result = run(root)
        assertEquals(ExitCode.OK, result.code, result.err)
        // ローカルの vm_internal は -1、パッケージの中の vm_internal は 2 乗
        assertEquals("321\n-1\n25\n", result.out)
        // 公開モジュールの名前が推移的な依存と同じでも、プロジェクトから見えるのは直接の依存だけ（wrapper の中からは copycat のもの）
        library("copycat", "1.0.0", mapOf("vector_math" to "fun length2(x: Int, y: Int): Int {\n    return 1000\n}\n"), listOf("vector_math"))
        library("wrapper", "1.0.0", mapOf("wrapper" to "import vector_math\nfun w(): Int {\n    return vector_math.length2(0, 0)\n}\n"), listOf("wrapper"), mapOf("copycat" to "1.0.0"))
        install(app, "github:owner/wrapper@v1.0.0")
        writeMain("import wrapper\nimport vector_math\nprintln(wrapper.w() + vector_math.length2(3, 4))\n")
        val layered = run()
        assertEquals("1025\n", layered.out, layered.err)
    }

    // エントリーと同じ名前の、パッケージの中のモジュールは別のモジュール（以前は誤って CS_MODULE_CYCLE: S8.2 の L-1）。本当の循環は報告する
    @Test
    fun entryNameAndCycles() {
        library("gamma", "1.0.0", mapOf("gamma" to "import main\nfun g(): Int {\n    return main.m()\n}\n", "main" to "fun m(): Int {\n    return 3\n}\n"), listOf("gamma"))
        install(app, "github:owner/gamma@v1.0.0")
        writeMain("import gamma\nprintln(gamma.g())\n")
        val result = run()
        assertEquals(ExitCode.OK, result.code, result.err)
        assertEquals("3\n", result.out)
        // ローカルのモジュールからエントリーへ
        Files.writeString(app.resolve("src/back.cscr"), "import main\nfun b(): Int {\n    return 0\n}\n")
        writeMain("import back\nprintln(back.b())\n")
        val local = run()
        assertEquals(ExitCode.COMPILE_ERROR, local.code)
        assertEquals("${app.resolve("src/back.cscr")}:1:8: error CS_MODULE_CYCLE (\"main -> back -> main\")\n", local.err)
        // パッケージの中の循環
        library("loop", "1.0.0", mapOf("loop" to "import inner\nfun l(): Int {\n    return 0\n}\n", "inner" to "import loop\nfun i(): Int {\n    return 0\n}\n"), listOf("loop"))
        install(app, "github:owner/loop@v1.0.0")
        writeMain("import loop\nprintln(loop.l())\n")
        val inPackage = run()
        assertEquals(ExitCode.COMPILE_ERROR, inPackage.code)
        assertEquals("${app.resolve(".createscript/packages/loop/1.0.0/modules/inner.cscr")}:1:8: error CS_MODULE_CYCLE (\"loop/loop -> loop/inner -> loop/loop\")\n", inPackage.err)
    }

    // ---- 実行の前の確認 ----

    @Test
    fun refusesTamperedInstalledFiles() {
        val module = app.resolve(".createscript/packages/numbers/3.0.0/modules/numbers.cscr")
        Files.writeString(module, "fun zero(): Int {\n    return 7\n}\n")
        val changed = run()
        assertEquals(ExitCode.INVALID_INPUT, changed.code)
        assertEquals("createscript: .createscript/packages/numbers/3.0.0 cannot be used (modules/numbers.cscr was changed); remove it and run createscript install\n", changed.err)
        assertEquals("", changed.out)
        // 足したファイル・消したパッケージ
        Files.writeString(module, "fun zero(): Int {\n    return 0\n}\n")
        assertEquals(ExitCode.OK, run().code)
        Files.writeString(module.resolveSibling("extra.cscr"), "fun x(): Int {\n    return 1\n}\n")
        assertTrue(run().err.contains("it has a file that was not installed: modules/extra.cscr"))
        Files.delete(module.resolveSibling("extra.cscr"))
        app.resolve(".createscript/packages/vector-math/1.2.0").toFile().deleteRecursively()
        val missing = run()
        assertEquals(ExitCode.INVALID_INPUT, missing.code)
        assertEquals("createscript: vector-math 1.2.0 is not installed; run createscript install\n", missing.err)
        // シンボリックリンクに差し替えたディレクトリ
        val elsewhere = Files.createDirectories(directory.resolve("elsewhere"))
        Files.createSymbolicLink(app.resolve(".createscript/packages/vector-math/1.2.0"), elsewhere)
        assertTrue(run().err.contains("it is a symbolic link"))
    }

    @Test
    fun refusesALockFileThatDoesNotMatch() {
        val lockPath = app.resolve("createscript.lock")
        val lock = Files.readString(lockPath)
        // ロックファイルが無い
        Files.delete(lockPath)
        val missing = run()
        assertEquals(ExitCode.INVALID_INPUT, missing.code)
        assertTrue(missing.err.contains("error CS_PKG_LOCK_MISSING"), missing.err)
        // マニフェストと合わない版
        Files.writeString(lockPath, lock.replace("\"version\": \"1.2.0\"", "\"version\": \"1.2.1\""))
        val version = run()
        assertEquals(ExitCode.INVALID_INPUT, version.code)
        assertTrue(version.err.contains("CS_PKG_LOCK_VERSION_MISMATCH"), version.err)
        // 形は正しいが、インストールしたアーカイブと違う SHA-256
        val sha = Regex("\"sha256\": \"([0-9a-f]{64})\"").findAll(lock).first().groupValues[1]
        Files.writeString(lockPath, lock.replace(sha, "0".repeat(64)))
        val other = run()
        assertEquals(ExitCode.INVALID_INPUT, other.code)
        assertTrue(other.err.contains("was installed from another archive"), other.err)
        // 正規形でない
        Files.writeString(lockPath, lock.replace("\n", "\n\n"))
        assertTrue(run().err.contains("CS_PKG_LOCK_NOT_CANONICAL"))
        Files.writeString(lockPath, lock)
        assertEquals(ExitCode.OK, run().code)
        // 中断したインストール
        Files.writeString(app.resolve(".createscript/transaction.json"), "{}")
        val interrupted = run()
        assertEquals(ExitCode.INVALID_INPUT, interrupted.code)
        assertTrue(interrupted.err.contains("an installation was interrupted"), interrupted.err)
    }

    // 実行は通信しない（キャッシュも使わない）。実行の経路のコードは、通信のクラスを使わない
    @Test
    fun runsWithoutTheNetwork() {
        cache.toFile().deleteRecursively()
        releases.assets.clear()
        assertEquals("25\n", run().out)
        for (file in listOf(
            "src/main/kotlin/com/creatrbypc/createanalytical/cli/ProjectProgram.kt",
            "../createscript-install/src/main/kotlin/com/creatrbypc/createanalytical/install/InstalledPackages.kt",
        )) {
            val text = File(file).readText()
            for (forbidden in listOf("GitHubReleases", "PackageFetcher", "ArchiveCache", "HttpClient", "java.net", "Installer(")) {
                assertFalse(text.lines().filterNot { it.trim().startsWith("//") }.any { it.contains(forbidden) }, "$file uses $forbidden")
            }
        }
    }

    // ---- 既存の動きとの関係 ----

    // ファイルを指定した run は今までどおり（パッケージを使わない）。プロジェクトモードでは --project-root を使えない
    @Test
    fun runningAFileIsUnchanged() {
        val file = cli("run", app.resolve("src/main.cscr").toString())
        assertEquals(ExitCode.COMPILE_ERROR, file.code)
        assertTrue(file.err.contains("error CS_MODULE_NOT_FOUND (\"vector_math\")"), file.err)
        writeMain("println(1)\n")
        assertEquals("1\n", cli("run", app.resolve("src/main.cscr").toString()).out)
        assertEquals("1\n", run().out)
        val usage = cli("run", app.toString(), "--project-root", app.toString())
        assertEquals(ExitCode.USAGE, usage.code)
        assertTrue(usage.err.contains("--project-root cannot be used when running a project"), usage.err)
        // オプションはプロジェクトモードでも同じ
        writeMain("var i = 0\nwhile (true) {\n    i = i + 1\n}\n")
        assertEquals(ExitCode.MAX_TICKS_EXCEEDED, cli("run", app.toString(), "--max-ticks", "3").code)
    }

    @Test
    fun aLibraryWithoutEntryCannotBeRun() {
        val library = Files.createDirectories(directory.resolve("lib"))
        Files.writeString(library.resolve("createscript.json"), """{"schemaVersion": 2, "kind": "library", "name": "lib", "version": "1.0.0", "modules": ["lib"]}""")
        Files.createDirectories(library.resolve("modules"))
        Files.writeString(library.resolve("modules/lib.cscr"), "fun f(): Int {\n    return 1\n}\n")
        val result = run(library)
        assertEquals(ExitCode.INVALID_INPUT, result.code)
        assertTrue(result.err.contains("has no entry; a library cannot be run"), result.err)
        assertEquals(ExitCode.NO_INPUT, run(directory.resolve("empty").also { Files.createDirectories(it) }).code)
    }
}
