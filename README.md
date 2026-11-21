# Pitchline for Android

Android 上で音声を読み込み、歌声の基音を解析してノートやピッチカーブを編集する日本語アプリです。音声処理は端末内で完結します。分離モデルの初回取得だけインターネット接続が必要です。

## APK の直接ダウンロード

[Pitchline v0.1.1-debug.1 ARM64 APK（10.12 MB）をダウンロード](https://github.com/tstyr/pitchline-android/releases/download/v0.1.1-debug.1/Pitchline-v0.1.1-debug.1-arm64-debug.apk) · [SHA-256](https://github.com/tstyr/pitchline-android/releases/download/v0.1.1-debug.1/Pitchline-v0.1.1-debug.1-arm64-debug.apk.sha256) · [Release](https://github.com/tstyr/pitchline-android/releases/tag/v0.1.1-debug.1)

スマホのブラウザで APK のリンクを開いてください。チャット内のファイルプレビューを経由せず取得できます。配布用署名鍵は未設定のため、これは **テスト用 Debug 署名のプレリリース**です。R8 の最適化を有効にするため、アプリの `debuggable` は false にしています。Galaxy S26 実機での動作は未確認です。

## 使い方

### 長時間音声のトリミング

ホームの「オーディオカッター · 長時間音声を切り出す」から元ファイルを選びます。カッターでは全曲のコピー・PCM 展開・F0 解析をせず、長さを確認して指定区間へ直接移動します。

1. 全体の範囲スライダーで大まかに選ぶか、開始・終了に `02:15:30.500` のような時刻を入力します。秒だけの入力（`8130.5`）も使えます。「適用」で反映し、試聴・保存時にも入力中の両時刻を検証して反映します。
2. 「開始を調整」「終了を調整」を選び、「拡大＋」で境界付近へ寄ります。表示幅が 30 秒以内なら、その窓だけの波形を取得します。「10 ms／1秒／10秒／1分」と「戻す／進める」で微調整できます。
3. 「開始から試聴」「終了前5秒を試聴」で確認し、再生位置を開始・終了に設定することもできます。試聴のシーク精度は端末のコーデックに依存します。
4. WAV・FLAC・M4A を選び「選択した区間を保存」で別ファイルへ書き出します。指定区間だけをデコードし、固定長バッファで再エンコードします。元ファイルは上書きしません。音程編集後のプロジェクトではなく、選択した元音声を切り出す機能です。

何時間の位置も `Long` の時刻とファイルオフセットで扱います。書き出しには選択区間の PCM と出力ファイル用の空き容量が必要です。不足時は開始前に通知します。WAV は RIFF の 4 GB 上限があり、圧縮音声も再エンコードします（無劣化のストリームコピーではありません）。書き出し中は画面を開いたままにしてください。「中止」で一時ファイルを削除し、保存先の途中の出力も削除を試みます。クラウド上など、プロバイダーがシークに対応しないファイルでは取得に時間がかかったり開けない場合があります。

### ピッチ編集

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

Release の APK リンクは `https://github.com/tstyr/pitchline-android/releases/download/<タグ>/<APK名>` です。チャット内プレビューを開かず、スマホのブラウザでこのリンクから APK をダウンロードしてください。ブラウザの「不明なアプリのインストール」を許可して開きます。配布用署名鍵が未設定の Debug 版は署名鍵を固定管理していないため、次の版へデータを維持して更新できることは保証しません。アンインストールは保存済みプロジェクトを削除します。

## 対応範囲

- モノラルとステレオの音声に対応します。エンコード・再生の対応レートは端末の Android コーデックと出力デバイスにも依存します。WAV 書き出しは RIFF の 4 GB 制限があります。
- F0 編集は単旋律向けです。重なった歌声を個別ノートに分割する機能と、MP3 の書き出しは未実装です。分離前のミックスを編集すると、優勢な音高成分を含むトラックに処理がかかります。
- 長いトラックでは最初の編集済みプレビューの生成に時間がかかります。分離品質は音源に依存します。GPU／NPU 推論は使用しません。
- Galaxy S26 実機での音質、発熱、Bluetooth レイテンシおよび長時間の連続動作は未検証です。

アプリは MIT ライセンスで提供されます。依存関係とモデルのライセンスは [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) を参照してください。

## v0.1.1-debug.1 の実測と検証（2026-11-21）

元の ZIP を取り込んだコミット `cdfca31` の ARM64 Debug APK と、タグ `v0.1.1-debug.1` の Actions から公開した APK を比較しています。ZIP の保存形式とバイト数は `scripts/apk-report.py` で実測しました。

| 対象 | 変更前 | 公開 APK |
| --- | ---: | ---: |
| APK 全体 | 45,058,563 bytes（42.97 MiB） | 10,120,803 bytes（9.65 MiB） |
| ネイティブライブラリの ZIP 内サイズ | 19,723,312 bytes（非圧縮） | 7,100,649 bytes（圧縮） |
| DEX の ZIP 内サイズ | 24,718,500 bytes（非圧縮） | 2,764,043 bytes（R8 後に圧縮） |

APK は **77.54% 削減**しました。公開ファイルを認証なしで直接ダウンロードし、SHA-256 `00757331778f49b3ad2d8eb914c94e0d56b61a76f05448bb4d91f9a5c52bfd79` と署名の検証に成功しています。ARM64 のみを含み、全ネイティブライブラリの ELF LOAD セグメントは 16 KiB アラインメント検査を通過しています。

- Gradle: Debug／Compact の単体テストは各 5 件成功。両ビルドの Lint はエラー 0 件（警告 17 件、ヒント 1 件）。
- `scripts/test-dsp.sh`: YIN の F0 検出、TD-PSOLA の移調・フォルマント・ビブラート、STFT／ISTFT、リサンプルの検証に成功。
- Android 15（ローカル x86_64）と Android 17（Actions、API 37.0、x86_64）で、縮小済み APK の全 4 件の動作テストに成功。WAV・MP3・FLAC・M4A・OGG の読み込み、プロジェクト復元、編集済み音声の WAV・FLAC・M4A 書き出しと再読み込み、エディター描画・半音編集・Undo・ホーム復帰を確認。
- 両 Android 環境で、通常のモデル初回取得と SHA-256 検証を経て Open-Unmix／HQ の CPU 分離が完走。検証音源は 48 kHz・ステレオ・約 1.2 秒で、有限の出力とステムのサンプル整列を確認。
- ローカルでインストールした複数 ABI 版とローカル ARM64 版の DEX および ARM64 ライブラリが同一であることも比較確認。公開 APK の DEX もローカルで動作確認した DEX と一致。

[タグビルドの検証・公開ログ](https://github.com/tstyr/pitchline-android/actions/runs/44891300128)（2 回目の実行ですべて成功）。初回はモデル配布元の HTTP 502 によって分離テストが失敗し、同じタグの再実行で成功しました。

**未確認:** 公開した ARM64 APK の Galaxy S26 実機へのインストール・ARM64 上での実行、長時間音源の処理、実機での音質・発熱・Bluetooth レイテンシ。端末が接続されていないため、実機検証を完了したとはしていません。
