package dev.tbagent.ai

import dev.tbagent.data.JsonCodec
import dev.tbagent.core.RunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AiEvaluation(
    val answered: String,
    val summary: String,
    val findings: List<String>,
    val caveats: List<String>,
)

sealed interface EvaluationOutcome {
    data class Done(val evaluation: AiEvaluation) : EvaluationOutcome
    data class Error(val message: String) : EvaluationOutcome
}

/**
 * Sends the collected evidence of a run to OpenAI and asks whether the tester's original request
 * was answered. This is an AI opinion shown next to, never instead of, the app's own verdict.
 */
class OpenAiEvaluator(private val apiKey: String, private val model: String) {

    suspend fun evaluate(result: RunResult): EvaluationOutcome = withContext(Dispatchers.IO) {
        try {
            val evidence = prune(JsonCodec.toJson(result)).toString().take(MAX_EVIDENCE_CHARS)
            val body = JSONObject()
                .put("model", model)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        .put(
                            JSONObject().put(
                                "role",
                                "user",
                            ).put("content", "ORIGINAL REQUEST:\n${result.instruction}\n\nEVIDENCE (JSON):\n$evidence"),
                        ),
                )
            val conn = (URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                    return@withContext EvaluationOutcome.Error("OpenAI answered $code. ${msg.orEmpty()}")
                }
                val content = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                val o = JSONObject(content)
                EvaluationOutcome.Done(
                    AiEvaluation(
                        answered = o.optString("answered", "INSUFFICIENT_EVIDENCE"),
                        summary = o.optString("summary"),
                        findings = strings(o.optJSONArray("findings")),
                        caveats = strings(o.optJSONArray("caveats")),
                    ),
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            EvaluationOutcome.Error("Could not evaluate: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.optString(i) } } ?: emptyList()

    /** Drops bulky, low-value fields so the evidence stays small. */
    private fun prune(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { out ->
            for (k in value.keys()) if (k !in DROP) out.put(k, prune(value.get(k)))
        }
        is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(prune(value.get(i))) }
        else -> value
    }

    private companion object {
        const val MAX_EVIDENCE_CHARS = 60_000
        val DROP = setOf(
            "params", "spans", "callerUid", "voiceName", "speechRate", "pitch", "variant", "country", "language",
            "receivedAtWallMillis", "receivedAtElapsed", "finishedAtElapsed", "atWallMillis", "atElapsed",
            "actionAtWallMillis", "actionAtElapsed", "startedAtWallMillis", "finishedAtWallMillis", "extras",
            "screenshot", "plannedAudioMs", "deliveredAudioMs", "id", "callerPackage",
        )
        val SYSTEM_PROMPT = """
            You review the evidence collected by a tool that moves Android TalkBack focus to an element in Chrome
            and records what TalkBack sent to the text-to-speech engine.
            Decide whether the evidence answers the tester's ORIGINAL REQUEST.
            Rules:
            - Use only the evidence. Never assume what TalkBack said beyond the recorded utterance texts.
            - Utterance texts are fragments in the order TalkBack requested them; status INTERRUPTED means the
              synthesis was cut off, not that the text was not requested.
            - Node properties (text, contentDescription, hintText) are inputs to TalkBack, not announcements.
            - If the evidence cannot answer the request, say so and name what is missing.
            - If a strategy did not reach the target, do not rely on its utterances.
            Reply with one JSON object:
            {"answered": "YES" | "NO" | "PARTIAL" | "INSUFFICIENT_EVIDENCE",
             "summary": "2-4 sentences answering the request directly, quoting the exact announced text",
             "findings": ["short bullet", ...],
             "caveats": ["short bullet", ...]}
            Write in English.
        """.trimIndent()
    }
}
