package dev.tbagent.capture

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import dev.tbagent.Graph
import java.util.Locale
import java.util.MissingResourceException

/**
 * Re-speaks captured text through a real TTS engine (Samsung / Google) so the tester can hear
 * TalkBack while our engine is selected. Purely a convenience: what is recorded comes from
 * [CaptureTtsService], never from this echo.
 */
class EchoSpeaker(context: Context) {
    @Volatile private var ready = false
    private var tts: TextToSpeech? = null
    private var lastLocale: Locale? = null
    val enginePackage: String? = pickEngine(context)

    init {
        if (enginePackage != null) {
            tts = TextToSpeech(context, { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                }
                Log.i(TAG, "Echo engine $enginePackage ready=$ready")
            }, enginePackage)
        }
    }

    fun speak(text: CharSequence, iso3Language: String?, iso3Country: String?, utteranceId: String) {
        val t = tts ?: return
        if (!ready) return
        val locale = toLocale(iso3Language, iso3Country)
        if (locale != null && locale != lastLocale) {
            runCatching { t.setLanguage(locale) }
            lastLocale = locale
        }
        t.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    fun stop() {
        if (ready) tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "CaptureTts"
        val PREFERRED = listOf("com.samsung.SMT", "com.google.android.tts")

        fun installedEngines(context: Context): List<String> =
            context.packageManager
                .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .map { it.serviceInfo.packageName }
                .filter { it != context.packageName }
                .distinct()

        fun pickEngine(context: Context): String? {
            val engines = installedEngines(context)
            Graph.settings.echoEngine?.let { if (it in engines) return it }
            return PREFERRED.firstOrNull { it in engines } ?: engines.firstOrNull()
        }

        private val iso3ToLocale: Map<String, Locale> by lazy {
            val m = HashMap<String, Locale>()
            for (l in Locale.getAvailableLocales()) {
                try {
                    val key = l.isO3Language + "-" + (if (l.country.isEmpty()) "" else l.isO3Country)
                    m.putIfAbsent(key, l)
                    m.putIfAbsent(l.isO3Language + "-", Locale(l.language))
                } catch (_: MissingResourceException) {
                }
            }
            m
        }

        fun toLocale(iso3Language: String?, iso3Country: String?): Locale? {
            if (iso3Language.isNullOrEmpty()) return null
            return iso3ToLocale["$iso3Language-${iso3Country ?: ""}"] ?: iso3ToLocale["$iso3Language-"]
        }
    }
}
