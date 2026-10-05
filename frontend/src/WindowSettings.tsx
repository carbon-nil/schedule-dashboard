import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import * as React from "react";
import { api, type Settings, type WeeklyWindow } from "./api";
import { hmToMinutes, minutesToHm } from "./time";

const weekdays = ["月", "火", "水", "木", "金", "土", "日"];

/** 曜日ごとの活動可能時間。昼休みを除くなら同じ曜日に 2 行入れる (設計書 4.2)。 */
export const WindowSettings = () => {
    const queryClient = useQueryClient();
    const settings = useQuery({ queryKey: ["settings"], queryFn: () => api<Settings>("GET", "/api/settings") });
    const [edited, setEdited] = React.useState<WeeklyWindow[] | null>(null);
    const save = useMutation({
        mutationFn: (windows: WeeklyWindow[]) =>
            api<Settings>("PUT", "/api/settings", {
                expectedVersion: settings.data?.settingsVersion,
                selectedCalendarIds: settings.data?.selectedCalendarIds ?? [],
                weeklyWindows: windows,
            }),
        onSuccess: () => {
            setEdited(null);
        },
        onSettled: () => queryClient.invalidateQueries(),
    });

    if (settings.data === undefined) return <p>設定を読み込み中…</p>;
    const rows = edited ?? settings.data.weeklyWindows;
    const update = (index: number, patch: Partial<WeeklyWindow>) => {
        setEdited(rows.map((w, i) => (i === index ? { ...w, ...patch } : w)));
    };

    return (
        <section className="space-y-2 rounded border p-3">
            <h2 className="font-bold">活動可能時間</h2>
            {rows.map((w, i) => (
                // 行の id はサーバーが振り直すので、並び順をキーにする
                <div key={i} className="flex items-center gap-1">
                    <select
                        className="rounded border px-1 py-1"
                        value={w.weekday}
                        onChange={e => {
                            update(i, { weekday: Number(e.target.value) });
                        }}
                    >
                        {weekdays.map((d, n) => (
                            <option key={d} value={n}>
                                {d}
                            </option>
                        ))}
                    </select>
                    <input
                        type="time"
                        className="rounded border px-1 py-1"
                        value={minutesToHm(w.startMinute)}
                        onChange={e => {
                            if (e.target.value !== "") update(i, { startMinute: hmToMinutes(e.target.value) });
                        }}
                    />
                    –
                    <input
                        type="time"
                        className="rounded border px-1 py-1"
                        value={minutesToHm(w.endMinute === 1440 ? 0 : w.endMinute)}
                        onChange={e => {
                            // 24:00 は入力できないので、00:00 を終了に入れたら 1440 とみなす
                            if (e.target.value !== "") {
                                const m = hmToMinutes(e.target.value);
                                update(i, { endMinute: m === 0 ? 1440 : m });
                            }
                        }}
                    />
                    <button
                        type="button"
                        className="px-2 text-red-600"
                        onClick={() => {
                            setEdited(rows.filter((_, n) => n !== i));
                        }}
                    >
                        ×
                    </button>
                </div>
            ))}
            <div className="flex gap-2">
                <button
                    type="button"
                    className="rounded border px-3 py-1"
                    onClick={() => {
                        setEdited([...rows, { weekday: 0, startMinute: 540, endMinute: 1080 }]);
                    }}
                >
                    追加
                </button>
                <button
                    type="button"
                    disabled={edited === null || save.isPending}
                    className="rounded bg-blue-600 px-3 py-1 text-white disabled:opacity-50"
                    onClick={() => {
                        if (edited !== null) save.mutate(edited);
                    }}
                >
                    保存
                </button>
            </div>
            {save.isError && <p className="text-red-600">{save.error.message}</p>}
        </section>
    );
};
