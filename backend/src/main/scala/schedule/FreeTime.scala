package schedule

import cats.effect.IO
import doobie.*
import doobie.implicits.*

/** 本人画面と共有で同じ計算を使う (設計書 7 章)。busy は freeBusy と Block から作り、予定一覧からは作らない。 */
final class FreeTime(xa: Transactor[IO], google: Google):
    /** Google の取得に失敗したら空きを返さない。「全部空き」を作らないため (A13)。 */
    def compute(range: Interval): IO[Either[GoogleError, FreeTime.Result]] =
        for
            windows <- FreeTime.windows.transact(xa)
            ids <- sql"SELECT calendar_id FROM selected_calendars ORDER BY calendar_id"
                .query[String]
                .to[List]
                .transact(xa)
            blocks <- Blocks.inRange(range).transact(xa)
            busy <- google.freeBusy(ids, range)
        yield busy.map { (fetchedAt, googleBusy) =>
            val all = googleBusy ++ blocks.map(b => Interval(b.start, b.end))
            FreeTime.Result(Availability.free(windows, all, range).toList, fetchedAt)
        }

object FreeTime:
    final case class Result(free: List[Interval], externalFetchedAt: Long)

    def windows: ConnectionIO[List[WeeklyWindow]] =
        sql"SELECT weekday, start_minute, end_minute FROM weekly_windows ORDER BY weekday, start_minute"
            .query[WeeklyWindow]
            .to[List]
