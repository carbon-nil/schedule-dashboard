package schedule

import java.time.{Instant, LocalDate, ZoneId}

/** 半開区間 [start, end)。単位は UTC の epoch ミリ秒。 */
final case class Interval(start: Long, end: Long):
    def minutes: Long = (end - start) / 60000

/** 曜日別の活動可能時間。weekday は月曜=0、日曜=6。分は日本時間の 0 時からの分数。 */
final case class WeeklyWindow(weekday: Int, startMinute: Int, endMinute: Int)

/** 空き時間の計算 (設計書 7 章)。外部 API や DB に依存しない純粋関数だけを置く。 */
object Availability:
    val Zone: ZoneId = ZoneId.of("Asia/Tokyo")

    /** 曜日別の活動可能時間を range 内の実際の日時区間へ展開する。 */
    def expand(windows: Seq[WeeklyWindow], range: Interval): Seq[Interval] =
        val first = Instant.ofEpochMilli(range.start).atZone(Zone).toLocalDate
        val last = Instant.ofEpochMilli(range.end).atZone(Zone).toLocalDate
        val days = Iterator.iterate(first)(_.plusDays(1)).takeWhile(!_.isAfter(last)).toSeq
        val expanded = for
            day <- days
            w <- windows if w.weekday == day.getDayOfWeek.getValue - 1
        yield Interval(at(day, w.startMinute), at(day, w.endMinute))
        clip(expanded, range)

    private def at(day: LocalDate, minute: Int): Long =
        day.atStartOfDay(Zone).plusMinutes(minute.toLong).toInstant.toEpochMilli

    /** 区間を range へ切り詰め、長さ 0 の区間を除く。 */
    def clip(xs: Seq[Interval], range: Interval): Seq[Interval] =
        xs.map(i => Interval(i.start.max(range.start), i.end.min(range.end))).filter(i => i.start < i.end)

    /** 開始時刻でソートし、重複・隣接する区間を結合する。 */
    def merge(xs: Seq[Interval]): Seq[Interval] =
        xs.sortBy(_.start)
            .foldLeft(List.empty[Interval]) {
                case (last :: rest, i) if i.start <= last.end => Interval(last.start, last.end.max(i.end)) :: rest
                case (acc, i)                                 => i :: acc
            }
            .reverse

    /** from から busy を差し引く。busy は merge 済みである前提。 */
    def subtract(from: Seq[Interval], busy: Seq[Interval]): Seq[Interval] =
        from.flatMap { a =>
            val (rest, out) = busy.foldLeft((Option(a), List.empty[Interval])) {
                case ((Some(cur), out), b) if b.end > cur.start && b.start < cur.end =>
                    val left = Option.when(b.start > cur.start)(Interval(cur.start, b.start))
                    val right = Option.when(b.end < cur.end)(Interval(b.end, cur.end))
                    (right, left.toList ++ out)
                case (state, _) => state
            }
            (rest.toList ++ out).sortBy(_.start)
        }

    /** 活動可能時間から busy (Google の予定と Block) を差し引いた空き区間。 */
    def free(windows: Seq[WeeklyWindow], busy: Seq[Interval], range: Interval): Seq[Interval] =
        subtract(merge(expand(windows, range)), merge(clip(busy, range)))

    /** 公開表示だけの加工 (設計書 7 章 5): 開始を now に切り詰め、分単位に丸め、最小連続時間未満を除く。 */
    def publicFree(free: Seq[Interval], now: Long, minMinutes: Int): Seq[Interval] =
        val minute = 60000L
        free
            .map(i => Interval(i.start.max(now), i.end))
            .map(i => Interval((i.start + minute - 1) / minute * minute, i.end / minute * minute))
            .filter(i => i.end - i.start >= minMinutes * minute)
