import { type Block, type Task } from "./api";
import { minutesBetween, toJstInput } from "./time";

/** Block のフォームの入力値。blockId が null なら新規作成。taskId があれば title は使わない。 */
export interface Draft {
    blockId: string | null;
    title: string;
    taskId: string | null;
    start: string; // 日本時間の "YYYY-MM-DDTHH:mm"
    minutes: number;
}

export const emptyDraft = (date: string): Draft => ({
    blockId: null,
    title: "",
    taskId: null,
    start: `${date}T09:00`,
    minutes: 60,
});

export const draftFor = (block: Block): Draft => ({
    blockId: block.id,
    title: block.title ?? "",
    taskId: block.todoistTaskId,
    start: toJstInput(block.startAt),
    minutes: minutesBetween(block.startAt, block.endAt),
});

/** Block の表示名。タスク参照は進行中の一覧から引き、見つからなければ Todoist 側で完了・削除されたと分かる形にする (A22)。 */
export const blockLabel = (block: Block, tasks: Task[]): string => {
    if (block.todoistTaskId === null) return block.title ?? "";
    return tasks.find(t => t.id === block.todoistTaskId)?.title ?? "(Todoist で完了または削除されたタスク)";
};
