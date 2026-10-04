import { useState } from "react";
import { type Block, type Day, type Task } from "./api";
import { blockLabel, draftFor, type Draft } from "./draft";
import { formatJstTime, overlaps, toUtcIso } from "./time";

interface Props {
    day: Day;
    draft: Draft;
    onDraftChange: (draft: Draft) => void;
    onCreate: (title: string | null, taskId: string | null, startAt: string, endAt: string) => Promise<void>;
    onMove: (block: Block, startAt: string, endAt: string) => Promise<void>;
    onDelete: (block: Block) => Promise<void>;
    onComplete: (task: Task) => Promise<void>;
}

/** スマホでドラッグしなくても、開始日時と分数のフォームだけで作成・移動・削除できる (設計書 3 章、A24)。 */
export const BlockPanel = ({ day, draft, onDraftChange, onCreate, onMove, onDelete, onComplete }: Props) => {
    const [busy, setBusy] = useState(false);
    const selected = day.blocks.find(b => b.id === draft.blockId) ?? null;
    const draftTask = day.tasks.find(t => t.id === draft.taskId) ?? null;

    const guard = async (action: () => Promise<void>) => {
        setBusy(true);
        try {
            await action();
        } finally {
            setBusy(false);
        }
    };

    const submit = () => {
        const startAt = toUtcIso(draft.start);
        const endAt = new Date(new Date(startAt).getTime() + draft.minutes * 60000).toISOString().replace(".000Z", "Z");
        return guard(() =>
            selected !== null
                ? onMove(selected, startAt, endAt)
                : onCreate(draft.taskId === null ? draft.title : null, draft.taskId, startAt, endAt),
        );
    };

    /** 見積があればその分数を初期値にする。Todoist の duration は変えない (A03)。 */
    const assign = (task: Task) => {
        onDraftChange({
            ...draft,
            blockId: null,
            title: "",
            taskId: task.id,
            minutes: task.estimateMinutes ?? draft.minutes,
        });
    };

    return (
        <section className="space-y-4">
            <form
                className="space-y-2 rounded border p-3"
                onSubmit={e => {
                    e.preventDefault();
                    void submit();
                }}
            >
                <h2 className="font-bold">{selected !== null ? "作業ブロックを変更" : "時間を割り当てる"}</h2>
                {draft.taskId !== null ? (
                    <p className="flex items-center gap-2">
                        <span className="flex-1">
                            {selected !== null ? blockLabel(selected, day.tasks) : (draftTask?.title ?? draft.taskId)}
                        </span>
                        {selected === null && (
                            <button
                                type="button"
                                className="rounded border px-2 py-1 text-sm"
                                onClick={() => {
                                    onDraftChange({ ...draft, taskId: null });
                                }}
                            >
                                タスクを外す
                            </button>
                        )}
                    </p>
                ) : (
                    <label className="block">
                        <span className="text-sm">作業名 (Todoist を使わないとき)</span>
                        <input
                            className="w-full rounded border px-2 py-1 disabled:bg-gray-100"
                            value={draft.title}
                            disabled={selected !== null}
                            required
                            maxLength={200}
                            onChange={e => {
                                onDraftChange({ ...draft, title: e.target.value });
                            }}
                        />
                    </label>
                )}
                <div className="flex gap-2">
                    <label className="block flex-1">
                        <span className="text-sm">開始 (日本時間)</span>
                        <input
                            type="datetime-local"
                            className="w-full rounded border px-2 py-1"
                            value={draft.start}
                            required
                            onChange={e => {
                                onDraftChange({ ...draft, start: e.target.value });
                            }}
                        />
                    </label>
                    <label className="block w-24">
                        <span className="text-sm">分</span>
                        <input
                            type="number"
                            min={5}
                            step={5}
                            className="w-full rounded border px-2 py-1"
                            value={draft.minutes}
                            required
                            onChange={e => {
                                onDraftChange({ ...draft, minutes: Number(e.target.value) });
                            }}
                        />
                    </label>
                </div>
                <div className="flex gap-2">
                    <button type="submit" disabled={busy} className="rounded bg-blue-600 px-3 py-1 text-white">
                        {selected !== null ? "時刻を変更" : "作成"}
                    </button>
                    {selected !== null && (
                        <>
                            <button
                                type="button"
                                disabled={busy}
                                className="rounded border border-red-600 px-3 py-1 text-red-600"
                                onClick={() => void guard(() => onDelete(selected))}
                            >
                                削除
                            </button>
                            <button
                                type="button"
                                className="rounded border px-3 py-1"
                                onClick={() => {
                                    onDraftChange({ ...draft, blockId: null, title: "", taskId: null });
                                }}
                            >
                                新規に戻る
                            </button>
                        </>
                    )}
                </div>
            </form>

            <ul className="space-y-1">
                {day.blocks.map(b => {
                    const warnings = [
                        ...(day.blocks.some(o => o.id !== b.id && overlaps(o, b))
                            ? ["ほかのブロックと重なっています"]
                            : []),
                        ...(day.events.some(e => !e.allDay && overlaps(e, b)) ? ["予定と重なっています"] : []),
                    ];
                    return (
                        <li key={b.id}>
                            <button
                                type="button"
                                className={`w-full rounded border px-2 py-1 text-left ${b.id === draft.blockId ? "border-blue-600" : ""}`}
                                onClick={() => {
                                    onDraftChange(draftFor(b));
                                }}
                            >
                                {formatJstTime(b.startAt)}–{formatJstTime(b.endAt)} {blockLabel(b, day.tasks)}
                                {warnings.map(w => (
                                    <span key={w} className="block text-sm text-amber-700">
                                        {w}
                                    </span>
                                ))}
                            </button>
                        </li>
                    );
                })}
            </ul>

            {/* 繰り返しタスクは配置も完了もせず、Todoist への導線だけ出す (A26) */}
            <section>
                <h2 className="font-bold">Todoist のタスク</h2>
                {day.tasks.length === 0 && <p className="text-sm text-gray-600">進行中のタスクはありません。</p>}
                <ul className="space-y-1">
                    {day.tasks.map(t => (
                        <li key={t.id} className="flex flex-wrap items-center gap-2 rounded border px-2 py-1">
                            <span className="flex-1">
                                {t.title}
                                <span className="block text-sm text-gray-600">
                                    {t.estimateMinutes !== null && `見積 ${t.estimateMinutes} 分 `}
                                    {t.deadlineDate !== null && `締切 ${t.deadlineDate}`}
                                </span>
                            </span>
                            {t.isRecurring ? (
                                <a
                                    className="text-sm text-blue-700 underline"
                                    href={t.url}
                                    target="_blank"
                                    rel="noreferrer"
                                >
                                    繰り返し: Todoist で操作
                                </a>
                            ) : (
                                <>
                                    <button
                                        type="button"
                                        className="rounded border px-2 py-1 text-sm"
                                        onClick={() => {
                                            assign(t);
                                        }}
                                    >
                                        割り当て
                                    </button>
                                    <button
                                        type="button"
                                        disabled={busy}
                                        className="rounded border border-green-700 px-2 py-1 text-sm text-green-700"
                                        onClick={() => void guard(() => onComplete(t))}
                                    >
                                        完了
                                    </button>
                                </>
                            )}
                        </li>
                    ))}
                </ul>
            </section>
        </section>
    );
};
