package dev.tbagent.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.tbagent.Graph
import dev.tbagent.core.Bounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Controller accessibility service. It runs next to TalkBack: it reads the window tree, moves
 * accessibility focus, injects gestures and takes screenshots. It intentionally does not request
 * touch exploration or key filtering so TalkBack keeps its normal behaviour.
 */
class ControllerService : AccessibilityService() {

    override fun onServiceConnected() {
        Graph.init(applicationContext)
        instance = this
        Graph.events.add("SERVICE_CONNECTED", packageName, null, "", null, null)
        Log.i(TAG, "Controller connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType !in RECORDED) return
        val src = event.source
        val bounds = src?.let {
            val r = Rect()
            it.getBoundsInScreen(r)
            Bounds(r.left, r.top, r.right, r.bottom)
        }
        val text = event.text.joinToString(" ").ifBlank { NodeSnapshots.describe(src) ?: "" }
        Graph.events.add(
            type = AccessibilityEvent.eventTypeToString(event.eventType),
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            text = text,
            contentDescription = event.contentDescription?.toString(),
            bounds = bounds,
        )
    }

    /** Root of Chrome's window, if Chrome is on screen. */
    fun chromeRoot(): AccessibilityNodeInfo? {
        val fromWindows = runCatching {
            windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() == CHROME } }
        }.getOrNull()
        return fromWindows ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == CHROME }
    }

    fun chromeUrlBarText(root: AccessibilityNodeInfo): String? =
        root.findAccessibilityNodeInfosByViewId("$CHROME:id/url_bar").firstOrNull()?.text?.toString()

    fun accessibilityFocused(): AccessibilityNodeInfo? = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

    /** Injects a one-finger horizontal swipe (TalkBack: right = next item). */
    suspend fun swipeHorizontal(toTheRight: Boolean, durationMs: Long = 150): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val dm = resources.displayMetrics
                val y = dm.heightPixels * 0.55f
                val left = dm.widthPixels * 0.2f
                val right = dm.widthPixels * 0.8f
                val path = Path().apply {
                    if (toTheRight) { moveTo(left, y); lineTo(right, y) } else { moveTo(right, y); lineTo(left, y) }
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build()
                val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, null)
                if (!dispatched && cont.isActive) cont.resume(false)
            }
        }

    /** Saves a PNG screenshot. Returns null on success or an error description. */
    suspend fun screenshot(file: File): String? {
        val result = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Pair<Bitmap?, String?>> { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hw = screenshot.hardwareBuffer
                        val bmp = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hw.close()
                        if (cont.isActive) cont.resume(bmp to if (bmp == null) "no se pudo convertir el buffer" else null)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null to "takeScreenshot falló (código $errorCode)")
                    }
                })
            }
        }
        val bmp = result.first ?: return result.second
        return withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                null
            }.getOrElse { "no se pudo guardar el screenshot: ${it.message}" }
        }
    }

    companion object {
        private const val TAG = "Controller"
        const val CHROME = "com.android.chrome"

        @Volatile
        var instance: ControllerService? = null
            private set

        private val RECORDED = setOf(
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
            AccessibilityEvent.TYPE_ANNOUNCEMENT,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_END,
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END,
        )
    }
}
