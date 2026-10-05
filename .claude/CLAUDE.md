# schedule-dashboard 作業手順

個人用スケジュールダッシュボードを作る。範囲と仕様は `docs/design.md`（2026-10-03 版の設計書）で決まっている。この手順書は設計書に沿って実装を進めるための決定事項と順序をまとめたもの。設計書と食い違う点があれば設計書を優先し、食い違いをユーザーに報告する。

## 決定済みの事項

2026-10-04 のセッションでユーザーと決めた。再検討はしない。

- リポジトリ: `carbon-nil/schedule-dashboard`（public、MIT）。ローカルは `~/workspace/personal/schedule-dashboard`、カテゴリ `personal` は GitHub アカウント carbon-nil に対応する。
- 対象範囲: 設計書 12 章の工程 1〜5（Routine を除く初版）。Routine（工程 6）は作らない。
- 順序: 早く使えることを優先し、設計書 12 章の順ではなく下の「PR の順序」で作る。
- 本番を動かすのは開発機とは別のマシン。Docker Compose で起動し、Cloudflare Tunnel で HTTPS 公開する（README の「配備」）。
- 構成は `haiiro2gou/karman` に合わせる。karman は同じ構成で作り始めたリポジトリで、設定ファイルの手本にする。
    - `backend/`: Scala 3.3 LTS、cats-effect、http4s（ember）、Doobie、sbt 1.13、scalafmt（インデント 4）
    - `frontend/`: React 19、Vite 7、Tailwind CSS 4、`@haiiro2gou/eslint-config`、prettier
    - ルート: husky と lint-staged、`.editorconfig`
    - `.devcontainer/`: JDK 21、Node 22、sbt、scalafmt。JDK と sbt はホストに入れず、コンテナの中だけで使う。
- karman と違う点
    - DB は設計書どおり SQLite（sqlite-jdbc と Doobie）。karman の PostgreSQL と compose の db サービスは持ち込まず、devcontainer は単一コンテナにする。
    - 本番では backend が `frontend/dist` を同一オリジンで配信し、1 プロセスで動かす。開発中は Vite の proxy で `/api` と `/auth` を backend に送る。
    - frontend に FullCalendar（React 版、v7）と TanStack Query を足す。
- バックエンドを Scala にしたのは、ユーザーが Scala を学びたいため。空き時間計算や外部 API のエラー型など、設計書の難しい部分はサーバー側にある。

## 再発防止の決まり

ユーザーの過去の個人開発（`haiiro2gou/task-manager` など）は、時間が取れない、範囲が膨らむ、UI で手が止まる、の 3 つが重なって未完で止まった。このリポジトリでは次のとおり進める。

- 範囲: 設計書にない機能は実装しない。思いついた機能は GitHub Issue に書いておき、初版が完成してから検討する。
- UI: FullCalendar と Tailwind の既定の見た目のまま、機能を先に完成させる。見た目の調整は PR4 が終わってから別に行う。
- 時間: 1 つの PR は 1 セッションで終わる大きさにする。PR を出したら、下の「進捗」に結果と次の作業を書く。間が空いても、この手順書だけを読めば再開できるようにする。

## 進め方

各 PR は次の順で進める。ユーザーは PR ごとの事前承認を省き、結果の報告だけを求めている。

1. `EnterWorktree` で `worktree-*` ブランチを作る。`master` へ直接コミットしない。前の PR が未マージなら、前の PR のブランチから作り、PR の base もそのブランチにする。
2. 実装し、検証コマンドを通す。
3. `gh pr create` で PR を出す。マージはユーザーが行う。
4. 下の「進捗」を更新する。

検証コマンドは次のとおり。

```bash
cd backend && sbt scalafmtCheckAll scalafmtSbtCheck "scalafixAll --check" test
npm ci && npx prettier --check . && npm run lint --prefix frontend && npm run build --prefix frontend
docker build -t schedule-dashboard:dev .   # 本番イメージ。起動して /api/health と画面配信を確かめる
```

同じものを GitHub Actions（`.github/workflows/ci.yml`）が PR と master で回す。ツールの構成（Scala と scalafix、Node と ESLint、devcontainer、CI）は `haiiro2gou/karman` に合わせており、karman 側が変わったら追従する。

このマシンには devcontainer CLI も JDK もない。sbt は `sbtscala/scala-sbt:eclipse-temurin-21.0.12_8_1.13.0_3.9.0` イメージを `docker run -u $(id -u):$(id -g)` で動かし、worktree を mount して実行する。キャッシュは `~/.cache/schedule-dashboard-sbt` に置く。

