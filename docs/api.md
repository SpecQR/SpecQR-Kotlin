# Kotlin API

The public Kotlin/JVM API is in `io.specqr`. Kotlin properties and default/named arguments are supported; Java callers get static factories, overloads, compatible accessor methods, and an options builder.

## 生成と計画

```kotlin
val qr = SpecQr.generate("日本語😀", Options(errorCorrectionLevel = "Q"))
val raw = SpecQr.generate(byteArrayOf(0, 255.toByte()))
val manual = SpecQr.generateSegments(listOf(Segment.numeric("123"), Segment.bytes("abc")))
val plan = SpecQr.estimate("A".repeat(1000), Options(version = 1))
check(!plan.ok)
val capacity = SpecQr.getCapacity(1, "M", "byte")
check(capacity.maxBytes == 14)
```

`generate(String, Options)` / `generate(ByteArray, Options)` / `generateSegments(List<Segment>, Options)` は matrix・codeword まで生成します。`estimate` と `analyzeSegments` は計画だけを行います。通常の容量超過では plan の `ok` が false、generate は `SpecQrException("DATA_TOO_LONG", ...)` です。不正モード・Unicode・資源上限は計画でも例外になります。

### Options

検証付き immutable `data class` です。全 property は `val`。`Options()` が既定値、`options.copy(...)` で変更します。Java では `Options.builder().errorCorrectionLevel("Q").build()` と `toBuilder()` を利用できます。

| property | default | 意味 |
| --- | --- | --- |
| errorCorrectionLevel | M | L/M/Q/H |
| mode | auto | auto/numeric/alphanumeric/byte/kanji |
| version | null | 固定 Version 1–40。固定値は自動範囲より優先 |
| minVersion / maxVersion | 1 / 40 | 自動選択範囲。両方を常に検証 |
| maskPattern | null | null は自動、0–7 は固定 |
| optimizeSegments | true | auto の混合最適化 |
| boostErrorCorrection | false | 最初に選んだ version 内だけで ECC を強化 |
| eci | null | 0–999999 の assignment |
| gs1 | false | element string を検証し FNC1 first を付加 |
| fnc1Second | null | ASCII 2 桁または英字 1 文字 |
| structuredAppend | null | 低水準 header Segment |
| margin / scale | 4 / 8 | module 単位の余白 / 1 module の pixel 数 |
| foreground / background | #000000 / #ffffff | 描画色 |
| printDpi | null | 正の有限値。印刷診断用 |

ECI・GS1・FNC1 second・Structured Append は SpecQR 契約で相互に排他的です。QR 規格で可能な全制御組合せを提供する意味ではありません。ECI 自体の複数遷移は manual segments で保持します。

自動 mode は numeric、alphanumeric、Kanji、byte の bit 費用を評価します。最小 bit、次に少ない segment、最後はその mode 順が優先されます。version 1–9、10–26、27–40 の count-width group ごとに再計算。自動 mask は 0–7 を評価し penalty 同点は小さい番号です。`optimizeSegments=false` では全体が入る単一 mode を同順で選びます。空文字は byte segment です。

### Plan と Capacity

`Plan`: `ok`, `version`/`selectedVersion`, `capacityVersion`, `errorCorrectionLevel`, `requestedErrorCorrectionLevel`, `boostedErrorCorrection`, `requiredBits`/`dataBitLength`, `capacityBits`, `remainingBits`, `overflowBits`, `capacityUtilization`, `segments`, `diagnostics`。

自動範囲に収まらなければ `version=null`、`capacityVersion` は範囲の最後。固定 version の不適合では `version` に指定値が残ります。`requiredBits` は padding 前の制御/header を含む算術長で、`remainingBits` は負にもなります。

`getCapacity(version, level="M", mode=null, controls=0)` は単一 data segment の容量です。mode は numeric/alphanumeric/byte/kanji または null。`controls` は 0–2^53−1 の予約 bit 数。`Capacity` property: version, errorCorrectionLevel, size, dataCodewords, totalCodewords, capacityBits, mode, characterCountBits, modeBits, controlBits, payloadBits, maxCharacters, maxBytes。`maximum()` は対象 mode の最大数。byte は UTF-8 文字数ではなく byte 数です。

