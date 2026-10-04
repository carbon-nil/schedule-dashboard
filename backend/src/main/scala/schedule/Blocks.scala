package schedule

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

final case class Block(id: String, title: String, start: Long, end: Long, version: Int)

enum Change[+A]:
    case Done(value: A)
    case NotFound
    case Conflict

/** Block の CRUD と version の比較 (設計書 5.3、11 章)。 */
object Blocks:
    def inRange(range: Interval): ConnectionIO[List[Block]] =
        sql"""SELECT id, title, start_at, end_at, version FROM blocks
              WHERE start_at < ${range.end} AND end_at > ${range.start} ORDER BY start_at"""
            .query[Block]
            .to[List]

    private def find(id: String): ConnectionIO[Option[Block]] =
        sql"SELECT id, title, start_at, end_at, version FROM blocks WHERE id = $id".query[Block].option

    /** id はクライアントが作った requestId。同じ id が既にあれば作らずに Conflict。 */
    def create(id: String, title: String, start: Long, end: Long): ConnectionIO[Change[Block]] =
        find(id).flatMap {
            case Some(_) => FC.pure(Change.Conflict)
            case None    =>
                sql"INSERT INTO blocks (id, title, start_at, end_at) VALUES ($id, $title, $start, $end)".update.run
                    .as(Change.Done(Block(id, title, start, end, 1)))
        }

    def move(id: String, start: Long, end: Long, expectedVersion: Int): ConnectionIO[Change[Block]] =
        sql"""UPDATE blocks SET start_at = $start, end_at = $end, version = version + 1
              WHERE id = $id AND version = $expectedVersion""".update.run.flatMap {
            case 0 => find(id).map(_.fold(Change.NotFound)(_ => Change.Conflict))
            case _ => find(id).map(_.fold(Change.NotFound)(Change.Done(_)))
        }

    def delete(id: String, expectedVersion: Int): ConnectionIO[Change[Unit]] =
        sql"DELETE FROM blocks WHERE id = $id AND version = $expectedVersion".update.run.flatMap {
            case 0 => find(id).map(_.fold(Change.NotFound)(_ => Change.Conflict))
            case _ => FC.pure(Change.Done(()))
        }
