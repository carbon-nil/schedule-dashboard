package schedule

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

final case class Share(
    id: String,
    rangeStart: Long,
    rangeEnd: Long,
    expiresAt: Long,
    revokedAt: Option[Long],
    minFreeMinutes: Int,
    createdAt: Long,
    version: Int
)

/** 共有リンクの保存と失効 (設計書 5.5)。公開トークンの平文は作成時に一度だけ返す。 */
object Shares:
    private val columns =
        fr"id, range_start, range_end, expires_at, revoked_at, min_free_minutes, created_at, version"

    def list: ConnectionIO[List[Share]] =
        (fr"SELECT" ++ columns ++ fr"FROM shares ORDER BY created_at DESC").query[Share].to[List]

    /** id はクライアントが作った requestId。同じ id が既にあれば作らずに Conflict。 */
    def create(id: String, tokenHash: String, share: Share): ConnectionIO[Change[Share]] =
        sql"SELECT 1 FROM shares WHERE id = $id".query[Int].option.flatMap {
            case Some(_) => FC.pure(Change.Conflict)
            case None    =>
                sql"""INSERT INTO shares (id, token_hash, range_start, range_end, expires_at, min_free_minutes, created_at)
                      VALUES ($id, $tokenHash, ${share.rangeStart}, ${share.rangeEnd}, ${share.expiresAt},
                              ${share.minFreeMinutes}, ${share.createdAt})""".update.run
                    .as(Change.Done(share.copy(id = id, version = 1)))
        }

    def revoke(id: String, expectedVersion: Int, now: Long): ConnectionIO[Change[Share]] =
        sql"""UPDATE shares SET revoked_at = COALESCE(revoked_at, $now), version = version + 1
              WHERE id = $id AND version = $expectedVersion""".update.run.flatMap {
            case 0 => find(id).map(_.fold(Change.NotFound)(_ => Change.Conflict))
            case _ => find(id).map(_.fold(Change.NotFound)(Change.Done(_)))
        }

    private def find(id: String): ConnectionIO[Option[Share]] =
        (fr"SELECT" ++ columns ++ fr"FROM shares WHERE id = $id").query[Share].option

    /** 有効なリンクだけ返す。失効・期限切れ・不明はすべて None (A12)。毎回 DB で確かめる。 */
    def valid(tokenHash: String, now: Long): ConnectionIO[Option[Share]] =
        (fr"SELECT" ++ columns ++
            fr"FROM shares WHERE token_hash = $tokenHash AND revoked_at IS NULL AND expires_at > $now")
            .query[Share]
            .option
