package schedule

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.file.Path
import org.http4s.{HttpRoutes, StaticFile}
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
                google = Google(cfg, client, auth.refreshToken, tokenCache)
                public = HttpRoutes.of[IO] { case GET -> Root / "api" / "health" => Ok("ok") }
                todoist = Todoist(cfg.todoistApiToken, client)
                api = auth.protect(Api(xa, google, todoist).routes)
                routes = public <+> auth.routes <+> api <+> cfg.staticDir.fold(HttpRoutes.empty[IO])(spa)
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
        val index = HttpRoutes.of[IO] {
            case req @ GET -> path if !path.startsWithString("/api") && !path.startsWithString("/auth") =>
                StaticFile.fromPath(Path(dir) / "index.html", Some(req)).getOrElseF(NotFound())
        }
        fileService[IO](FileService.Config(dir)) <+> index
