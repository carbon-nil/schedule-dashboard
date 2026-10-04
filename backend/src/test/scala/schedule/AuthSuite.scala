package schedule

import cats.effect.{IO, Ref}
import doobie.implicits.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.typelevel.ci.*

import java.nio.file.Files
import java.util.Base64

class AuthSuite extends munit.CatsEffectSuite:
    private val cfg = Config(
        baseUrl = "https://example.test",
        databasePath = "",
        staticDir = None,
        googleClientId = "client",
        googleClientSecret = "secret",
        ownerGoogleSub = "owner",
        tokenEncryptionKey = new Array[Byte](32)
    )

    private val setup =
        for
            xa <- IO(Files.createTempFile("auth", ".db").toString).map(Db.transactor)
            _ <- Db.migrate(xa)
            pending <- Ref.of[IO, Map[String, Auth.Pending]](Map.empty)
            noNetwork = Client[IO](_ => cats.effect.Resource.eval(IO.raiseError(new Exception("no network in tests"))))
            auth = Auth(cfg, xa, noNetwork, Crypto.Aead(cfg.tokenEncryptionKey), pending)
            app = auth.protect(HttpRoutes.of[IO] { case _ => Ok("secret data") }).orNotFound
            _ <- sql"INSERT INTO sessions VALUES (${Crypto.sha256("good")}, ${Long.MaxValue})".update.run.transact(xa)
        yield app

    private def withSid(req: Request[IO], sid: String) = req.addCookie(Auth.SessionCookie, sid)

    test("A01: セッションなしは 401 でデータを返さない") {
        for
            app <- setup
            res <- app.run(Request[IO](Method.GET, uri"/api/day"))
            body <- res.as[String]
        yield
            assertEquals(res.status, Status.Unauthorized)
            assert(!body.contains("secret data"))
    }

    test("/api 以外と /api/public は保護せず、後ろのルートへ回す") {
        for
            app <- setup
            page <- app.run(Request[IO](Method.GET, uri"/some/page"))
            public <- app.run(Request[IO](Method.GET, uri"/api/public/availability/x"))
        yield
            assertEquals(page.status, Status.NotFound)
            assertEquals(public.status, Status.NotFound)
    }

    test("有効なセッションなら通す。未知のセッションは 401") {
        for
            app <- setup
            ok <- app.run(withSid(Request[IO](Method.GET, uri"/api/day"), "good"))
            bad <- app.run(withSid(Request[IO](Method.GET, uri"/api/day"), "unknown"))
        yield
            assertEquals(ok.status, Status.Ok)
            assertEquals(bad.status, Status.Unauthorized)
    }

    test("更新系は Origin が BASE_URL と一致しなければ 403") {
        val post = withSid(Request[IO](Method.POST, uri"/api/blocks"), "good")
        for
            app <- setup
            noOrigin <- app.run(post)
            other <- app.run(post.putHeaders(Header.Raw(ci"Origin", "https://evil.test")))
            same <- app.run(post.putHeaders(Header.Raw(ci"Origin", "https://example.test")))
        yield
            assertEquals(noOrigin.status, Status.Forbidden)
            assertEquals(other.status, Status.Forbidden)
            assertEquals(same.status, Status.Ok)
    }

    /** Google のトークンエンドポイントを、指定した sub の ID トークンを返すモックに差し替えてログインを通す。 */
    private def loginAs(sub: String) =
        for
            xa <- IO(Files.createTempFile("login", ".db").toString).map(Db.transactor)
            _ <- Db.migrate(xa)
            pending <- Ref.of[IO, Map[String, Auth.Pending]](Map.empty)
            nonce <- Ref.of[IO, String]("")
            google = Client.fromHttpApp[IO](HttpApp[IO] { _ =>
                nonce.get.flatMap { n =>
                    val claims =
                        s"""{"iss":"https://accounts.google.com","aud":"client","exp":9999999999,"sub":"$sub","nonce":"$n"}"""
                    val idToken = s"h.${Base64.getUrlEncoder.withoutPadding.encodeToString(claims.getBytes)}.s"
                    Ok(s"""{"id_token":"$idToken","refresh_token":"rt"}""")
                }
            })
            auth = Auth(cfg, xa, google, Crypto.Aead(cfg.tokenEncryptionKey), pending)
            app = auth.routes.orNotFound
            login <- app.run(Request[IO](Method.GET, uri"/auth/login"))
            p <- pending.get.map(_.values.head)
            _ <- nonce.set(p.nonce)
            callback <- app.run(
                Request[IO](Method.GET, uri"/auth/callback".withQueryParams(Map("code" -> "c", "state" -> p.state)))
                    .addCookie("oauth_state", p.state)
            )
            creds <- sql"SELECT count(*) FROM google_credentials".query[Int].unique.transact(xa)
            sessions <- sql"SELECT count(*) FROM sessions".query[Int].unique.transact(xa)
        yield (login, callback, creds, sessions)

    test("所有者はログインでき、refresh token とセッションを保存する") {
        loginAs("owner").map { (login, callback, creds, sessions) =>
            assertEquals(login.status, Status.SeeOther)
            assertEquals(callback.status, Status.SeeOther)
            assert(callback.cookies.exists(_.name == Auth.SessionCookie))
            assertEquals((creds, sessions), (1, 1))
        }
    }

    test("A02: 所有者以外は 403 で、Calendar 連携データもセッションも保存しない") {
        loginAs("someone-else").map { (_, callback, creds, sessions) =>
            assertEquals(callback.status, Status.Forbidden)
            assertEquals((creds, sessions), (0, 0))
        }
    }

    test("ID トークンの payload を読む") {
        val payload = """{"iss":"https://accounts.google.com","aud":"client","exp":1,"sub":"owner","nonce":"n"}"""
        val token = s"h.${Base64.getUrlEncoder.withoutPadding.encodeToString(payload.getBytes)}.s"
        assertEquals(Auth.parseClaims(token).map(_.sub), Right("owner"))
        assert(Auth.parseClaims("broken").isLeft)
    }

    test("refresh token の暗号化は往復でき、改ざんを検出する") {
        val aead = Crypto.Aead(new Array[Byte](32))
        val stored = aead.encrypt("refresh-token")
        assertNotEquals(stored, aead.encrypt("refresh-token"))
        assertEquals(aead.decrypt(stored), "refresh-token")
        val bytes = Base64.getDecoder.decode(stored)
        bytes(bytes.length - 1) = (bytes.last ^ 1).toByte
        intercept[javax.crypto.AEADBadTagException](aead.decrypt(Base64.getEncoder.encodeToString(bytes)))
    }
