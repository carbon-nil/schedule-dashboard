import { QueryClient, QueryClientProvider, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, ApiError, type Block, type Day, type Task } from "./api";
import { BlockPanel } from "./BlockPanel";
import { CalendarSettings } from "./CalendarSettings";
import { draftFor, emptyDraft, type Draft } from "./draft";
import { addDays, minutesBetween, toJstInput, todayJst } from "./time";
import { Timeline } from "./Timeline";

const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: (count, error) => !(error instanceof ApiError) && count < 2 } },
});

export default function App() {
    return (
        <QueryClientProvider client={queryClient}>
            <Gate />
        </QueryClientProvider>
    );
}

const Gate = () => {
    const me = useQuery({ queryKey: ["me"], queryFn: () => api<unknown>("GET", "/api/me") });
    if (me.isPending) return <p className="p-4">確認中…</p>;
    if (me.error instanceof ApiError && me.error.status === 401) {
        return (
            <main className="mx-auto max-w-xl p-4">
                <h1 className="text-xl font-bold">Schedule Dashboard</h1>
                <a className="mt-4 inline-block rounded bg-blue-600 px-4 py-2 text-white" href="/auth/login">
                    Google でログイン
                </a>
            </main>
        );
    }
    if (me.isError) return <p className="p-4 text-red-600">サーバーに接続できません。</p>;
    return <Dashboard />;
};

const Dashboard = () => {
    const client = useQueryClient();
    const [date, setDate] = useState(todayJst);
    const [tab, setTab] = useState<"timeline" | "tasks">("timeline");
    const [draft, setDraft] = useState<Draft>(() => emptyDraft(todayJst()));
    const [message, setMessage] = useState<string | null>(null);
    // 表示中は 60 秒ごと、画面に戻ったとき、更新ボタンで取り直す (設計書 4.1)
    const day = useQuery({
        queryKey: ["day", date],
        queryFn: () => api<Day>("GET", `/api/day?date=${date}`),
        refetchInterval: 60_000,
    });

    const changeDate = (next: string) => {
        setDate(next);
        setDraft(emptyDraft(next));
    };

    /** 失敗は画面に出し、409 などで古くなった表示は取り直す。失敗した変更を確定したようには見せない。 */
    const run = async (action: () => Promise<unknown>) => {
        setMessage(null);
        try {
            await action();
        } catch (e) {
            setMessage(e instanceof Error ? e.message : String(e));
            throw e;
        } finally {
            await client.invalidateQueries({ queryKey: ["day"] });
        }
    };

    const create = (title: string | null, taskId: string | null, startAt: string, endAt: string) =>
        run(async () => {
            const block = await api<Block>("POST", "/api/blocks", {
                requestId: crypto.randomUUID(),
                title,
                todoistTaskId: taskId,
                startAt,
                endAt,
            });
            setDraft(draftFor(block));
        });

    /** 失敗やタイムアウトでは完了を表示せず、取り直した一覧で状態を確かめる (A27)。 */
    const complete = (task: Task) =>
        run(async () => {
            await api("POST", `/api/tasks/${task.id}/complete`);
            if (draft.taskId === task.id) setDraft({ ...draft, taskId: null });
        });

    const move = (block: Block, startAt: string, endAt: string) =>
        run(async () => {
            const moved = await api<Block>("PATCH", `/api/blocks/${block.id}`, {
                startAt,
                endAt,
                expectedVersion: block.version,
            });
            setDraft(draftFor(moved));
        });

    const remove = (block: Block) =>
        run(async () => {
            await api("DELETE", `/api/blocks/${block.id}`, { expectedVersion: block.version });
            setDraft(emptyDraft(date));
        });

    const tabClass = (t: typeof tab) => `flex-1 py-2 ${tab === t ? "border-b-2 border-blue-600 font-bold" : ""}`;

    return (
        <main className="mx-auto max-w-6xl p-2 md:p-4">
            <header className="mb-2 flex flex-wrap items-center gap-2">
                <button
                    type="button"
                    className="rounded border px-2 py-1"
                    onClick={() => {
                        changeDate(addDays(date, -1));
                    }}
                >
                    前日
                </button>
                <input
                    type="date"
                    className="rounded border px-2 py-1"
                    value={date}
                    onChange={e => {
                        if (e.target.value !== "") changeDate(e.target.value);
                    }}
                />
                <button
                    type="button"
                    className="rounded border px-2 py-1"
                    onClick={() => {
                        changeDate(addDays(date, 1));
                    }}
                >
                    翌日
                </button>
                <button
                    type="button"
                    className="rounded border px-2 py-1"
                    onClick={() => {
                        changeDate(todayJst());
                    }}
                >
                    今日
                </button>
                <button type="button" className="rounded border px-2 py-1" onClick={() => void day.refetch()}>
                    更新
                </button>
                {day.data !== undefined && (
                    <span className="text-sm text-gray-600">取得 {toJstInput(day.data.fetchedAt).slice(11)}</span>
                )}
            </header>

            {day.isError && <p className="text-red-600">取得に失敗しました: {day.error.message}</p>}
            {day.data?.errors.map(e => (
                <p key={e.target} className="text-red-600">
                    {e.target}: {e.message}
                </p>
            ))}
            {message !== null && <p className="text-red-600">{message}</p>}

            <nav className="mb-2 flex md:hidden">
                <button
                    type="button"
                    className={tabClass("timeline")}
                    onClick={() => {
                        setTab("timeline");
                    }}
                >
                    時間軸
                </button>
                <button
                    type="button"
                    className={tabClass("tasks")}
                    onClick={() => {
                        setTab("tasks");
                    }}
                >
                    タスク
                </button>
            </nav>

            {day.data !== undefined && (
                <div className="md:grid md:grid-cols-[2fr_1fr] md:gap-4">
                    <div className={tab === "timeline" ? "" : "hidden md:block"}>
                        <Timeline
                            day={day.data}
                            onSelectRange={(startAt, endAt) => {
                                setDraft({
                                    ...draft,
                                    blockId: null,
                                    start: toJstInput(startAt),
                                    minutes: minutesBetween(startAt, endAt),
                                });
                                setTab("tasks");
                            }}
                            onSelectBlock={id => {
                                const b = day.data.blocks.find(x => x.id === id);
                                if (b !== undefined) setDraft(draftFor(b));
                                setTab("tasks");
                            }}
                            onMoveBlock={(id, startAt, endAt, revert) => {
                                const b = day.data.blocks.find(x => x.id === id);
                                if (b !== undefined) move(b, startAt, endAt).catch(revert);
                            }}
                        />
                    </div>
                    <div className={`space-y-4 ${tab === "tasks" ? "" : "hidden md:block"}`}>
                        <BlockPanel
                            day={day.data}
                            draft={draft}
                            onDraftChange={setDraft}
                            onCreate={create}
                            onMove={move}
                            onDelete={remove}
                            onComplete={complete}
                        />
                        <CalendarSettings />
                    </div>
                </div>
            )}
        </main>
    );
};
