package com.noslop.mvp.util

import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_mutex_destroy
import kotlinx.cinterop.cValue
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.nativeHeap

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual class ConcurrentMap<K, V> actual constructor() {
    private val map = mutableMapOf<K, V>()
    private val mutex = nativeHeap.alloc<pthread_mutex_t>()

    init {
        pthread_mutex_init(mutex.ptr, null)
    }

    actual fun get(key: K): V? = lock { map[key] }

    actual fun put(key: K, value: V) {
        lock { map[key] = value }
    }

    actual fun remove(key: K): V? = lock { map.remove(key) }

    actual fun clear() {
        lock { map.clear() }
    }

    actual fun containsKey(key: K): Boolean = lock { map.containsKey(key) }

    actual fun computeIfAbsent(key: K, mappingFunction: (K) -> V): V = lock {
        map[key] ?: mappingFunction(key).also { map[key] = it }
    }

    private inline fun <T> lock(block: () -> T): T {
        pthread_mutex_lock(mutex.ptr)
        try {
            return block()
        } finally {
            pthread_mutex_unlock(mutex.ptr)
        }
    }
}
