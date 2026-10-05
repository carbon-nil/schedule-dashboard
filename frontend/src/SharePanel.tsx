import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import * as React from "react";
import { api, type PublicAvailability, type Share } from "./api";
import { addDays, formatJst, todayJst, toUtcIso } from "./time";

/** 共有リンクの作成と失効 (設計書 4.2)。初期値は今日から 7 日間、期限は期間の終了。URL は作成応答でだけ見える。 */
export const SharePanel = () => {
    const queryClient = useQueryClient();
    const [from, setFrom] = React.useState(todayJst);
    const [to, setTo] = React.useState(() => addDays(todayJst(), 7));
    const [minMinutes, setMinMinutes] = React.useState(30);
    const [createdUrl, setCreatedUrl] = React.useState<string | null>(null);
    const shares = useQuery({ queryKey: ["shares"], queryFn: () => api<Share[]>("GET", "/api/shares") });

    // 終了日の 24:00 まで。期限はサーバーの初期値 (期間の終了) に任せる
    const body = () => ({
        rangeStart: toUtcIso(`${from}T00:00`),
        rangeEnd: toUtcIso(`${addDays(to, 1)}T00:00`),
        minFreeMinutes: minMinutes,
    });
    const preview = useMutation({
        mutationFn: () => api<PublicAvailability>("POST", "/api/shares/preview", body()),
    });
    const create = useMutation({
        mutationFn: () =>
            api<Share & { url: string }>("POST", "/api/shares", { requestId: crypto.randomUUID(), ...body() }),
        onSuccess: s => {
            setCreatedUrl(s.url);
            preview.reset();
        },
        onSettled: () => queryClient.invalidateQueries({ queryKey: ["shares"] }),
    });
    const revoke = useMutation({
        mutationFn: (s: Share) => api<Share>("POST", `/api/shares/${s.id}/revoke`, { expectedVersion: s.version }),
        onSettled: () => queryClient.invalidateQueries({ queryKey: ["shares"] }),
    });
    const error = preview.error ?? create.error ?? revoke.error;
    const state = (s: Share) => {
        if (s.revokedAt !== null) return " (失効済み)";
        if (s.expiresAt < new Date().toISOString()) return " (期限切れ)";
        return ` 期限 ${formatJst(s.expiresAt)}`;
    };

    return (
        <section className="space-y-2 rounded border p-3">
            <h2 className="font-bold">空き時間を共有</h2>
            <div className="flex flex-wrap items-center gap-1 text-sm">
                <input
                    type="date"
                    className="rounded border px-1 py-1"
                    value={from}
                    onChange={e => {
                        if (e.target.value !== "") setFrom(e.target.value);
                    }}
                />
                –
                <input
                    type="date"
                    className="rounded border px-1 py-1"
                    value={to}
                    onChange={e => {
                        if (e.target.value !== "") setTo(e.target.value);
                    }}
                />
                <label className="flex items-center gap-1">
                    最小
                    <input
                        type="number"
                        min={1}
                        max={1440}
                        step={5}
                        className="w-16 rounded border px-1 py-1"
                        value={minMinutes}
                        onChange={e => {
                            setMinMinutes(Number(e.target.value));
                        }}
                    />
                    分
                </label>
            </div>
            <div className="flex gap-2">
                <button
                    type="button"
                    disabled={preview.isPending}
                    className="rounded border px-3 py-1"
                    onClick={() => {
                        setCreatedUrl(null);
                        preview.mutate();
                    }}
                >
                    プレビュー
                </button>
                <button
                    type="button"
                    disabled={create.isPending}
                    className="rounded bg-blue-600 px-3 py-1 text-white disabled:opacity-50"
                    onClick={() => {
                        create.mutate();
                    }}
                >
                    リンクを作る
                </button>
            </div>
            {error !== null && <p className="text-red-600">{error.message}</p>}
            {preview.data !== undefined && (
                <ul className="text-sm">
                    {preview.data.freeIntervals.length === 0 && <li>相手に見える空きはありません。</li>}
                    {preview.data.freeIntervals.map(i => (
                        <li key={i.start}>
                            {formatJst(i.start)}–{formatJst(i.end)}
                        </li>
                    ))}
                </ul>
            )}
            {createdUrl !== null && (
                <p className="text-sm">
                    この URL は今だけ表示されます:{" "}
                    <input readOnly className="w-full rounded border px-1" value={createdUrl} />
                </p>
            )}
            <ul className="space-y-1 text-sm">
                {shares.data?.map(s => (
                    <li key={s.id} className="flex items-center gap-2">
                        <span className="flex-1">
                            {formatJst(s.rangeStart)}–{formatJst(s.rangeEnd)}
                            {state(s)}
                        </span>
                        {s.revokedAt === null && (
                            <button
                                type="button"
                                className="rounded border border-red-600 px-2 text-red-600"
                                onClick={() => {
                                    revoke.mutate(s);
                                }}
                            >
                                失効
                            </button>
                        )}
                    </li>
                ))}
            </ul>
        </section>
    );
};
