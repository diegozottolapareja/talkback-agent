package dev.tbagent.data

import android.os.SystemClock
import dev.tbagent.core.A11yEventRecord
import dev.tbagent.core.Bounds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Accessibility events seen by our controller service (in memory only). */
class A11yEventLog {
    private val lock = Any()
    private var nextId = 1L
    private val _items = MutableStateFlow<List<A11yEventRecord>>(emptyList())
    val items: StateFlow<List<A11yEventRecord>> = _items.asStateFlow()

    fun add(type: String, packageName: String?, className: String?, text: String, contentDescription: String?, bounds: Bounds?) =
        synchronized(lock) {
            val r = A11yEventRecord(
                id = nextId++,
                atElapsed = SystemClock.elapsedRealtime(),
                atWallMillis = System.currentTimeMillis(),
                type = type,
                packageName = packageName,
                className = className,
                text = text,
                contentDescription = contentDescription,
                bounds = bounds,
            )
            _items.value = (_items.value + r).takeLast(MAX_ITEMS)
        }

    fun between(fromElapsed: Long, toElapsed: Long): List<A11yEventRecord> =
        _items.value.filter { it.atElapsed in fromElapsed..toElapsed }

    fun clear() = synchronized(lock) { _items.value = emptyList() }

    private companion object {
        const val MAX_ITEMS = 1500
    }
}
