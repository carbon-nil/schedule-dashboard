import { type Block } from "./api";
import { minutesBetween, toJstInput } from "./time";

/** Block のフォームの入力値。blockId が null なら新規作成。 */
export interface Draft {
    blockId: string | null;
    title: string;
    start: string; // 日本時間の "YYYY-MM-DDTHH:mm"
    minutes: number;
}

export const emptyDraft = (date: string): Draft => ({ blockId: null, title: "", start: `${date}T09:00`, minutes: 60 });

export const draftFor = (block: Block): Draft => ({
    blockId: block.id,
    title: block.title,
    start: toJstInput(block.startAt),
    minutes: minutesBetween(block.startAt, block.endAt),
});
