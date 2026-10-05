package schedule

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

import scala.io.Source

object Db:
    // 適用順に並べる。追加したら末尾に足し、既存のファイルは書き換えない。
    private val migrations = List("001_init.sql", "002_blocks.sql", "003_block_task.sql", "004_shares.sql")

    /** foreign_keys・WAL・busy_timeout は接続ごとの設定なので、sqlite-jdbc の URL で毎回指定する。 */
    def transactor(path: String): Transactor[IO] =
        Transactor.fromDriverManager[IO](
            driver = "org.sqlite.JDBC",
            url = s"jdbc:sqlite:$path?foreign_keys=true&journal_mode=WAL&busy_timeout=5000",
            logHandler = None
        )

    /** PRAGMA user_version に適用済みの件数を持ち、未適用のマイグレーションだけを 1 件ずつトランザクションで流す。 */
    def migrate(xa: Transactor[IO]): IO[Unit] =
        for
            applied <- sql"PRAGMA user_version".query[Int].unique.transact(xa)
            _ <- migrations.zipWithIndex.drop(applied).traverse_ { (name, i) =>
                val statements = statementsOf(name).traverse_(s => Fragment.const(s).update.run)
                (statements *> Fragment.const(s"PRAGMA user_version = ${i + 1}").update.run).transact(xa)
            }
        yield ()

    // ponytail: ";" で分割するだけなので、トリガーや文字列中の ";" は書けない。必要になったら JDBC の executeBatch へ
    private def statementsOf(name: String): List[String] =
        val src = Source.fromResource(s"migrations/$name", getClass.getClassLoader)
        try
            src.mkString
                .split(";")
                .map(_.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n").trim)
                .filter(_.nonEmpty)
                .toList
        finally src.close()
