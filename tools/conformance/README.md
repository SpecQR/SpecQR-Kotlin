# Development-only conformance tools

Every candidate QR operation in these tools runs the actual Kotlin implementation
in a separate Java 17+ JVM.

Python は比較を制御し、Node は独立した参照実装とデコーダーを実行します。どちらも候補のシンボル生成には関与しません。配布ライブラリの依存先は JDK と Kotlin 標準ライブラリだけです。候補の runtime は Java 版やエンコーダー、デコーダーを使用しません。

Java 17+（`java` と同じディレクトリの `javac`）、Kotlin 2.4.20、Python 3.11+、Node 18+ をインストールし、リポジトリのルートから実行します。

```sh
export KOTLIN_HOME=/absolute/path/to/kotlinc
npm ci --prefix tools/conformance --ignore-scripts
git clone https://github.com/SpecQR/SpecQR.git ../SpecQR-baseline
git -C ../SpecQR-baseline checkout 15ad15e5c770ea0e39072f8f88b2733018f02ffd
python3 tools/conformance/verify_conformance.py --baseline ../SpecQR-baseline
python3 tools/conformance/verify_jsqr.py
python3 tools/conformance/verify_decoders.py --decoder cpp --dependency-dir .tools/zxing-cpp
python3 tools/conformance/verify_decoders.py --decoder java --dependency-dir .tools/zxing-java
```

既定の候補はこのチェックアウトです。`--candidate PATH` で別のソースのチェックアウトを選択します。`--candidate-java /absolute/jdk/bin/java` で候補の JVM を選択します。bridge は `src/main/kotlin` だけを `kotlinc` でコンパイルし、language/API 2.2、JVM target 17 を指定して警告をエラーとして扱います。Java テスト adapter は同じディレクトリの `javac --release 17` で別途コンパイルします。その後、一時保存先にあるコンパイル済みの候補クラス、adapter クラス、`kotlin-stdlib.jar` だけを使って起動します。`SPECQR_JAVA` で既定の JVM を設定します。ZXing Java の独立した `--java` オプションはデコーダーの JVM を選択し、候補の runtime とは独立しています。

ビルド済み配布物を利用する側の検査では、各コマンドに `--jar /absolute/path/specqr-kotlin.jar` を追加します。その JAR 自体に対して adapter だけをコンパイルし、adapter クラス、JAR、Kotlin 標準ライブラリだけで構成した classpath を使用します。配布物の代わりにソースのチェックアウトを読み込むことはありません。JAR のビルドと内容検査は別の検査です。`--jar` を渡すだけではパッケージの監査にはなりません。

デコーダーの wheel は選択した Python/OS に対応している必要があります。`--no-install` はハッシュ検証済みのデコーダーファイルだけを使用し、ファイルがなければ失敗します。Nayuki/jsQR は `SPECQR_DEV_NODE_MODULES=/absolute/package/directory` により、別途用意したパッケージディレクトリから解決できます。デコーダーライブラリ、バイナリ、node_modules、生成したレポート、fixture は配布ライブラリの依存関係には含めません。レポートをチェックアウトの外へ保存する場合は `--output PATH` を使います。

## Exact scope and controls

- 固定した JavaScript を実行する比較: 公開 API の 3,028 matrices を比較します。全 version 1–40 × ECC L/M/Q/H × mask 0–7、Numeric、Alphanumeric、Byte、Kanji、混在する手動 segments、自動最適化、自動 mask、ECI 境界、FNC1 first/second、GS1、ECC boost、決定的な Unicode fuzz、ランダム化した 160 binary cases を含みます。padding 済みの全 data codewords、interleave 済みの全 data/ECC codewords、version、ECC、mask、全 matrix module の SHA-256 を比較します。
- 独立した Nayuki 1.8.0 との比較: 全 1,280 version/ECC/mask の組合せを含む 2,400 matrices を比較します。version と mask を固定し、独立した auto-mask/segmentation/ECC-boost の差分を無効にします。segment 境界は保持します。
- 内部比較: 4,320 raw matrices（three data patterns × 40 versions ×
  four ECC levels × automatic and eight explicit masks）、全 8 mask scores、全 65,536 GF products、次数 1–255 の RS generator/remainder を比較します。
