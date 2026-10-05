// 表示と日付の境界は日本時間で固定する。ブラウザのタイムゾーン設定には頼らない (設計書 3 章)。
// ponytail: Asia/Tokyo に夏時間がないので、+9 時間のずらしで済ませている。ほかの地域を扱うなら Temporal に置き換える
const JST_OFFSET_MS = 9 * 60 * 60 * 1000;

/** 今日の日付 (日本時間) を YYYY-MM-DD で返す。 */
export const todayJst = (): string => new Date(Date.now() + JST_OFFSET_MS).toISOString().slice(0, 10);

export const addDays = (date: string, days: number): string => {
    const d = new Date(`${date}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() + days);
    return d.toISOString().slice(0, 10);
};

/** UTC の ISO 文字列を、datetime-local 入力用の日本時間 "YYYY-MM-DDTHH:mm" にする。 */
export const toJstInput = (iso: string): string =>
    new Date(new Date(iso).getTime() + JST_OFFSET_MS).toISOString().slice(0, 16);

/** 日本時間の "YYYY-MM-DDTHH:mm" やオフセットなしの日時を UTC の ISO 文字列にする。オフセット付きはそのまま解釈する。 */
export const toUtcIso = (value: string): string => {
    const hasOffset = /(Z|[+-]\d{2}:\d{2})$/.test(value);
    return new Date(hasOffset ? value : `${value}+09:00`).toISOString().replace(".000Z", "Z");
};

export const formatJstTime = (iso: string): string => toJstInput(iso).slice(11, 16);

/** 日付ごとに区切った区間の終了時刻。翌日 0 時なら "24:00" と出す。 */
export const formatJstEnd = (iso: string): string => {
    const t = formatJstTime(iso);
    return t === "00:00" ? "24:00" : t;
};

/** 区間を日本時間の日付境界で分ける。日をまたぐ空きを「22:00–02:00」のように 1 日へ押し込まない。 */
export const splitByJstDay = (i: { start: string; end: string }): { day: string; start: string; end: string }[] => {
    const day = toJstInput(i.start).slice(0, 10);
    const nextMidnight = toUtcIso(`${addDays(day, 1)}T00:00`);
    return new Date(nextMidnight) < new Date(i.end)
        ? [{ day, start: i.start, end: nextMidnight }, ...splitByJstDay({ start: nextMidnight, end: i.end })]
        : [{ day, start: i.start, end: i.end }];
};

/** "MM/DD HH:mm" (日本時間)。 */
export const formatJst = (iso: string): string => {
    const s = toJstInput(iso);
    return `${s.slice(5, 7)}/${s.slice(8, 10)} ${s.slice(11, 16)}`;
};

/** 分数を "HH:mm" にする (活動可能時間の表示用)。 */
export const minutesToHm = (minutes: number): string =>
    `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;

export const hmToMinutes = (hm: string): number => {
    const [h, m] = hm.split(":").map(Number);
    return h * 60 + m;
};

export const minutesBetween = (startIso: string, endIso: string): number =>
    Math.round((new Date(endIso).getTime() - new Date(startIso).getTime()) / 60000);

/** 半開区間 [start, end) が重なるか。隣接は重ならない (設計書 5.1)。 */
export const overlaps = (a: { startAt: string; endAt: string }, b: { startAt: string; endAt: string }): boolean =>
    new Date(a.startAt) < new Date(b.endAt) && new Date(b.startAt) < new Date(a.endAt);
