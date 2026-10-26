package dev.tbagent.data

import android.content.Context

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Re-speak every captured utterance through a real engine so the tester can hear it. */
    var echoEnabled: Boolean
        get() = p.getBoolean("echo", true)
        set(v) = p.edit().putBoolean("echo", v).apply()

    /** Package of the engine used for the audible echo; null = auto (Samsung, then Google). */
    var echoEngine: String?
        get() = p.getString("echo_engine", null)
        set(v) = p.edit().putString("echo_engine", v).apply()

    /** Deliver no audio at all: faster, but interruptions can no longer be observed. */
    var fastMode: Boolean
        get() = p.getBoolean("fast", false)
        set(v) = p.edit().putBoolean("fast", v).apply()

    /** Length of the (silent) audio we produce per character, to mimic real speech timing. */
    var msPerChar: Int
        get() = p.getInt("ms_per_char", 65)
        set(v) = p.edit().putInt("ms_per_char", v).apply()

    /** Set by the tester after Milestone 1 confirmed captured text == what TalkBack said. */
    var captureValidated: Boolean
        get() = p.getBoolean("capture_validated", false)
        set(v) = p.edit().putBoolean("capture_validated", v).apply()

    var lastUrl: String
        get() = p.getString("last_url", "") ?: ""
        set(v) = p.edit().putString("last_url", v).apply()

    var lastInstruction: String
        get() = p.getString("last_instruction", "") ?: ""
        set(v) = p.edit().putString("last_instruction", v).apply()

    fun plannedAudioMs(textLength: Int): Long =
        if (fastMode) 0 else (textLength.toLong() * msPerChar).coerceIn(250, 15_000)
}
