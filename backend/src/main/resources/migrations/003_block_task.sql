-- Block は Todoist のタスク参照か、Todoist を使わないときのタイトルのどちらか一方を持つ (設計書 5.3)。
-- SQLite は CHECK を変えられないので作り直す。
CREATE TABLE blocks_new (
    id TEXT PRIMARY KEY,
    title TEXT CHECK (title IS NULL OR length(title) BETWEEN 1 AND 200),
    todoist_task_id TEXT,
    start_at INTEGER NOT NULL,
    end_at INTEGER NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    CHECK (start_at < end_at),
    CHECK ((title IS NULL) <> (todoist_task_id IS NULL))
);
INSERT INTO blocks_new SELECT id, title, NULL, start_at, end_at, version FROM blocks;
DROP TABLE blocks;
ALTER TABLE blocks_new RENAME TO blocks;
CREATE INDEX blocks_range ON blocks (start_at, end_at);
CREATE INDEX blocks_todoist_task ON blocks (todoist_task_id);
