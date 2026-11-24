# 走り書きメモ Android版

PWA版（このリポジトリの `index.html`）をそのまま表示する WebView アプリです。
手書き入力などの本体機能はPWA版と同一で、ビルド時にリポジトリ直下の
`index.html` / `about.html` / `sw.js` をアプリ資産として同梱します
（`.github/workflows/android-build.yml` の「Web資産」ステップ）。
そのため、PWA版を更新してpushすれば、Android版も次回ビルドで同じ内容になります。

## 音声入力
- WebView内ではブラウザの音声認識（Web Speech API）が使えないため、
  🎤ボタンはAndroid標準の `SpeechRecognizer` に橋渡ししています
  （`window.HandwritingAndroid.startSpeech()` → 結果は
  `window.__onNativeSpeechResult(text)` でWeb側へ返ります）。
- 言語は設定→音声入力タブで指定でき（既定は日本語 ja-JP）、OS標準の認識サービス（通常はGoogleの音声入力）を使います。言語タグはハイフン形式（ja-JP）で渡します（ja_JP形式は認識エンジンに無視されローマ字書き取りになる場合があります）。
  APIキーやLLMは使いません。

## ビルド
- GitHub Actions の「Android APK」ワークフローが `android/**` や
  `index.html` 等のpushで自動実行され、成果物 `handwriting-debug-apk` に
  デバッグAPKが上がります。手動実行（workflow_dispatch）も可能です。
- 署名は `debug.keystore.b64` の固定デバッグ鍵（同期アラームと同じ鍵）を使うため、
  再ビルドしたAPKでそのまま上書きインストールできます。

## 既知の制限（v1.0）
- 画像の共有・保存（Web Share / ダウンロード）はWebViewでは一部動作が
  PWA版と異なる場合があります。
- データ（メモ・設定）はアプリ内のWebViewストレージに保存され、
  PWA版（ブラウザ）とは共有されません。
