# GS1 differential audit

This development-only audit uses Python 3's standard library, a JDK 17+, Kotlin 2.4.20 and Node.

公開する Kotlin ライブラリは、Python、Node、JavaScript、エンコーダー・デコーダーを runtime の依存関係に持ちません。外部の runtime 依存関係は Kotlin 標準ライブラリだけです。Java テスト probe は実際の公開 Kotlin GS1 実装を呼び出します。

`java`、`javac`、`node` を `PATH` に通し、リポジトリのルートから実行します。

```sh
export KOTLIN_HOME=/absolute/path/to/kotlinc
python3 tools/gs1-conformance/verify_gs1.py --baseline /path/to/SpecQR
```

baseline は、変更していない SpecQR JavaScript の `15ad15e5c770ea0e39072f8f88b2733018f02ffd` のチェックアウトである必要があります。`--java`、`--javac`、`--node` または `JAVA`、`JAVAC`、`NODE` で使用するツールを指定できます。`--report` で JSON レポートの保存先を選択します（既定は `build/gs1-conformance.json`）。Kotlin ソースは一時保存先へ別途コンパイルし、JVM 17 bytecode、language/API 2.2 を指定して警告をエラーとして扱います。Java テスト adapter はその実際の Kotlin クラスに対してコンパイルします。`--jar /absolute/path/specqr-kotlin.jar` を指定すると、代わりに指定した thin artifact 自体を検査します。どちらの経路も、外部の runtime 依存関係は Kotlin 標準ライブラリだけです。すべての子プロセスにタイムアウトを設定し、JVM 処理は有効な processor 2 個に制限します。追跡対象の baseline source/package files は、実行の前後で固定した HEAD と一致する必要があります。Kotlin ソース、テスト、補助ツールは実行前後にハッシュを計算し、実行中に変更されると監査は失敗します。テスト専用の JVM property により、実際の Kotlin GS1 実行後に catalog 出力を改変します。同じ comparator が、この negative control を値の不一致として検出する必要があります。参照実装や fault-injection branch を runtime にコピーすることはありません。

決定的な corpus は、全 50 AI metadata entries、各 AI の有効・無効な値、host・credentials・stem・path・query 値・creator 値における全 ASCII code、IPv4 の数値形式、IPv6、port、special-scheme URL 構文、percent escapes、全 UTF-8 byte 値と継続境界、seed を指定した不正な URL/byte-string samples、IDNA deviations、literal または encoded の dot-only GS1 値を対象とします。各 URI について parse、validate、normalize を検査します。

レポートは厳密な request と結果、corpus hash、Java と Node の版、baseline commit、および次の 3 種類の差分件数を保持します。

- Acceptance: 一方の実装が受理し、もう一方が拒否する。
- Value: 両方が成功するが、論理要素、未知 query の値・順序、AI metadata、正規化・作成された URI のいずれかが異なる。
- Diagnostic: 受理・拒否は一致するが、安定した error/warning fields が異なる。比較する field は `code`、`reason`、`ai`、`value`、`key`、`offset`、`elementIndex`、`expected`、`count` であり、欠落と null は正規化する。人間向けの `message` の文章は意図的に比較しない。

通常のケースまたは catalog で差分が生じると、失敗を示す終了コードになります。次の 2 分類は想定する差分として分けて明示します。

1. Java の `java.net.IDN` は IDNA2003 ですが、ブラウザ URL は UTS #46 を使用します。たとえば、Java では `faß.de` が `fass.de` になり、final sigma が畳み込まれ、一部の joiners が削除されます。新しい Unicode 文字は拒否される場合があります。ASCII ACE（`xn--`）labels は `IDN.toUnicode(..., ALLOW_UNASSIGNED)` による round-trip が必要です。不正な ACE だけでなく、`xn--fa-hia.de` のような有効な IDNA2008 deviation labels も拒否します。emoji などの新しい有効な ACE scalars は、この flag により raw Unicode host 形式が拒否される場合でも通ることがあります。通常の ASCII non-ACE hosts は、この IDNA 境界を回避します。adapter は `java.net.URI` のより厳格な raw-character grammar を使用せず、host の名前解決もネットワークアクセスも行いません。
2. dot-only GS1 値は、意図的に値を失わず扱います。Kotlin parser は literal または percent-encoded の `.` / `..` 値を保持し、作成・正規化時には query に置きます。JS baseline のブラウザ URL 正規化では、これらを削除したり、結果の path を拒否したりする場合があります。レポートは受理・拒否の差分と値の差分の両方を数えます。

