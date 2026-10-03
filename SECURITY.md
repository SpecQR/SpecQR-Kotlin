# Security

SpecQR generates QR symbols; it does not authenticate their payloads or make scanned URLs trustworthy.

QR の内容や Structured Append parity を認証に使わないでください。外部入力には実装上限とは別にアプリケーション側の長さ・用途制限を設けてください。SVG の色は XML escaping を行い、PNG の geometry と出力サイズを事前に制限します。Digital Link は範囲限定 GS1 adapter で、汎用 URL firewall ではありません。

セキュリティ報告は GitHub の private vulnerability reporting が利用できる場合に利用し、利用できなければ再現に不要な機密情報を含めず issue で連絡してください。秘密鍵・credential・実データを issue に投稿しないでください。

現時点で正式な長期サポート期間・外部認証は提供していません。独立テストは重要な検証ですが、完全性の証明ではありません。
