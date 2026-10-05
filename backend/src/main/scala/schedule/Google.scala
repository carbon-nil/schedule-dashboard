package schedule

import cats.effect.{IO, Ref}
import io.circe.{Decoder, Json}
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.implicits.*

import java.time.{Instant, LocalDate, OffsetDateTime, ZoneId}
import scala.concurrent.duration.*

/** 取得失敗を成功した空配列と区別するための型 (設計書 11 章)。 */
enum GoogleError:
    case NotConnected
    case Failed(detail: String)

    def message: String = this match
        case NotConnected => "Google Calendar が未接続です。ログインし直してください"
        case Failed(m)    => m

final case class CalendarEntry(id: String, title: String, accessRole: String, primary: Boolean)
final case class CalendarEvent(id: String, calendarId: String, title: String, start: Long, end: Long, allDay: Boolean)

/** Google Calendar API の読み取り (設計書 6.1)。外部 API はすべてサーバー側から呼ぶ。 */
final class Google(
    cfg: Config,
    client: Client[IO],
    refreshToken: IO[Option[String]],
    cache: Ref[IO, Option[(String, Long)]],
    busyCache: Ref[IO, Map[(List[String], Interval), (Long, List[Interval])]]
):
    import Google.*

    def calendars: IO[Either[GoogleError, List[CalendarEntry]]] =
        pages[CalendarListPage](uri"https://www.googleapis.com/calendar/v3/users/me/calendarList").map(_.map { pages =>
            for
                page <- pages
                item <- page.items
                if Readable.contains(item.accessRole)
            yield CalendarEntry(item.id, item.summary.getOrElse(item.id), item.accessRole, item.primary)
        })

    /** 繰り返し予定は Google に展開させる (singleEvents=true)。終日予定はカレンダーのタイムゾーンで区間にする。 */
    def events(calendarId: String, range: Interval): IO[Either[GoogleError, List[CalendarEvent]]] =
        val uri = (uri"https://www.googleapis.com/calendar/v3/calendars" / calendarId / "events").withQueryParams(
            Map(
                "timeMin" -> Instant.ofEpochMilli(range.start).toString,
                "timeMax" -> Instant.ofEpochMilli(range.end).toString,
                "singleEvents" -> "true",
                "showDeleted" -> "false",
                "maxResults" -> "2500"
            )
        )
        pages[EventsPage](uri).map(_.map { pages =>
            for
                page <- pages
                zone = page.timeZone.map(ZoneId.of).getOrElse(Availability.Zone)
                item <- page.items
                if item.status.forall(_ != "cancelled")
                (start, allDay) <- item.start.flatMap(toMillis(_, zone))
                (end, _) <- item.end.flatMap(toMillis(_, zone))
            yield CalendarEvent(item.id, calendarId, item.summary.getOrElse("(無題)"), start, end, allDay)
        })

    /** 選択した全カレンダーの busy。1 件でも errors があれば全体を失敗にする (設計書 6.1、A13)。完全成功した結果だけを 60 秒キャッシュする。キーに選択集合を含むので設定変更で自然に外れる
      * (A08)。戻り値は (取得時刻, busy)。
      */
    def freeBusy(calendarIds: List[String], range: Interval): IO[Either[GoogleError, (Long, List[Interval])]] =
        val key = (calendarIds.sorted, range)
        IO.realTime.map(_.toMillis).flatMap { now =>
            busyCache.get.map(_.get(key).filter(_._1 > now - 60000)).flatMap {
                case Some(hit) => IO.pure(Right(hit))
                case None      =>
                    val body = Json.obj(
                        "timeMin" -> Json.fromString(Instant.ofEpochMilli(range.start).toString),
                        "timeMax" -> Json.fromString(Instant.ofEpochMilli(range.end).toString),
                        "items" -> Json.arr(calendarIds.map(id => Json.obj("id" -> Json.fromString(id)))*)
                    )
                    call[FreeBusyResponse](
                        Method.POST,
                        uri"https://www.googleapis.com/calendar/v3/freeBusy",
                        Some(body)
                    )
                        .flatMap {
                            case Left(e)    => IO.pure(Left(e))
                            case Right(res) =>
                                val missing = calendarIds.filterNot(res.calendars.contains)
                                val failed = res.calendars.collect { case (id, c) if c.errors.nonEmpty => id }
                                if missing.nonEmpty || failed.nonEmpty then
                                    val ids = (missing ++ failed).mkString(", ")
                                    IO.pure(Left(GoogleError.Failed(s"カレンダーの busy を取得できません: $ids")))
                                else
                                    val busy = res.calendars.values.toList
                                        .flatMap(_.busy)
                                        .map(b =>
                                            Interval(
                                                OffsetDateTime.parse(b.start).toInstant.toEpochMilli,
                                                OffsetDateTime.parse(b.end).toInstant.toEpochMilli
                                            )
                                        )
                                    busyCache.update(_ + (key -> (now, busy))).as(Right((now, busy)))
                        }
            }
        }

    private def toMillis(t: EventTime, zone: ZoneId): Option[(Long, Boolean)] =
        t.dateTime
            .map(dt => (OffsetDateTime.parse(dt).toInstant.toEpochMilli, false))
            .orElse(t.date.map(d => (LocalDate.parse(d).atStartOfDay(zone).toInstant.toEpochMilli, true)))

    /** nextPageToken を最後までたどる。 */
    private def pages[A: Decoder](uri: Uri)(using paged: Paged[A]): IO[Either[GoogleError, List[A]]] =
        def loop(token: Option[String], acc: List[A]): IO[Either[GoogleError, List[A]]] =
            get[A](token.fold(uri)(t => uri.withQueryParam("pageToken", t))).flatMap {
                case Left(e)     => IO.pure(Left(e))
                case Right(page) =>
                    paged.next(page) match
                        case Some(t) => loop(Some(t), page :: acc)
                        case None    => IO.pure(Right((page :: acc).reverse))
            }
        loop(None, Nil)

    private def get[A: Decoder](uri: Uri): IO[Either[GoogleError, A]] = call(Method.GET, uri, None)

    /** 429 と一時的な 5xx は短い待ちのあと 1 回だけ再試行する (設計書 7 章)。 */
    private def call[A: Decoder](method: Method, uri: Uri, body: Option[Json]): IO[Either[GoogleError, A]] =
        def attempt(retried: Boolean): IO[Either[GoogleError, A]] =
            accessToken
                .flatMap {
                    case Left(e)      => IO.pure(Left(e))
                    case Right(token) =>
                        val base = Request[IO](method, uri).putHeaders(
                            Authorization(Credentials.Token(AuthScheme.Bearer, token))
                        )
                        val req = body.fold(base)(base.withEntity(_))
                        client.run(req).use { res =>
                            res.status.code match
                                case c if c >= 200 && c < 300 => res.as[A].map(Right(_))
                                case 401 => cache.set(None).as(Left(GoogleError.Failed("Google の認可が切れています")))
                                case c if (c == 429 || c >= 500) && !retried => IO.pure(Left(Retry))
                                case c => IO.pure(Left(GoogleError.Failed(s"Google Calendar API が $c を返しました")))
                        }
                }
                .handleError(e => Left(GoogleError.Failed(s"Google Calendar API に接続できません: ${e.getMessage}")))
                .flatMap {
                    case Left(Retry) => IO.sleep(500.millis) *> attempt(retried = true)
                    case other       => IO.pure(other)
                }
        attempt(retried = false)

    /** アクセストークンはメモリにだけ置き、期限の 1 分前に refresh token で取り直す。 */
    private def accessToken: IO[Either[GoogleError, String]] =
        IO.realTime.flatMap { now =>
            cache.get.flatMap {
                case Some((token, expiresAt)) if expiresAt > now.toMillis + 60000 => IO.pure(Right(token))
                case _                                                            =>
                    refreshToken.flatMap {
                        case None     => IO.pure(Left(GoogleError.NotConnected))
                        case Some(rt) => refresh(rt, now.toMillis)
                    }
            }
        }

    private def refresh(rt: String, now: Long): IO[Either[GoogleError, String]] =
        val form = UrlForm(
            "client_id" -> cfg.googleClientId,
            "client_secret" -> cfg.googleClientSecret,
            "refresh_token" -> rt,
            "grant_type" -> "refresh_token"
        )
        client.run(Request[IO](Method.POST, uri"https://oauth2.googleapis.com/token").withEntity(form)).use { res =>
            if res.status.isSuccess then
                res.as[AccessToken].flatMap { t =>
                    cache.set(Some((t.access_token, now + t.expires_in * 1000))).as(Right(t.access_token))
                }
            // invalid_grant などで更新できないときは、本人画面に再接続を促す
            else IO.pure(Left(GoogleError.NotConnected))
        }

