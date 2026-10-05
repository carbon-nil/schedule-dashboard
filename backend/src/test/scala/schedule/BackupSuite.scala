package schedule

import cats.effect.IO
import doobie.implicits.*

import java.nio.file.Files
import java.time.{LocalDate, ZonedDateTime}
import scala.jdk.CollectionConverters.*

class BackupSuite extends munit.CatsEffectSuite:
    private def freshDir = IO(Files.createTempDirectory("schedule"))

    test("バックアップは本体と同じスキーマと行を持ち、同じ日に 2 回取れる") {
        for
            tmp <- freshDir
            xa = Db.transactor(tmp.resolve("schedule.db").toString)
            _ <- Db.migrate(xa)
            _ <- Blocks.create("b", Some("t"), None, 1, 2).transact(xa)
            dir = tmp.resolve("backups")
            file <- Backup.run(xa, dir, LocalDate.of(2026, 10, 5))
            again <- Backup.run(xa, dir, LocalDate.of(2026, 10, 5))
            copy = Db.transactor(file.toString)
            version <- sql"PRAGMA user_version".query[Int].unique.transact(copy)
            blocks <- sql"SELECT count(*) FROM blocks".query[Int].unique.transact(copy)
        yield
            assertEquals(file, dir.resolve("schedule-2026-10-05.db"))
            assertEquals(again, file)
            assertEquals(version, 4)
            assertEquals(blocks, 1)
    }

    test("古いバックアップは新しい順に Keep 個だけ残す") {
        for
            tmp <- freshDir
            xa = Db.transactor(tmp.resolve("schedule.db").toString)
            _ <- Db.migrate(xa)
            dir = tmp.resolve("backups")
            _ <- IO.blocking {
                Files.createDirectories(dir)
                (1 to Backup.Keep).foreach(d => Files.createFile(dir.resolve(f"schedule-2026-09-$d%02d.db")))
                Files.createFile(dir.resolve("notes.txt"))
            }
            _ <- Backup.run(xa, dir, LocalDate.of(2026, 10, 5))
            names <- IO.blocking(Files.list(dir).iterator.asScala.map(_.getFileName.toString).toList)
        yield
            assertEquals(names.size, Backup.Keep + 1)
            assert(!names.contains("schedule-2026-09-01.db"))
            assert(names.contains("schedule-2026-09-02.db"))
            assert(names.contains("schedule-2026-10-05.db"))
            assert(names.contains("notes.txt"))
    }

    test("次の実行は当日か翌日の 03:00 JST") {
        val before = ZonedDateTime.of(2026, 10, 5, 2, 59, 0, 0, Availability.Zone)
        val after = ZonedDateTime.of(2026, 10, 5, 3, 0, 0, 0, Availability.Zone)
        assertEquals(Backup.nextRun(before), before.withHour(3).withMinute(0))
        assertEquals(Backup.nextRun(after), after.plusDays(1))
    }
