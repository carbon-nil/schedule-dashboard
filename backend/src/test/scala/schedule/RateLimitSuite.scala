package schedule

import cats.effect.{IO, Ref}
import cats.syntax.all.*

class RateLimitSuite extends munit.CatsEffectSuite:
    test("上限までは通し、超えたら拒否する。キー数の上限を超えると新しいキーは通さない") {
        for
            ref <- Ref.of[IO, (Long, Map[String, Int])]((0L, Map.empty))
            limiter = RateLimit(ref)
            first <- (1 to 3).toList.traverse(_ => limiter.allow("a", 2))
            _ <- (1 to RateLimit.MaxKeys - 1).toList.traverse_(i => limiter.allow(s"k$i", 1))
            known <- limiter.allow("a", 10)
            unknown <- limiter.allow("new", 10)
            size <- ref.get.map(_._2.size)
        yield
            assertEquals(first, List(true, true, false))
            assert(known, "既にあるキーは上限いっぱいでも数える")
            assert(!unknown, "キー数の上限を超えたら新しいキーは通さない")
            assertEquals(size, RateLimit.MaxKeys)
    }
