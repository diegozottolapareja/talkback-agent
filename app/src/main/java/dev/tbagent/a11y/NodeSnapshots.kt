package dev.tbagent.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.tbagent.core.Bounds
import dev.tbagent.core.ElementMatcher
import dev.tbagent.core.NodeSnapshot

/** Copies AccessibilityNodeInfo trees into platform-independent [NodeSnapshot]s. */
object NodeSnapshots {

    class Captured(val snapshots: List<NodeSnapshot>, val nodes: Map<String, AccessibilityNodeInfo>)

    private const val WEB_VIEW = "android.webkit.WebView"

    fun capture(root: AccessibilityNodeInfo, maxNodes: Int = 5000): Captured {
        val snapshots = ArrayList<NodeSnapshot>()
        val nodes = HashMap<String, AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
            if (snapshots.size >= maxNodes || depth > 120) return
            snapshots += snapshot(node, path, snapshots.size)
            nodes[path] = node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, "$path.$i", depth + 1)
            }
        }
        visit(root, "0", 0)
        return Captured(snapshots, nodes)
    }

    fun snapshot(n: AccessibilityNodeInfo, path: String, order: Int): NodeSnapshot {
        val r = Rect()
        n.getBoundsInScreen(r)
        return NodeSnapshot(
            path = path,
            order = order,
            className = n.className?.toString(),
            text = n.text?.toString(),
            contentDescription = n.contentDescription?.toString(),
            hintText = n.hintText?.toString(),
            stateDescription = n.stateDescription?.toString(),
            tooltipText = n.tooltipText?.toString(),
            paneTitle = n.paneTitle?.toString(),
            viewId = n.viewIdResourceName,
            labeledByText = n.labeledBy?.let { (it.text ?: it.contentDescription)?.toString() },
            extras = extrasOf(n),
            isFocusable = n.isFocusable,
            isScreenReaderFocusable = n.isScreenReaderFocusable,
            isClickable = n.isClickable,
            isEditable = n.isEditable,
            isCheckable = n.isCheckable,
            isChecked = n.isChecked,
            isEnabled = n.isEnabled,
            isVisibleToUser = n.isVisibleToUser,
            isHeading = n.isHeading,
            childCount = n.childCount,
            bounds = Bounds(r.left, r.top, r.right, r.bottom),
        )
    }

    private fun extrasOf(n: AccessibilityNodeInfo): Map<String, String> {
        val b = n.extras ?: return emptyMap()
        val out = HashMap<String, String>()
        for (key in b.keySet()) {
            @Suppress("DEPRECATION")
            val v = b.get(key)
            if (v is CharSequence || v is Number || v is Boolean) out[key] = v.toString()
        }
        return out
    }

    fun findWebView(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 3000) {
            val n = queue.removeFirst()
            visited++
            if (n.className?.toString() == WEB_VIEW) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    fun describe(n: AccessibilityNodeInfo?): String? {
        n ?: return null
        val s = snapshot(n, "", 0)
        val name = ElementMatcher.accessibleName(s)
        val cls = s.className?.substringAfterLast('.') ?: "?"
        return "$cls \"$name\" ${s.bounds}"
    }
}
