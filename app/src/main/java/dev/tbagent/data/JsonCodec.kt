package dev.tbagent.data

import dev.tbagent.core.A11yEventRecord
import dev.tbagent.core.Bounds
import dev.tbagent.core.Candidate
import dev.tbagent.core.NodeSnapshot
import dev.tbagent.core.RunResult
import dev.tbagent.core.StepRecord
import dev.tbagent.core.StrategyResult
import dev.tbagent.core.SynthesisStatus
import dev.tbagent.core.Utterance
import org.json.JSONArray
import org.json.JSONObject

object JsonCodec {

    fun toJson(u: Utterance): JSONObject = JSONObject()
        .put("id", u.id)
        .put("receivedAtElapsed", u.receivedAtElapsed)
        .put("receivedAtWallMillis", u.receivedAtWallMillis)
        .put("text", u.text)
        .put("callerUid", u.callerUid)
        .put("callerPackage", u.callerPackage ?: JSONObject.NULL)
        .put("fromTalkBack", u.isFromTalkBack)
        .put("language", u.language)
        .put("country", u.country)
        .put("variant", u.variant)
        .put("voiceName", u.voiceName ?: JSONObject.NULL)
        .put("speechRate", u.speechRate)
        .put("pitch", u.pitch)
        .put("params", JSONObject(u.params as Map<*, *>))
        .put("spans", JSONArray(u.spans))
        .put("status", u.status.name)
        .put("finishedAtElapsed", u.finishedAtElapsed ?: JSONObject.NULL)
        .put("plannedAudioMs", u.plannedAudioMs)
        .put("deliveredAudioMs", u.deliveredAudioMs)

    fun utteranceFromJson(o: JSONObject): Utterance {
        val params = o.optJSONObject("params")
        val spans = o.optJSONArray("spans")
        return Utterance(
            id = o.getLong("id"),
            receivedAtElapsed = o.getLong("receivedAtElapsed"),
            receivedAtWallMillis = o.getLong("receivedAtWallMillis"),
            text = o.getString("text"),
            callerUid = o.optInt("callerUid", -1),
            callerPackage = o.optString("callerPackage").takeIf { !o.isNull("callerPackage") && it.isNotEmpty() },
            language = o.optString("language"),
            country = o.optString("country"),
            variant = o.optString("variant"),
            voiceName = o.optString("voiceName").takeIf { !o.isNull("voiceName") && it.isNotEmpty() },
            speechRate = o.optInt("speechRate"),
            pitch = o.optInt("pitch"),
            params = params?.keys()?.asSequence()?.associateWith { params.optString(it) } ?: emptyMap(),
            spans = spans?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            status = runCatching { SynthesisStatus.valueOf(o.getString("status")) }.getOrDefault(SynthesisStatus.ERROR),
            finishedAtElapsed = if (o.isNull("finishedAtElapsed")) null else o.optLong("finishedAtElapsed"),
            plannedAudioMs = o.optLong("plannedAudioMs"),
            deliveredAudioMs = o.optLong("deliveredAudioMs"),
        )
    }

    fun toJson(e: A11yEventRecord): JSONObject = JSONObject()
        .put("id", e.id)
        .put("atElapsed", e.atElapsed)
        .put("atWallMillis", e.atWallMillis)
        .put("type", e.type)
        .put("package", e.packageName ?: JSONObject.NULL)
        .put("className", e.className ?: JSONObject.NULL)
        .put("text", e.text)
        .put("contentDescription", e.contentDescription ?: JSONObject.NULL)
        .put("bounds", e.bounds?.toString() ?: JSONObject.NULL)

    fun toJson(b: Bounds?): Any = b?.toString() ?: JSONObject.NULL

    fun toJson(n: NodeSnapshot): JSONObject = JSONObject()
        .put("path", n.path)
        .put("order", n.order)
        .put("className", n.className ?: JSONObject.NULL)
        .put("text", n.text ?: JSONObject.NULL)
        .put("contentDescription", n.contentDescription ?: JSONObject.NULL)
        .put("hintText", n.hintText ?: JSONObject.NULL)
        .put("stateDescription", n.stateDescription ?: JSONObject.NULL)
        .put("labeledByText", n.labeledByText ?: JSONObject.NULL)
        .put("viewId", n.viewId ?: JSONObject.NULL)
        .put("extras", JSONObject(n.extras as Map<*, *>))
        .put("focusable", n.isFocusable)
        .put("clickable", n.isClickable)
        .put("editable", n.isEditable)
        .put("checkable", n.isCheckable)
        .put("checked", n.isChecked)
        .put("enabled", n.isEnabled)
        .put("visibleToUser", n.isVisibleToUser)
        .put("heading", n.isHeading)
        .put("bounds", n.bounds.toString())

    fun toJson(c: Candidate): JSONObject = JSONObject()
        .put("key", c.key)
        .put("accessibleName", c.accessibleName)
        .put("role", c.role.name)
        .put("score", c.score)
        .put("matchedField", c.matchedField)
        .put("node", toJson(c.node))

    fun toJson(s: StepRecord): JSONObject = JSONObject()
        .put("index", s.index)
        .put("action", s.action)
        .put("actionAtElapsed", s.actionAtElapsed)
        .put("actionAtWallMillis", s.actionAtWallMillis)
        .put("actionAccepted", s.actionAccepted)
        .put("focusedAfter", s.focusedDescription ?: JSONObject.NULL)
        .put("focusOnTarget", s.focusOnTarget)
        .put("utterances", JSONArray(s.utterances.map { toJson(it) }))

    fun toJson(r: StrategyResult): JSONObject = JSONObject()
        .put("strategy", r.strategy.name)
        .put("status", r.status.name)
        .put("announcementConcatenated", r.concatenated)
        .put("fragments", JSONArray(r.fragments.map { toJson(it) }))
        .put("otherUtterances", JSONArray(r.otherUtterances.map { toJson(it) }))
        .put("steps", JSONArray(r.steps.map { toJson(it) }))
        .put("focusEvents", JSONArray(r.focusEvents.map { toJson(it) }))
        .put("screenshot", r.screenshotPath ?: JSONObject.NULL)
        .put("notes", JSONArray(r.notes))
        .put("expectationMet", r.expectationMet ?: JSONObject.NULL)

    fun toJson(r: RunResult): JSONObject = JSONObject()
        .put("id", r.id)
        .put("startedAtWallMillis", r.startedAtWallMillis)
        .put("finishedAtWallMillis", r.finishedAtWallMillis)
        .put("instruction", r.instruction)
        .put("url", r.url)
        .put("observedPageUrl", r.observedPageUrl ?: JSONObject.NULL)
        .put("target", JSONObject().put("name", r.target.name).put("role", r.target.role?.name ?: JSONObject.NULL))
        .put("selectedElement", r.selected?.let { toJson(it) } ?: JSONObject.NULL)
        .put("expectation", r.expectation?.let { JSONObject().put("text", it.text).put("mode", it.mode.name) } ?: JSONObject.NULL)
        .put("verdict", r.verdict.name)
        .put("reasons", JSONArray(r.reasons))
        .put("strategies", JSONArray(r.strategies.map { toJson(it) }))
        .put("environment", JSONObject(r.environment as Map<*, *>))
}
