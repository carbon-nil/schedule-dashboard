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

    /** Google は events だけを返すモック。failing なら 503 を返す。 */
    private def setup(failing: Boolean = false) =
        for
            xa <- IO(Files.createTempFile("api", ".db").toString).map(Db.transactor)
            _ <- Db.migrate(xa)
            cache <- Ref.of[IO, Option[(String, Long)]](Some(("at", Long.MaxValue)))
            client = Client.fromHttpApp[IO](HttpApp[IO] { _ =>
                if failing then ServiceUnavailable()
                else
                    Ok(
                        """{"items":[{"id":"e","summary":"会議",
                        "start":{"dateTime":"2026-10-05T10:00:00+09:00"},"end":{"dateTime":"2026-10-05T11:00:00+09:00"}}]}"""
                    )
            })
            google = Google(AuthSuite.cfg, client, IO.pure(Some("rt")), cache)
            _ <- sql"INSERT INTO selected_calendars VALUES ('primary')".update.run.transact(xa)
        yield Api(xa, google).routes.orNotFound

    private def send(app: HttpApp[IO], method: Method, uri: Uri, body: Json = Json.Null) =
        app.run(Request[IO](method, uri).withEntity(body)).flatMap(res => res.as[Json].attempt.map(res.status -> _))

    private val newBlock =
        j("""{"requestId":"b1","title":"レポート","startAt":"2026-10-05T05:00:00Z","endAt":"2026-10-05T06:30:00Z"}""")

    test("Block を作り、同じ requestId は 409、終了が開始以前なら 422") {
        for
            app <- setup()
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
            app <- setup()
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
            app <- setup()
            _ <- send(app, Method.POST, uri"/api/blocks", newBlock)
            (status, day) <- send(app, Method.GET, uri"/api/day?date=2026-10-05")
            c = day.toOption.get.hcursor
        yield
            assertEquals(status, Status.Ok)
            assertEquals(c.downField("events").downArray.get[String]("title"), Right("会議"))
            assertEquals(c.downField("blocks").downArray.get[String]("title"), Right("レポート"))
            assertEquals(c.downField("errors").values.map(_.size), Some(0))
    }

    test("Google が取得できなくても Block は返し、失敗を errors に載せる") {
        for
            app <- setup(failing = true)
            _ <- send(app, Method.POST, uri"/api/blocks", newBlock)
            (status, day) <- send(app, Method.GET, uri"/api/day?date=2026-10-05")
            c = day.toOption.get.hcursor
        yield
            assertEquals(status, Status.Ok)
            assertEquals(c.downField("blocks").values.map(_.size), Some(1))
            assertEquals(c.downField("errors").downArray.get[String]("target"), Right("primary"))
    }

    test("設定は expectedVersion が古ければ 409") {
        for
            app <- setup()
            (ok, body) <- send(
                app,
                Method.PUT,
                uri"/api/settings",
                j("""{"expectedVersion":1,"selectedCalendarIds":["a","b"]}""")
            )
            (stale, _) <- send(
                app,
                Method.PUT,
                uri"/api/settings",
                j("""{"expectedVersion":1,"selectedCalendarIds":["c"]}""")
            )
        yield
            assertEquals(ok, Status.Ok)
            assertEquals(
                body.toOption.flatMap(_.hcursor.get[List[String]]("selectedCalendarIds").toOption),
                Some(List("a", "b"))
            )
            assertEquals(stale, Status.Conflict)
    }
