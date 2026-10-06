package net.cumba.corej.editor.run;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A per-key sliding-window limit: at most {@code aLimit} events per {@code aWindow} — the anonymous
 * run and search limits and the login limit per client (PLAN-rule-editor §5.8.5, security review
 * M4, L9, L12).
 *
 * <p>
 * Each key's window is updated atomically inside {@code ConcurrentHashMap.compute} (the earlier
 * check-then-act could record an event into a window another thread had just removed — a lost
 * event, so one more grant than the limit), and a key whose window empties is removed by the same
 * atomic step. Keys that saw nothing for a window are swept at most once per window, not on every
 * call (that was O(keys) per request).
 * </p>
 */
public final class RateLimiter
{

    private final int limit;

    private final long windowNanos;

    private final LongSupplier clock;

    private final Map<String, Deque<Long>> events = new ConcurrentHashMap<>();

    private final AtomicLong lastSweep;

    private final AtomicInteger sweeps = new AtomicInteger();

    public RateLimiter(int aLimit, Duration aWindow)
    {
        this(aLimit, aWindow, System::nanoTime);
    }


    RateLimiter(int aLimit, Duration aWindow, LongSupplier aClock)
    {
        limit = aLimit;
        windowNanos = aWindow.toNanos();
        clock = aClock;
        lastSweep = new AtomicLong(aClock.getAsLong());
    }


    /** Record an event for the key; false when the key is over its limit (nothing recorded). */
    public boolean tryAcquire(String aKey)
    {
        if (limit <= 0)
        {
            return true;
        }
        long now = clock.getAsLong();
        sweep(now);
        boolean[] granted = new boolean[1];
        events.compute(aKey, (k, window) ->
        {
            Deque<Long> q = window == null ? new ArrayDeque<>() : window;
            expire(q, now);
            if (q.size() < limit)
            {
                q.addLast(now);
                granted[0] = true;
            }
            return q.isEmpty() ? null : q;
        });
        return granted[0];
    }


    private void expire(Deque<Long> aWindow, long aNow)
    {
        while (!aWindow.isEmpty() && aNow - aWindow.peekFirst() >= windowNanos)
        {
            aWindow.pollFirst();
        }
    }


    /** Drops the keys whose window emptied — at most once per window, by one caller. */
    private void sweep(long aNow)
    {
        long last = lastSweep.get();
        if (aNow - last < windowNanos || !lastSweep.compareAndSet(last, aNow))
        {
            return;
        }
        sweeps.incrementAndGet();
        for (String key : events.keySet())
        {
            events.computeIfPresent(key, (k, window) ->
            {
                expire(window, aNow);
                return window.isEmpty() ? null : window;
            });
        }
    }


    /** How many keys are tracked (tests). */
    int keys()
    {
        return events.size();
    }


    /** How many sweeps ran (tests). */
    int sweeps()
    {
        return sweeps.get();
    }
}
