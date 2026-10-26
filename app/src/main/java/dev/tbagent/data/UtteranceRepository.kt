package dev.tbagent.data

import android.os.SystemClock
import android.util.Log
import dev.tbagent.core.SynthesisStatus
import dev.tbagent.core.Utterance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Everything our TTS engine was asked to synthesize. In memory (for correlation and the UI)
 * and appended to a JSONL file once each utterance reaches a final state.
 */
class UtteranceRepository(private val file: File) {
    private val lock = Any()
    private var nextId = 1L
    private val _items = MutableStateFlow<List<Utterance>>(emptyList())
    val items: StateFlow<List<Utterance>> = _items.asStateFlow()

    init {
        val loaded = runCatching {
            if (!file.exists()) emptyList()
            else file.readLines().takeLast(MAX_ITEMS).mapNotNull { line ->
                runCatching { JsonCodec.utteranceFromJson(JSONObject(line)) }.getOrNull()
            }
        }.getOrElse {
            Log.w(TAG, "Could not load $file", it)
            emptyList()
        }
        _items.value = loaded
        nextId = (loaded.maxOfOrNull { it.id } ?: 0L) + 1
    }

    fun snapshot(): List<Utterance> = _items.value

    fun begin(
        text: String,
        callerUid: Int,
        callerPackage: String?,
        language: String,
        country: String,
        variant: String,
        voiceName: String?,
        speechRate: Int,
        pitch: Int,
        params: Map<String, String>,
        spans: List<String>,
        plannedAudioMs: Long,
    ): Long = synchronized(lock) {
        val id = nextId++
        val u = Utterance(
            id = id,
            receivedAtElapsed = SystemClock.elapsedRealtime(),
            receivedAtWallMillis = System.currentTimeMillis(),
            text = text,
            callerUid = callerUid,
            callerPackage = callerPackage,
            language = language,
            country = country,
            variant = variant,
            voiceName = voiceName,
            speechRate = speechRate,
            pitch = pitch,
            params = params,
            spans = spans,
            status = SynthesisStatus.REQUESTED,
            plannedAudioMs = plannedAudioMs,
        )
        _items.value = (_items.value + u).takeLast(MAX_ITEMS)
        Log.i(TAG, "REQUESTED #$id from $callerPackage: $text")
        id
    }

    fun markSynthesizing(id: Long) = update(id) { it.copy(status = SynthesisStatus.SYNTHESIZING) }

    fun finish(id: Long, status: SynthesisStatus, deliveredAudioMs: Long) {
        val done = update(id) {
            it.copy(status = status, finishedAtElapsed = SystemClock.elapsedRealtime(), deliveredAudioMs = deliveredAudioMs)
        } ?: return
        Log.i(TAG, "$status #$id (${deliveredAudioMs}ms audio)")
        synchronized(lock) {
            runCatching { file.appendText(JsonCodec.toJson(done).toString() + "\n") }
                .onFailure { Log.w(TAG, "persist failed", it) }
        }
    }

    fun clear() = synchronized(lock) {
        _items.value = emptyList()
        file.delete()
    }

    private fun update(id: Long, f: (Utterance) -> Utterance): Utterance? = synchronized(lock) {
        var result: Utterance? = null
        _items.value = _items.value.map { if (it.id == id) f(it).also { n -> result = n } else it }
        result
    }

    companion object {
        private const val TAG = "CaptureTts"
        private const val MAX_ITEMS = 1000
    }
}
