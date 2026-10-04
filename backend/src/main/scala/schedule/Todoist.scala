package schedule

import cats.effect.IO
import io.circe.Decoder
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** 取得失敗を成功した空配列と区別するための型 (設計書 11 章)。 */
enum TodoistError:
    case NotConfigured
    case Failed(detail: String)

    def message: String = this match
        case NotConfigured => "Todoist のトークンが設定されていません (TODOIST_API_TOKEN)"
        case Failed(m)     => m

/** 画面用モデル (設計書 6.2)。duration は分に正規化し、deadline を締切とする。due は締切に使わない。 */
final case class Task(
    id: String,
    title: String,
    projectId: String,
    estimateMinutes: Option[Int],
    deadlineDate: Option[String],
    isRecurring: Boolean,
    url: String
)

/** Todoist API v1 の読み取りと完了 (設計書 6.2)。トークンは所有者 1 人分を環境変数で持つ。 */
final class Todoist(token: Option[String], client: Client[IO]):
    import Todoist.*

    /** 進行中のタスクを cursor の最後まで取る。 */
    def tasks: IO[Either[TodoistError, List[Task]]] =
        def loop(cursor: Option[String], acc: List[Task]): IO[Either[TodoistError, List[Task]]] =
            val uri = cursor.foldLeft(Base.withQueryParam("limit", "200"))(_.withQueryParam("cursor", _))
            get[Page](uri).flatMap {
                case Left(e)     => IO.pure(Left(e))
                case Right(page) =>
                    val tasks = acc ++ page.results.map(_.toTask)
                    page.next_cursor.fold(IO.pure(Right(tasks)))(c => loop(Some(c), tasks))
            }
        loop(None, Nil)

    /** 単件取得。完了・削除済みは 404 なので None。 */
    def task(id: String): IO[Either[TodoistError, Option[Task]]] =
        get[Item](Base / id).map {
            case Right(item)                      => Right(Some(item.toTask))
            case Left(TodoistError.Failed("404")) => Right(None)
            case Left(e)                          => Left(e)
        }

    /** 完了。タイムアウトや 5xx は Failed にし、成功したように見せない (A27)。再試行もしない。 */
    def close(id: String): IO[Either[TodoistError, Unit]] =
        token match
            case None    => IO.pure(Left(TodoistError.NotConfigured))
            case Some(t) =>
                client
                    .run(Request[IO](Method.POST, Base / id / "close").putHeaders(bearer(t)))
                    .use(res => IO.pure(if res.status.isSuccess then Right(()) else Left(failed(res.status.code))))
                    .handleError(e => Left(TodoistError.Failed(s"Todoist に接続できません: ${e.getMessage}")))

    /** 429 と 5xx は短い待ちのあと 1 回だけ再試行する。 */
    private def get[A: Decoder](uri: Uri): IO[Either[TodoistError, A]] =
        def attempt(t: String, retried: Boolean): IO[Either[TodoistError, A]] =
            client
                .run(Request[IO](Method.GET, uri).putHeaders(bearer(t)))
                .use { res =>
                    res.status.code match
                        case c if c >= 200 && c < 300                => res.as[A].map(Right(_))
                        case c if (c == 429 || c >= 500) && !retried => IO.pure(Left(Retry))
                        case c                                       => IO.pure(Left(failed(c)))
                }
                .handleError(e => Left(TodoistError.Failed(s"Todoist に接続できません: ${e.getMessage}")))
                .flatMap {
                    case Left(Retry) => IO.sleep(500.millis) *> attempt(t, retried = true)
                    case other       => IO.pure(other)
                }
        token.fold(IO.pure(Left(TodoistError.NotConfigured)))(attempt(_, retried = false))

    private def bearer(t: String) = Authorization(Credentials.Token(AuthScheme.Bearer, t))

object Todoist:
    private val Base = uri"https://api.todoist.com/api/v1/tasks"
    private val Retry = TodoistError.Failed("retry")

    private def failed(code: Int): TodoistError = code match
        case 401 | 403 => TodoistError.Failed("Todoist のトークンが無効です。TODOIST_API_TOKEN を設定し直してください")
        case 404       => TodoistError.Failed("404")
        case c         => TodoistError.Failed(s"Todoist API が $c を返しました")

    final case class Duration(amount: Int, unit: String) derives Decoder
    final case class Due(is_recurring: Boolean) derives Decoder
    final case class Deadline(date: String) derives Decoder
    final case class Item(
        id: String,
        content: String,
        project_id: String,
        duration: Option[Duration],
        deadline: Option[Deadline],
        due: Option[Due]
    ) derives Decoder:
        def toTask: Task = Task(
            id,
            content,
            project_id,
            duration.map(d => if d.unit == "day" then d.amount * 1440 else d.amount),
            deadline.map(_.date),
            due.exists(_.is_recurring),
            s"https://app.todoist.com/app/task/$id"
        )
    final case class Page(results: List[Item], next_cursor: Option[String]) derives Decoder
