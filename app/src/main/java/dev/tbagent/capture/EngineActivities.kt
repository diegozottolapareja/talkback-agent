package dev.tbagent.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.MissingResourceException

/** Answers the framework's CHECK_TTS_DATA query: every common language is "installed". */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val voices = ArrayList<String>()
        for (l in Locale.getAvailableLocales()) {
            if (l.country.isEmpty() || l.variant.isNotEmpty()) continue
            try {
                voices += "${l.isO3Language}-${l.isO3Country}"
            } catch (_: MissingResourceException) {
            }
        }
        val data = Intent()
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(voices.distinct()))
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList())
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, data)
        finish()
    }
}

class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spanish = intent?.getStringExtra("language")?.startsWith("spa") == true
        val sample = if (spanish) "Esto es una prueba del motor de captura." else "This is a capture engine test."
        setResult(TextToSpeech.LANG_AVAILABLE, Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample))
        finish()
    }
}
