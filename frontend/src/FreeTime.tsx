import { type Day } from "./api";
import { formatJstTime, minutesBetween } from "./time";

/** 「1 日の空き」と「今からの空き」を分けて出す (設計書 7 章)。共有の最小時間フィルタは本人には掛けない。 */
export const FreeTime = ({ day }: { day: Day }) => {
    if (day.freeIntervals === null) return <p className="text-red-600">空き時間は現在確認できません。</p>;
    const now = new Date().toISOString();
    const fromNow = day.freeIntervals
        .filter(i => i.end > now)
        .map(i => ({ start: i.start > now ? i.start : now, end: i.end }));
    const total = (xs: { start: string; end: string }[]) => xs.reduce((n, i) => n + minutesBetween(i.start, i.end), 0);
    return (
        <section className="rounded border p-3">
            <h2 className="font-bold">空き時間</h2>
            <p className="text-sm">
                1 日の空き {total(day.freeIntervals)} 分 / 今からの空き {total(fromNow)} 分
            </p>
            {day.freeIntervals.length === 0 && (
                <p className="text-sm text-gray-600">空きはありません。活動可能時間を設定してください。</p>
            )}
            <ul className="text-sm">
                {day.freeIntervals.map(i => (
                    <li key={i.start}>
                        {formatJstTime(i.start)}–{formatJstTime(i.end)}
                    </li>
                ))}
            </ul>
        </section>
    );
};
