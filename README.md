# schedule-dashboard

Google Calendar の予定と自分の作業計画を一画面で見て、空き時間を URL で共有する個人用ダッシュボード。仕様は [docs/design.md](docs/design.md) にある。

## 構成

- `backend/`: Scala 3（Cats Effect、http4s、Doobie）。DB は SQLite。
- `frontend/`: TypeScript（React、Vite、Tailwind CSS）。
- 本番では backend が `frontend/dist` を同じオリジンで配信し、1 プロセスで動く。

## 開発環境

Docker と VS Code の Dev Containers 拡張で「Reopen in Container」を選ぶと、JDK・sbt・Node.js と依存パッケージが入る。

```bash
cd backend && sbt test          # テスト
cd backend && sbt run           # http://localhost:8080 (下の環境変数が要る)
npm run dev --prefix frontend   # http://localhost:3000 (/api と /auth は 8080 へ転送)
```

自分用の設定は、追跡しない `.devcontainer/postCreate.local.sh` と `.devcontainer/postStart.local.sh` に書く。

## 設定値

すべて環境変数で渡す。秘密情報はリポジトリに置かない。

| 名前                                        | 内容                                                                                 |
| ------------------------------------------- | ------------------------------------------------------------------------------------ |
| `BASE_URL`                                  | 公開 URL。例 `https://schedule.example.com`。開発中は `http://localhost:3000`        |
| `DATABASE_PATH`                             | SQLite のファイル。コンテナでは `/data/schedule.db` が既定                           |
| `STATIC_DIR`                                | `frontend/dist` の場所。コンテナでは設定済み。開発中は不要                           |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Google Cloud の OAuth クライアント                                                   |
| `OWNER_GOOGLE_SUB`                          | ログインを許すただ 1 人の Google アカウントの subject ID                             |
| `TOKEN_ENCRYPTION_KEY`                      | refresh token を暗号化する 32 バイトの鍵（base64）。`openssl rand -base64 32` で作る |
| `TODOIST_API_TOKEN`                         | Todoist の個人 API トークン。省略すると Todoist なしで動く（タスク欄に案内が出る）   |

### Google OAuth クライアントの作り方

1. [Google Cloud Console](https://console.cloud.google.com/) でプロジェクトを作り、「Google Calendar API」を有効にする。
2. 「Google Auth Platform」で同意画面を作る。対象は「外部」、スコープに `openid`、`email`、`https://www.googleapis.com/auth/calendar.readonly` を足す。
3. 公開ステータスを「本番環境」にする。「テスト」のままだと refresh token が 7 日で失効する。自分だけが使うアプリなので、未確認アプリの警告が出ても続行できる。
4. 「クライアント」で種類「ウェブ アプリケーション」を作り、承認済みのリダイレクト URI に `<BASE_URL>/auth/callback` を登録する。開発用に `http://localhost:3000/auth/callback` も足してよい。
5. `OWNER_GOOGLE_SUB` は、最初は仮の値（`unknown` など）で起動してログインすると分かる。403 のエラーに自分の `sub` が出るので、その値に書き換えて再起動する。

### Todoist のトークン

Todoist の「設定」→「連携機能」→「開発者」にある API トークンを `TODOIST_API_TOKEN` に入れる。所有者 1 人のため OAuth は使わない。

## 配備

動かすマシンに Docker と Docker Compose があれば、次の手順で動く。

1. リポジトリを clone する。
2. 設定値を `~/.config/schedule-dashboard/env`（ディレクトリ 700、ファイル 600）に `NAME=value` の形で書く。`DATABASE_PATH` と `STATIC_DIR` は書かない。
3. `docker compose up -d --build` で起動する。アプリは `127.0.0.1:8080` だけで待ち受ける。
4. Cloudflare Tunnel で `BASE_URL` のホスト名を `http://localhost:8080` へ向け、HTTPS で公開する。
5. `BASE_URL` を開いて Google でログインする。

DB は Docker の `data` ボリュームに残る。更新は `git pull` のあと `docker compose up -d --build` を流す。

## ライセンス

[MIT](LICENSE)
