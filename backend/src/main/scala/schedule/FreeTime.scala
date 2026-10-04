package schedule

import doobie.*
import doobie.implicits.*

object FreeTime:
    def windows: ConnectionIO[List[WeeklyWindow]] =
        sql"SELECT weekday, start_minute, end_minute FROM weekly_windows ORDER BY weekday, start_minute"
            .query[WeeklyWindow]
            .to[List]
