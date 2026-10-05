package schedule

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.util.transactor.Strategy

import java.nio.file.{Files, Path}
import java.time.{LocalDate, LocalTime, ZonedDateTime}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** SQLite の毎日のバックアップ (設計書 14 章)。VACUUM INTO は WAL 稼働中でも一貫した 1 ファイルを書く。 */
object Backup:
    /** 残す日数。volume を際限なく使わないため */
    val Keep = 30
    private val At = LocalTime.of(3, 0)
    private val Name = """schedule-\d{4}-\d{2}-\d{2}\.db""".r

    /** `dir/schedule-<date>.db` を書き直し、日付の古いものを Keep 個を超えた分だけ消す。 */
    def run(xa: Transactor[IO], dir: Path, date: LocalDate): IO[Path] =
        val file = dir.resolve(s"schedule-$date.db")
        // VACUUM はトランザクションの中では動かないので、doobie の BEGIN/COMMIT を外す
        val plain = Transactor.strategy[IO].set(xa, Strategy.void)
        for
            _ <- IO.blocking {
                Files.createDirectories(dir)
                Files.deleteIfExists(file)
            }
            _ <- sql"VACUUM INTO ${file.toString}".update.run.transact(plain)
            _ <- IO.blocking(prune(dir))
        yield file

    /** 毎日 03:00 JST に取る。失敗しても止めず、翌日にまた試す。 */
    def daily(xa: Transactor[IO], databasePath: String): IO[Nothing] =
        val dir = Path.of(databasePath).toAbsolutePath.resolveSibling("backups")
        val step =
            for
                now <- IO.realTimeInstant.map(_.atZone(Availability.Zone))
                next = nextRun(now)
                _ <- IO.sleep((next.toInstant.toEpochMilli - now.toInstant.toEpochMilli).millis)
                _ <- run(xa, dir, next.toLocalDate).void.handleErrorWith(e =>
                    IO.consoleForIO.errorln(s"backup failed: $e")
                )
            yield ()
        step.foreverM

    def nextRun(now: ZonedDateTime): ZonedDateTime =
        val today = now.toLocalDate.atTime(At).atZone(now.getZone)
        if today.isAfter(now) then today else today.plusDays(1)

    private def prune(dir: Path): Unit =
        val listing = Files.list(dir)
        try
            val files = listing.iterator.asScala.filter(p => Name.matches(p.getFileName.toString)).toList.sorted
            files.dropRight(Keep).foreach(Files.delete)
        finally listing.close()
