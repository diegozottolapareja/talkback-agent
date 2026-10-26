package dev.tbagent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tbagent.core.SynthesisStatus
import dev.tbagent.core.Utterance
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Ok = Color(0xFF1B7F3B)
val Bad = Color(0xFFB3261E)
val Warn = Color(0xFF8A5A00)

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(4.dp))
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text("$key: ", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun Mono(text: String, color: Color = Color.Unspecified) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color)
}

fun statusColor(s: SynthesisStatus): Color = when (s) {
    SynthesisStatus.SYNTHESIS_COMPLETED -> Ok
    SynthesisStatus.INTERRUPTED -> Warn
    SynthesisStatus.ERROR -> Bad
    else -> Color.Gray
}

fun statusLabel(s: SynthesisStatus): String = when (s) {
    SynthesisStatus.REQUESTED -> "requested"
    SynthesisStatus.SYNTHESIZING -> "synthesizing…"
    SynthesisStatus.SYNTHESIS_COMPLETED -> "synthesis completed"
    SynthesisStatus.INTERRUPTED -> "interrupted"
    SynthesisStatus.ERROR -> "error"
}

fun callerLabel(u: Utterance, ownPackage: String): String = when {
    u.isFromTalkBack -> "TalkBack"
    u.callerPackage == ownPackage -> "this app"
    else -> u.callerPackage ?: "uid ${u.callerUid}"
}

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

fun wallTime(ms: Long): String = synchronized(timeFormat) { timeFormat.format(Date(ms)) }

@Composable
fun UtteranceRow(u: Utterance, ownPackage: String, offsetFromMs: Long? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val offset = offsetFromMs?.let { " (+${u.receivedAtElapsed - it} ms)" } ?: ""
        Mono(
            "#${u.id} ${wallTime(u.receivedAtWallMillis)}$offset · ${callerLabel(u, ownPackage)} · " +
                "${u.language}-${u.country} · ${statusLabel(u.status)}",
            color = statusColor(u.status),
        )
        Text(if (u.text.isEmpty()) "(empty utterance)" else "“${u.text}”", style = MaterialTheme.typography.bodyMedium)
        if (u.spans.isNotEmpty()) Mono("spans: ${u.spans.joinToString("; ")}")
    }
}
