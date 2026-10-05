import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import * as React from "react";
import { api, type CalendarEntry, type Settings } from "./api";

/** 予定を表示するカレンダーを選ぶ。選んだ集合は共有の busy 計算にも使う (設計書 5.2)。 */
export const CalendarSettings = () => {
    const queryClient = useQueryClient();
    const calendars = useQuery({
        queryKey: ["calendars"],
        queryFn: () => api<CalendarEntry[]>("GET", "/api/calendars"),
    });
    const settings = useQuery({ queryKey: ["settings"], queryFn: () => api<Settings>("GET", "/api/settings") });
    const [picked, setPicked] = React.useState<string[] | null>(null);
    const save = useMutation({
        mutationFn: (ids: string[]) =>
            api<Settings>("PUT", "/api/settings", {
                expectedVersion: settings.data?.settingsVersion,
                selectedCalendarIds: ids,
            }),
        onSuccess: () => {
            setPicked(null);
        },
        onSettled: () => queryClient.invalidateQueries(),
    });

    if (calendars.isError)
        return <p className="text-red-600">カレンダー一覧を取得できません: {calendars.error.message}</p>;
    if (calendars.data === undefined || settings.data === undefined) return <p>カレンダーを読み込み中…</p>;
    const current = picked ?? settings.data.selectedCalendarIds;

    return (
        <section className="space-y-2 rounded border p-3">
            <h2 className="font-bold">表示するカレンダー</h2>
            {calendars.data.map(c => (
                <label key={c.id} className="flex items-center gap-2">
                    <input
                        type="checkbox"
                        checked={current.includes(c.id)}
                        onChange={e => {
                            setPicked(e.target.checked ? [...current, c.id] : current.filter(id => id !== c.id));
                        }}
                    />
                    {c.title}
                </label>
            ))}
            <button
                type="button"
                disabled={picked === null || save.isPending}
                className="rounded bg-blue-600 px-3 py-1 text-white disabled:opacity-50"
                onClick={() => {
                    if (picked !== null) save.mutate(picked);
                }}
            >
                保存
            </button>
            {save.isError && <p className="text-red-600">{save.error.message}</p>}
        </section>
    );
};
