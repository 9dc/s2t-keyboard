package dev.s2tmic.companion.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import dev.s2tmic.companion.S2TApplication
import dev.s2tmic.companion.dictation.DictationController
import dev.s2tmic.companion.dictation.DictationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DictationAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: DictationController
    private lateinit var windowManager: WindowManager
    private var overlay: DictationOverlayView? = null
    private var overlayAttached = false
    private var editorAvailable = false
    private var keyboardVisible = false
    private var currentState: DictationState = DictationState.Idle

    private val overlayParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(12)
            y = dp(110)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val keyStore = (application as S2TApplication).apiKeyStore
        controller = DictationController(this, keyStore, ::insertAtCursor)
        overlay = DictationOverlayView(this, windowManager, overlayParams) { controller.toggle() }

        scope.launch {
            controller.state.collectLatest { state ->
                currentState = state
                overlay?.render(state)
                updateOverlayVisibility()
            }
        }
        refreshFocusedEditor()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::controller.isInitialized || event == null || event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> refreshFocusedEditor(event.source)

            else -> Unit
        }
    }

    override fun onInterrupt() {
        if (::controller.isInitialized) controller.cancel()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.cancel()
        scope.cancel()
        removeOverlay()
        super.onDestroy()
    }

    private fun refreshFocusedEditor(eventSource: AccessibilityNodeInfo? = null) {
        val candidate = when {
            eventSource.isSafeEditor() && eventSource?.isFocused == true -> eventSource
            else -> findFocusedEditor()
        }
        editorAvailable = candidate.isSafeEditor()
        keyboardVisible = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (!editorAvailable && currentState !is DictationState.Idle && currentState !is DictationState.Failed) {
            controller.cancel()
        }
        updateOverlayVisibility()
    }

    private fun findFocusedEditor(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            if (focused.isSafeEditor()) return focused
        }
        return windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .firstOrNull { it.isSafeEditor() }
    }

    private fun updateOverlayVisibility() {
        // Keep the control reachable while dictation is active, even if the keyboard closes.
        val shouldShow = (editorAvailable && keyboardVisible) || currentState !is DictationState.Idle
        if (shouldShow && !overlayAttached) {
            overlay?.let {
                runCatching { windowManager.addView(it, overlayParams) }
                    .onSuccess { overlayAttached = true }
            }
        } else if (!shouldShow && overlayAttached) {
            removeOverlay()
        }
    }

    private fun removeOverlay() {
        if (!overlayAttached) return
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlayAttached = false
    }

    private fun insertAtCursor(transcript: String) {
        val editor = findFocusedEditor()
        if (!editor.isSafeEditor()) {
            Toast.makeText(this, "Aktives Textfeld nicht mehr gefunden", Toast.LENGTH_SHORT).show()
            return
        }

        checkNotNull(editor)
        val currentText = editor.text?.toString().orEmpty()
        val start = editor.textSelectionStart.takeIf { it >= 0 } ?: currentText.length
        val end = editor.textSelectionEnd.takeIf { it >= 0 } ?: start
        val result = TextInsertion.atSelection(currentText, start, end, transcript)

        val setText = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result.text)
        }
        val inserted = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)
        if (inserted) {
            editor.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, result.cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, result.cursor)
                },
            )
        } else {
            Toast.makeText(this, "Diese App erlaubt das Einfügen nicht", Toast.LENGTH_SHORT).show()
        }
    }

    private fun AccessibilityNodeInfo?.isSafeEditor(): Boolean =
        this != null && isEditable && isEnabled && !isPassword

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
