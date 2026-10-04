package schedule

import cats.effect.{IO, Ref}
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*

class TodoistSuite extends munit.CatsEffectSuite:
    /** Todoist 側をモックし、受けたリクエストのパスを記録する。 */
    private def todoist(app: Request[IO] => IO[Response[IO]], token: Option[String] = Some("tok")) =
        for
            seen <- Ref.of[IO, List[String]](Nil)
            client = Client.fromHttpApp[IO](HttpApp[IO](req => seen.update(_ :+ req.uri.path.renderString) *> app(req)))
        yield (Todoist(token, client), seen)

    test("cursor を最後までたどり、duration を分に、deadline を締切に、due.is_recurring を繰り返しにする") {
        val page1 =
            """{"results":[
              {"id":"a","content":"レポート","project_id":"p","duration":{"amount":1,"unit":"day"},
               "deadline":{"date":"2026-10-10","lang":"ja"},"due":{"date":"2026-10-09","is_recurring":false}}],
              "next_cursor":"c2"}"""
        val page2 =
            """{"results":[
              {"id":"b","content":"掃除","project_id":"p","duration":null,"deadline":null,
               "due":{"date":"2026-10-05","is_recurring":true,"string":"every week"}}],
              "next_cursor":null}"""
        for
            (t, seen) <- todoist(req => if req.params.get("cursor").contains("c2") then Ok(page2) else Ok(page1))
            r <- t.tasks
            paths <- seen.get
        yield
            assertEquals(
                r,
                Right(
                    List(
                        Task(
                            "a",
                            "レポート",
                            "p",
                            Some(1440),
                            Some("2026-10-10"),
                            false,
                            "https://app.todoist.com/app/task/a"
                        ),
                        Task("b", "掃除", "p", None, None, true, "https://app.todoist.com/app/task/b")
                    )
                )
            )
            assertEquals(paths.size, 2)
    }

    test("単件取得の 404 は None。5xx は 1 回だけ再試行して Failed") {
        for
            (t, seen) <- todoist(req =>
                if req.uri.path.renderString.endsWith("/gone") then NotFound() else BadGateway()
            )
            gone <- t.task("gone")
            down <- t.task("x")
            paths <- seen.get
        yield
            assertEquals(gone, Right(None))
            assert(down.isLeft)
            assertEquals(paths.count(_.endsWith("/x")), 2)
    }

    test("close は接続失敗を Failed にし、再試行しない (A27)。トークン未設定なら呼ばずに NotConfigured") {
        for
            (t, seen) <- todoist(_ => IO.raiseError(new java.util.concurrent.TimeoutException("timeout")))
            r <- t.close("a")
            paths <- seen.get
            (none, seenNone) <- todoist(_ => Ok("null"), token = None)
            n <- none.close("a")
            pathsNone <- seenNone.get
        yield
            assert(r.isLeft)
            assertEquals(paths, List("/api/v1/tasks/a/close"))
            assertEquals(n, Left(TodoistError.NotConfigured))
            assertEquals(pathsNone, Nil)
    }
