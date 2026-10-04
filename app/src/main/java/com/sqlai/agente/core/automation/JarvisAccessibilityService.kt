package com.sqlai.agente.core.automation

import kotlin.coroutines.resume

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable snapshot of a node in the active window's UI hierarchy. */
data class UiNode(
    val id: String,
    val viewId: String?,
    val text: String?,
    val desc: String?,
    val className: String?,
    val packageName: String,
    val bounds: RectF,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val checked: Boolean?,
    val depth: Int,
    val childCount: Int,
) {
    data class RectF(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
}

data class AutomationStep(
    val action: Action,
    val target: String,
    val value: String? = null,
) {
    enum class Action { TAP, LONG_PRESS, TYPE, SCROLL, SWAIT, BACK, HOME, WAIT_UNTIL }
}

/**
 * Device Controller Engine.
 *
 * Runs as a privileged AccessibilityService: parses the live UI tree in-memory,
 * resolves nodes by resource-id / text / content-desc, and dispatches precise
 * gestures through [GestureDescription]. OCR fallback (ML Kit, on-device) fills in
 * when a target exposes no accessibility text.
 *
 * Nothing leaves the device — the event stream is a local [StateFlow].
 */
class JarvisAccessibilityService : AccessibilityService() {

    interface EventSink {
        fun onWindowStateChanged(packageName: String)
        fun onNodeCaptured(node: UiNode)
    }

    private val _tree = MutableStateFlow<List<UiNode>>(emptyList())
    val tree: StateFlow<List<UiNode>> = _tree.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile
    var currentPackage: String = ""
        private set

    var sink: EventSink? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _active.value = true
        _events.tryEmit("accessibility service connected")
        refreshTree()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg != currentPackage) {
                    currentPackage = pkg
                    _events.tryEmit("window -> $pkg")
                    sink?.onWindowStateChanged(pkg)
                }
                refreshTree()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> refreshTree()
        }
    }

    override fun onInterrupt() {
        _events.tryEmit("accessibility interrupted")
    }

    override fun onDestroy() {
        _active.value = false
        sink = null
        instance = null
        super.onDestroy()
    }

    // ------------------------------ tree access ------------------------------

    /** Depth-first snapshot of the interactive tree, capped to keep allocations flat. */
    fun refreshTree() {
        val root = rootInActiveWindow ?: return
        val out = ArrayList<UiNode>(256)
        walk(root, out, depth = 0)
        _tree.value = out
        root.recycleCompat()
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<UiNode>, depth: Int) {
        if (depth > 18 || out.size > 512) return
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        out += UiNode(
            id = System.identityHashCode(node).toString(),
            viewId = node.viewIdResourceName,
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            className = node.className?.toString(),
            packageName = node.packageName?.toString() ?: currentPackage,
            bounds = UiNode.RectF(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            editable = node.isEditable,
            enabled = node.isEnabled,
            checked = if (node.isCheckable) node.isChecked else null,
            depth = depth,
            childCount = node.childCount,
        )
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, depth + 1)
            child.recycleCompat()
        }
    }

    /** Locates a node by view-id, text or content description (first match wins). */
    fun findNode(selector: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val byId = root.findAccessibilityNodeInfosByViewId(selector).firstOrNull()
        if (byId != null) return byId
        return findByText(root, selector)
    }

    private fun findByText(node: AccessibilityNodeInfo, needle: String): AccessibilityNodeInfo? {
        val t = node.text?.toString()
        val d = node.contentDescription?.toString()
        if (t?.contains(needle, true) == true || d?.contains(needle, true) == true) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            findByText(c, needle)?.let { return it }
            c.recycleCompat()
        }
        return null
    }

    // ------------------------------ actions ------------------------------

    fun tap(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback?.invoke(true) }
            override fun onCancelled(g: GestureDescription?) { callback?.invoke(false) }
        }, null)
    }

    fun longPress(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 600))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback?.invoke(true) }
            override fun onCancelled(g: GestureDescription?) { callback?.invoke(false) }
        }, null)
    }

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 300,
              callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback?.invoke(true) }
            override fun onCancelled(g: GestureDescription?) { callback?.invoke(false) }
        }, null)
    }

    /** Taps a resolved node's on-screen centre. */
    fun tapNode(selector: String, callback: ((Boolean) -> Unit)? = null) {
        val node = findNode(selector)
        if (node == null) { callback?.invoke(false); return }
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        node.recycleCompat()
        tap(rect.exactCenterX(), rect.exactCenterY(), callback)
    }

    /** Types into the currently focused editable node (or focuses selector first). */
    fun typeText(text: String, selector: String? = null, callback: ((Boolean) -> Unit)? = null) {
        val target = selector?.let { findNode(it) } ?: findFocusedEditable()
            ?: run { callback?.invoke(false); return }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        target.recycleCompat()
        callback?.invoke(ok)
    }

    fun pressBack(callback: ((Boolean) -> Unit)? = null) {
        callback?.invoke(performGlobalAction(GLOBAL_ACTION_BACK))
    }

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun scrollForward(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    /** Executes a scripted step sequence (workflow builder output). */
    suspend fun executeSteps(steps: List<AutomationStep>): Boolean {
        for (step in steps) {
            when (step.action) {
                AutomationStep.Action.TAP -> if (!tapSync(step.target)) return false
                AutomationStep.Action.LONG_PRESS -> {
                    val n = findNode(step.target) ?: return false
                    val r = android.graphics.Rect(); n.getBoundsInScreen(r); n.recycleCompat()
                    if (!gestureSync { longPress(r.exactCenterX(), r.exactCenterY()) }) return false
                }
                AutomationStep.Action.TYPE -> {
                    val ok = kotlin.coroutines.suspendCoroutine<Boolean> { cont ->
                        typeText(step.value ?: "", step.target) { cont.resume(it) }
                    }
                    if (!ok) return false
                }
                AutomationStep.Action.SCROLL -> {
                    val n = findNode(step.target) ?: return false
                    val ok = scrollForward(n); n.recycleCompat()
                    if (!ok) return false
                }
                AutomationStep.Action.BACK -> pressBack { }
                AutomationStep.Action.HOME -> pressHome()
                AutomationStep.Action.SWAIT, AutomationStep.Action.WAIT_UNTIL ->
                    kotlinx.coroutines.delay(step.value?.toLongOrNull() ?: 500)
            }
            kotlinx.coroutines.delay(120)
        }
        return true
    }

    private suspend fun tapSync(selector: String): Boolean =
        kotlin.coroutines.suspendCoroutine { cont -> tapNode(selector) { cont.resume(it) } }

    private suspend fun gestureSync(block: (tap: (Boolean) -> Unit) -> Unit): Boolean =
        kotlin.coroutines.suspendCoroutine { cont -> block { cont.resume(it) } }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null && focus.isEditable) return focus
        return if (root.isEditable) root else null
    }

    private fun AccessibilityNodeInfo.recycleCompat() {
        recycle()
    }

    companion object {
        /** Process-wide handle; nulled on destroy so the service never leaks. */
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set
    }
}
