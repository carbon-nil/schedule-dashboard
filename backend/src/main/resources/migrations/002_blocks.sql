-- 作業計画。Todoist 連携までの間はタイトルを直接持つ (設計書 5.3 からの一時的なずれ)。
CREATE TABLE blocks (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    todoist_task_id TEXT,
    start_at INTEGER NOT NULL,
    end_at INTEGER NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    CHECK (start_at < end_at)
);
CREATE INDEX blocks_range ON blocks (start_at, end_at);
CREATE INDEX blocks_todoist_task ON blocks (todoist_task_id);
