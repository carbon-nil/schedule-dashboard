package schedule

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.typelevel.ci.*

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.util.Try

/** 本人用の API (設計書 9 章)。認証は Auth.protect が前段で済ませる。 */
final class Api(
    xa: Transactor[IO],
    google: Google,
    todoist: Todoist,
    freeTime: FreeTime,
    cfg: Config,
    limiter: RateLimit
):
    import Api.*

    val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
        case GET -> Root / "api" / "me" => Ok(Json.obj("ok" -> Json.True))

        case GET -> Root / "api" / "day" :? DateParam(date) =>
            Try(LocalDate.parse(date)).toOption.fold(invalid("date は YYYY-MM-DD で指定してください"))(day)

        case GET -> Root / "api" / "calendars" =>
            google.calendars.flatMap {
                case Right(cs) => Ok(cs.map(c => CalendarDto(c.id, c.title, c.accessRole, c.primary)).asJson)
                case Left(e)   => ApiError(Status.ServiceUnavailable, "GOOGLE_UNAVAILABLE", e.message)
            }

        case GET -> Root / "api" / "settings" => settings.transact(xa).flatMap(s => Ok(s.asJson))

        case req @ PUT -> Root / "api" / "settings" =>
            withBody[SettingsInput](req) { in =>
                if in.selectedCalendarIds.size > 50 then invalid("カレンダーは 50 個まで選べます")
                else
                    windowsError(in.weeklyWindows) match
                        case Some(message) => invalid(message)
                        case None          =>
                            replaceSettings(in).transact(xa).flatMap {
                                case Some(s) => Ok(s.asJson)
                                case None    => conflict
                            }
            }

        case req @ POST -> Root / "api" / "blocks" =>
            withBody[BlockCreate](req) { in =>
                val title = in.title.map(_.trim)
                (title, in.todoistTaskId) match
                    case (Some(t), None) if t.isEmpty || t.length > 200 => invalid("タイトルは 1〜200 文字にしてください")
                    case (None, Some(id)) if id.isEmpty                 => invalid("タスクを指定してください")
                    case (Some(_), None) | (None, Some(_))              =>
                        validRange(in.startAt, in.endAt) { (start, end) =>
                            Blocks
                                .create(in.requestId, title, in.todoistTaskId, start, end)
                                .transact(xa)
                                .flatMap(respond(Status.Created))
                        }
                    case _ => invalid("タイトルかタスクのどちらか一方を指定してください")
            }

        case req @ PATCH -> Root / "api" / "blocks" / id =>
            withBody[BlockMove](req) { in =>
                validRange(in.startAt, in.endAt) { (start, end) =>
                    Blocks.move(id, start, end, in.expectedVersion).transact(xa).flatMap(respond(Status.Ok))
                }
            }

        case req @ DELETE -> Root / "api" / "blocks" / id =>
            withBody[VersionInput](req) { in =>
                Blocks.delete(id, in.expectedVersion).transact(xa).flatMap {
                    case Change.Done(_)  => NoContent()
                    case Change.NotFound => notFound
                    case Change.Conflict => conflict
                }
            }

        // 単件取得で状態を確かめてから close する (設計書 6.2)。繰り返しは本画面から完了しない (A26)
        case POST -> Root / "api" / "tasks" / id / "complete" =>
            todoist.task(id).flatMap {
                case Left(e)                         => todoistUnavailable(e)
                case Right(None)                     => notFound
                case Right(Some(t)) if t.isRecurring =>
                    ApiError(Status.UnprocessableContent, "RECURRING_TASK", "繰り返しタスクは Todoist で完了してください")
                case Right(Some(_)) =>
                    todoist.close(id).flatMap {
                        case Right(()) => Ok(Json.obj("id" -> Json.fromString(id), "completed" -> Json.True))
                        case Left(e)   => todoistUnavailable(e)
                    }
            }

        // 共有 (設計書 5.5、9 章)。プレビューは公開と同じ計算をする
        case req @ POST -> Root / "api" / "shares" / "preview" =>
            withBody[SharePreview](req) { in =>
                shareRange(in.rangeStart, in.rangeEnd, in.minFreeMinutes) { (range, min) =>
                    publicDto(range, min).flatMap(_.fold(googleUnavailable, dto => Ok(dto.asJson)))
                }
            }

        case req @ POST -> Root / "api" / "shares" =>
            withBody[ShareCreate](req) { in =>
                shareRange(in.rangeStart, in.rangeEnd, in.minFreeMinutes) { (range, min) =>
                    IO.realTime.map(_.toMillis).flatMap { now =>
                        val expires =
                            in.expiresAt.fold(Option(range.end))(s => Try(Instant.parse(s).toEpochMilli).toOption)
                        expires match
                            case None                                 => invalid("期限は RFC3339 で指定してください")
                            case Some(e) if e <= now || e > range.end =>
                                invalid("期限は現在より後、共有期間の終了以前にしてください")
                            case Some(e) =>
                                val token = Crypto.randomToken()
                                val share = Share(in.requestId, range.start, range.end, e, None, min, now, 1)
                                Shares.create(in.requestId, Crypto.sha256(token), share).transact(xa).flatMap {
                                    case Change.Done(s) =>
                                        // 平文のトークンはこの応答でだけ返す
                                        val body =
                                            ShareDto.from(s).asJsonObject.add("url", Json.fromString(shareUrl(token)))
                                        IO.pure(Response[IO](Status.Created).withEntity(Json.fromJsonObject(body)))
                                    case Change.Conflict => conflict
                                    case Change.NotFound => notFound
                                }
                    }
                }
            }

        case GET -> Root / "api" / "shares" => Shares.list.transact(xa).flatMap(ss => Ok(ss.map(ShareDto.from).asJson))

        case req @ POST -> Root / "api" / "shares" / id / "revoke" =>
            withBody[VersionInput](req) { in =>
                IO.realTime.map(_.toMillis).flatMap { now =>
                    Shares.revoke(id, in.expectedVersion, now).transact(xa).flatMap {
                        case Change.Done(s)  => Ok(ShareDto.from(s).asJson)
                        case Change.NotFound => notFound
                        case Change.Conflict => conflict
                    }
                }
            }
    }

    /** 認証なしの公開 API (設計書 10 章)。受け取るのはトークンだけで、期間やカレンダーは指定できない (A23)。 */
    val publicRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
        case req @ GET -> Root / "api" / "public" / "availability" / token =>
            val ip = req.headers
                .get(ci"X-Forwarded-For")
                .map(_.head.value.takeWhile(_ != ',').trim)
                .orElse(req.remoteAddr.map(_.toString))
                .getOrElse("-")
            (limiter.allow(s"ip:$ip", 60), limiter.allow(s"token:${Crypto.sha256(token)}", 30)).tupled
                .flatMap {
                    case (ipOk, tokenOk) if !ipOk || !tokenOk =>
                        ApiError(Status.TooManyRequests, "RATE_LIMITED", "しばらく待ってから開き直してください")
                    case _ =>
                        IO.realTime.map(_.toMillis).flatMap { now =>
                            Shares.valid(Crypto.sha256(token), now).transact(xa).flatMap {
                                // 失効・期限切れ・不明は同じ 404 (A12)
                                case None    => ApiError(Status.NotFound, "NOT_FOUND", "このリンクは無効です")
                                case Some(s) =>
                                    publicDto(Interval(s.rangeStart, s.rangeEnd), s.minFreeMinutes).flatMap(
                                        _.fold(googleUnavailable, dto => Ok(dto.asJson))
                                    )
                            }
                        }
                }
                .map(_.putHeaders(Header.Raw(ci"Cache-Control", "no-store")))
    }

    /** 公開用 DTO は明示的に組み立てる。busy・内部 ID・タイトルを含めない (設計書 9 章)。 */
    private def publicDto(range: Interval, minFreeMinutes: Int): IO[Either[GoogleError, PublicDto]] =
        for
            result <- freeTime.compute(range)
            now <- IO.realTime.map(_.toMillis)
        yield result.map { r =>
            PublicDto(
                "Asia/Tokyo",
                iso(now),
                iso(r.externalFetchedAt),
                iso(range.start),
                iso(range.end),
                Availability.publicFree(r.free, now, minFreeMinutes).map(IntervalDto.from).toList
            )
        }

    /** 外部の取得に失敗しても本人画面は返し、失敗を errors に載せる。空配列にはしない。 */
    private def day(date: LocalDate): IO[Response[IO]] =
        val range = Interval(
            date.atStartOfDay(Availability.Zone).toInstant.toEpochMilli,
            date.plusDays(1).atStartOfDay(Availability.Zone).toInstant.toEpochMilli
        )
        for
            ids <- sql"SELECT calendar_id FROM selected_calendars".query[String].to[List].transact(xa)
            // Google、Todoist、空き時間は並列に取る。タプルの分解は main では使えない (-source:future はテストだけ)
            all <- (
                ids.parTraverse(id => google.events(id, range).map(id -> _)),
                todoist.tasks,
                freeTime.compute(range)
            ).parTupled
            (fetched, tasks, free) = all
            blocks <- Blocks.inRange(range).transact(xa)
            now <- IO.realTime
            events = fetched.flatMap(_._2.toOption.toList.flatten)
            errors = fetched.collect { case (id, Left(e)) => ErrorDto("google", id, e.message) } ++
                tasks.left.toOption.map(e => ErrorDto("todoist", "tasks", e.message)) ++
                free.left.toOption.map(e => ErrorDto("google", "freeBusy", e.message))
            res <- Ok(
                DayDto(
                    date.toString,
                    events.sortBy(_.start).map(EventDto.from),
                    blocks.map(BlockDto.from),
                    tasks.getOrElse(Nil).map(TaskDto.from),
                    free.toOption.map(_.free.map(IntervalDto.from)),
                    iso(now.toMillis),
                    errors
                ).asJson
            )
        yield res

    private def settings: ConnectionIO[SettingsDto] =
        for
            version <- sql"SELECT version FROM app_settings WHERE id = 1".query[Int].unique
            ids <- sql"SELECT calendar_id FROM selected_calendars ORDER BY calendar_id".query[String].to[List]
            windows <- FreeTime.windows
        yield SettingsDto(version, ids, windows.map(w => WindowDto(w.weekday, w.startMinute, w.endMinute)))

    /** 一括置換。version が一致したときだけ置き換え、version を進める。 */
    private def replaceSettings(in: SettingsInput): ConnectionIO[Option[SettingsDto]] =
        sql"UPDATE app_settings SET version = version + 1 WHERE id = 1 AND version = ${in.expectedVersion}".update.run
            .flatMap {
                case 0 => FC.pure(None)
                case _ =>
                    sql"DELETE FROM selected_calendars".update.run *>
                        in.selectedCalendarIds.distinct.traverse_(id =>
                            sql"INSERT INTO selected_calendars (calendar_id) VALUES ($id)".update.run
                        ) *>
                        sql"DELETE FROM weekly_windows".update.run *>
                        in.weeklyWindows.traverse_ { w =>
                            val id = UUID.randomUUID().toString
                            sql"""INSERT INTO weekly_windows (id, weekday, start_minute, end_minute)
                                  VALUES ($id, ${w.weekday}, ${w.startMinute}, ${w.endMinute})""".update.run
                        } *> settings.map(Some(_))
            }

    private def shareUrl(token: String) = s"${cfg.baseUrl}/share/$token"