## PR の順序

括弧内は設計書 13 章の受け入れ条件。外部 API はモックでテストし、実アカウントでの確認はユーザーが行う。

| PR  | 内容                                                                                                                                            | 受け入れ条件                     |
| --- | ----------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------- |
| PR1 | 雛形、devcontainer、本番用 Dockerfile と compose、DB マイグレーション、空き時間計算、Google ログイン（所有者のみ）と refresh token の暗号化保存 | A01、A02、A05、A06、7 章の計算例 |
| PR2 | Google の予定表示（events.list）、Block の作成・移動・削除、2 列の画面とスマホ用フォーム。ここで使えるようになる                                | A04、A07、A24                    |
| PR3 | Todoist の一覧と完了、Block とタスクの紐付け                                                                                                    | A03、A14、A22、A26、A27          |
| PR4 | 活動可能時間の設定、freeBusy、空き時間の表示、共有リンク                                                                                        | A08〜A13、A23                    |
| PR5 | SQLite の毎日のバックアップと復元手順（設計書 14 章）                                                                                           | 14 章                            |

PR2 では Todoist がまだないため、Block にタイトル列を持たせた。PR3 でタスク参照を足したあとも、Todoist を使わないときのためにタイトルは残した（どちらか一方だけ持つ）。PR2 では表示カレンダーの選択も先に入れた（選ばないと予定が出ないため）。

PR1 と PR2 は同じブランチ `worktree-pr1-scaffold` に積み、PR #1 にまとめた。セッションが worktree に入ったままでは、ExitWorktree をユーザーの指示なしに呼べず、新しい worktree を作れなかったため。PR4 も同じ理由で、PR #2 のブランチ `worktree-pr3-todoist` を master に追従させてから積んだ。

## 設計書からのずれ

- 認証ライブラリを使わず、Google OAuth のコード交換を自前で書いた（`backend/src/main/scala/schedule/Auth.scala`）。ID トークンはトークンエンドポイントから TLS で直接受け取るので署名検証を省き、iss・aud・exp・nonce を検証する。PKCE と state も使う。セッションは `sessions` 表にトークンの SHA-256 を保存する。
- Block はタスク参照のほかにタイトルも持てる（`003_block_task.sql` の CHECK でどちらか一方）。Todoist のトークンがなくても作業計画を置けるようにするため。設計書 5.3 ではタスク参照だけ。
- Todoist のタスクの URL は API が返さないので、`https://app.todoist.com/app/task/<id>` を組み立てている（API 資料の「Task URLs」の形式）。
- 公開 API の回数制限は 1 分の固定窓で、境界では最大 2 倍通る。1 つの窓で持つキー数は 10000 までで、超えたら新しいキーは通さない。接続元は `CF-Connecting-IP`（Cloudflare が上書きする）か接続元アドレスで、閲覧者が書ける `X-Forwarded-For` は使わない。同じキーの同時取得を 1 回にまとめる処理は入れていない（利用者 1 人なので）。
- Tapir は入れていない。API 型を TypeScript へ生成する必要が出たら入れる。
- バックアップはアプリの中で毎日 03:00 JST に `VACUUM INTO` で取り（`Backup.scala`）、`/data/backups/` に 30 個残す。別マシンへの複製は README の `docker compose cp` で人が行う。cron や別サービスは足していない（設計書 3 章）。

## このマシンでの注意

- `git -C` は git-guard フックが拒否する。リポジトリのディレクトリに移ってから `git` を実行する。
- worktree に入ったセッションでは、`.gitignore` という語や base64 を含む複雑なコマンドが拒否されることがある。ファイル作成は Write ツールを使い、複雑な処理は `$CLAUDE_JOB_DIR/tmp` のスクリプトに分ける。
- `haiiro2gou/karman` は private で、carbon-nil の認証では読めない。参照するときは `~/workspace/vault` など haiiro2gou のカテゴリから `gh api` で読む。
- 秘密情報（Google の client secret、Todoist のトークン、暗号鍵）はリポジトリに置かない。値は `~/.config/schedule-dashboard/env`（ディレクトリ 700、ファイル 600）に置く。

## 進捗

