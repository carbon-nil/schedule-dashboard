package schedule

import java.time.LocalDateTime

class AvailabilitySuite extends munit.FunSuite:
    // 2026-10-05 は月曜日
    private def jst(hh: Int, mm: Int = 0, day: Int = 5): Long =
        LocalDateTime.of(2026, 10, day, hh, mm).atZone(Availability.Zone).toInstant.toEpochMilli
    private def iv(h1: Int, m1: Int, h2: Int, m2: Int): Interval = Interval(jst(h1, m1), jst(h2, m2))

    private val day = Interval(jst(0), jst(0, day = 6))
    private val monday9to18 = Seq(WeeklyWindow(0, 9 * 60, 18 * 60))

    test("A05: 重なる予定は 1 つの busy に結合する") {
        assertEquals(Availability.merge(Seq(iv(10, 0, 11, 0), iv(10, 30, 12, 0))), Seq(iv(10, 0, 12, 0)))
    }

    test("A06: 隣接する区間は結合する") {
        assertEquals(Availability.merge(Seq(iv(15, 0, 16, 0), iv(14, 0, 15, 0))), Seq(iv(14, 0, 16, 0)))
    }

    test("設計書 7 章の計算例: 空き合計 330 分で、重なりを二重控除しない") {
        val busy = Seq(iv(10, 0, 11, 0), iv(10, 30, 12, 0), iv(14, 0, 15, 30))
        val free = Availability.free(monday9to18, busy, day)
        assertEquals(free, Seq(iv(9, 0, 10, 0), iv(12, 0, 14, 0), iv(15, 30, 18, 0)))
        assertEquals(free.map(_.minutes).sum, 330L)
    }

    test("曜日が一致する日だけ展開し、1440 分は翌日 0 時になる") {
        val windows = Seq(WeeklyWindow(0, 22 * 60, 1440), WeeklyWindow(1, 0, 60))
        val twoDays = Interval(jst(0), jst(0, day = 7))
        assertEquals(
            Availability.expand(windows, twoDays),
            Seq(Interval(jst(22), jst(0, day = 6)), Interval(jst(0, day = 6), jst(1, day = 6)))
        )
    }

    test("範囲の外は切り詰め、範囲外の busy は無視する") {
        val range = iv(12, 0, 13, 0)
        val free = Availability.free(monday9to18, Seq(iv(8, 0, 12, 30), iv(19, 0, 20, 0)), range)
        assertEquals(free, Seq(iv(12, 30, 13, 0)))
    }

    test("busy が活動可能時間を覆えば空きはない") {
        assertEquals(Availability.free(monday9to18, Seq(iv(8, 0, 19, 0)), day), Seq.empty)
    }