object Api:
    object DateParam extends QueryParamDecoderMatcher[String]("date")

    final case class BlockCreate(
        requestId: String,
        title: Option[String],
        todoistTaskId: Option[String],
        startAt: String,
        endAt: String
    ) derives Decoder
    final case class BlockMove(startAt: String, endAt: String, expectedVersion: Int) derives Decoder
    final case class VersionInput(expectedVersion: Int) derives Decoder
    final case class WindowDto(weekday: Int, startMinute: Int, endMinute: Int) derives Decoder, Encoder.AsObject
    final case class SettingsInput(
        expectedVersion: Int,
        selectedCalendarIds: List[String],
        weeklyWindows: List[WindowDto]
    ) derives Decoder
    final case class SharePreview(rangeStart: String, rangeEnd: String, minFreeMinutes: Option[Int]) derives Decoder
    final case class ShareCreate(
        requestId: String,
        rangeStart: String,
        rangeEnd: String,
        minFreeMinutes: Option[Int],
        expiresAt: Option[String]
    ) derives Decoder

    final case class BlockDto(
        id: String,
        title: Option[String],
        todoistTaskId: Option[String],
        startAt: String,
        endAt: String,
        version: Int
    ) derives Encoder.AsObject
    object BlockDto:
        def from(b: Block): BlockDto = BlockDto(b.id, b.title, b.taskId, iso(b.start), iso(b.end), b.version)
    final case class EventDto(
        id: String,
        calendarId: String,
        title: String,
        startAt: String,
        endAt: String,
        allDay: Boolean
    ) derives Encoder.AsObject
    object EventDto:
        def from(e: CalendarEvent): EventDto = EventDto(e.id, e.calendarId, e.title, iso(e.start), iso(e.end), e.allDay)
    final case class TaskDto(
        id: String,
        title: String,
        projectId: String,
        estimateMinutes: Option[Int],
        deadlineDate: Option[String],
        isRecurring: Boolean,
        url: String
    ) derives Encoder.AsObject
    object TaskDto:
        def from(t: Task): TaskDto =
            TaskDto(t.id, t.title, t.projectId, t.estimateMinutes, t.deadlineDate, t.isRecurring, t.url)
    final case class IntervalDto(start: String, end: String) derives Encoder.AsObject
    object IntervalDto:
        def from(i: Interval): IntervalDto = IntervalDto(iso(i.start), iso(i.end))
    final case class ErrorDto(source: String, target: String, message: String) derives Encoder.AsObject
    final case class DayDto(
        date: String,
        events: List[EventDto],
        blocks: List[BlockDto],
        tasks: List[TaskDto],
        freeIntervals: Option[List[IntervalDto]],
        fetchedAt: String,
        errors: List[ErrorDto]
    ) derives Encoder.AsObject
    final case class CalendarDto(id: String, title: String, accessRole: String, primary: Boolean)
        derives Encoder.AsObject
    final case class SettingsDto(
        settingsVersion: Int,
        selectedCalendarIds: List[String],
        weeklyWindows: List[WindowDto]
    ) derives Encoder.AsObject
    final case class ShareDto(
        id: String,
        rangeStart: String,
        rangeEnd: String,
        expiresAt: String,
        revokedAt: Option[String],
        minFreeMinutes: Int,
        createdAt: String,
        version: Int
    ) derives Encoder.AsObject
    object ShareDto:
        def from(s: Share): ShareDto = ShareDto(
            s.id,
            iso(s.rangeStart),
            iso(s.rangeEnd),
            iso(s.expiresAt),
            s.revokedAt.map(iso),
            s.minFreeMinutes,
            iso(s.createdAt),
            s.version
        )

    /** 公開レスポンス (設計書 9 章の例)。この型にない情報は出せない。 */
    final case class PublicDto(
        timezone: String,
        computedAt: String,
        externalFetchedAt: String,
        rangeStart: String,
        rangeEnd: String,
        freeIntervals: List[IntervalDto]
    ) derives Encoder.AsObject

    def iso(millis: Long): String = Instant.ofEpochMilli(millis).toString

    private val notFound = ApiError(Status.NotFound, "NOT_FOUND", "見つかりません")
    private val conflict = ApiError(Status.Conflict, "VERSION_CONFLICT", "ほかの操作で更新されています。再読み込みしてください")
    private def invalid(message: String) = ApiError(Status.UnprocessableContent, "INVALID_INPUT", message)
    private def todoistUnavailable(e: TodoistError) =
        ApiError(Status.ServiceUnavailable, "TODOIST_UNAVAILABLE", e.message)
    // 取得できないときは 503 で「現在確認できません」。空の busy から全部空きを作らない (設計書 7 章)
    private def googleUnavailable(e: GoogleError) =
        ApiError(Status.ServiceUnavailable, "GOOGLE_UNAVAILABLE", s"現在確認できません: ${e.message}")

    private def respond(created: Status)(c: Change[Block]): IO[Response[IO]] = c match
        case Change.Done(b)  => IO.pure(Response[IO](created).withEntity(BlockDto.from(b).asJson))
        case Change.NotFound => notFound
        case Change.Conflict => conflict

    private def withBody[A: Decoder](req: Request[IO])(f: A => IO[Response[IO]]): IO[Response[IO]] =
        req.attemptAs[Json].value.map(_.flatMap(_.as[A].left.map(e => e: Throwable))).flatMap {
            case Right(a) => f(a)
            case Left(_)  => invalid("入力の形式が正しくありません")
        }

    /** 日時は RFC3339、区間は start < end。 */
    private def validRange(startAt: String, endAt: String)(f: (Long, Long) => IO[Response[IO]]): IO[Response[IO]] =
        (Try(Instant.parse(startAt)).toOption, Try(Instant.parse(endAt)).toOption) match
            case (Some(s), Some(e)) if s.isBefore(e) => f(s.toEpochMilli, e.toEpochMilli)
            case (Some(_), Some(_))                  => invalid("終了は開始より後にしてください")
            case _                                   => invalid("日時は RFC3339 で指定してください")

    /** 共有期間は 31 日以内、最小連続時間は 1〜1440 分 (初期値 30)。 */
    private def shareRange(startAt: String, endAt: String, minFreeMinutes: Option[Int])(
        f: (Interval, Int) => IO[Response[IO]]
    ): IO[Response[IO]] =
        val min = minFreeMinutes.getOrElse(30)
        if min < 1 || min > 1440 then invalid("最小連続時間は 1〜1440 分にしてください")
        else
            validRange(startAt, endAt) { (s, e) =>
                if e - s > 31L * 24 * 60 * 60000 then invalid("共有期間は 31 日以内にしてください")
                else f(Interval(s, e), min)
            }

    /** 曜日 0〜6、0 <= 開始 < 終了 <= 1440、同じ曜日で重ならない (設計書 5.2)。 */
    private def windowsError(ws: List[WindowDto]): Option[String] =
        val sorted = ws.sortBy(w => (w.weekday, w.startMinute))
        if ws.exists(w => w.weekday < 0 || w.weekday > 6) then Some("曜日は 0 (月) 〜 6 (日) で指定してください")
        else if ws.exists(w => w.startMinute < 0 || w.startMinute >= w.endMinute || w.endMinute > 1440) then
            Some("時刻は 0 <= 開始 < 終了 <= 1440 分にしてください")
        else if sorted.lazyZip(sorted.drop(1)).exists((a, b) => a.weekday == b.weekday && a.endMinute > b.startMinute)
        then Some("同じ曜日の活動可能時間が重なっています")
        else None