object Google:
    private val Readable = Set("reader", "writer", "owner")
    // 再試行の合図。呼び出し元へは返さない。
    private val Retry = GoogleError.Failed("retry")

    final case class AccessToken(access_token: String, expires_in: Long) derives Decoder
    final case class CalendarListItem(id: String, summary: Option[String], accessRole: String, primary: Boolean)
    object CalendarListItem:
        given Decoder[CalendarListItem] = Decoder.forProduct4("id", "summary", "accessRole", "primary")(
            (id: String, s: Option[String], r: String, p: Option[Boolean]) =>
                CalendarListItem(id, s, r, p.getOrElse(false))
        )
    final case class CalendarListPage(items: List[CalendarListItem], nextPageToken: Option[String])
    object CalendarListPage:
        given Decoder[CalendarListPage] = Decoder.forProduct2("items", "nextPageToken")(
            (i: Option[List[CalendarListItem]], n: Option[String]) => CalendarListPage(i.getOrElse(Nil), n)
        )
    final case class EventTime(dateTime: Option[String], date: Option[String]) derives Decoder
    final case class EventItem(
        id: String,
        status: Option[String],
        summary: Option[String],
        start: Option[EventTime],
        end: Option[EventTime]
    ) derives Decoder
    final case class EventsPage(items: List[EventItem], nextPageToken: Option[String], timeZone: Option[String])
    object EventsPage:
        given Decoder[EventsPage] = Decoder.forProduct3("items", "nextPageToken", "timeZone")(
            (i: Option[List[EventItem]], n: Option[String], z: Option[String]) => EventsPage(i.getOrElse(Nil), n, z)
        )

    final case class BusyPeriod(start: String, end: String) derives Decoder
    final case class FreeBusyCalendar(busy: List[BusyPeriod], errors: List[Json])
    object FreeBusyCalendar:
        given Decoder[FreeBusyCalendar] = Decoder.forProduct2("busy", "errors")(
            (b: Option[List[BusyPeriod]], e: Option[List[Json]]) => FreeBusyCalendar(b.getOrElse(Nil), e.getOrElse(Nil))
        )
    final case class FreeBusyResponse(calendars: Map[String, FreeBusyCalendar]) derives Decoder

    trait Paged[A]:
        def next(a: A): Option[String]
    given Paged[CalendarListPage] = _.nextPageToken
    given Paged[EventsPage] = _.nextPageToken
