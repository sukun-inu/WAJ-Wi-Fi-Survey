# WAJ Wi-Fi Survey

TamoGraph Site Survey の考え方に着想を得た、Java (JavaFX) 製の Wi-Fi サイトサーベイ/監視ツールです。
Windows の Native Wifi API (`Wlanapi.dll`) を JNA で直接叩き、実機の Wi-Fi アダプタ (例: Intel(R) Wi-Fi 6 AX201) から
SSID / BSSID / チャネル / RSSI / リンク品質 / PHY種別 / セキュリティ種別をリアルタイムに取得します。
インフラエンジニアの日常業務(サイト調査・障害後検証・チャネル設計・セキュリティ監査・継続監視)を想定したフル装備版です。
UIは日本語/英語(設定画面から切り替え、再起動後に反映)に対応しています。

> 仕様更新日: 2026-07-08

## できること

- **Dashboard**: 周辺AP一覧(表示ON/OFF・信頼済み登録/解除)、5分間RSSI推移(全AP重ね描画)、疑似スペクトラム(帯域切替: 2.4/5/6GHz)、ウォーターフォール、推定ノイズフロアとSNR目盛り表示。AP一覧はCSV/JSON出力可能。
- **Site Survey**: フロアプラン画像を読み込み、計測モードでクリックした地点のWi-Fiスナップショット(+任意でPing応答時間)を記録。IDW補間ヒートマップを描画し、対象APは「最強AP自動選択」またはBSSID固定で切替可能。プロジェクト保存/読込、地点CSV/JSON出力、HTML/PDFレポート生成に対応。
- **Security Audit**: 802.11 情報要素(RSN/WPA IE)を解析し、Open/WEP/WPA/WPA2/WPA2-WPA3(混在)/WPA3を判定。開放/弱暗号APを色分け表示し、検出件数サマリを提示。
- **Channel Planning**: チャネル混雑度スコア(RSSI + 取得可能な場合はBSS Load利用率を併用)を帯域別に可視化。2.4GHz/6GHzは単一パネル、5GHzは日本の公式サブバンド(J52/J53/J56)で分割表示。推奨チャネルを算出してハイライト。
- **Alerts**: 4種類のルール(RSSI低下、未信頼AP出現、新規SSID、チャネル混雑度上昇)をルールベースで検知。警告/重大アラートはWindows通知でも通知し、しきい値・ルールON/OFF・通知ON/OFF・信頼済みAP・言語を設定ダイアログから管理可能。
- **History**: SQLite長期ログを期間プリセット(15分/1時間/24時間/7日間)とBSSIDで検索。複数BSSIDをチェックしてRSSI推移を重ね書きし、ホバーで系列値をランキング表示。表示中行をCSV出力可能(検索結果は最大5000行)。
- **Traceroute (経路診断)**: Windows `tracert -d` で経路を探索後、全ホップへ約1秒間隔で継続ping。RTT推移チャート(直近5分)と、ホップ別統計(現在/最小/平均/最大RTT・損失率)を表示。

## 設定・保存仕様

アプリ初回起動時に `~/.waj-wifi-survey/` 配下へ以下を作成します。

- `settings.json`: 永続設定
- `scan-log.db`: 長期スキャンログ(SQLite)

`settings.json` の主要項目(既定値):

| Key | Default | 説明 |
| --- | --- | --- |
| `rssiThresholdDbm` | `-75` | RSSIしきい値アラートの閾値(dBm) |
| `channelCongestionThreshold` | `50.0` | チャネル混雑度アラート閾値 |
| `rssiAlertEnabled` | `true` | RSSIしきい値アラートON/OFF |
| `rogueApAlertEnabled` | `true` | 未信頼AP出現アラートON/OFF |
| `newSsidAlertEnabled` | `true` | 新規SSIDアラートON/OFF |
| `channelCongestionAlertEnabled` | `true` | 混雑度アラートON/OFF |
| `windowsNotificationsEnabled` | `true` | Windows通知ON/OFF |
| `defaultPingHost` | `""` | Site Surveyで使う既定Ping先 |
| `language` | `"ja"` | UI言語(`ja`/`en`) |
| `scanLogIntervalMillis` | `2000` | 長期ログ書き込み間隔(ms) |

`scan-log.db` の主要テーブル:

- `scan_samples` (Historyタブが参照する長期スキャンログ)
- `survey_points_log` (将来拡張向けに確保済み)
- `alert_log` (将来拡張向けに確保済み)

## 技術的な制約(重要)

