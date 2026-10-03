# SpecQR Kotlin

A from-scratch QR Code Model 2 encoder for Kotlin/JVM. No third-party runtime dependencies: only the Kotlin standard library and `java.base`. It does not wrap the Java edition or another QR implementation.

日本語を主とする Kotlin/JVM 版 SpecQR です。Version 1–40、L/M/Q/H、全 8 mask、混合セグメント最適化、Kanji、ECI/FNC1、GS1、Structured Append、PNG/SVG を Kotlin で実装しています。現在は `0.1.0-rc.1`。安定版・規格認証・GS1 認証を意味しません。

## 対象環境

- Kotlin/JVM。ソースの language/API は **2.2**、bytecode/API target は **JVM 17**
- 検証対象 compiler: **2.2.21 / 2.3.21 / 2.4.20**
- CI: 各 compiler × JDK **17 / 21 / 25** × Linux / macOS / Windows
- runtime: Kotlin stdlib と JDK `java.base` のみ。`java.desktop`、kotlin-reflect、coroutines、外部 PNG/QR ライブラリは不要
- Kotlin/Native、JS、Wasm、Android の platform 検証は含みません

[公式 compiler オプション](https://kotlinlang.org/docs/compiler-reference.html)を使い、`-Xjdk-release=17 -language-version 2.2 -api-version 2.2 -no-reflect` でビルドします。compiler 配布物が含む開発用ライブラリを runtime JAR に入れません。

## ビルド

必要なのはフル JDK 17 以降と公式 Kotlin compiler。Gradle/Maven は必須ではありません。

```sh
# リポジトリのルートで実行。公式配布 ZIP を固定 SHA-256 で検証します。
export KOTLIN_HOME="$(java tools/SetupKotlin.java 2.4.20)"
java tools/Build.java test
java tools/VerifyPackage.java
```

既に compiler を持つ場合は、`lib/kotlin-compiler.jar` と `lib/kotlin-stdlib.jar` を含むディレクトリを `KOTLIN_HOME` に設定できます。Windows PowerShell:

```powershell
$env:KOTLIN_HOME = java tools/SetupKotlin.java 2.4.20
java tools/Build.java test
java tools/VerifyPackage.java
```

生成物は `dist/specqr-kotlin-0.1.0-rc.1.jar` と `-sources.jar` です。薄い JAR のため stdlib は利用側の classpath に置きます。コンパイラやテスト依存を同梱しません。Maven Central への公開は行っていません。

### 既存の Kotlin/JVM project へ入れる

JAR を project の `libs/` などにコピーし、既存の Kotlin/JVM build でローカル依存として追加できます。以下は Gradle Kotlin DSL の例です（このライブラリ自身のビルドに Gradle は不要）。Kotlin stdlib は利用側の通常の Kotlin 設定で提供してください。

```kotlin
dependencies {
    implementation(files("libs/specqr-kotlin-0.1.0-rc.1.jar"))
}
```

classpath だけでも利用できます。`examples/Basic.kt` を使う場合:

```sh
"$KOTLIN_HOME/bin/kotlinc" examples/Basic.kt -jvm-target 17 \
  -classpath "dist/specqr-kotlin-0.1.0-rc.1.jar:$KOTLIN_HOME/lib/kotlin-stdlib.jar" -d example-classes
java --limit-modules java.base \
  -cp "example-classes:dist/specqr-kotlin-0.1.0-rc.1.jar:$KOTLIN_HOME/lib/kotlin-stdlib.jar" BasicKt
```

## Kotlin API

```kotlin
import io.specqr.*

val options = Options(errorCorrectionLevel = "Q", scale = 4)
val qr = SpecQr.generate("こんにちは、SpecQR Kotlin!", options)
println("Version ${qr.version}, mask ${qr.maskPattern}")
val svg: String = qr.toSvg()
val png: ByteArray = qr.toPng()
val rgba: Render.Pixels = qr.toPixels()
val dataUrl: String = qr.toPngDataUrl()
val dark: Boolean = qr[0, 0]

val smaller = options.copy(scale = 2)
val plan = SpecQr.estimate("1234567890", smaller)
check(plan.ok)
println(plan.requiredBits)
```

`Options` は検証付き immutable data class。`QrCode`/`Plan`/`Segment` は immutable で、配列は入出力時にコピーします。Kotlin property に加え Java 向けの同名メソッド・static factory・builder を提供します。`Options.copy` も不正値を検証します。

```kotlin
val mixed = SpecQr.generateSegments(listOf(
    Segment.numeric("123456789"),
    Segment.alphanumeric(" SPECQR "),
    Segment.kanji("漢字"),
    Segment.bytes("😀")
))
val binary = SpecQr.generate(byteArrayOf(0, 127, 128.toByte(), 255.toByte()))
val utf8Label = SpecQr.generate("日本語", Options(eci = 26))
```

[API 詳細](docs/api.md) · [GS1](docs/gs1.md) · [Structured Append](docs/structured-append.md) · [検証方法・限界](docs/verification.md)

## CLI

```sh
java -cp "dist/specqr-kotlin-0.1.0-rc.1.jar:$KOTLIN_HOME/lib/kotlin-stdlib.jar" \
  io.specqr.Main --ecc Q --format png --output code.png "こんにちは"

java -cp "dist/specqr-kotlin-0.1.0-rc.1.jar:$KOTLIN_HOME/lib/kotlin-stdlib.jar" \
  io.specqr.Main --segments examples/segments.json --estimate
```

Windows は classpath 区切りに `;` を使います。`--help` に全オプション、`--diagnostics` は stderr に JSON、`--binary FILE` は任意の raw bytes、`--` は dash で始まるテキストです。PNG を stdout に出す場合はバイナリ安全なリダイレクトを使ってください。

## 実装範囲

- Model 2 Version 1–40、4 ECC、固定/自動 version、固定/自動 mask、同一 version 内の ECC boosting
- numeric / alphanumeric / UTF-8 byte / raw binary / Kanji、自動 mixed 最適化と手動境界
- ECI、FNC1 first/second。制御の組合せは SpecQR 契約による制限あり
- plan/estimate/capacity、codeword と mask/scan/print 診断
- 範囲限定 GS1 AI element string、括弧表記、Digital Link
- Structured Append 2–16 個、XOR parity、scalar 安全な分割、検証付き merge
- 決定的 RGBA PNG、SVG、pixels、data URLs。静かな余白や色の診断

Micro QR、rMQR、QR の読み取り、ロゴ埋込み、他の QR 実装への委譲はありません。ECI は encoding label であり、任意 charset への変換機能ではありません。UTF-16 の孤立 surrogate は拒否します。

## 品質と出典

Kotlin の実コードを、所有者の固定 JS baseline、独立 Nayuki encoder、jsQR、ZXing-C++、ZXing Java と比較します。テスト専用依存は runtime と隔離し、候補 class/JAR・stdlib・ソース fingerprint を記録します。破損 negative control と外部 consumer を含み、未検証や decoder の制約は成功数と分けます。

アルゴリズムと API 契約の参照:
- [SpecQR JS](https://github.com/SpecQR/SpecQR/tree/15ad15e5c770ea0e39072f8f88b2733018f02ffd), `3.0.0-rc.2`
- [SpecQR Java](https://github.com/SpecQR/SpecQR-Java/tree/1f2bf277a582f0eb4e31c9975443c1a76552aa23)

新しい Kotlin runtime ソースは `src/main/kotlin` のみです。Java のテスト bridge とビルドツールは開発専用です。固定 Kanji 表は WHATWG Shift_JIS first mapping。詳細は [NOTICE](NOTICE)。

## ライセンス

[MIT](LICENSE)。改善方法は [CONTRIBUTING](CONTRIBUTING.md)、報告方針は [SECURITY](SECURITY.md) を参照してください。
