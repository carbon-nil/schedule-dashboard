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

export const minutesBetween = (startIso: string, endIso: string): number =>
    Math.round((new Date(endIso).getTime() - new Date(startIso).getTime()) / 60000);

/** 半開区間 [start, end) が重なるか。隣接は重ならない (設計書 5.1)。 */
export const overlaps = (a: { startAt: string; endAt: string }, b: { startAt: string; endAt: string }): boolean =>
    new Date(a.startAt) < new Date(b.endAt) && new Date(b.startAt) < new Date(a.endAt);