- 2026-10-04: `claude-init-category personal --account carbon-nil` でカテゴリを作り、`gh repo create --public --license mit` でリポジトリを作成した。`gh repo create` と `git clone` を `git -C` と同じコマンドでつないだところ git-guard に拒否されたので、`git -C` を外して再実行した。
- 2026-10-04: PR1 を実装した。backend のテスト 16 件、frontend の lint、本番イメージのビルドと起動確認（health、未ログイン時 401、画面配信、Google へのリダイレクト）が通った。起動確認で、`protect` が `/api` 以外にも 401 を返す不具合を見つけて直した。実際の Google ログインは OAuth クライアントがまだないので試していない。
- 2026-10-04: PR2 を実装し、PR #1 に積んだ。backend のテストは 25 件。Playwright の Docker イメージ（`mcr.microsoft.com/playwright/python`、`pip install playwright` が別に要る）で、ブラウザのタイムゾーンを米国東部にして画面を確かめた。ブロックは日本時間の位置に出て、フォームで「10:00 開始・30 分」と入れると UTC 01:00〜01:30 で保存された。FullCalendar v7 は要素に `.fc-event` クラスを付けないので、テストの選択子は文字で探す。次の作業は、ユーザーが OAuth クライアントを作って実際にログインできるかの確認と、PR3（Todoist）。
- 2026-10-04: PR3（Todoist）を実装した。backend のテストは 31 件。Todoist API v1 の形は公式資料のページに埋め込まれた OpenAPI 定義（`ItemSyncView`）で確かめた。タスク一覧は `GET /api/v1/tasks` の `results` と `next_cursor`、完了は `POST /api/v1/tasks/{id}/close`、単件取得は完了済みだと 404。本番イメージを起動し、トークン未設定でもタスク参照の Block が「完了または削除されたタスク」として出て、タイトルの Block も作れることを確かめた。実際の Todoist トークンでの確認はユーザーが行う。次の作業は PR4（活動可能時間、freeBusy、空き時間、共有リンク）。
- 2026-10-05: PR4（活動可能時間、freeBusy、空き時間、共有リンク）を実装した。backend のテストは 38 件。A09 は公開応答のキー集合で、A12・A13・A23 と回数制限は API のテストで確かめた。本番イメージを起動し、Google 未接続では空き時間が「確認できません」になり、プレビューが 503、不明なトークンの共有ページが 404 の表示とヘッダー（no-store、no-referrer、noindex）になることを確かめた。実際の Google アカウントでの freeBusy と共有ページの確認はユーザーが行う。残りは SQLite の毎日のバックアップ。
- 2026-10-05: PR #3 に Codex の adversarial review（`/codex:adversarial-review master`。`/codex:review` は引数を取れず作業ツリーだけを見る）を掛けた。指摘 2 件を直した: 公開 API で IP の上限を先に見ず不明なトークンごとにカウンターを作っていた点（有効な共有にだけ割り当て、キー数に上限）と、共有ページで日をまたぐ空きを開始日の下に「22:00–02:00」と出していた点（日本時間の日付で分け、終了は 24:00 と出す）。
- 2026-10-05: karman の現在の構成に追従した（Scala 3.9.0 と scalafix、doobie RC12 などの更新、Node 24・ESLint 10・TypeScript 6・Vite 8、compose 構成の devcontainer、CI）。`@haiiro2gou/eslint-config` 2.0.0 の react プリセットは `import * as React from "react"` と `React.useState` の形を要求するので、合わせて直した。PR #3（空き時間と共有リンク）はこの変更より前のブランチなので、マージ後に `WindowSettings.tsx`、`FreeTime.tsx`、`SharePanel.tsx`、`SharePage.tsx` にも同じ直しが要る。tapir と flyway は karman にあるがこの repo では使わない。prettier と editorconfig の設定も karman と差があるが、全ファイルの整形し直しになるので据え置いた。
- 2026-10-05: SQLite の毎日のバックアップを足した。`VACUUM INTO` は doobie のトランザクション内では動かないので `Strategy.void` の transactor で流す。復元手順は README の「バックアップと復元」。backend のテストは 43 件。設計書 1〜5 章と 14 章の範囲はこれで揃い、残りは実アカウントでの確認。
- 2026-10-05: devcontainer でリポジトリをホストと同じパスに mount し、`/workspaces/schedule-dashboard` は symlink にした。`claude --resume` の既定の一覧は、セッションの最初の cwd が今のディレクトリの祖先か同じものだけを出す（worktree のディレクトリにあるものは例外）。ホストとコンテナでパスが違うと、`~/.claude/projects` を mount して名前の symlink を置いても相手側の一覧に出ないため。ホストのパスは `initializeCommand` が `.devcontainer/.env` に書き、compose は 1 つ目の compose ファイルと同じディレクトリの `.env` を読む（Dev Containers CLI は `-f` だけを渡す）。
