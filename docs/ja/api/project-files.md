# `createscript-project` でプロジェクトのファイルを読む

> **原文（英語）**：[docs/api/project-files.md](../../api/project-files.md)（コミット `4a2192b` の時点）。内容が食い違うときは英語版が正です。

`createscript-project` は、プロジェクトのファイル `createscript.json` と `createscript.lock` を読み取り・検査・書き出しします。
扱うのは **バイト列と文字列だけ** です。ファイルを読まず、ネットワークも使いません。そのため、ツールは自分でファイルを読み、
そのバイト列を渡します。ファイルの形式と規則は [../packages.md](../packages.md) で説明しています。この案内では、
安定 API（一覧は [api-surface.md](api-surface.md)）の使い方を示します。

ビルドは `./gradlew :createscript-project:jar` です（`createscript-project/build/libs/createscript-project.jar` ができます）。
必要なのは Kotlin の標準ライブラリ 2.0.0（と、それが使う JetBrains の annotations）だけです。パッケージは `com.creatrbypc.createanalytical.project` です。

## マニフェスト

```kotlin
import com.creatrbypc.createanalytical.project.Manifests

fun checkManifest(bytes: ByteArray) {
    val result = Manifests.parse(bytes)
    val manifest = result.manifest
    if (manifest == null) {
        result.diagnostics.forEach { println(it.format(Manifests.FILE_NAME)) }
        return
    }
    println("${manifest.name} ${manifest.version} (${manifest.kind.id})")
    manifest.entry?.let { println("entry $it at line ${result.entryPosition.line}") }
}
```

- `parse` は厳密です。知らないキーや重複したキー、型の誤り、規則に合わない値は、**例外ではなく診断** になります
  （`CS_PKG_INVALID_NAME` のような安定した ID が付きます）。`Manifests.MAX_BYTES` を超えるファイルは拒否します。
- `ProjectDiagnostic.format(file)` は `file:line:column: error CODE ("argument", ...)` の形の文字列を返します。引数はエスケープするので、
  そのまま端末に表示しても安全です。
- `ManifestResult` は、エントリー・各依存・各モジュールの位置（`FilePosition`）を持ちます。後で見つかった問題（ファイルが無いなど）を、
  正しい場所で報告するために使います。
- `Manifests.write(manifest)` は正規形の文字列を返します。同じマニフェストからは、いつも同じ文字列になります。

> [!TIP]
> 診断の出力の形：`createscript.json:<行>:<列>: error CS_PKG_INVALID_NAME ("Bad")`。行と列は 1 から数え、列は UTF-16 のコード単位です（`FilePosition` も同じ）。

## ロックファイル

```kotlin
import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.Manifest

fun checkLock(bytes: ByteArray, manifest: Manifest): Boolean {
    val result = Lockfiles.parse(bytes)
    val problems = Lockfiles.check(bytes, result, manifest)
    problems.forEach { println(it.format(Lockfiles.FILE_NAME)) }
    return problems.isEmpty()
}
```

- `parse` は、ロックファイルだけで決まることを確かめます（値・重複・参照・循環）。
- `check` は、さらに正規形のバイト列（`Lockfiles.write`）とまったく同じであることと、マニフェストと合っていることを求めます。
  `parse` が失敗していたときは、`parse` の診断を返します。
- `Lockfile.of(root, packages)` は、正規の順に並べたロックファイルを作ります。値が正しくなければ `IllegalArgumentException` を投げます。

<!-- ja-only -->
| 段階 | 関数 | 確かめること |
|---|---|---|
| 1 | `Lockfiles.parse` | JSON の形、値、重複、参照、循環 |
| 2 | `Lockfiles.check` | 正規形のバイト列と同じか、マニフェストと合っているか |

## 規則

`ProjectRules` は、規則を単独で使えるようにしたものです。`isValidName`（パッケージ名）、`isValidModuleName`、`isValidVersion`（SemVer）、
`isValidLicense`、`isValidSource`（`github:owner/repository`）、`isValidSha256`、`pathProblem`（エントリーとモジュールのパス）、
`isWindowsReserved` と、それぞれの上限があります。どの規則も ASCII だけを受け付けます。

## 安定 API ではないもの

JSON の読み書き、パッケージのアーカイブ（`PackageArchives`）、レジストリの形式は **内部 API**（`@InternalCreateScriptProjectApi`）です。
CLI とインストーラーが使っており、どのリリースでも変わることがあります。`PackageArchives` は、規則が固まったら安定 API にする可能性があります。

このモジュールのオブジェクトは、どれも変更可能な状態を持たず、複数のスレッドから使えます。
