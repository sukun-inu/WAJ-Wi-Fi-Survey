# OpenSiteSurvey

Java (JavaFX) 製の Wi-Fi サイトサーベイ/監視ツールです。
Windows の Native Wifi API (`Wlanapi.dll`) を JNA で直接叩き、実機の Wi-Fi アダプタから
SSID / BSSID / チャネル / RSSI / リンク品質 / PHY種別 / セキュリティ種別をリアルタイムに取得します。
インフラエンジニアの日常業務(サイト調査・障害後検証・チャネル設計・セキュリティ監査・継続監視)を想定したフル装備版です。
UIは日本語/英語(設定画面から切り替え、再起動後に反映)に対応しています。

> 仕様更新日: 2026-07-12(UI/UX全面刷新 — エンタープライズ ネットワーク管理コンソール風シェル版)

## できること

- **UI/UXシェル**: エンタープライズ向けネットワーク管理コンソールを意識した画面構成に刷新しました。常時表示の左ナビゲーションレール(選択中の画面をアイコン+太字+アクセントバーで明示)、各画面共通の「アイコン+タイトル+説明+ライブ状態チップ」ページヘッダー、リンク状態LED・セッション内アラート累計件数・REST API/プラグイン状態を表示する下部シャーシステータスバーで構成されています。`Ctrl+1`〜`Ctrl+7`で各画面に直接切り替え可能です。配色はWCAG AA(コントラスト比4.5:1以上)を目安に調整済みです。
- **Dashboard**: 周辺AP一覧(表示ON/OFF・信頼済み登録/解除・MACベンダー表示・Wi-Fi7/MLO対応表示)、5分間RSSI推移(全AP重ね描画)、疑似スペクトラム(帯域切替: 2.4/5/6GHz)、ウォーターフォール、推定ノイズフロアとSNR目盛り表示。疑似スペクトラムはチェックボックスでChannel Planningの混雑度スコア(帯域内の全候補チャネル分、推奨チャネルは緑でハイライト)を重ねて表示可能です。AP一覧はCSV/JSON出力可能。
- **Wi-Fi 7 (802.11be) / MLO検出**: ビーコン/プローブ応答の生IEからEHT Capabilities/EHT Operation要素とMulti-Link要素の有無を直接判定するため、ドライバのPHY種別表示がまだ802.11be対応を報告していないAPでも検出できる場合があります。Dashboard一覧の「Wi-Fi7/MLO」列、およびCSV/JSON出力の`eht_capable`/`mlo_capable`(`ehtCapable`/`mloCapable`)フィールドで確認できます。
- **グラフのカーソル固定/スクロール**: Dashboard(RSSI推移・疑似スペクトラム)、History、Channel Planningの各グラフはクリックでカーソル位置(またはChannel Planningの場合は山の内訳)を固定できます。固定中はマウスをどこに動かしても表示が変わらないため、サイドパネルへマウスを移動してスクロールしながら値を確認できます。もう一度クリックすると解除され、通常のホバー追従に戻ります。値一覧パネルは長い項目でも省略記号で消えず、必要に応じて横スクロールで全文を確認できます。
- **Site Survey**: フロアプラン画像を読み込み、計測モードでクリックした地点のWi-Fiスナップショット(+任意でPing応答時間、+任意でダウンロードスループット計測)を記録。補間アルゴリズムはIDW・Ordinary Kriging(球形バリオグラム)・Natural Neighbor(離散サンプリングによるSibson近似)から選択可能で、対象APは「最強AP自動選択」またはBSSID固定で切替可能。フロアプラン画像はプロジェクトファイルに埋め込まれるため、画像を移動/リネームしてもプロジェクトの再読込だけで復元できます。RSSIしきい値を下回るエリアを斜線でハイライトする「カバレッジホール表示」、別プロジェクトを比較対象として読み込みBefore/Afterの差分ヒートマップ(改善=緑/悪化=赤)を表示する「比較モード」に対応。「推定AP位置を表示」チェックボックスで、記録済み地点のRSSIを重みとした加重重心によるAP位置の簡易推定(オレンジのダイヤモンドマーカー)を表示可能(三辺測量ではないため目安として利用)。「AP配置を提案」ボタンで、現在のヒートマップ(対象AP・補間方式・しきい値)を基にカバレッジホールを減らせそうな新規AP設置候補地点を最大3件、貪欲法(greedy)で探索して水色の+マーカーで表示(学習済みAIモデルではなく、一般的な屋内伝搬モデルによる概算です)。「用途別カバレッジ判定」ボタンで、現在のヒートマップを音声(-67dBm以上)・映像(-70dBm以上)・データ(-80dBm以上)という3つの用途別しきい値と照合し充足率を表示。「ローミング解析を表示」チェックボックスで、同一SSIDの2台以上のAPが近いRSSI(ギャップしきい値以内)で競合している「ローミング境界」の可能性がある地点を黄色い三角マーカーで表示(端末依存のため目安)。「スループットテストURL」欄にURLを設定すると、地点記録時にHTTP GETによる概算ダウンロードスループット(最大3秒間、iperf3等の専用プロトコルではありません)も記録します。プロジェクト保存/読込、地点CSV/JSON出力、GeoPackage(QGIS等のGISツールで開ける.gpkg形式、AP位置推定レイヤも含む)出力、HTML/PDFレポート生成にも対応。
- **GPS/Wi-Fi歩行ヒートマップ(屋内/屋外両対応)**: 「測位方式」をGPSまたはWi-Fiから選び、「自動記録」トグルを有効にすると、歩きながらの現在地を自動的にSite Surveyの計測地点として記録できます。
  - **GPS方式**: 背景画像(屋内はフロアプラン、屋外はユーザーが用意した地図/航空写真のスクリーンショット等)上で2〜3点をクリックし、その実世界の緯度経度(手入力、または現在地のGPS取得値)とペアにして「GPSキャリブレーション」を行う必要があります。2点キャリブレーションでは相似変換(拡大縮小・回転・平行移動)、3点以上では最小二乗によるアフィン変換を使い、キャリブレーション点間の実距離(Haversine大円距離)から画像座標→メートル換算も算出します。GPS位置は「最小記録間隔(m)」未満の移動、および「GPS精度しきい値(m)」より粗い測位は自動的に間引かれます。屋外向き、屋内ではGPSの電波が届かず測位できない場合があります。
  - **Wi-Fi方式**: キャリブレーション不要です。既に記録済みの地点から「推定AP位置を表示」と同じ仕組み(RSSI加重重心)で各APのおおよその位置を推定し、現在見えているAPのRSSIから現在地を逆算します(GPSの三辺測量ならぬ「Wi-Fi版三辺測量」の簡易ヒューリスティックです)。GPSが届きにくい屋内向けですが、事前に数地点を手動記録(またはGPSで記録)しておく必要があります。ステータス表示に使用AP数が出るため、推定の信頼度の目安になります。
  - いずれの方式でも「最小記録間隔(m)」未満の移動は間引かれ、同じ場所に立ち止まっている間の過剰記録を防ぎます。記録後の処理(ヒートマップ描画・補間・エクスポート・レポート生成)は既存のSite Surveyパイプラインをそのまま共有します。
