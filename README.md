# Pitchline for Android

Android 上で音声を読み込み、歌声の基音を解析してノートやピッチカーブを編集する日本語アプリです。音声処理は端末内で完結します。分離モデルの初回取得だけインターネット接続が必要です。

## 使い方

1. ホームの「音声ファイルを開く」から MP3、WAV、FLAC、M4A／AAC、OGG を選択します。音声をアプリ内にコピーして新規プロジェクトを作ります。
2. 楽曲から歌声だけを編集する場合は、エディター右上のソースメニューから「ボーカル分離」を開きます。標準は Open-Unmix、高品質は Open-Unmix HQ です。モデルはそれぞれ約 34 MiB で、公式配布元から取得した重みを SHA-256 で検証します。
3. ピアノロールでノートをタップして選択し、上下にドラッグすると半音単位で移調できます。「複数」「範囲」「描画」を切り替えて複数選択や手描きカーブ編集を行います。ピンチで拡大縮小し、ダブルタップでノートにフォーカスします。
4. 編集パネルで中心音程、ドリフト、モジュレーション、平坦化、トランジション、ビブラート、フォルマントを調整します。「キー」からスケールの設定と自動補正ができます。「ノート操作」には分割、結合、編集設定のコピー・貼り付け、リセットがあります。
5. 再生バーでシーク、範囲ループ、Undo／Redo、原音と編集後の A/B 比較を行えます。分離済みのソースではボーカルと伴奏の同期再生、ソロ再生、個別音量調整ができます。
6. 「書き出し」で WAV 24-bit、FLAC 16-bit、M4A／AAC のいずれかを選びます。分離済みプロジェクトでは編集済みボーカル、未編集ボーカル、伴奏、ミックスを選べます。

プロジェクトはアプリ内の `project.pitchline` と PCM・解析キャッシュに自動保存されます。元ファイルへの参照、元の F0、目標カーブを復元する編集値と描画点、ミックス、最大 24 回分の Undo／Redo を保持します。元ファイルは上書きしません。

## 音声処理

- Android の Storage Access Framework、MediaExtractor、MediaCodec と、RIFF WAV 用の PCM 読み込みを使います。音声はディスク上の float32 PCM を区間単位で処理します。
- F0 は C++ の YIN 系検出器で約 12 ms ごとに分析します。信頼度を使用して低品質な区間を除外し、有声音区間とピッチ跳躍からノートを切り出します。長い単一ノートでは、周期性が確認できた元ビブラートの速さと深さを表示します。
- 目標 F0 とフォルマント指定を C++ の TD-PSOLA に渡して編集結果を再合成します。有声音のパルスを相関で追跡し、窓付き信号の重ね合わせで時間長を維持します。音程補正の既定値はグレイン内の声質を保持し、フォルマント調整はグレインの局所的なリサンプリングで行います。
- 2 種類の Open-Unmix を ONNX Runtime の CPU で実行します。Hann 窓の STFT、ソフトマスク、重複区間のクロスフェードを使用します。伴奏は元の音声から分離ボーカルを差し引いて作成するため、両ステムはサンプル数と位置が揃います。モデル入力に 44.1 kHz が必要な場合は窓付き sinc でリサンプルし、ステムを元のサンプルレートへ戻します。
- 分離は通知付きフォアグラウンドサービスで実行し、開始後は画面を閉じても継続できます。キャンセルまたは処理失敗時は完了済みプロジェクトを保持します。

## ビルド・テスト

JDK 17、Android SDK 36、Build Tools 35.0.0、NDK 28.2.13676358、CMake 3.22.1 を使用します。対象 ABI は ARM64、最小 SDK は 28、ターゲット SDK は 36 です。

```sh
sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
scripts/test-dsp.sh
```

Debug APK は `app/build/outputs/apk/debug/app-debug.apk` に生成されます。x86_64 の Android エミュレーターで音声入出力、保存・復元、分離、Compose 操作をテストするには、起動済みエミュレーターに対して次を実行します。

