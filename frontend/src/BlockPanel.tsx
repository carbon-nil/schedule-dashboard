import { useState } from "react";
import { type Block, type Day } from "./api";
import { draftFor, type Draft } from "./draft";
import { formatJstTime, overlaps, toUtcIso } from "./time";

interface Props {
    day: Day;
    draft: Draft;
    onDraftChange: (draft: Draft) => void;
    onCreate: (title: string, startAt: string, endAt: string) => Promise<void>;
    onMove: (block: Block, startAt: string, endAt: string) => Promise<void>;
    onDelete: (block: Block) => Promise<void>;
}

/** スマホでドラッグしなくても、開始日時と分数のフォームだけで作成・移動・削除できる (設計書 3 章、A24)。 */
export const BlockPanel = ({ day, draft, onDraftChange, onCreate, onMove, onDelete }: Props) => {
    const [busy, setBusy] = useState(false);
    const selected = day.blocks.find(b => b.id === draft.blockId) ?? null;

    const submit = async () => {
        const startAt = toUtcIso(draft.start);
        const endAt = new Date(new Date(startAt).getTime() + draft.minutes * 60000).toISOString().replace(".000Z", "Z");
        setBusy(true);
        try {
            if (selected !== null) await onMove(selected, startAt, endAt);
            else await onCreate(draft.title, startAt, endAt);
        } finally {
            setBusy(false);
        }
    };

    const remove = async () => {
        if (selected === null) return;
        setBusy(true);
        try {
            await onDelete(selected);
        } finally {
            setBusy(false);
        }
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
                <label className="block">
                    <span className="text-sm">作業名</span>
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
                                onClick={() => void remove()}
                            >
                                削除
                            </button>
                            <button
                                type="button"
                                className="rounded border px-3 py-1"
                                onClick={() => {
                                    onDraftChange({ ...draft, blockId: null, title: "" });
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
                                {formatJstTime(b.startAt)}–{formatJstTime(b.endAt)} {b.title}
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
        </section>
    );
};