### Segment と Segments

```kotlin
val segments = listOf(
    Segment.eci(26), Segment.bytes("日本語"),
    Segment.eci(3), Segment.bytes(byteArrayOf(0xe9.toByte()))
)
val qr = SpecQr.generateSegments(segments)
```

- `Segment.numeric(text)`: ASCII decimal digits
- `alphanumeric(text)`: `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:`。大文字変換なし
- `bytes(text)`: strict UTF-8。`bytes(ByteArray)`: raw data のコピー
- `kanji(text)`: 固定 WHATWG Shift_JIS first mapping の 6,953 文字
- `eci(assignment)`, `fnc1()`, `fnc1Second(indicator)`
- `structuredAppend(index, total, parity)`: index は 1 始まり、2–16 symbols、parity 0–255

property と同名 accessor: mode, text, assignment, applicationIndicator, index, total, parity, binary, logicalBytes, count, characterCount, byteCount, applicationIndicatorCodeword。`binary` / `binary()` と `logicalBytes` / `logicalBytes()` は毎回コピーを返します。`count` は QR count field に書く値（byte mode は byte 数、その他の data mode は scalar 数）、`characterCount` は元の Unicode scalar 数（binary/control は 0）、`byteCount` は byte 診断（Kanji は scalar 数 × 2）です。`applicationIndicatorCodeword` は FNC1 second の符号値、それ以外は null。`isControl()` は制御 segment の判定、`dataBitLength()` / `totalBits(version)` / `bits(version)` は符号化量と bit 列です。公開 helper `fromText(mode, text)` は numeric/alphanumeric/byte/kanji のみを受け付けます。

テキストは孤立 surrogate を拒否し、正しい補助平面 pair は 1 scalar とします。Kanji の論理 byte/parity は元テキスト UTF-8、QR payload は 13-bit 値。JDK の charset provider に依存しません。ECI は解釈ラベルで、データ変換を実行しません。ECI option 使用時には auto Kanji 選択を抑えますが、明示 Kanji segment は使えます。

`Segments.create`、`normalize`、`bitLength`、`bits` は低水準 API。manual 境界は自動結合しません。FNC1 / FNC1 second / SA は先頭一意。FNC1 下の手動 alphanumeric `%` は区切り、`%%` は literal percent です。高水準 GS1 は literal percent を守るため auto を byte にし、強制 alphanumeric を拒否します。

`Segments.create(text, version, mode, optimize, allowKanji)` は指定 version の count-width で segment を作ります。binary overload では mode は auto/byte のみです。`normalize` は元リストから独立した immutable snapshot を返し、manual sequence 全体の payload units（text は Unicode scalar、binary は byte）を合計します。`Segment.totalBits` と `Segments.bitLength` は count-field/symbol 容量を超えても算術長を返します。一方 `bits` は各 count-field と **23,648 bits** の単一 symbol materialization 上限を先に検証します。直接 `Segments.create` を optimize=true/auto で呼ぶ場合の上限は **7,089 scalars** です。高水準計画の大入力 fallback とは別の契約です。

`Segments.OptimizationTracker(version, allowKanji)` は定数メモリの可変 prefix tracker です。`append(codePoint: Int)` または `append(character: String)` に正しい Unicode scalar をちょうど 1 個渡すと、その prefix の最小 bit 費用を `Long` で返します。最大 1,000,000 scalars まで追跡でき、複数 symbol にまたがる入力の費用計算にも使えます。返された費用は count-field や単一 symbol に収まる保証ではなく、segment 境界自体も返しません。

### Tables と Core

`Tables` と `Core` は Kotlin object、公開関数は Java から static にも呼べます。

