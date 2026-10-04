package schedule

import cats.effect.{IO, Ref}

/** 公開 API の回数制限 (設計書 10 章)。1 分の固定窓をキーごとにメモリで数える。状態は (窓, キーごとの回数)。 */
// ponytail: 固定窓なので境界で最大 2 倍通る。厳密にしたいなら sliding window へ
final class RateLimit(state: Ref[IO, (Long, Map[String, Int])]):
    import RateLimit.*

    def allow(key: String, perMinute: Int): IO[Boolean] =
        IO.realTime.flatMap { now =>
            val window = now.toMillis / 60000
            state.modify { (w, counts) =>
                // 窓が変わったら前の窓の回数は全部捨てる。1 つの窓で持つキー数にも上限を置き、超えたら新しいキーは通さない
                val base = if w == window then counts else Map.empty[String, Int]
                if !base.contains(key) && base.size >= MaxKeys then ((window, base), false)
                else
                    val count = base.getOrElse(key, 0) + 1
                    ((window, base.updated(key, count)), count <= perMinute)
            }
        }

object RateLimit:
    val MaxKeys = 10000
    def empty: IO[RateLimit] = Ref.of[IO, (Long, Map[String, Int])]((0L, Map.empty)).map(RateLimit(_))
