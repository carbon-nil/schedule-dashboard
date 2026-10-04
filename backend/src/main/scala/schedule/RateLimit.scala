package schedule

import cats.effect.{IO, Ref}

/** 公開 API の回数制限 (設計書 10 章)。1 分の固定窓をキーごとにメモリで数える。 */
// ponytail: 固定窓なので境界で最大 2 倍通る。厳密にしたいなら sliding window へ
final class RateLimit(state: Ref[IO, Map[String, (Long, Int)]]):
    def allow(key: String, perMinute: Int): IO[Boolean] =
        IO.realTime.flatMap { now =>
            val window = now.toMillis / 60000
            state.modify { m =>
                val count = m.get(key).filter(_._1 == window).map(_._2).getOrElse(0) + 1
                // 古い窓の項目は触ったときに捨てる
                val next = m.filter(_._2._1 == window) + (key -> (window, count))
                (next, count <= perMinute)
            }
        }