- 公開計画 API: 全 version、ECC、data mode について、厳密な容量 640 件と、容量ちょうど・直下・直上の見積り 1,920 件を比較します。Structured Append は 22 sets、112 matrices を比較し、各メンバーの厳密な data/ECC codewords も含みます。同一プロセスを共有する 8 thread の再実行では、さらに 256 matrices を比較します。不正入力には Kotlin の `SpecQrException` と認識済みの安定した error code が必要です。通常の JVM 例外は型付きの失敗として受け入れません。
- 候補に対する 6 negative controls は、それぞれ実際の JVM を起動して Kotlin API に到達した後、matrix hash、data byte、interleave 済み codeword のいずれかを改変するか、強制終了、応答欠落、エラーを発生させます。追加の 3 controls では、型なし例外の漏出を拒否することを確認します。候補とデコーダーの応答件数を厳密に検査し、応答の切捨てを合格にはしません。

レポートには、source fingerprints、候補の JVM/version/PID/nonce、Kotlin metadata と標準ライブラリの fingerprint、class-loading origin、classpath、コンパイル済み class または JAR の fingerprints、参照 commit/source fingerprints、参照 corpus hashes、テストツールの fingerprints を記録します。ソース、バイナリ、テストツールを変更すると、適合性検証の結果は無効になります。`--suite public` または `--suite internal` の結果は、明示した範囲だけの結果です。

## Three independent decoders

- jsQR 1.4.0: matrix 検出 232 件と実 PNG 検出 232 件を行い、全 version、Numeric、Alphanumeric、Byte、Kanji、binary bytes、64 ECI-header checks を含みます。jsQR は FNC1 と Structured Append に対応していないため、これらの検証結果を主張するのは ZXing による検証だけです。
- ZXing-C++ 3.1.1: matrix 検出 706 件と実 PNG 検出 750 件を行い、全 version、有効な Structured Append headers 135 件、手動 API と高水準 API を通じた全 152 FNC1-second indicators（304 symbols）、ECI bytes/text/metadata、高水準 API による完全な 5 sets（44 symbols）、独立した集合の復元を含みます。
- ZXing Java 3.5.4: matrix 検出 446 件と実 PNG 検出 446 件を行い、全 version、135 Structured Append headers と sequence/parity metadata、両方の経路で復元した高水準 API による完全な 5 sets、32 件の損傷シンボル検査を含みます。損傷シンボル検査では、それぞれ独立に訂正された 3 codewords が必要です。

実 PNG の全 pixel と quiet-zone pixel を検出前に検証します。Java の厳密な PNG 検出は `PURE_BARCODE` を使わず scale 3 で実行し、検出失敗時に matrix へフォールバックしません。既定の scale 8 における既知の PNG detector の挙動は、独立した同一 pixel の PNG とともに、別の診断として保持します。失敗を明示し、厳密な成功件数に加算しません。C++ は既定の scale 8 を検査します。すべてのデコーダーは、実際の全白画像による negative control を拒否する必要があります。

これらは有限の合成テストであり、ISO/GS1 認証、カメラ・印刷検査、任意の入力や損傷に対する保証ではありません。デコード失敗、未検査の version、依存関係の欠落、ソースの変更、テストの省略を、黙って合格に数えることはありません。

## Provenance and pinned development dependencies

MIT ライセンスの SpecQR-Java `1f2bf277a582f0eb4e31c9975443c1a76552aa23` を Kotlin 向けに移植しています。この Java 版自体は SpecQR-Python tools の `1deb5cf83ed7a93eeb44138e5980a963a00cdffb` を移植したものです。上流の oracle、Structured Append corpus、ECI control、デコーダーの workflow は SpecQR-CPP `e91cd8fe4434fd6ef10d1126bb2fd17b0b19321f` に由来します。Java デコーダーと固定したデコーダー依存条件は SpecQR-CSharp `057c4b3f25e52c4786a8f94c744ff884eedcecfa` に由来します。Java デコーダーは元の SpecQR-Swift の帰属表示を保持しています。JavaScript baseline は `15ad15e5c770ea0e39072f8f88b2733018f02ffd` です。

npm lock はパッケージの integrity hashes を含みます。ツールは ZXing Java JAR の SHA-256 と ZXing-C++ の公式 PyPI wheel hashes を検査します。第三者のエンコーダーやデコーダーのソース、バイナリ、依存関係、import が Kotlin runtime に入ることはありません。
