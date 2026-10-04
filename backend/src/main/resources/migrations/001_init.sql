-- 設定は id=1 の 1 行。タイムゾーンは Asia/Tokyo 固定なので列を持たない。
CREATE TABLE app_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    version INTEGER NOT NULL DEFAULT 1
);
INSERT INTO app_settings (id) VALUES (1);

CREATE TABLE weekly_windows (
    id TEXT PRIMARY KEY,
    weekday INTEGER NOT NULL CHECK (weekday BETWEEN 0 AND 6),
    start_minute INTEGER NOT NULL CHECK (start_minute BETWEEN 0 AND 1439),
    end_minute INTEGER NOT NULL CHECK (end_minute BETWEEN 1 AND 1440),
    CHECK (start_minute < end_minute)
);

CREATE TABLE selected_calendars (
    calendar_id TEXT PRIMARY KEY
);

CREATE TABLE google_credentials (
    owner_sub TEXT PRIMARY KEY,
    refresh_token_ciphertext TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 認証ライブラリを使わないため、セッションはトークンの SHA-256 だけを保存する。
CREATE TABLE sessions (
    token_hash TEXT PRIMARY KEY,
    expires_at INTEGER NOT NULL
);
