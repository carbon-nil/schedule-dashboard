import FullCalendar from "@fullcalendar/react";
import interactionPlugin from "@fullcalendar/react/interaction";
import jaLocale from "@fullcalendar/react/locales/ja";
import classicThemePlugin from "@fullcalendar/react/themes/classic";
import timeGridPlugin from "@fullcalendar/react/timegrid";
import "@fullcalendar/react/skeleton.css";
import "@fullcalendar/react/themes/classic/theme.css";
import "@fullcalendar/react/themes/classic/palette.css";
import { type Day } from "./api";
import { toUtcIso } from "./time";

interface Props {
    day: Day;
    onSelectRange: (startAt: string, endAt: string) => void;
    onSelectBlock: (id: string) => void;
    onMoveBlock: (id: string, startAt: string, endAt: string, revert: () => void) => void;
}

/** 固定予定は読み取り専用の灰色、Block は青で表示する。PC ではドラッグで選択・移動・伸縮できる。 */
export const Timeline = ({ day, onSelectRange, onSelectBlock, onMoveBlock }: Props) => {
    const events = [
        ...day.events.map(e => ({
            id: `event:${e.calendarId}:${e.id}`,
            title: e.title,
            start: e.startAt,
            end: e.endAt,
            allDay: e.allDay,
            editable: false,
            color: "#9ca3af",
        })),
        ...day.blocks.map(b => ({
            id: b.id,
            title: b.title,
            start: b.startAt,
            end: b.endAt,
            editable: true,
            color: "#2563eb",
        })),
    ];

    return (
        <FullCalendar
            // 日付が変わったら作り直して initialDate を反映する
            key={day.date}
            plugins={[timeGridPlugin, interactionPlugin, classicThemePlugin]}
            locale={jaLocale}
            initialView="timeGridDay"
            initialDate={day.date}
            timeZone="Asia/Tokyo"
            headerToolbar={false}
            height="auto"
            nowIndicator
            selectable
            selectMirror
            events={events}
            select={info => {
                onSelectRange(toUtcIso(info.startStr), toUtcIso(info.endStr));
            }}
            eventClick={info => {
                if (!info.event.id.startsWith("event:")) onSelectBlock(info.event.id);
            }}
            eventDrop={info => {
                onMoveBlock(info.event.id, toUtcIso(info.event.startStr), toUtcIso(info.event.endStr), info.revert);
            }}
            eventResize={info => {
                onMoveBlock(info.event.id, toUtcIso(info.event.startStr), toUtcIso(info.event.endStr), info.revert);
            }}
        />
    );
};
