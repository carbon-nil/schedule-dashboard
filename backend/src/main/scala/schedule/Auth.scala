package schedule

import cats.data.{Kleisli, OptionT}
import cats.effect.{IO, Ref}
import doobie.*
import doobie.implicits.*
import io.circe.Decoder
import io.circe.parser.decode
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.http4s.implicits.*
import org.typelevel.ci.*

import java.util.Base64
import scala.concurrent.duration.*

/** Google OAuth の Web サーバーフローによる所有者ログイン (設計書 10 章)。
  *
  * 認証ライブラリは使わず、code を Google のトークンエンドポイントと TLS で直接交換する。この経路で受け取った ID トークンは OpenID Connect Core 3.1.3.7
  * により署名検証を省略できるので、iss・aud・exp・nonce だけを検証する。
  */
final class Auth(
    cfg: Config,
    xa: Transactor[IO],
    client: Client[IO],
    aead: Crypto.Aead,
    pending: Ref[IO, Map[String, Auth.Pending]]
):
    import Auth.*

    private val redirectUri = s"${cfg.baseUrl}/auth/callback"
    private val secureCookie = cfg.baseUrl.startsWith("https://")

    val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
        case req @ GET -> Root / "auth" / "login"    => login(req.params.contains("consent"))
        case req @ GET -> Root / "auth" / "callback" => callback(req)
        case req @ POST -> Root / "auth" / "logout"  =>
            val drop = sessionToken(req).fold(IO.unit)(t =>
                sql"DELETE FROM sessions WHERE token_hash = ${Crypto.sha256(t)}".update.run.transact(xa).void
            )
            drop *> Ok().map(_.removeCookie(cookie(SessionCookie, "", 0)))
    }

    /** /api/public 以外の /api はセッション必須。更新系は Origin も確認する。 */
    def protect(routes: HttpRoutes[IO]): HttpRoutes[IO] = Kleisli { req =>
        val unsafe = !Set(Method.GET, Method.HEAD).contains(req.method)
        val originOk = req.headers.get(ci"Origin").exists(_.head.value == cfg.baseUrl)
        val path = req.pathInfo
        if !path.startsWithString("/api/") || path.startsWithString("/api/public/") then OptionT.none
        else
            OptionT.liftF(isOwnerSession(req)).flatMap {
                case false => OptionT.liftF(ApiError(Status.Unauthorized, "UNAUTHENTICATED", "ログインが必要です"))
                case true if unsafe && !originOk =>
                    OptionT.liftF(ApiError(Status.Forbidden, "BAD_ORIGIN", "不正なリクエスト元です"))
                case true => routes(req)
            }
    }

    def isOwnerSession(req: Request[IO]): IO[Boolean] =
        sessionToken(req).fold(IO.pure(false)) { t =>
            IO.realTime.flatMap { now =>
                sql"SELECT 1 FROM sessions WHERE token_hash = ${Crypto.sha256(t)} AND expires_at > ${now.toMillis}"
                    .query[Int]
                    .option
                    .transact(xa)
                    .map(_.isDefined)
            }
        }

    /** Calendar API 用の refresh token。未接続なら None。 */
    def refreshToken: IO[Option[String]] =
        sql"SELECT refresh_token_ciphertext FROM google_credentials WHERE owner_sub = ${cfg.ownerGoogleSub}"
            .query[String]
            .option
            .transact(xa)
            .map(_.map(aead.decrypt))

    private def login(forceConsent: Boolean): IO[Response[IO]] =
        for
            connected <- refreshToken.map(_.isDefined)
            now <- IO.realTime
            p = Pending(Crypto.randomToken(), Crypto.randomToken(), Crypto.randomToken(), now + PendingTtl)
            _ <- pending.update(m => m.filter(_._2.expiresAt > now) + (p.state -> p))
            uri = uri"https://accounts.google.com/o/oauth2/v2/auth".withQueryParams(
                Map(
                    "client_id" -> cfg.googleClientId,
                    "redirect_uri" -> redirectUri,
                    "response_type" -> "code",
                    "scope" -> "openid email https://www.googleapis.com/auth/calendar.readonly",
                    "access_type" -> "offline",
                    "include_granted_scopes" -> "true",
                    "state" -> p.state,
                    "nonce" -> p.nonce,
                    "code_challenge" -> Crypto.s256(p.verifier),
                    "code_challenge_method" -> "S256"
                ) ++ Option.when(forceConsent || !connected)("prompt" -> "consent")
            )
            res <- SeeOther(Location(uri))
        yield res.addCookie(cookie(StateCookie, p.state, PendingTtl.toSeconds))

    private def callback(req: Request[IO]): IO[Response[IO]] =
        val state = req.params.get("state")
        val fromCookie = req.cookies.find(_.name == StateCookie).map(_.content)
        for
            now <- IO.realTime
            found <- pending.modify(m => (state.fold(m)(m - _), state.flatMap(m.get)))
            res <- (found, req.params.get("code")) match
                case (Some(p), Some(code)) if fromCookie.contains(p.state) && p.expiresAt > now =>
                    exchange(code, p).flatMap {
                        case Left(msg) => ApiError(Status.BadRequest, "LOGIN_FAILED", msg)
                        case Right(t) if t.claims.sub != cfg.ownerGoogleSub =>
                            // sub はログインした本人の ID なので見せてよい。OWNER_GOOGLE_SUB の初期設定に使う。
                            ApiError(
                                Status.Forbidden,
                                "NOT_OWNER",
                                s"このアカウント (sub=${t.claims.sub}) ではログインできません"
                            )
                        case Right(t) => completeLogin(t, now)
                    }
                case _ => ApiError(Status.BadRequest, "LOGIN_FAILED", "ログインをやり直してください")
        yield res.removeCookie(cookie(StateCookie, "", 0))

    private def completeLogin(t: Tokens, now: FiniteDuration): IO[Response[IO]] =
        val saveRefresh = t.refreshToken match
            case Some(rt) =>
                val ct = aead.encrypt(rt)
                sql"""INSERT INTO google_credentials (owner_sub, refresh_token_ciphertext, updated_at)
                      VALUES (${t.claims.sub}, $ct, ${now.toMillis})
                      ON CONFLICT (owner_sub) DO UPDATE SET refresh_token_ciphertext = $ct, updated_at = ${now.toMillis}""".update.run
                    .transact(xa)
                    .as(true)
            // 新しい応答に refresh token がなくても既存値は消さない。既存値もなければ再同意へ回す。
            case None => refreshToken.map(_.isDefined)
        saveRefresh.flatMap {
            case false => SeeOther(Location(uri"/auth/login?consent=1"))
            case true  =>
                val token = Crypto.randomToken()
                val expires = (now + SessionTtl).toMillis
                sql"INSERT INTO sessions (token_hash, expires_at) VALUES (${Crypto.sha256(token)}, $expires)".update.run
                    .transact(xa) *>
                    SeeOther(Location(uri"/")).map(_.addCookie(cookie(SessionCookie, token, SessionTtl.toSeconds)))
        }

    private def exchange(code: String, p: Pending): IO[Either[String, Tokens]] =
        val form = UrlForm(
            "code" -> code,
            "client_id" -> cfg.googleClientId,
            "client_secret" -> cfg.googleClientSecret,
            "redirect_uri" -> redirectUri,
            "grant_type" -> "authorization_code",
            "code_verifier" -> p.verifier
        )
        val req = Request[IO](Method.POST, uri"https://oauth2.googleapis.com/token").withEntity(form)
        client.run(req).use { res =>
            if !res.status.isSuccess then IO.pure(Left(s"token endpoint returned ${res.status.code}"))
            else
                for
                    body <- res.as[TokenResponse]
                    now <- IO.realTime
                yield
                    for
                        claims <- parseClaims(body.id_token)
                        _ <- Either.cond(Issuers.contains(claims.iss), (), "issuer mismatch")
                        _ <- Either.cond(claims.aud == cfg.googleClientId, (), "audience mismatch")
                        _ <- Either.cond(claims.exp * 1000 > now.toMillis, (), "id token expired")
                        _ <- Either.cond(claims.nonce.contains(p.nonce), (), "nonce mismatch")
                    yield Tokens(claims, body.refresh_token)
        }

    private def cookie(name: String, value: String, maxAgeSeconds: Long): ResponseCookie =
        ResponseCookie(
            name,
            value,
            maxAge = Some(maxAgeSeconds),
            path = Some("/"),
            secure = secureCookie,
            httpOnly = true,
            sameSite = Some(SameSite.Lax)
        )

object Auth:
    final case class Pending(state: String, nonce: String, verifier: String, expiresAt: FiniteDuration)
    final case class Claims(iss: String, aud: String, exp: Long, sub: String, nonce: Option[String]) derives Decoder
    final case class TokenResponse(id_token: String, refresh_token: Option[String]) derives Decoder
    final case class Tokens(claims: Claims, refreshToken: Option[String])

    val SessionCookie = "sid"
    private val StateCookie = "oauth_state"
    private val PendingTtl = 10.minutes
    private val SessionTtl = 30.days
    private val Issuers = Set("https://accounts.google.com", "accounts.google.com")

    def sessionToken(req: Request[IO]): Option[String] = req.cookies.find(_.name == SessionCookie).map(_.content)

    def parseClaims(idToken: String): Either[String, Claims] =
        idToken.split('.') match
            case Array(_, payload, _) =>
                val json = String(Base64.getUrlDecoder.decode(payload), "UTF-8")
                decode[Claims](json).left.map(e => s"invalid id token: ${e.getMessage}")
            case _ => Left("malformed id token")
