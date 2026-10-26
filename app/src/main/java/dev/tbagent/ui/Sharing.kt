package dev.tbagent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

private const val MAX_CLIPBOARD_CHARS = 300_000

/** Shares [content] as a file through a content URI; large logs break plain-text intents. */
fun shareFile(context: Context, fileName: String, content: String, mime: String = "application/json") {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, fileName).apply { writeText(content) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, fileName)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(Intent.createChooser(send, fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, "Could not open the share sheet: ${it.message}", Toast.LENGTH_LONG).show() }
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipped = text.length > MAX_CLIPBOARD_CHARS
    cm.setPrimaryClip(ClipData.newPlainText(label, if (clipped) text.take(MAX_CLIPBOARD_CHARS) else text))
    Toast.makeText(
        context,
        if (clipped) "Copied the first $MAX_CLIPBOARD_CHARS characters (too long). Use Share for the full file." else "Copied to clipboard",
        Toast.LENGTH_SHORT,
    ).show()
}
