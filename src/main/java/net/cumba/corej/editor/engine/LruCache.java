package net.cumba.corej.editor.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small access-ordered LRU map: the per-view caches of the engine integration (the package
 * standards per content, the corpus index per view). Not thread-safe on its own — callers
 * synchronise.
 *
 * @param <K>
 *            the key
 * @param <V>
 *            the value
 */
public final class LruCache<K, V> extends LinkedHashMap<K, V>
{

    private static final long serialVersionUID = 1L;

    private final int maxEntries;

    public LruCache(int aMaxEntries)
    {
        super(16, 0.75f, true);
        maxEntries = aMaxEntries;
    }


    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> aEldest)
    {
        return size() > maxEntries;
    }
}