この corpus は検査した挙動を示すものであり、WHATWG URL または GS1 への完全準拠を主張するものではありません。

## Exact reference-runtime profiles

固定した JavaScript ソースは、host Node runtime の WHATWG URL parser を使用します。ソースを固定するだけでは、その parser の意味まで固定されません。監査した厳密な参照 profile は Node **24.19.0 / Ada 3.4.4** と Node **24.21.0 / Ada 4.0.0** であり、どちらも ICU 78.3 と Unicode 17.0 を使用します。ほかの版や依存関係の組合せは、結果をレビューするまで fail-closed で拒否します。選択した profile は必ず記録します。

インストール済みの公式 Node バイナリをそれぞれ明示して使います。前述の compiler/JDK 設定も必要です。

```sh
python3 tools/gs1-conformance/verify_gs1.py --baseline /path/to/SpecQR --node /path/to/node-24.19.0 --node-profile 24.19.0 --report artifacts/gs1-node24.19.json
python3 tools/gs1-conformance/verify_gs1.py --baseline /path/to/SpecQR --node /path/to/node-24.21.0 --node-profile 24.21.0 --report artifacts/gs1-node24.21.json
```

corpus 全体は変更していません。**5,610 cases、15,690 operations** で、corpus SHA-256 は `61827bb17127b1c931f05d436b72984818636150e8d0a9c1cdf5cec678b99df6` です。`known-url-outcomes.json` は 30 IDNA cases と 24 dot-only cases の全正規化結果を固定しており、不変の Kotlin 結果集合 1 つと、明示した JavaScript 結果集合 2 つを保持します。`known_url_profiles.py` は厳密な request、順序、応答件数、受理・拒否、正規化した URI 値と診断を検査します。同じ分類に属することや、集計件数が変わらないことを理由に、別の結果を許容しません。通常のケースと catalog のケースは、引き続き差分による失敗がゼロである必要があります。

- Node 24.19.0: **文書化した operation 差分は 64 件**です。内訳は IDNA 24 件（acceptance 21 件、value 3 件）と dot-only 40 件（acceptance 12 件、value 28 件）です。
- Node 24.21.0: **文書化した operation 差分は 91 件**です。内訳は IDNA 51 件（acceptance 48 件、value 3 件）と、同じ dot-only 差分 40 件です。
- どちらの profile も **diagnostic 差分がゼロ**である必要があります。

追加の 27 operations は、次の 9 ASCII ACE hosts に対する parse、normalize、validate だけです。`xn--a`、`xn--`、`xn--abc`、`xn--abc-`、`xn--a-ecp.ru`、`xn--0.pt`、`xn--a.test`、`xn--a_.test`、`xn--%61.test`（`xn--a.test` に正規化）を対象とします。Kotlin adapter は、文書化した ACE 検証方針に基づき、両方の profile でこれらを拒否します。新しい Node/Ada parser は WHATWG の非 strict ASCII domain 規則に基づいて受理します。これは意図した参照規格の変更であり、Node の不具合を主張するものでも、範囲を限定した Kotlin の方針を変更する理由でもありません。

一次資料は [WHATWG URL IDNA/domain parser](https://url.spec.whatwg.org/#concept-domain-to-ascii) と [Ada 4's documented ASCII ACE behavior](https://github.com/ada-url/ada/blob/v4.0.0/src/url_pattern_helpers.cpp#L323-L327) です。fixture は、公式 Node バイナリアーカイブのチェックサムと、公開ソースアーカイブのチェックサムを記録します。各実行では、実際に実行する Node バイナリの fingerprint を別途取得し、全依存関係の版、その path/platform/architecture、JDK の識別情報、Kotlin class/JAR と標準ライブラリの hashes、全参照 JS ソースファイル、fixture、監査ツールを記録します。ソースと実行ファイルの識別情報は、実行中に変わらない必要があります。公開ソースアーカイブのチェックサムは出典の metadata であり、この監査でそのアーカイブをビルド・実行したという主張ではありません。

catalog 改変 control も、引き続き実際の JVM を呼び出します。追加の 3 controls は実際のプロセス結果のコピーを改変し、拒否される ACE host を Kotlin が受理したことにする、差分の集計件数を保ったまま IDNA の参照 URI を変更する、同じ集計件数を保ったまま Kotlin IDNA URI を変更する、という操作を行います。厳密な結果を検査する guard は、この 3 つすべてを拒否する必要があります。候補の結果から profile を選択することはなく、失敗時に fixture を自動更新することもありません。
