package schedule

import cats.effect.IO
import io.circe.Json
import org.http4s.circe.*
import org.http4s.{Response, Status}

/** エラー形式は {error: {code, message}} (設計書 9 章)。 */
object ApiError:
    def apply(status: Status, code: String, message: String): IO[Response[IO]] =
        IO.pure(
            Response[IO](status).withEntity(
                Json.obj("error" -> Json.obj("code" -> Json.fromString(code), "message" -> Json.fromString(message)))
            )
        )
