package net.cumba.corej.editor.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The per-key sliding window behind the anonymous run limit (§5.8.5). */
class RateLimiterTest
{

    @Test
    void theLimitIsPerKeyAndTheWindowSlides()
    {
        AtomicLong clock = new AtomicLong();
        RateLimiter limiter = new RateLimiter(2, Duration.ofSeconds(60), clock::get);
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"), "the third within the window is refused");
        assertTrue(limiter.tryAcquire("b"), "another key has its own budget");
        clock.set(Duration.ofSeconds(59).toNanos());
        assertFalse(limiter.tryAcquire("a"), "still inside the window");
        clock.set(Duration.ofSeconds(61).toNanos());
        assertTrue(limiter.tryAcquire("a"), "the window slid: the first events expired");
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"));
    }


    @Test
    void aZeroLimitDisablesTheLimiter()
    {
        RateLimiter limiter = new RateLimiter(0, Duration.ofSeconds(1));
        for (int i = 0; i < 100; i++)
        {
            assertTrue(limiter.tryAcquire("a"));
        }
    }
}
