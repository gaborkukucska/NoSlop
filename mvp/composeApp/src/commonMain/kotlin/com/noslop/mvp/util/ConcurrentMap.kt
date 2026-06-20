package com.noslop.mvp.util

expect class ConcurrentMap<K, V>() {
    fun get(key: K): V?
    fun put(key: K, value: V)
    fun remove(key: K): V?
    fun clear()
    fun containsKey(key: K): Boolean
    fun computeIfAbsent(key: K, mappingFunction: (K) -> V): V
}
