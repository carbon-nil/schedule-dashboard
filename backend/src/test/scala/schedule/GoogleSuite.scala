package schedule

import cats.effect.{IO, Ref}
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*

import java.time.LocalDateTime

class GoogleSuite extends munit.CatsEffectSuite:
    private val cfg = AuthSuite.cfg

    private def jst(day: Int, hh: Int): Long =
        LocalDateTime.of(2026, 10, day, hh, 0).atZone(Availability.Zone).toInstant.toEpochMilli
    private val range = Interval(jst(5, 0), jst(6, 0))

    /** Google 側をモックし、受けたリクエストのパスを記録する。 */
    private def google(app: Request[IO] => IO[Response[IO]], refresh: Option[String] = Some("rt")) =
        for
            seen <- Ref.of[IO, List[String]](Nil)
            cache <- Ref.of[IO, Option[(String, Long)]](None)
            client = Client.fromHttpApp[IO](HttpApp[IO](req => seen.update(_ :+ req.uri.path.renderString) *> app(req)))
        yield (Google(cfg, client, IO.pure(refresh), cache), seen)

    private val token = Ok("""{"access_token":"at","expires_in":3600}""")

    test("ページを最後までたどり、終日予定はカレンダーのタイムゾーンで区間にし、取り消し済みは除く") {
        val page1 =
            """{"timeZone":"Asia/Tokyo","nextPageToken":"p2","items":[
              {"id":"a","summary":"講義","start":{"dateTime":"2026-10-05T10:00:00+09:00"},"end":{"dateTime":"2026-10-05T11:30:00+09:00"}},
              {"id":"x","status":"cancelled"}]}"""
        val page2 =
            """{"timeZone":"Asia/Tokyo","items":[
              {"id":"b","start":{"date":"2026-10-05"},"end":{"date":"2026-10-06"}}]}"""
        for
            (g, seen) <- google { req =>
                if req.uri.path.renderString == "/token" then token
                else if req.params.get("pageToken").contains("p2") then Ok(page2)
                else Ok(page1)
            }
            r <- g.events("primary", range)
            _ <- g.events("primary", range)
            paths <- seen.get
        yield
            assertEquals(
                r,
                Right(
                    List(
                        CalendarEvent("a", "primary", "講義", jst(5, 10), jst(5, 10) + 90 * 60000, false),
                        CalendarEvent("b", "primary", "(無題)", jst(5, 0), jst(6, 0), true)
                    )
                )
            )
            assertEquals(paths.count(_ == "/token"), 1, "アクセストークンはキャッシュする")
    }

    test("5xx は 1 回だけ再試行し、それでも失敗なら Failed を返す") {
        for
            (g, seen) <- google(req => if req.uri.path.renderString == "/token" then token else InternalServerError())
            r <- g.events("primary", range)
            paths <- seen.get
        yield
            assert(r.isLeft)
            assertEquals(paths.count(_.endsWith("/events")), 2)
    }

    test("refresh token がなければ NotConnected で、Google を呼ばない") {
        for
            (g, seen) <- google(_ => Ok("{}"), refresh = None)
            r <- g.events("primary", range)
            paths <- seen.get
        yield
            assertEquals(r, Left(GoogleError.NotConnected))
            assertEquals(paths, Nil)
    }

    test("カレンダー一覧は reader 以上だけを返す") {
        val list =
            """{"items":[{"id":"me","summary":"自分","accessRole":"owner","primary":true},
              {"id":"fb","summary":"他人","accessRole":"freeBusyReader"}]}"""
        for
            (g, _) <- google(req => if req.uri.path.renderString == "/token" then token else Ok(list))
            r <- g.calendars
        yield assertEquals(r, Right(List(CalendarEntry("me", "自分", "owner", true))))
    }
