export interface CalendarEvent {
    id: string;
    calendarId: string;
    title: string;
    startAt: string;
    endAt: string;
    allDay: boolean;
}

/** title と todoistTaskId はどちらか一方だけ入る。 */
export interface Block {
    id: string;
    title: string | null;
    todoistTaskId: string | null;
    startAt: string;
    endAt: string;
    version: number;
}

export interface Task {
    id: string;
    title: string;
    projectId: string;
    estimateMinutes: number | null;
    deadlineDate: string | null;
    isRecurring: boolean;
    url: string;
}

export interface Day {
    date: string;
    events: CalendarEvent[];
    blocks: Block[];
    tasks: Task[];
    fetchedAt: string;
    errors: { source: string; target: string; message: string }[];
}

export interface CalendarEntry {
    id: string;
    title: string;
    accessRole: string;
    primary: boolean;
}

/** weekday は月曜=0、日曜=6。分は日本時間の 0 時からの分数。 */
export interface WeeklyWindow {
    weekday: number;
    startMinute: number;
    endMinute: number;
}

export interface Settings {
    settingsVersion: number;
    selectedCalendarIds: string[];
    weeklyWindows: WeeklyWindow[];
}

export class ApiError extends Error {
    constructor(
        readonly status: number,
        message: string,
    ) {
        super(message);
    }
}

/** サーバーのエラー形式 {error: {code, message}} を ApiError にして投げる。 */
export const api = async <T>(method: string, path: string, body?: unknown): Promise<T> => {
    const res = await fetch(path, {
        method,
        headers: body === undefined ? undefined : new Headers([["Content-Type", "application/json"]]),
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (res.status === 204) return undefined as T;
    const json: unknown = await res.json().catch(() => null);
    if (!res.ok) {
        const message = (json as { error?: { message?: string } } | null)?.error?.message ?? `HTTP ${res.status}`;
        throw new ApiError(res.status, message);
    }
    return json as T;
};
