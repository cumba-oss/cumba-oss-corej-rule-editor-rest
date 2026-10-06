package net.cumba.corej.editor.run;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A per-key sliding-window limit: at most {@code aLimit} events per {@code aWindow} — the anonymous
 * run limit per client address (PLAN-rule-editor §5.8.5). Keys that saw nothing in a window are
 * dropped on the next touch, so the map does not grow with every address ever seen.
 */
public final class RateLimiter
{

    private final int limit;

    private final long windowNanos;

    private final LongSupplier clock;

    private final Map<String, Deque<Long>> events = new ConcurrentHashMap<>();

    public RateLimiter(int aLimit, Duration aWindow)
    {
        this(aLimit, aWindow, System::nanoTime);
    }


    RateLimiter(int aLimit, Duration aWindow, LongSupplier aClock)
    {
        limit = aLimit;
        windowNanos = aWindow.toNanos();
        clock = aClock;
    }


    /** Record an event for the key; false when the key is over its limit (nothing recorded). */
    public boolean tryAcquire(String aKey)
    {
        if (limit <= 0)
        {
            return true;
        }
        long now = clock.getAsLong();
        Deque<Long> q = events.computeIfAbsent(aKey, k -> new ArrayDeque<>());
        synchronized (q)
        {
            while (!q.isEmpty() && now - q.peekFirst() >= windowNanos)
            {
                q.pollFirst();
            }
            if (q.size() >= limit)
            {
                return false;
            }
            q.addLast(now);
        }
        events.entrySet().removeIf(e -> e.getValue() != q && stale(e.getValue(), now));
        return true;
    }


    private boolean stale(Deque<Long> aQueue, long aNow)
    {
        synchronized (aQueue)
        {
            Long last = aQueue.peekLast();
            return last == null || aNow - last >= windowNanos;
        }
    }
}
