package com.lekathedon.visionlite.data

/**
 * A memory cache that never grows past [max] entries: the least recently used one goes first (weak boxes: every
 * title-keyed cache is capped, so browsing for hours can't fill the heap). Thread-safe; getOrPut is not atomic
 * (two callers may both compute, as with ConcurrentHashMap.getOrPut) so its block may suspend.
 */
class Lru<K : Any, V : Any>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }
    operator fun get(key: K): V? = synchronized(map) { map[key] }
    operator fun set(key: K, value: V) { synchronized(map) { map[key] = value } }
    fun remove(key: K): V? = synchronized(map) { map.remove(key) }
    inline fun getOrPut(key: K, make: () -> V): V = get(key) ?: make().also { set(key, it) }
}
