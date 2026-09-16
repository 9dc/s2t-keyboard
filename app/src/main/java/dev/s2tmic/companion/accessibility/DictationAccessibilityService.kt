package dev.s2tmic.companion.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import dev.s2tmic.companion.S2TApplication
import dev.s2tmic.companion.data.OverlaySettingsStore
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
    private lateinit var overlaySettings: OverlaySettingsStore
    private var overlay: DictationOverlayView? = null
    private var overlayAttached = false
    private var editorAvailable = false
    private var keyboardVisible = false
    private var currentState: DictationState = DictationState.Idle
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshFocusedEditor() }

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
            x = dp(5)
            y = dp(110) // Replaced with the detected keyboard position before display.
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val app = application as S2TApplication
        val keyStore = app.apiKeyStore
        overlaySettings = app.overlaySettingsStore
        controller = DictationController(
            this,
            keyStore,
            app.transcriptionSettingsStore,
            ::insertAtCursor,
        )
        overlay = DictationOverlayView(
            context = this,
            windowManager = windowManager,
            layoutParams = overlayParams,
            onTap = { controller.toggle() },
            canDrag = { !overlaySettings.isPositionLocked() },
            onPositionChanged = overlaySettings::savePosition,
        )

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
            -> scheduleRefresh()

            else -> Unit
        }
    }

    override fun onInterrupt() {
        if (::controller.isInitialized) controller.cancel()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.cancel()
        refreshHandler.removeCallbacks(refreshRunnable)
        scope.cancel()
        removeOverlay()
        super.onDestroy()
    }

    private fun scheduleRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS)
    }

    private fun refreshFocusedEditor() {
        val candidate = findFocusedEditor()
        editorAvailable = candidate.isSafeEditor()
        keyboardVisible = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (!overlayAttached) {
            positionOverlayOverKeyboard()
        } else if (overlaySettings.isResetRequested()) {
            positionOverlayOverKeyboard()
        }
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

    private fun positionOverlayOverKeyboard(): Boolean {
        val bounds = screenBounds()
        if (!overlaySettings.isResetRequested()) {
            overlaySettings.loadPosition()?.let { stored ->
                overlayParams.x = stored.x.coerceIn(0, (bounds.width() - dp(40)).coerceAtLeast(0))
                overlayParams.y = stored.y.coerceIn(0, (bounds.height() - dp(40)).coerceAtLeast(0))
                applyOverlayLayout()
                return true
            }
        }

        val keyboardWindow = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?: return false
        val keyboardBounds = Rect()
        keyboardWindow.getBoundsInScreen(keyboardBounds)
        if (keyboardBounds.isEmpty) return false

        // Gboard's microphone is normally in the top-right 48 dp toolbar cell.
        // Anchor our 40 dp control there; dragging still handles custom layouts.
        overlayParams.x = (bounds.width() - keyboardBounds.right + dp(5)).coerceAtLeast(dp(5))
        overlayParams.y = (bounds.height() - keyboardBounds.top - dp(43)).coerceAtLeast(dp(5))
        overlaySettings.consumeResetRequest()
        applyOverlayLayout()
        return true
    }

    private fun applyOverlayLayout() {
        if (!overlayAttached) return
        overlay?.let { runCatching { windowManager.updateViewLayout(it, overlayParams) } }
    }

    private fun screenBounds(): Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windowManager.currentWindowMetrics.bounds
    } else {
        @Suppress("DEPRECATION")
        val metrics = resources.displayMetrics
        Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
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
        val currentText = TextInsertion.editableText(
            exposedText = editor.text,
            hintText = editor.hintText,
            isShowingHintText = editor.isShowingHintText,
        )
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

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 80L
    }
}
