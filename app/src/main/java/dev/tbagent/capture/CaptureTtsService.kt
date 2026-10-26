package dev.tbagent.capture

import android.media.AudioFormat
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.text.Spanned
import android.text.style.LocaleSpan
import android.text.style.TtsSpan
import android.util.Log
import dev.tbagent.Graph
import dev.tbagent.core.SynthesisStatus
import kotlin.math.min

/**
 * Capture TTS engine (PoC-1).
 *
 * When selected as the system's preferred TTS engine, TalkBack hands every utterance to
 * [onSynthesizeText] as text. We record that text with timestamps, caller and language, and
 * track whether its synthesis completed or was interrupted by [onStop].
 *
 * Audio: we deliver silence whose length mimics speech (so TalkBack's flush/interrupt
 * behaviour stays observable). Optionally the same text is re-spoken through a real engine
 * ([EchoSpeaker]) so the tester can hear it; that echo is NOT evidence of anything.
 */
class CaptureTtsService : TextToSpeechService() {

    @Volatile private var stopRequested = false
    @Volatile private var currentLanguage = arrayOf("eng", "USA", "")
    private var echo: EchoSpeaker? = null

    override fun onCreate() {
        Graph.init(applicationContext)
        super.onCreate()
        echo = EchoSpeaker(applicationContext)
        Log.i(TAG, "Capture engine created")
    }

    override fun onDestroy() {
        echo?.shutdown()
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = when {
        lang.isNullOrEmpty() -> TextToSpeech.LANG_NOT_SUPPORTED
        !variant.isNullOrEmpty() -> TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        !country.isNullOrEmpty() -> TextToSpeech.LANG_COUNTRY_AVAILABLE
        else -> TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> = currentLanguage

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            currentLanguage = arrayOf(lang ?: "", country ?: "", variant ?: "")
        }
        return result
    }

    override fun onStop() {
        stopRequested = true
        echo?.stop()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopRequested = false

        val text: CharSequence = request.charSequenceText ?: ""
        val uid = request.callerUid
        val repo = Graph.utterances
        val planned = Graph.settings.plannedAudioMs(text.length)
        val id = repo.begin(
            text = text.toString(),
            callerUid = uid,
            callerPackage = callerPackage(uid),
            language = request.language ?: "",
            country = request.country ?: "",
            variant = request.variant ?: "",
            voiceName = request.voiceName,
            speechRate = request.speechRate,
            pitch = request.pitch,
            params = bundleToMap(request.params),
            spans = describeSpans(text),
            plannedAudioMs = planned,
        )

        if (Graph.settings.echoEnabled) echo?.speak(text, request.language, request.country, "u$id")

        if (callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
            repo.finish(id, if (stopRequested) SynthesisStatus.INTERRUPTED else SynthesisStatus.ERROR, 0)
            return
        }
        repo.markSynthesizing(id)

        val chunkBytes = min(SAMPLE_RATE * 2 * CHUNK_MS / 1000, callback.maxBufferSize).coerceAtLeast(2) and 1.inv()
        val chunkMs = chunkBytes * 1000L / (SAMPLE_RATE * 2)
        val silence = ByteArray(chunkBytes)
        val startedAt = SystemClock.elapsedRealtime()
        var delivered = 0L

        while (delivered < planned) {
            if (stopRequested) {
                repo.finish(id, SynthesisStatus.INTERRUPTED, delivered)
                return
            }
            if (callback.audioAvailable(silence, 0, chunkBytes) != TextToSpeech.SUCCESS) {
                repo.finish(id, if (stopRequested) SynthesisStatus.INTERRUPTED else SynthesisStatus.ERROR, delivered)
                return
            }
            delivered += chunkMs
            // Pace at real time so a flush from TalkBack arrives while we are still "speaking".
            val ahead = startedAt + delivered - SystemClock.elapsedRealtime()
            if (ahead > 0) {
                try {
                    Thread.sleep(ahead)
                } catch (e: InterruptedException) {
                    repo.finish(id, SynthesisStatus.INTERRUPTED, delivered)
                    return
                }
            }
        }
        if (stopRequested) {
            repo.finish(id, SynthesisStatus.INTERRUPTED, delivered)
            return
        }
        callback.done()
        repo.finish(id, SynthesisStatus.SYNTHESIS_COMPLETED, delivered)
    }

    private fun callerPackage(uid: Int): String? {
        if (uid == Process.myUid()) return packageName
        return runCatching { packageManager.getPackagesForUid(uid)?.joinToString(",") }.getOrNull()
    }

    private fun bundleToMap(b: Bundle?): Map<String, String> {
        if (b == null) return emptyMap()
        return b.keySet().associateWith { key ->
            @Suppress("DEPRECATION")
            b.get(key)?.toString() ?: "null"
        }
    }

    private fun describeSpans(text: CharSequence): List<String> {
        if (text !is Spanned) return emptyList()
        return text.getSpans(0, text.length, Any::class.java).map { span ->
            val range = "${text.getSpanStart(span)}-${text.getSpanEnd(span)}"
            when (span) {
                is TtsSpan -> "TtsSpan(${span.type}) $range ${span.args}"
                is LocaleSpan -> "LocaleSpan(${span.locale}) $range"
                else -> "${span.javaClass.simpleName} $range"
            }
        }
    }

    private companion object {
        const val TAG = "CaptureTts"
        const val SAMPLE_RATE = 16_000
        const val CHUNK_MS = 50
    }
}
