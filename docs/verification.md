# Verification

Verification executes the Kotlin implementation itself. Independent encoders and decoders are development-only tools in separate processes, not dependencies of the shipped library.

## ローカル最終結果 (2026-10-03 UTC)

8 Java 相互運用 suite **263,805 assertions** と Kotlin native **1,607 assertions** が失敗・skip なし。Kotlin compiler 3 版 × JDK 17/25 の 6 build 組合せを実行し、すべて同じ suite を通過しました。JDK 21 では最小 stdlib 2.2.21 を使った実行も通過しています。独立追加検査 432 + 194 assertions は、JDK 17/25 × stdlib 3 版の各 6 実行組合せで通過。これらは別々の母集団です。

最終 Kotlin source と exact thin JAR の両方について、下記 conformance・3 decoder・GS1 全 lane が合格しました。source/tool の実行前後 fingerprint 一致も検証しています。公開 commit の各 OS/JDK/compiler 結果は GitHub Actions の当該 commit checks を参照してください。

## 検証の分離

1. `java tools/Build.java test`: Kotlin runtime をコンパイルし、Java 相互運用の回帰テストと Kotlin native API テストを実行
2. `java tools/VerifyPackage.java`: 同一ツールチェーンで 2 回ビルドし runtime/source JAR の byte 一致を確認。owned class、Kotlin metadata、license、依存を検査。別ディレクトリに JAR と stdlib だけを置き Java/Kotlin consumer と CLI をコンパイル・実行
3. `tools/conformance`: 固定した所有者 JS と独立 Nayuki encoder に exact matrix/data/ECC 比較。jsQR、ZXing-C++、ZXing Java はそれぞれ実画像を読む独立 decoder
4. `tools/gs1-conformance`: Kotlin GS1 API と固定 JS baseline の operation と structured issue を照合
5. GitHub Actions: Kotlin 2.2.21 / 2.3.21 / 2.4.20 × JDK 17 / 21 / 25 × Linux / macOS / Windows。別の independent lane と aggregate required gate

比較用 Java bridge は入力を Kotlin API に渡すだけで、QR を生成しません。candidate JVM の classpath は adapter + candidate classes（または指定された exact JAR）+ kotlin-stdlib のみです。`kotlin.Metadata`、nonce/PID、class origin、source/class/JAR/stdlib SHA-256 を読み返して候補同一性を確認します。

## 詳細な比較範囲

- 公開生成 3,028 件: V1–40 × ECC4 × mask8 を網羅、全 data/ECC codeword と matrix module hash を照合
- 独立 Nayuki 1.8.0: 2,400 matrices。固定 version/mask、明示 segment 境界で比較
- 内部配置 4,320 件、全 mask penalty、GF(256) 全 65,536 積、RS degree 1–255
- 640 容量、1,920 境界 plan、Unicode/任意バイト fuzz、18 malformed cases
- SA 22 sets / 112 symbols の data/ECC/matrix、8-thread 256 replay
- matrix/data/ECC corruption、process fault、response fault、untyped error の 9 negative controls
- GS1 15,690 operations: AI catalog、正常/異常値、URL、validation structure、typed-error negative control

テスト数は「assertion」「operation」「symbol」「matrix」を区別します。これらのカテゴリを合算して一つの独立テスト数とは呼びません。実行環境・fingerprint・集計は [verification-summary.json](verification-summary.json) と CI artifacts に残します。

## 独立 decoder と既知の制約

- jsQR 1.4.0: matrix 232 / actual PNG 232。FNC1 と SA は対応範囲外なので成功対象に含めない
- ZXing-C++ 3.1.1: matrix 706 / actual PNG 750。ECI/FNC1/SA metadata、任意 byte、色/alpha、破損訂正を含む
- ZXing Java 3.5.3: matrix 446 / actual PNG 446。scale 3 の strict detection に fallback は使わない
- 小規模な破損を加えた 32 symbols について独立 decoder の誤り訂正を検証

ZXing Java は特定の通常 scale-8 PNG で detection を失敗します。同一 pixel を独立 PNG encoder で保存した control も失敗し、他 decoder は読めます。この現象は `knownLimitations` に candidate/control とともに記録し、成功数へ加えません。生成画像を後から小さくして同じ試験の成功に置き換えません。

GS1 adapter は Java `IDN` を利用するため IDNA2003 と Node WHATWG の差異があります。固定 differential audit は IDNA 24 operations と、dot-only path value を失わず query に移す policy の 40 operations を明示分類します。単なる「安全のため」という理由で従来の無害な URL を一括拒否しません。[具体例と制限](gs1.md#url-adapter-の範囲と-jvm-adapter-固有の差分)。

## 再現性と runtime 依存

`Build.java` はファイル順序、ZIP timestamp、stored method を固定。再現性は同一 compiler/JDK/OS/ソースでの比較です。異なる compiler/JDK が常に同一 class bytes を生成する保証ではありません。

runtime JAR には `io/specqr` の Kotlin class と metadata/license だけを入れます。Kotlin standard library は別ファイルです。`jdeps --multi-release 17 --class-path kotlin-stdlib.jar --print-module-deps ...jar` と `--limit-modules java.base` の detached consumer で platform 依存を検査します。Kotlin reflection、coroutines、Java edition、外部 QR/PNG library は runtime classpath に置きません。

## 保証しないもの

独立 oracle もテストも規格認証ではありません。全 scanner/照明/印刷条件、全 GS1 業種規則、汎用 IDNA2008/WHATWG URL 完全互換、Native/JS/Wasm/Android、無制限入力を保証しません。QR payload の URL を信頼してよいかは別途アプリケーションで判断してください。
