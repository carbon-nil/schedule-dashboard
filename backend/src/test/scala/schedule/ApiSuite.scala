package schedule

import cats.effect.{IO, Ref}
import doobie.implicits.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*

import java.nio.file.Files

class ApiSuite extends munit.CatsEffectSuite:
    private def j(s: String): Json = parse(s).fold(throw _, identity)

    private val todoistTask =
        """{"id":"t1","content":"レポート","project_id":"p","duration":{"amount":90,"unit":"minute"},"deadline":null,"due":null}"""
    private val recurringTask =
        """{"id":"t2","content":"毎週","project_id":"p","duration":null,"deadline":null,"due":{"is_recurring":true}}"""

    /** Google は events、Todoist はタスク 2 件を返すモック。failing なら両方 503 を返す。 */
    private def setup(failing: Boolean = false) =
        for
            xa <- IO(Files.createTempFile("api", ".db").toString).map(Db.transactor)
            _ <- Db.migrate(xa)
            cache <- Ref.of[IO, Option[(String, Long)]](Some(("at", Long.MaxValue)))
            closed <- Ref.of[IO, List[String]](Nil)
            client = Client.fromHttpApp[IO](HttpApp[IO] { req =>
                val path = req.uri.path.renderString
                if failing then ServiceUnavailable()
                else if path == "/api/v1/tasks" then
                    Ok(s"""{"results":[$todoistTask,$recurringTask],"next_cursor":null}""")
                else if path == "/api/v1/tasks/t1" then Ok(todoistTask)
                else if path == "/api/v1/tasks/t2" then Ok(recurringTask)
                else if path.startsWith("/api/v1/tasks/") && path.endsWith("/close") then
                    closed.update(_ :+ path) *> Ok("null")
                else if path.startsWith("/api/v1/tasks/") then NotFound()
                else
                    Ok(
                        """{"items":[{"id":"e","summary":"会議",
                        "start":{"dateTime":"2026-10-05T10:00:00+09:00"},"end":{"dateTime":"2026-10-05T11:00:00+09:00"}}]}"""
                    )
            })
            google = Google(AuthSuite.cfg, client, IO.pure(Some("rt")), cache)
            todoist = Todoist(Some("token"), client)
            _ <- sql"INSERT INTO selected_calendars VALUES ('primary')".update.run.transact(xa)
            // 月曜 9:00〜18:00
            _ <- sql"INSERT INTO weekly_windows VALUES ('w', 0, 540, 1080)".update.run.transact(xa)
        yield (Api(xa, google, todoist).routes.orNotFound, closed)

    private def send(app: HttpApp[IO], method: Method, uri: Uri, body: Json = Json.Null) =
        app.run(Request[IO](method, uri).withEntity(body)).flatMap(res => res.as[Json].attempt.map(res.status -> _))

    private val newBlock =
        j("""{"requestId":"b1","title":"レポート","startAt":"2026-10-05T05:00:00Z","endAt":"2026-10-05T06:30:00Z"}""")

    test("Block を作り、同じ requestId は 409、終了が開始以前なら 422") {
        for
            (app, _) <- setup()
            (created, body) <- send(app, Method.POST, uri"/api/blocks", newBlock)
            (dup, _) <- send(app, Method.POST, uri"/api/blocks", newBlock)
            (bad, _) <- send(
                app,
                Method.POST,
                uri"/api/blocks",
                j("""{"requestId":"b2","title":"x","startAt":"2026-10-05T06:00:00Z","endAt":"2026-10-05T06:00:00Z"}""")
            )
        yield
            assertEquals(created, Status.Created)
            assertEquals(body.toOption.flatMap(_.hcursor.get[Int]("version").toOption), Some(1))
            assertEquals(dup, Status.Conflict)
            assertEquals(bad, Status.UnprocessableContent)
    }

    test("古い version での移動・削除は 409。正しい version なら移動でき、削除で一覧から消える (A04)") {
        val moved = j("""{"startAt":"2026-10-05T07:00:00Z","endAt":"2026-10-05T08:00:00Z","expectedVersion":1}""")
        for
            (app, _) <- setup()
            _ <- send(app, Method.POST, uri"/api/blocks", newBlock)
            (ok, body) <- send(app, Method.PATCH, uri"/api/blocks/b1", moved)
            (stale, _) <- send(app, Method.PATCH, uri"/api/blocks/b1", moved)
            (staleDelete, _) <- send(app, Method.DELETE, uri"/api/blocks/b1", j("""{"expectedVersion":1}"""))
            (deleted, _) <- send(app, Method.DELETE, uri"/api/blocks/b1", j("""{"expectedVersion":2}"""))
            (_, day) <- send(app, Method.GET, uri"/api/day?date=2026-10-05")
        yield
            assertEquals(ok, Status.Ok)
            assertEquals(body.toOption.flatMap(_.hcursor.get[String]("startAt").toOption), Some("2026-10-05T07:00:00Z"))
            assertEquals(stale, Status.Conflict)
            assertEquals(staleDelete, Status.Conflict)
            assertEquals(deleted, Status.NoContent)
            assertEquals(day.toOption.flatMap(_.hcursor.downField("blocks").values.map(_.size)), Some(0))
    }

    test("1 日分の予定と Block を返す") {
        for
            (app, _) <- setup()
            _ <- send(app, Method.POST, uri"/api/blocks", newBlock)
            (status, day) <- send(app, Method.GET, uri"/api/day?date=2026-10-05")
            c = day.toOption.get.hcursor
        yield
            assertEquals(status, Status.Ok)
            assertEquals(c.downField("events").downArray.get[String]("title"), Right("会議"))
            assertEquals(c.downField("blocks").downArray.get[String]("title"), Right("レポート"))
            assertEquals(c.downField("errors").values.map(_.size), Some(0))
    }

    test("Google と Todoist が取得できなくても Block は返し、失敗を errors に載せる (A14)") {
        for
            (app, _) <- setup(failing = true)
            _ <- send(app, Method.POST, uri"/api/blocks", newBlock)
            (status, day) <- send(app, Method.GET, uri"/api/day?date=2026-10-05")
            c = day.toOption.get.hcursor
        yield
            assertEquals(status, Status.Ok)
            assertEquals(c.downField("blocks").values.map(_.size), Some(1))
            assertEquals(c.downField("errors").values.map(_.size), Some(2))
            assertEquals(c.downField("errors").downArray.get[String]("source"), Right("google"))
            assertEquals(c.downField("tasks").values.map(_.size), Some(0))
    }

    test("タスク参照の Block を同じタスクに 2 つ作れる。参照とタイトルの両方や両方なしは 422 (A03)") {
        def task(id: String, h: Int) =
            j(s"""{"requestId":"$id","todoistTaskId":"t1","startAt":"2026-10-05T0${h}:00:00Z","endAt":"2026-10-05T0${h}:30:00Z"}""")
        for
            (app, _) <- setup()
            (a, body) <- send(app, Method.POST, uri"/api/blocks", task("k1", 1))
            (b, _) <- send(app, Method.POST, uri"/api/blocks", task("k2", 2))
            (both, _) <- send(
                app,
                Method.POST,
                uri"/api/blocks",
                j(
                    """{"requestId":"k3","title":"x","todoistTaskId":"t1","startAt":"2026-10-05T03:00:00Z","endAt":"2026-10-05T04:00:00Z"}"""
                )
            )
            (neither, _) <- send(
                app,
                Method.POST,
                uri"/api/blocks",
                j("""{"requestId":"k4","startAt":"2026-10-05T03:00:00Z","endAt":"2026-10-05T04:00:00Z"}""")
            )
            (_, day) <- send(app, Method.GET, uri"/api/day?date=2026-10-05")
            c = day.toOption.get.hcursor
        yield
            assertEquals(a, Status.Created)
            assertEquals(body.toOption.flatMap(_.hcursor.get[Option[String]]("title").toOption), Some(None))
            assertEquals(b, Status.Created)
            assertEquals(both, Status.UnprocessableContent)
            assertEquals(neither, Status.UnprocessableContent)
            assertEquals(c.downField("blocks").values.map(_.size), Some(2))
            assertEquals(c.downField("tasks").downArray.get[Int]("estimateMinutes"), Right(90))
    }

    test("完了は単件取得のあと close を呼ぶ。繰り返しは 422 で close しない (A26)。完了・削除済みは 404") {
        for
            (app, closed) <- setup()
            (ok, body) <- send(app, Method.POST, uri"/api/tasks/t1/complete")
            (recurring, _) <- send(app, Method.POST, uri"/api/tasks/t2/complete")
            (gone, _) <- send(app, Method.POST, uri"/api/tasks/t9/complete")
            paths <- closed.get
        yield
            assertEquals(ok, Status.Ok)
            assertEquals(body.toOption.flatMap(_.hcursor.get[Boolean]("completed").toOption), Some(true))
            assertEquals(recurring, Status.UnprocessableContent)
            assertEquals(gone, Status.NotFound)
            assertEquals(paths, List("/api/v1/tasks/t1/close"))
    }

    test("設定は expectedVersion が古ければ 409") {
        for
            (app, _) <- setup()
            (ok, body) <- send(
                app,
                Method.PUT,
                uri"/api/settings",
                j(
                    """{"expectedVersion":1,"selectedCalendarIds":["a","b"],
                      "weeklyWindows":[{"weekday":1,"startMinute":780,"endMinute":1080},{"weekday":1,"startMinute":540,"endMinute":720}]}"""
                )
            )
            (stale, _) <- send(
                app,
                Method.PUT,
                uri"/api/settings",
                j("""{"expectedVersion":1,"selectedCalendarIds":["c"],"weeklyWindows":[]}""")
            )
            (overlap, _) <- send(
                app,
                Method.PUT,
                uri"/api/settings",
                j(
                    """{"expectedVersion":2,"selectedCalendarIds":[],
                      "weeklyWindows":[{"weekday":1,"startMinute":540,"endMinute":720},{"weekday":1,"startMinute":700,"endMinute":800}]}"""
                )
            )
            (_, got) <- send(app, Method.GET, uri"/api/settings")
        yield
            assertEquals(ok, Status.Ok)
            assertEquals(
                body.toOption.flatMap(_.hcursor.get[List[String]]("selectedCalendarIds").toOption),
                Some(List("a", "b"))
            )
            assertEquals(stale, Status.Conflict)
            assertEquals(overlap, Status.UnprocessableContent)
            assertEquals(
                got.toOption.flatMap(_.hcursor.downField("weeklyWindows").downArray.get[Int]("startMinute").toOption),
                Some(540)
            )
    }
