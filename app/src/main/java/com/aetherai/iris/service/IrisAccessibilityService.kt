package com.aetherai.iris.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class BooleanResult {
    @Volatile var value = false
}

private class TextResult {
    @Volatile var value: List<String> = emptyList()
}

private class ReadScreenTextRunnable(
    private val service: IrisAccessibilityService,
    private val result: TextResult,
    private val latch: CountDownLatch
) : Runnable {
    override fun run() {
        result.value = service.readScreenTextOnMainThread()
        latch.countDown()
    }
}

private class TapByTextRunnable(
    private val service: IrisAccessibilityService,
    private val target: String,
    private val result: BooleanResult,
    private val latch: CountDownLatch
) : Runnable {
    override fun run() {
        result.value = service.tapByTextOnMainThread(target)
        latch.countDown()
    }
}

private class SwipeRunnable(
    private val service: IrisAccessibilityService,
    private val direction: String,
    private val result: BooleanResult,
    private val latch: CountDownLatch
) : Runnable {
    override fun run() {
        result.value = service.swipeOnMainThread(direction)
        latch.countDown()
    }
}

private class TypeTextRunnable(
    private val service: IrisAccessibilityService,
    private val text: String,
    private val result: BooleanResult,
    private val latch: CountDownLatch
) : Runnable {
    override fun run() {
        result.value = service.typeTextOnMainThread(text)
        latch.countDown()
    }
}

/**
 * Named GestureResultCallback impl — no lambdas/anonymous classes, per the
 * D8 toolchain constraint used throughout this project.
 */
private class SimpleGestureCallback : AccessibilityService.GestureResultCallback() {
    override fun onCompleted(gestureDescription: GestureDescription?) {
        super.onCompleted(gestureDescription)
    }
    override fun onCancelled(gestureDescription: GestureDescription?) {
        super.onCancelled(gestureDescription)
    }
}

/**
 * Core device-control service: reads on-screen text, taps elements by
 * matching visible text, and performs swipe gestures. Android requires
 * this to be enabled manually in Settings (already wired in the
 * Settings tab's permission list) — it can't be granted like a normal
 * runtime permission.
 */
class IrisAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        // Other classes (CommandRouter) reach the running service through
        // this static reference, since Android controls the service's
        // lifecycle and callers can't construct/bind to it directly.
        @Volatile
        var instance: IrisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Reserved for future reactive behavior (e.g. auto-reading
        // notifications as they appear). Reading happens on-demand via
        // readScreenText() for now, triggered by voice commands.
    }

    override fun onInterrupt() {
        // No-op.
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    /** Returns all visible text on screen, top-to-bottom as Android's tree walk finds it. */
    fun readScreenText(): List<String> {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val result = TextResult()
            val latch = CountDownLatch(1)
            mainHandler.post(ReadScreenTextRunnable(this, result, latch))
            latch.await(1500, TimeUnit.MILLISECONDS)
            return result.value
        }
        return readScreenTextOnMainThread()
    }

    fun readScreenTextOnMainThread(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<String>()
        collectText(root, results)
        return results
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val text = node.text
        if (!text.isNullOrBlank()) {
            out.add(text.toString())
        }
        val desc = node.contentDescription
        if (!desc.isNullOrBlank() && desc.toString() != text?.toString()) {
            out.add(desc.toString())
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
            child.recycle()
        }
    }

    /** Finds the first visible element whose text or content-description contains [target] (case-insensitive) and taps its center. Returns true if a match was tapped. */
    fun tapByText(target: String): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val result = BooleanResult()
            val latch = CountDownLatch(1)
            mainHandler.post(TapByTextRunnable(this, target, result, latch))
            return latch.await(1500, TimeUnit.MILLISECONDS) && result.value
        }
        return tapByTextOnMainThread(target)
    }

    fun tapByTextOnMainThread(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, target.lowercase())
        if (node == null) return false
        // Text is often a child of the actual Button/ImageButton. Walk upward
        // so commands activate the control instead of merely tapping its label.
        var clickTarget = node
        while (!clickTarget.isClickable) {
            val parent = clickTarget.parent ?: break
            clickTarget = parent
        }
        if (clickTarget.isClickable && clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val bounds = Rect()
        clickTarget.getBoundsInScreen(bounds)
        return performTapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, targetLower: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()
        if ((text != null && text.contains(targetLower)) || (desc != null && desc.contains(targetLower))) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, targetLower)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun performTapAt(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, SimpleGestureCallback(), null)
    }

    /** Swipes from 80% to 20% of screen height (down-to-up motion = "scroll down" reading direction) or reverse. */
    fun swipe(direction: String): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val result = BooleanResult()
            val latch = CountDownLatch(1)
            mainHandler.post(SwipeRunnable(this, direction, result, latch))
            return latch.await(1500, TimeUnit.MILLISECONDS) && result.value
        }
        return swipeOnMainThread(direction)
    }

    fun swipeOnMainThread(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollAction = if (direction == "down") {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val scrollable = findScrollableNode(root)
        if (scrollable != null && scrollable.performAction(scrollAction)) return true

        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        val centerX = bounds.centerX().toFloat()
        val top = bounds.top + bounds.height() * 0.2f
        val bottom = bounds.top + bounds.height() * 0.8f

        val path = Path()
        if (direction == "down") {
            path.moveTo(centerX, bottom)
            path.lineTo(centerX, top)
        } else {
            path.moveTo(centerX, top)
            path.lineTo(centerX, bottom)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, SimpleGestureCallback(), null)
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    /**
     * Finds the first editable field on screen and sets its text.
     * ACTION_SET_TEXT is a real, documented AccessibilityNodeInfo action
     * (unlike an earlier attempt at this that referenced a constant that
     * doesn't actually exist on this class). Submission after typing is
     * handled by the caller tapping a visible "search" icon via
     * tapByText, since there's no reliable cross-app way to simulate an
     * IME "enter" key through the accessibility API.
     */
    fun typeText(text: String): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val result = BooleanResult()
            val latch = CountDownLatch(1)
            mainHandler.post(TypeTextRunnable(this, text, result, latch))
            return latch.await(1500, TimeUnit.MILLISECONDS) && result.value
        }
        return typeTextOnMainThread(text)
    }

    fun typeTextOnMainThread(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findEditableNode(root) ?: return false
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }
}
