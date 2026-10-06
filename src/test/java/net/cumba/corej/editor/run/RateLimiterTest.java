package net.cumba.corej.editor.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
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


    @Test
    void idleKeysAreSweptAtMostOncePerWindow()
    {
        // review: removeIf over every key on EVERY call — O(keys) per request
        AtomicLong clock = new AtomicLong();
        RateLimiter limiter = new RateLimiter(1000, Duration.ofSeconds(60), clock::get);
        for (int i = 0; i < 500; i++)
        {
            assertTrue(limiter.tryAcquire("client-" + i));
        }
        assertEquals(0, limiter.sweeps(), "no sweep inside the first window");
        assertEquals(500, limiter.keys());
        clock.set(Duration.ofSeconds(61).toNanos());
        for (int i = 0; i < 100; i++)
        {
            assertTrue(limiter.tryAcquire("late-" + i));
        }
        assertEquals(1, limiter.sweeps(), "one sweep for a window, however many calls");
        assertEquals(100, limiter.keys(), "the idle keys are gone, the live ones stay");
    }


    @Test
    void concurrentCallersNeverGrantMoreThanTheLimit() throws InterruptedException
    {
        // review: an event recorded into a window another thread had just removed was lost
        AtomicLong clock = new AtomicLong();
        RateLimiter limiter = new RateLimiter(50, Duration.ofSeconds(60), clock::get);
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++)
        {
            int id = t;
            Thread thread = new Thread(() ->
            {
                try
                {
                    go.await();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < 200; i++)
                {
                    if (limiter.tryAcquire("shared"))
                    {
                        granted.incrementAndGet();
                    }
                    limiter.tryAcquire("own-" + id + "-" + i);
                    if (i % 50 == 0)
                    {
                        clock.addAndGet(Duration.ofSeconds(61).toNanos()); // force sweeps
                    }
                }
            });
            thread.start();
            threads.add(thread);
        }
        go.countDown();
        for (Thread thread : threads)
        {
            thread.join(30_000);
        }
        assertTrue(granted.get() <= 50 * (1 + 8 * 4), "bounded by the windows: " + granted.get());
        // within one frozen window, exactly the limit
        RateLimiter frozen = new RateLimiter(50, Duration.ofSeconds(60), () -> 0L);
        AtomicInteger once = new AtomicInteger();
        List<Thread> racers = new ArrayList<>();
        for (int t = 0; t < 8; t++)
        {
            Thread thread = new Thread(() ->
            {
                for (int i = 0; i < 100; i++)
                {
                    if (frozen.tryAcquire("k"))
                    {
                        once.incrementAndGet();
                    }
                }
            });
            thread.start();
            racers.add(thread);
        }
        for (Thread thread : racers)
        {
            thread.join(30_000);
        }
        assertEquals(50, once.get(), "exactly the limit, no lost event");
    }
}
