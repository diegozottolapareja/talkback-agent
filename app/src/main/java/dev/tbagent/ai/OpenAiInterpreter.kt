package dev.tbagent.ai

import dev.tbagent.core.Expectation
import dev.tbagent.core.Interpretation
import dev.tbagent.core.NavigationStrategy
import dev.tbagent.core.Role
import dev.tbagent.core.RuleBasedInterpreter
import dev.tbagent.core.RunSpec
import dev.tbagent.core.TargetQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns a free-text request into a [RunSpec] with the OpenAI Chat Completions API.
 * The API key is supplied by the user on the phone and is never logged or exported.
 * The model only structures the request; it never decides what TalkBack announced.
 */
class OpenAiInterpreter(private val apiKey: String, private val model: String) {

    suspend fun interpret(instruction: String, url: String?): Interpretation = withContext(Dispatchers.IO) {
        val reply = try {
            call(instruction, url)
        } catch (e: ApiException) {
            return@withContext Interpretation.NeedsClarification(e.message ?: "Error calling OpenAI.")
        } catch (e: Exception) {
            return@withContext Interpretation.NeedsClarification("Could not reach OpenAI: ${e.javaClass.simpleName}.")
        }
        parse(reply, url, instruction)
    }

    private class ApiException(message: String) : Exception(message)

    private fun call(instruction: String, url: String?): String {
        val body = JSONObject()
            .put("model", model)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", "URL field: ${url.orEmpty()}\nRequest: $instruction")),
            )
        val conn = (URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
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
                throw ApiException(
                    when (code) {
                        401 -> "OpenAI rejected the key (401). Check it in Setup."
                        404 -> "Model \"$model\" does not exist or you have no access (404). Change it in Setup."
                        429 -> "OpenAI answered 429: no quota or too many requests. ${msg.orEmpty()}"
                        else -> "OpenAI answered $code. ${msg.orEmpty()}"
                    },
                )
            }
            return JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(reply: String, urlField: String?, instruction: String): Interpretation {
        val o = runCatching { JSONObject(reply) }.getOrNull()
            ?: return Interpretation.NeedsClarification("The AI reply was not valid JSON.")
        o.optString("clarification").takeIf { it.isNotBlank() && it != "null" }?.let {
            return Interpretation.NeedsClarification(it)
        }
        val url = RuleBasedInterpreter.normalizeUrl(urlField?.takeIf { it.isNotBlank() } ?: o.optString("url"))
            ?: return Interpretation.NeedsClarification("Missing URL.")
        val name = o.optString("target_name").trim()
        if (name.isEmpty() || name == "null") return Interpretation.NeedsClarification("Could not identify the element.")
        val role = o.optString("role").takeIf { it.isNotBlank() && it != "null" }
            ?.let { r -> runCatching { Role.valueOf(r.uppercase()) }.getOrNull() }
        val strategies = (o.optJSONArray("strategies") ?: JSONArray())
            .let { a -> (0 until a.length()).mapNotNull { runCatching { NavigationStrategy.valueOf(a.getString(it)) }.getOrNull() } }
            .ifEmpty { listOf(NavigationStrategy.DIRECT_FOCUS, NavigationStrategy.LINEAR_NAVIGATION) }
        val expected = o.optString("expectation_text").takeIf { it.isNotBlank() && it != "null" }?.let {
            Expectation(
                it,
                if (o.optString("expectation_mode").equals("EQUALS", true)) Expectation.Mode.EQUALS else Expectation.Mode.CONTAINS,
            )
        }
        return Interpretation.Ok(
            RunSpec(url, TargetQuery(name, role), strategies, expected, instruction = instruction),
            listOf("Interpreted with AI ($model)."),
        )
    }

    private companion object {
        val SYSTEM_PROMPT = """
            You convert a tester's request (English or Spanish) into JSON for an Android tool that opens a web page
            in Chrome, moves TalkBack focus to ONE element and records what TalkBack announces.
            Reply with a single JSON object with these keys:
            - "url": string or null (a URL mentioned in the request)
            - "target_name": string, the accessible name of the element to find (e.g. "Primary Address")
            - "role": one of EDIT_TEXT, BUTTON, LINK, CHECKBOX, RADIO, SWITCH, COMBOBOX, HEADING, GROUP, IMAGE or null
            - "strategies": array containing "DIRECT_FOCUS" and/or "LINEAR_NAVIGATION" (both unless the tester asks for one)
            - "expectation_text": string or null, the announcement the tester expects, only if they state one
            - "expectation_mode": "CONTAINS" or "EQUALS" (EQUALS only if they say exactly)
            - "clarification": string or null; set it (in the tester's language) if the request is not a single
              element check, e.g. it asks for multi-step exploration, or the element is missing.
            Never invent an announcement. Never include anything except the JSON object.
        """.trimIndent()
    }
}