- **真のRFスペクトラムアナライザではありません。** 通常のクライアントNIC(AX201含む)はWindows経由で生RF波形を提供しないため、専用ハードウェア(Wi-Spy等)なしに実測スペクトラムは取得できません。Dashboardの「疑似スペクトラム表示」、Channel Planningの「混雑度スコア」はいずれもRSSIから合成・推定した表示です。
- **ノイズフロアは推定値です。** `WLAN_BSS_ENTRY` にノイズ値のフィールドは存在しないため、固定の推定値(既定 -95dBm)を使用しています。
- **セキュリティ種別判定はヒューリスティックです。** RSN/WPA IEの有無とAKMスイート種別から判定していますが、802.11の全IEを厳密にパースするものではありません。
- **なりすまし/野良AP検知は「信頼済みAP」として明示登録したSSIDのみ対象です。** 何も登録していない状態では発火しません。
- **Windows の位置情報アクセス許可が必要な場合があります。** Windows 11 (build 25977+) では `WlanScan` / `WlanGetNetworkBssList` はユーザーが位置情報アクセスを許可していないと `ERROR_ACCESS_DENIED` を返します。拒否された場合はアプリ上部に赤いバナーが表示され、「Windowsの位置情報設定を開く」ボタンから設定画面 (設定 > プライバシーとセキュリティ > 位置情報) を開けます。
- **Windows専用です。** `Wlanapi.dll` および `ping.exe` を直接呼び出すため、他OSでは動作しません。
- **Wi-Fi接続中は電波再スキャンの間隔を自動的に延長します。** アダプタが実際にSSIDへ接続している間に`WlanScan`を頻繁に発行すると、通信中のスループットに影響するため、接続中は再スキャン間隔を約15秒まで自動的に間引きます(表示自体のキャッシュ更新は従来通り)。未接続時は最短2秒間隔のままです。
- **表示言語は再起動後に反映されます。** 設定画面で日本語/英語を切り替えても、既に構築済みのUI文字列はその場では再描画されません(次回起動時に反映)。

## 必要環境

- Windows 10/11 + Wi-Fi アダプタ
- ビルド/実行に外部インストールは不要です。JDK 21 と Maven をプロジェクトローカルの `.tools/` 配下に同梱しています。

## ビルド・実行

```
.\mvnw.cmd javafx:run
```

## テスト

```
.\mvnw.cmd test
```

## 配布用exeビルド

外部インストール不要(同梱の `.tools/jdk21` の `jpackage` を使用)で、単体で実行できるWindowsアプリイメージ(exe)を `dist/<version>/` 配下に生成します。

```
.\build-release.ps1
```

- Maven本ビルド(テスト込み)→ 依存込みfat jar化(`maven-shade-plugin`)→ `jpackage --type app-image` の順で実行します。
- 生成物: `dist/<version>/WAJ WiFi Survey/WAJ WiFi Survey.exe`(Java実行環境同梱、そのまま配布可能)、および同ディレクトリに配布用zipも作成されます。
- `dist/` はバージョンごとにサブディレクトリを分けるビルド専用の配布物置き場です(gitでは追跡しません)。再ビルドすると対象バージョンのフォルダのみ作り直されます。
- テストをスキップしたい場合: `.\build-release.ps1 -SkipTests`
- インストーラ形式(`.msi`/単一exeインストーラ)が必要な場合は [WiX Toolset](https://wixtoolset.org/) を別途インストールした上で、スクリプト内の `--type app-image` を `--type msi` 等に変更してください(現状はWiX不要なapp-image形式のみ対応)。

主なテスト対象:

- WLAN/セキュリティ: `Dot11SsidTest`, `SecurityClassifierTest`
- チャネル設計: `ChannelPlannerTest`, `BssLoadParserTest`, `ChannelUtilTest`
- アラート: `AlertEngineTest`
- Ping/Traceroute: `PingProbeTest`, `TracerouteProbeTest`, `HopRttHistoryTest`
- Site Survey/レポート: `IdwInterpolatorTest`, `SurveyProjectStoreTest`, `PdfReportGeneratorTest`
- ダッシュボード/履歴補助: `RssiHistoryStoreTest`, `CategoricalColorPaletteTest`, `CsvUtilTest`
- 設定/国際化: `AppConfigStoreTest`, `MessagesTest`

## プロジェクト構成

```
com.waj.tool
├── App.java                 JavaFXエントリポイント + MenuBar
├── i18n/                    メッセージバンドル切替
├── wlan/                    Wlanapi.dll のJNAバインディングとポーリング
├── model/                   AP/スキャン/サーベイ点のデータモデル
├── util/                    チャネル変換・色スケール・ノイズ推定
├── security/                IE解析によるセキュリティ種別判定 + Security Audit UI
├── channel/                 チャネル混雑度スコア + Channel Planning UI
├── alert/                   アラートルール・エンジン・信頼済みAP管理・設定ダイアログ・Windows通知
├── ping/                    ping/tracert 実行・パース
├── report/                  HTML/PDFサイトサーベイレポート生成
├── ui/dashboard/            ライブダッシュボードUI
├── ui/survey/               サイトサーベイ・ヒートマップUI
├── ui/history/              長期ログ閲覧UI
├── ui/ping/                 Traceroute監視UI
└── persistence/             設定・長期ログ(SQLite)・サーベイプロジェクト・CSV/JSON出力
```

## 既知の制約 / 今後の拡張候補

- Historyタブの検索結果は最大5000件までです(大量データ時は直近側へ切り詰め表示)。
- Historyタブの期間指定はプリセット(15分/1時間/24時間/7日間)のみで、任意の日付範囲指定はできません。
- Site Surveyプロジェクトはフロアプランの絶対パスを保持するため、画像ファイルを移動した場合は再指定が必要です。
- `jpackage` によるexe化(`.\build-release.ps1`)に対応済みです。現状は WiX Toolset 不要な app-image 形式のみで、`.msi` 等の単体インストーラ生成は未対応です。
