package schedule

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.file.Path
import org.http4s.{Header, Headers, HttpRoutes, StaticFile}
import org.typelevel.ci.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.staticcontent.{fileService, FileService}

import scala.concurrent.duration.*

object Main extends IOApp.Simple:
    def run: IO[Unit] =
        val cfg = Config.fromEnv()
        val xa = Db.transactor(cfg.databasePath)
        EmberClientBuilder.default[IO].withTimeout(10.seconds).build.use { client =>
            for
                _ <- Db.migrate(xa)
                pending <- Ref.of[IO, Map[String, Auth.Pending]](Map.empty)
                auth = Auth(cfg, xa, client, Crypto.Aead(cfg.tokenEncryptionKey), pending)
                tokenCache <- Ref.of[IO, Option[(String, Long)]](None)
                busyCache <- Ref.of[IO, Map[(List[String], Interval), (Long, List[Interval])]](Map.empty)
                google = Google(cfg, client, auth.refreshToken, tokenCache, busyCache)
                health = HttpRoutes.of[IO] { case GET -> Root / "api" / "health" => Ok("ok") }
                todoist = Todoist(cfg.todoistApiToken, client)
                limiter <- RateLimit.empty
                api = Api(xa, google, todoist, FreeTime(xa, google), cfg, limiter)
                routes = health <+> auth.routes <+> api.publicRoutes <+> auth.protect(api.routes) <+>
                    cfg.staticDir.fold(HttpRoutes.empty[IO])(spa)
                _ <- EmberServerBuilder
                    .default[IO]
                    .withHost(ipv4"0.0.0.0")
                    .withPort(port"8080")
                    .withHttpApp(routes.orNotFound)
                    .build
                    .useForever
            yield ()
        }

    /** 本番では frontend のビルド結果を同一オリジンで配信する。知らないパスは index.html に回す。 */
    private def spa(dir: String): HttpRoutes[IO] =
        // 共有ページはキャッシュ・参照元・検索エンジンに残さない (設計書 10 章)
        val shareHeaders = Headers(
            Header.Raw(ci"Cache-Control", "no-store"),
            Header.Raw(ci"Referrer-Policy", "no-referrer"),
            Header.Raw(ci"X-Robots-Tag", "noindex, nofollow")
        )
        val index = HttpRoutes.of[IO] {
            case req @ GET -> path if !path.startsWithString("/api") && !path.startsWithString("/auth") =>
                StaticFile.fromPath(Path(dir) / "index.html", Some(req)).getOrElseF(NotFound()).map { res =>
                    if path.startsWithString("/share/") then res.withHeaders(res.headers ++ shareHeaders) else res
                }
        }
        fileService[IO](FileService.Config(dir)) <+> index
