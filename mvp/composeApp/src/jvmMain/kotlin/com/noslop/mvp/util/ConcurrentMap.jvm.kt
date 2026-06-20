package com.noslop.mvp.util

import java.util.concurrent.ConcurrentHashMap

actual class ConcurrentMap<K, V> actual constructor() {
    private val map = ConcurrentHashMap<K, V>()

    actual fun get(key: K): V? = map[key]
    actual fun put(key: K, value: V) {
        map[key] = value
    }
    actual fun remove(key: K): V? = map.remove(key)
    actual fun clear() = map.clear()
    actual fun containsKey(key: K): Boolean = map.containsKey(key)
    actual fun computeIfAbsent(key: K, mappingFunction: (K) -> V): V =
        map.computeIfAbsent(key) { mappingFunction(it) }
}