- **Site Surveyツールバーの整理**: 機能追加を重ねてボタンが横並びで肥大化していたツールバーを、「計測・保存」「解析・表示」「GPS/Wi-Fi測位」「エクスポート」の4つの折りたたみ可能なセクションに整理しました。普段使う「計測・保存」のみ初期状態で展開され、他は必要な時にクリックして展開できます。各セクション内はウィンドウ幅に応じて自動折り返しされるため、ボタン文字列が省略されることもありません。
- **Security Audit**: 802.11 情報要素(RSN/WPA IE)を解析し、Open/WEP/WPA/WPA2/WPA2-WPA3(混在)/WPA3を判定。開放/弱暗号APを色分け表示し、検出件数サマリを提示。MACアドレスのOUIからベンダー名を表示するため、既知SSIDのAPが予期しないベンダーに変わった場合の見分けにも使えます。
- **Channel Planning**: チャネル混雑度スコア(RSSI + 取得可能な場合はBSS Load利用率を併用)を帯域別に可視化。2.4GHz/6GHzは単一パネル、5GHzは日本の公式サブバンド(J52/J53/J56)で分割表示。HT40/VHT80/VHT160の実チャネル幅をビーコンIEから検出し、幅の広いAPほど広い範囲を占有しているものとしてスコアに反映します(6GHzは現状20MHz固定扱い)。推奨チャネルを算出してハイライト。
- **Alerts**: 4種類のルール(RSSI低下、未信頼AP出現、新規SSID、チャネル混雑度上昇)をルールベースで検知。警告/重大アラートはWindows通知でも通知し、しきい値・ルールON/OFF・通知ON/OFF・信頼済みAP・言語を設定ダイアログから管理可能。
- **History**: SQLite長期ログをプリセット期間(15分/1時間/24時間/7日間)または任意の日付範囲で検索。複数BSSIDをチェックしてRSSI推移を重ね書きし、ホバーで系列値をランキング表示。表示中行のCSV出力(検索結果は最大5000行に切り詰め)に加え、件数上限なしで検索範囲の全行をCSV出力する機能もあります。
- **Traceroute (経路診断)**: Windows `tracert -d` で経路を探索後、全ホップへ約1秒間隔で継続ping。RTT推移チャート(直近5分)と、ホップ別統計(現在/最小/平均/最大RTT・損失率)を表示。
- **ヘッドレス/CLIモード**: `--headless` 引数で起動すると、JavaFX UIを一切表示せずコンソールのみでスキャンループを実行します。GUIタブと同じSQLite長期ログに書き込むため、モニタのないサーバやリモートホストでの常時監視・ログ収集用途に使えます。
- **REST API(任意/既定OFF)**: 設定画面から有効化すると、`http://127.0.0.1:<ポート>/`(既定8787、ループバックのみ・認証なし)で最新のWi-Fiスキャン結果とSite Surveyデータを読み取り専用JSONとして取得できます。エンドポイントは `/api/v1/status`・`/api/v1/aps`・`/api/v1/survey/points`・`/api/v1/survey/ap-estimates` の4つ。外部ツールからの自動連携を想定した最小構成で、有効/無効・ポート番号は設定画面から変更可能(いずれも再起動後に反映)。
- **Plugin API**: `~/.opensitesurvey/plugins/` に `OpenSiteSurveyPlugin` インターフェース(`com.opensitesurvey.tool.plugin`)を実装した`.jar`を配置すると、次回起動時に`ServiceLoader`経由で自動読み込みされ、スキャン毎に`onScanSnapshot(ScanSnapshot)`が呼び出されます。現状は読み取り専用の通知のみ(UI・アラート・エクスポート形式への介入は不可)の最小構成です。読み込み済みプラグイン一覧は ヘルプ > 読み込み済みプラグイン から確認できます。詳細は本READMEの「プラグイン開発」節を参照してください。

