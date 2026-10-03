# Contributing

Contributions are welcome. Runtime code must remain Kotlin with no dependency beyond Kotlin stdlib and `java.base`.

変更前後に `KOTLIN_HOME` を指定して `java tools/Build.java test` と `java tools/VerifyPackage.java` を実行してください。独立検証は `tools/conformance/README.md`、GS1 は `tools/gs1-conformance/README.md` に記載しています。

- 実装変更には境界・負例・所有権/immutable テストを追加する
- matrix だけでなく data/ECC codeword、decoder、source/JAR fingerprint を確認する
- Kotlin compiler、JDK、OS の実検証結果だけを主張する
- 第三者 QR source を runtime にコピーしない
- 出力互換性や resource budget の変更を文書化する
- テスト用ライブラリや compiler を JAR に含めない
- 公開 API の Kotlin と Java 呼出しをともに確認する

issues/PR は英語・日本語どちらでも構いません。安定版 tag、Maven Central、他 repo の変更は別の承認が必要です。
