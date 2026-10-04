package schedule

import cats.effect.IO
import doobie.implicits.*

import java.nio.file.Files

class DbSuite extends munit.CatsEffectSuite:
    private def freshDb = IO(Files.createTempFile("schedule", ".db").toString).map(Db.transactor)

    test("マイグレーションは 2 回流しても 1 回分だけ適用され、外部キーと WAL が有効") {
        for
            xa <- freshDb
            _ <- Db.migrate(xa)
            _ <- Db.migrate(xa)
            version <- sql"PRAGMA user_version".query[Int].unique.transact(xa)
            fk <- sql"PRAGMA foreign_keys".query[Int].unique.transact(xa)
            mode <- sql"PRAGMA journal_mode".query[String].unique.transact(xa)
            settings <- sql"SELECT count(*) FROM app_settings".query[Int].unique.transact(xa)
        yield
            assertEquals(version, 2)
            assertEquals(fk, 1)
            assertEquals(mode, "wal")
            assertEquals(settings, 1)
    }

    test("活動可能時間の CHECK 制約で開始 >= 終了を拒否する") {
        for
            xa <- freshDb
            _ <- Db.migrate(xa)
            r <- sql"INSERT INTO weekly_windows VALUES ('w', 0, 600, 600)".update.run.transact(xa).attempt
        yield assert(r.isLeft)
    }