## 設定・保存仕様

アプリ初回起動時に `~/.opensitesurvey/` 配下へ以下を作成します。

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
| `restApiEnabled` | `false` | REST API有効/無効(ループバックのみ) |
| `restApiPort` | `8787` | REST APIの待受ポート番号 |

`scan-log.db` の主要テーブル:

- `scan_samples` (Historyタブが参照する長期スキャンログ)
- `survey_points_log` (将来拡張向けに確保済み)
- `alert_log` (将来拡張向けに確保済み)

## 技術的な制約(重要)

- **真のRFスペクトラムアナライザではありません。** 通常のクライアントNICはWindows経由で生RF波形を提供しないため、専用のスペクトラムアナライザハードウェアなしに実測スペクトラムは取得できません。Dashboardの「疑似スペクトラム表示」、Channel Planningの「混雑度スコア」はいずれもRSSIから合成・推定した表示です。
- **ノイズフロアは推定値です。** `WLAN_BSS_ENTRY` にノイズ値のフィールドは存在しないため、固定の推定値(既定 -95dBm)を使用しています。
- **セキュリティ種別判定はヒューリスティックです。** RSN/WPA IEの有無とAKMスイート種別から判定していますが、802.11の全IEを厳密にパースするものではありません。
- **なりすまし/野良AP検知は「信頼済みAP」として明示登録したSSIDのみ対象です。** 何も登録していない状態では発火しません。
- **Windows の位置情報アクセス許可が必要な場合があります。** Windows 11 (build 25977+) では `WlanScan` / `WlanGetNetworkBssList` はユーザーが位置情報アクセスを許可していないと `ERROR_ACCESS_DENIED` を返します。拒否された場合はアプリ上部に赤いバナーが表示され、「Windowsの位置情報設定を開く」ボタンから設定画面 (設定 > プライバシーとセキュリティ > 位置情報) を開けます。
- **Windows専用です。** `Wlanapi.dll` および `ping.exe` を直接呼び出すため、他OSでは動作しません。
- **Wi-Fi接続中は電波再スキャンの間隔を自動的に延長します。** アダプタが実際にSSIDへ接続している間に`WlanScan`を頻繁に発行すると、通信中のスループットに影響するため、接続中は再スキャン間隔を約15秒まで自動的に間引きます(表示自体のキャッシュ更新は従来通り)。未接続時は最短2秒間隔のままです。
- **表示言語は再起動後に反映されます。** 設定画面で日本語/英語を切り替えても、既に構築済みのUI文字列はその場では再描画されません(次回起動時に反映)。
- **「AP配置を提案」はヒューリスティックです。** 一般的な屋内伝搬モデル(log-distanceパスロス)を使った貪欲法(greedy)による候補地点探索であり、学習済みAIモデルではありません。実距離のキャリブレーション機能が無いため、提案位置はあくまで目安です。
- **REST APIには認証がありません。** ループバック(127.0.0.1)にのみバインドされるため他マシンからは到達できませんが、有効化した場合は同一マシン上の他プロセスからは読み取り可能です。既定は無効(OFF)です。
- **Natural Neighbor補間は近似実装です。** 本来のSibson座標(Voronoi図/Delaunay三角形分割)を厳密に構築する代わりに、クエリ点周辺を離散グリッドでサンプリングして「奪われる面積」を数値的に推定しています(計算幾何ライブラリに依存しないための設計判断)。
- **ローミング解析は静的スナップショットに基づく目安です。** 実際にクライアントがいつローミングするかは端末のローミング積極性に依存するため、「境界の可能性がある地点」の提示に留まります。
- **スループット計測は簡易的な推定値です。** iperf3等の専用プロトコルではなく、指定URLへのHTTP GETを最大3秒間実行して計測した概算のダウンロード速度です。
- **Plugin APIは読み取り専用の通知のみの最小構成です。** UI・アラートルール・エクスポート形式へのプラグインからの介入は現状サポートしていません。また、`survey-core`のような独立モジュールへの分離は行っておらず、単一Mavenモジュール内のパッケージとして提供しています。
- **GPS位置取得はWindows PowerShell(.NET Framework)経由です。** `System.Device.Location.GeoCoordinateWatcher` をバックグラウンドの `powershell.exe` サブプロセスから利用するため、Windowsの位置情報アクセス許可(設定 > プライバシーとセキュリティ > 位置情報)が必要です。屋内ではGPS本来の測位精度が大きく劣化する(場合によっては全く測位できない)ため、キャリブレーション画面では緯度経度を手入力するフォールバックも用意しています。
- **屋外モードは地図タイルを自動取得しません。** オフライン動作を維持するため、OpenStreetMap等の地図タイルサービスには接続せず、ユーザーが用意した地図/航空写真の画像をキャリブレーションして使う設計です(屋内フロアプランと全く同じ仕組みを流用しています)。
- **Wi-Fi測位は真の三辺測量ではありません。** 実距離のキャリブレーション機能が無いため(`ApPositionEstimator`と同様の設計判断)、RSSIを重みとした加重重心による簡易推定です。少数のAP、または信号強度が拮抗している状況では精度が大きく低下します。事前にある程度の地点(理想的には3点以上)を手動記録またはGPS記録しておく必要があり、何も記録していない状態では動作しません。

