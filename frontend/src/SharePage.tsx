import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query";
import { api, ApiError, type Interval, type PublicAvailability } from "./api";
import { formatJst, formatJstEnd, formatJstTime, splitByJstDay } from "./time";

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

export const SharePage = ({ token }: { token: string }) => (
    <QueryClientProvider client={queryClient}>
        <Availability token={token} />
    </QueryClientProvider>
);

/** 失効・期限切れ・不明は同じ表示 (A12)。取得できないときは空きを出さない。 */
const describe = (e: Error): string => {
    if (e instanceof ApiError && e.status === 404) return "このリンクは無効です。";
    if (e instanceof ApiError && e.status === 429) return "しばらく待ってから開き直してください。";
    return "現在確認できません。時間をおいて開き直してください。";
};

/** 公開ページ。日時だけを出し、理由や所有者の情報は出さない (設計書 4.2、10 章)。60 秒ごとに取り直す。 */
const Availability = ({ token }: { token: string }) => {
    const q = useQuery({
        queryKey: ["public", token],
        queryFn: () => api<PublicAvailability>("GET", `/api/public/availability/${token}`),
        refetchInterval: 60_000,
    });
    const byDay = (q.data?.freeIntervals ?? [])
        .flatMap(splitByJstDay)
        .reduce<Record<string, Interval[]>>((acc, p) => ({ ...acc, [p.day]: [...(acc[p.day] ?? []), p] }), {});

    return (
        <main className="mx-auto max-w-xl p-4">
            <h1 className="text-xl font-bold">空き時間</h1>
            <p className="text-sm text-gray-600">日本時間 (Asia/Tokyo)。予約を確定するものではありません。</p>
            {q.isError && <p className="mt-4 text-red-600">{describe(q.error)}</p>}
            {q.data !== undefined && !q.isError && (
                <>
                    <p className="text-sm text-gray-600">
                        {formatJst(q.data.rangeStart)}–{formatJst(q.data.rangeEnd)} / 計算時刻{" "}
                        {formatJst(q.data.computedAt)}
                    </p>
                    {q.data.freeIntervals.length === 0 && <p className="mt-4">この期間に空きはありません。</p>}
                    {Object.entries(byDay).map(([day, intervals]) => (
                        <section key={day} className="mt-4">
                            <h2 className="font-bold">{day}</h2>
                            <ul>
                                {intervals.map(i => (
                                    <li key={i.start}>
                                        {formatJstTime(i.start)}–{formatJstEnd(i.end)}
                                    </li>
                                ))}
                            </ul>
                        </section>
                    ))}
                </>
            )}
        </main>
    );
};