```sh
./gradlew -PpitchlineEmulator -PpitchlineTestBuildType=compact :app:assembleCompact :app:assembleCompactAndroidTest
scripts/test-device.sh
```

ONNX グラフを再生成して PyTorch 版との一致を確認する場合は、Python に PyTorch 2.7.1 CPU、ONNX 1.18.0、ONNX Runtime 1.23.2 と NumPy を用意し、`python scripts/models.py --workspace /tmp/pitchline-models` を実行します。

## GitHub Actions と配布

通常の push／PR では DSP・Gradle のテスト、Lint、軽量化した ARM64 APK の生成と Artifacts へのアップロードを行います。エミュレーターでは R8 適用済みの `compact` ビルドで 5 形式の読み込み、プロジェクト復元、3 形式の書き出しと再読み込み、ノート編集と Undo、Open-Unmix 2 種の CPU 分離を確認します。モデルはアプリの通常の取得経路でダウンロードと SHA-256 検証を行い、分離テストを省略しません。

配布ビルドは ARM64 のみ、ネイティブライブラリと DEX を ZIP 圧縮し、R8 とリソース縮小を適用します。ONNX Runtime 全体の JNI API と `NativeDsp` の名前を keep ルールで保護しています。縮小済み APK 自体を計測・検証するため、音声処理と Compose テストが使う公開 API の境界も保持します。共通ライブラリではテスト側の派生クラスとのバイナリ互換性を保ちます。CI の動作テストは Android 17（API 37.0、x86_64）で行い、ARM64 APK の全ネイティブライブラリでは 16 KiB ELF アラインメントも検査します。ライブラリはインストール時に展開されるため、ダウンロードサイズとインストール後の使用容量は異なります。モデル重みは APK に含めず、既存どおり初回取得と SHA-256 検証を行います。

```sh
./gradlew :app:assembleCompact :app:testCompactUnitTest :app:lintCompact
python3 scripts/apk-report.py app/build/outputs/apk/compact/app-compact.apk --compact --arm64
```

`compact` は Release と同じ縮小設定を使用するテスト用 Debug 署名ビルドです。`release` は配布用署名鍵が必要です。タグ `vX.Y.Z` の push ではビルドとエミュレーターテストの両方が成功した後、次の Actions Secrets が揃っていれば配布用に署名した APK と SHA-256 ファイルを GitHub Releases に追加します。

- `PITCHLINE_KEYSTORE_BASE64`
- `PITCHLINE_STORE_PASSWORD`
- `PITCHLINE_KEY_ALIAS`
- `PITCHLINE_KEY_PASSWORD`

署名設定がない場合は、Debug と明記されたプレリリースを作成します。秘密鍵はリポジトリに含めません。

公開先: https://github.com/tstyr/pitchline-android/releases

Release の APK リンクは `https://github.com/tstyr/pitchline-android/releases/download/<タグ>/<APK名>` です。チャット内プレビューを開かず、スマホのブラウザでこのリンクから APK をダウンロードしてください。ブラウザの「不明なアプリのインストール」を許可して開きます。配布用署名鍵が未設定の Debug 版は Actions 実行ごとに署名が変わるため、次の版へデータを維持して更新できることは保証しません。アンインストールは保存済みプロジェクトを削除します。

## 対応範囲

- モノラルとステレオの音声に対応します。エンコード・再生の対応レートは端末の Android コーデックと出力デバイスにも依存します。WAV 書き出しは RIFF の 4 GB 制限があります。
- F0 編集は単旋律向けです。重なった歌声を個別ノートに分割する機能と、MP3 の書き出しは未実装です。分離前のミックスを編集すると、優勢な音高成分を含むトラックに処理がかかります。
- 長いトラックでは最初の編集済みプレビューの生成に時間がかかります。分離品質は音源に依存します。GPU／NPU 推論は使用しません。
- Galaxy S26 実機での音質、発熱、Bluetooth レイテンシおよび長時間の連続動作は未検証です。

アプリは MIT ライセンスで提供されます。依存関係とモデルのライセンスは [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) を参照してください。