## 必要環境

- Windows 10/11 + Wi-Fi アダプタ
- ビルド/実行に外部インストールは不要です。JDK 21 と Maven をプロジェクトローカルの `.tools/` 配下に同梱しています。

## ビルド・実行

```
.\mvnw.cmd javafx:run
```

### ヘッドレス/CLIモード

パッケージ済みjar/exeに `--headless` を渡すと、GUIなしでスキャンループのみを実行します(Ctrl+Cで停止)。

```
java -jar target\open-site-survey-<version>-shaded.jar --headless
java -jar target\open-site-survey-<version>-shaded.jar --headless --interval 5000
```

- `--interval <ms>`: ポーリング間隔(既定2000ms)。
- 通常のGUI版と同じ `~/.opensitesurvey/scan-log.db` に書き込むため、ヘッドレス機で収集したログをGUI版のHistoryタブから閲覧できます。
- 検出APの一覧をコンソールへ1スキャンごとに出力します。

## プラグイン開発

`com.opensitesurvey.tool.plugin.OpenSiteSurveyPlugin` インターフェースを実装したクラスを持つ`.jar`を作成し、`META-INF/services/com.opensitesurvey.tool.plugin.OpenSiteSurveyPlugin` に実装クラスの完全修飾名を1行書いて同梱、`~/.opensitesurvey/plugins/` に配置してアプリを再起動すると読み込まれます(標準の`ServiceLoader`規約)。