- `Tables.size(version)`, `rawCodewords(version)`, `dataCodewords(version, level)`: Version 1–40 の辺・全 codeword 数・data codeword 数
- `Tables.blockInfo(version, level)`: immutable `BlockInfo`。blocks/eccPerBlock/rawCodewords/dataCodewords の property と同名 accessor
- `Tables.alignmentPositions(version)`: 毎回新しい alignment center `IntArray`
- `Tables.countBits(mode, version)`: numeric/alphanumeric/byte/kanji の count-field 幅
- `Core.padDataBits(bits, version, level)`: 0/1 の `IntArray` を terminator・alignment・交互 EC/11 byte で padding。選択した symbol の容量を入力走査より先に検証
- `Core.gfMultiply(left, right)`, `gfPow(base, exponent)`: GF(256)、既約多項式 0x11D。byte operand は 0–255、exponent は 0 以上
- `Core.reedSolomonDivisor(degree)`: 先頭 monic 1 を含む係数 `ByteArray`。degree は 1–255
- `Core.reedSolomonRemainder(data, degree)`: 最大 3,706 codewords の data から parity を計算
- `Core.interleaveCodewords(data, version, level)`: data 長はその version/level の dataCodewords と一致する必要あり
- `Core.buildMatrix(codewords, version, level, mask)`: 完全に interleave した codewords を配置。長さは rawCodewords と一致する必要あり。Kotlin では mask 引数なしの overload も提供
- `Core.maskCondition(mask, x, y)`: mask は 0–7、x/y はそれぞれ 0–176
- `Core.penaltyScore(matrix)`: 辺 1–177 の正方形について SpecQR の N1–N4 penalty を計算

matrix の配列順は `[row][column]` です。`InterleavedResult` は codewords/errorCorrectionCodewords/blocks/dataCodewords/errorCorrectionCodewordCount/totalCodewords、各 `CodewordBlock` は data/ecc を提供します。`MatrixResult` は matrix/maskPattern/penalty/maskPenalties、各 `MaskPenalty` は maskPattern/penalty を提供します。これらは property と同名 accessor を持ち、配列は毎回コピー、リストは immutable です。`InterleavedResult.dataCodewords` は byte 配列ではなく data codeword **数** です。

### QrCode と Render

`QrCode`: version, size, maskPattern, errorCorrectionLevel, matrix, dataCodewords, codewords, errorCorrectionCodewords, segments, diagnostics, options。`qr[x,y]` と `module(x,y)`。配列は毎回コピーです。`codewords` は配置順の interleaved 全体、末尾の `errorCorrectionCodewords` は interleaved parity 部分。`dataCodewords` は padding 後・interleave 前です。

`toSvg`, `toPng`, `toPixels`, `toSvgDataUrl`, `toPngDataUrl` は省略時に生成時の Options を使い、描画時 override も可能。`Render` の同名 static 関数は任意の square matrix を受け取ります。Pixels は width/height/pixels（RGBA byte array コピー）。PNG は filter 0 と stored DEFLATE、CRC-32、Adler-32 を直接構築します。

raster 色は hex 3/4/6/8 桁、black、white、transparent。SVG は XML escape した色文字列です。未知の CSS 色はコントラストを判定できません。DPI は診断用で PNG metadata は書きません。module margin 4 未満、透過、低コントラスト、過小 print module を警告します。

### エラー・予算・並行実行

`SpecQrException` は `IllegalArgumentException` の派生で `code` / `code()` を持ちます。通常は INVALID_INPUT、INVALID_VERSION、INVALID_MODE、INVALID_ECI、INVALID_GS1、INVALID_COLOR、INVALID_OUTPUT、DATA_TOO_LONG のいずれかです。Kotlin の非 null constructor 引数は言語の null 契約にも従います。

上限: 1,000,000 payload units、16,384 manual segments、低水準 bit 配列は単一 symbol に必要な範囲、raster 4,194,304 pixels（辺 2,048 以下）、SVG 8 MiB 文字、data URL 32 MiB。Unicode scalar count が 7,089 を超える高水準計画では単一 symbol に不要な混合最適化を避け算術単一 mode 計画へ切り替えます。詳細値・境界は `Segments.MAX_*` とテストに固定されています。

完成オブジェクトは immutable で共有できます。RS cache は同期で安全に初期化し、呼び出しへはコピーを返します。`Options.Builder` と低水準 streaming tracker は可変であり、同時書込み用ではありません。
