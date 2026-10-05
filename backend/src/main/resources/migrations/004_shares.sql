-- 共有リンク (設計書 5.5)。公開 URL のトークンは SHA-256 だけを保存する。
CREATE TABLE shares (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    range_start INTEGER NOT NULL,
    range_end INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    revoked_at INTEGER,
    min_free_minutes INTEGER NOT NULL CHECK (min_free_minutes BETWEEN 1 AND 1440),
    created_at INTEGER NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    CHECK (range_start < range_end),
    CHECK (created_at < expires_at AND expires_at <= range_end)
);