```java
package com.example;

import com.opensitesurvey.tool.model.ScanSnapshot;
import com.opensitesurvey.tool.plugin.OpenSiteSurveyPlugin;

public class MyPlugin implements OpenSiteSurveyPlugin {
    @Override
    public String name() {
        return "My Plugin";
    }

    @Override
    public void onScanSnapshot(ScanSnapshot snapshot) {
        // スキャン毎にWLANポーラーのバックグラウンドスレッドから呼び出されます。
        // JavaFX Application Threadではないため、UI操作は行わないでください。
        // 処理は短時間で完了させてください(ブロックすると他プラグイン/次スキャンが遅延します)。
    }
}
```

1台のプラグインの読み込み失敗・例外は他のプラグインやアプリ本体には影響しません(stderrへログ出力されるのみ)。読み込み済みプラグインの一覧は ヘルプ > 読み込み済みプラグイン から確認できます。

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
- 生成物: `dist/<version>/OpenSiteSurvey/OpenSiteSurvey.exe`(Java実行環境同梱、そのまま配布可能)、および同ディレクトリに配布用zipも作成されます。
- `dist/` はバージョンごとにサブディレクトリを分けるビルド専用の配布物置き場です(gitでは追跡しません)。再ビルドすると対象バージョンのフォルダのみ作り直されます。
- テストをスキップしたい場合: `.\build-release.ps1 -SkipTests`
- `.msi` インストーラも生成したい場合: `.\build-release.ps1 -Msi`。jpackageのMSI生成には WiX Toolset (`candle.exe`/`light.exe`) が必要ですが、システムへのインストールは不要です — 初回実行時にWiX v3.11のポータブル版バイナリを自動ダウンロードし、`.tools/wix/` 配下に配置します(JDK/Mavenと同じ「システムにインストールせずプロジェクトローカルに同梱する」方針)。ダウンロードのみネットワーク接続が必要です。
- `PdfReportGeneratorTest`(1件)は環境によって、生成直後の一時PDFファイルをOS側(Windows Defenderのリアルタイム保護やSearch Indexer等)が瞬間的にロックし、JUnitの`@TempDir`削除に失敗して`BUILD FAILURE`になることがあります(コード側のリソースリークではなく、開発機のファイルロックに起因する既知の環境要因です)。発生した場合は`-SkipTests`を使うか、`.\mvnw.cmd test`を単体で再実行すれば通ることが多いです。

主なテスト対象:

- WLAN/セキュリティ: `Dot11SsidTest`, `SecurityClassifierTest`
- チャネル設計: `ChannelPlannerTest`, `BssLoadParserTest`, `ChannelUtilTest`, `ChannelWidthParserTest`
- Wi-Fi7/MLO検出: `Wifi7ParserTest`
- アラート: `AlertEngineTest`
- Ping/Traceroute: `PingProbeTest`, `TracerouteProbeTest`, `HopRttHistoryTest`, `ThroughputProbeTest`
- Site Survey/レポート: `IdwInterpolatorTest`, `KrigingInterpolatorTest`, `NaturalNeighborInterpolatorTest`, `ApPositionEstimatorTest`, `ApPlacementAdvisorTest`, `CoverageRequirementEvaluatorTest`, `RoamingAnalyzerTest`, `GeoPackageExporterTest`, `SurveyProjectStoreTest`, `PdfReportGeneratorTest`
- GPS/Wi-Fi歩行ヒートマップ: `GeoReferenceTest`, `PathSamplerTest`, `GpsProbeTest`, `WifiPositionEstimatorTest`
- REST API: `ApiServerTest`
- Plugin API: `PluginManagerTest`
- ダッシュボード/履歴補助: `RssiHistoryStoreTest`, `CategoricalColorPaletteTest`, `CsvUtilTest`, `VendorLookupTest`
- 設定/国際化: `AppConfigStoreTest`, `MessagesTest`

## プロジェクト構成

```
com.opensitesurvey.tool
├── App.java                 JavaFXエントリポイント + MenuBar
├── Launcher.java             fat jar/exe用のプレーンなmainエントリポイント(--headless判定含む)
├── HeadlessRunner.java       GUIなしのコンソールスキャンループ(--headless)
├── i18n/                    メッセージバンドル切替
├── wlan/                    Wlanapi.dll のJNAバインディングとポーリング
├── model/                   AP/スキャン/サーベイ点のデータモデル
├── util/                    チャネル変換・色スケール・ノイズ推定・MACベンダー(OUI)検索
├── security/                IE解析によるセキュリティ種別判定 + Security Audit UI
├── channel/                 チャネル混雑度スコア・チャネル幅(HT/VHT IE)検出 + Channel Planning UI
├── wifi7/                   EHT Capabilities/Operation・Multi-Link要素(Wi-Fi7/MLO)の検出
├── alert/                   アラートルール・エンジン・信頼済みAP管理・設定ダイアログ・Windows通知
├── ping/                    ping/tracert 実行・パース
├── gps/                     GPS位置取得(PowerShell/GeoCoordinateWatcher)・緯度経度⇔画像座標キャリブレーション・移動距離間引き
├── report/                  HTML/PDFサイトサーベイレポート生成
├── ui/dashboard/            ライブダッシュボードUI
├── ui/survey/               サイトサーベイ・ヒートマップ・カバレッジホール・比較モードUI
├── ui/history/              長期ログ閲覧UI(プリセット/カスタム期間検索)
├── ui/ping/                 Traceroute監視UI
├── api/                     REST API(JDK標準com.sun.net.httpserver、ループバック限定)
├── plugin/                  Plugin API(ServiceLoaderベースのプラグイン読み込み・通知)
└── persistence/             設定・長期ログ(SQLite)・サーベイプロジェクト・CSV/JSON出力
```

## 既知の制約 / 今後の拡張候補

- Historyタブの画面表示・通常CSV出力は最大5000件までです(大量データ時は直近側へ切り詰め表示)。件数上限なしで出力したい場合は「全件CSV出力」を使用してください。
- Site Surveyプロジェクトはフロアプラン画像そのものを埋め込んで保存しますが、旧バージョンで保存したプロジェクトファイル(画像パスのみ保持)は、そのパスに画像が存在しないと読み込めません。
- Channel PlanningのHT/VHTチャネル幅検出は2.4GHz/5GHzのみです。6GHz(HE Operation要素)は未対応で、常に20MHz固定として扱われます。
- `jpackage` によるexe化(`.\build-release.ps1`)、および `-Msi` オプションによる `.msi` インストーラ生成に対応しています。

## ライセンス

MIT License。Copyright (c) 2026 Hibiki Suzuki。詳細は [LICENSE](LICENSE) を参照してください。

同梱している依存ライブラリ(OpenJFX / JNA / Jackson / SQLite JDBC / OpenPDF)のライセンス表記は、アプリ内の Help > About Third-Party Licenses から確認できます。
