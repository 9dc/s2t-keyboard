package dev.s2tmic.companion.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
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
    private var keyboard: KeyboardFrame? = null
    private var currentState: DictationState = DictationState.Idle

    private val handler = Handler(Looper.getMainLooper())
    private var refreshScheduled = false
    private var lastRefreshAt = 0L
    private val refreshRunnable = Runnable {
        refreshScheduled = false
        refresh()
    }
    private val settleRunnable = Runnable { refresh() }
    private var hideScheduled = false
    private val hideRunnable = Runnable {
        hideScheduled = false
        if (!shouldShowOverlay()) removeOverlay()
    }

    private var notice: String? = null
    private val clearNotice = Runnable { showNotice(null) }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Nobody is dictating into a dark screen: finish the recording instead of running to the limit.
            if (::controller.isInitialized) controller.stopAndCommit()
        }
    }
    private var screenOffRegistered = false

    // Offsets are measured from the physical right edge, where the keyboard has its microphone in every locale.
    @Suppress("RtlHardcoded")
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
            gravity = Gravity.BOTTOM or Gravity.RIGHT
            x = 0
            y = dp(110) // Replaced with the keyboard-relative position before display.
        }
    }

    /** Puts the 40 dp circle into the top-right toolbar cell of the keyboard, where Gboard has its microphone. */
    private val defaultAnchor by lazy {
        OverlayAnchor(
            fromRight = dp(5 - DictationOverlayView.CIRCLE_INSET_DP),
            aboveTop = -dp(DictationOverlayView.CIRCLE_SIZE_DP + DictationOverlayView.CIRCLE_INSET_DP),
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val app = application as S2TApplication
        overlaySettings = app.overlaySettingsStore
        controller = DictationController(
            this,
            app.apiKeyStore,
            app.transcriptionSettingsStore,
            ::insertTranscript,
        )
        overlay = DictationOverlayView(
            context = this,
            windowManager = windowManager,
            layoutParams = overlayParams,
            onTap = {
                showNotice(null)
                controller.toggle()
            },
            onLongPress = ::discardDictation,
            canDrag = { !overlaySettings.isPositionLocked() },
            onDragEnd = ::saveDraggedPosition,
        )
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenOffRegistered = true

        scope.launch {
            controller.state.collectLatest { state ->
                currentState = state
                overlay?.render(state)
                keepScreenOn(state == DictationState.Listening)
                updateOverlayVisibility()
            }
        }
        refresh()
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

    /** Asks to stop spoken feedback, which this service does not give. Any app can trigger it. */
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (::controller.isInitialized) controller.cancel()
        handler.removeCallbacksAndMessages(null)
        if (screenOffRegistered) {
            runCatching { unregisterReceiver(screenOffReceiver) }
            screenOffRegistered = false
        }
        scope.cancel()
        removeOverlay()
        super.onDestroy()
    }

    /**
     * Refreshes soon after an event, but at most every [MIN_REFRESH_INTERVAL_MS] while events keep coming,
     * and once more after they stop so the final state is never missed.
     */
    private fun scheduleRefresh() {
        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(settleRunnable, SETTLE_DELAY_MS)
        if (refreshScheduled) return
        refreshScheduled = true
        val at = maxOf(SystemClock.uptimeMillis() + REFRESH_DELAY_MS, lastRefreshAt + MIN_REFRESH_INTERVAL_MS)
        handler.postAtTime(refreshRunnable, at)
    }

    private fun refresh() {
        lastRefreshAt = SystemClock.uptimeMillis()
        editorAvailable = findFocusedEditor() != null
        keyboard = findKeyboard()
        positionOverlay()
        updateOverlayVisibility()
    }

    /** The focused text field, or null if there is none or it is a password field. */
    private fun findFocusedEditor(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            if (focused.isSafeEditor()) return focused
        }
        return windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .firstOrNull { it.isSafeEditor() }
    }

    private fun findKeyboard(): KeyboardFrame? {
        val window = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return null
        val screen = screenBounds()
        return KeyboardFrame(
            topFromBottom = screen.height() - bounds.top,
            rightInset = (screen.width() - bounds.right).coerceAtLeast(0),
        )
    }

    private fun shouldShowOverlay(): Boolean =
        // Keep the control reachable while dictation is active, even if the keyboard closes.
        (editorAvailable && keyboard != null) || currentState != DictationState.Idle || notice != null

    private fun updateOverlayVisibility() {
        if (shouldShowOverlay()) {
            handler.removeCallbacks(hideRunnable)
            hideScheduled = false
            if (!overlayAttached) {
                overlay?.let {
                    runCatching { windowManager.addView(it, overlayParams) }
                        .onSuccess { overlayAttached = true }
                }
            }
        } else if (overlayAttached && !hideScheduled) {
            // Focus is often lost for a moment while an app rebuilds its screen; do not flicker then.
            hideScheduled = true
            handler.postDelayed(hideRunnable, HIDE_GRACE_MS)
        }
    }

    /** Follows the keyboard: runs on every refresh so rotation and a changed keyboard height are picked up. */
    private fun positionOverlay() {
        val keyboard = keyboard ?: return
        if (overlay?.isDragging == true) return
        val screen = screenBounds()
        val landscape = screen.width() > screen.height()
        val anchor = overlaySettings.loadAnchor(landscape)
            ?: migrateLegacyPosition(landscape, keyboard)
            ?: defaultAnchor
        val offsets = OverlayPlacement.offsets(
            anchor,
            keyboard,
            screen.width(),
            screen.height(),
            dp(DictationOverlayView.TOUCH_SIZE_DP),
        )
        if (offsets.x == overlayParams.x && offsets.y == overlayParams.y) return
        overlayParams.x = offsets.x
        overlayParams.y = offsets.y
        applyOverlayLayout()
    }

    /**
     * Earlier versions stored the window offsets of a 43 x 46 dp window whose circle touched its right
     * edge and sat 3 dp above its bottom edge. Converts them so the circle stays where the user put it.
     */
    private fun migrateLegacyPosition(landscape: Boolean, keyboard: KeyboardFrame): OverlayAnchor? {
        val legacy = overlaySettings.loadLegacyOffsets(landscape) ?: return null
        val inset = dp(DictationOverlayView.CIRCLE_INSET_DP)
        val offsets = OverlayOffsets(legacy.x - inset, legacy.y + dp(3) - inset)
        return OverlayPlacement.anchor(offsets, keyboard).also { overlaySettings.saveAnchor(landscape, it) }
    }

    private fun saveDraggedPosition(x: Int, y: Int) {
        // Without a visible keyboard there is nothing to anchor to; the next refresh restores the old spot.
        val keyboard = keyboard ?: return
        val screen = screenBounds()
        overlaySettings.saveAnchor(
            landscape = screen.width() > screen.height(),
            anchor = OverlayPlacement.anchor(OverlayOffsets(x, y), keyboard),
        )
    }

    private fun discardDictation(): Boolean {
        if (currentState == DictationState.Idle) return false
        controller.cancel()
        return true
    }

    private fun keepScreenOn(enabled: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val flags = if (enabled) overlayParams.flags or flag else overlayParams.flags and flag.inv()
        if (flags == overlayParams.flags) return
        overlayParams.flags = flags
        applyOverlayLayout()
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

    private fun insertTranscript(transcript: String) {
        val inserted = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && commitLikeKeyboard(transcript)) ||
            replaceEditorText(transcript)
        if (inserted) return

        // Never lose what was said: the user can still paste it.
        val copied = runCatching {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("S2T Mic", transcript))
        }.isSuccess
        showNotice(
            if (copied) {
                "Einfügen nicht möglich – der Text liegt in der Zwischenablage"
            } else {
                "Diese App erlaubt das Einfügen nicht"
            },
        )
        handler.postDelayed(clearNotice, NOTICE_VISIBLE_MS)
    }

    private fun showNotice(text: String?) {
        handler.removeCallbacks(clearNotice)
        notice = text
        overlay?.showNotice(text)
        updateOverlayVisibility()
    }

    /**
     * Types the transcript through the field's input connection, exactly as a keyboard would: at the
     * cursor, replacing a selection, without touching the rest of the text or any placeholder.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun commitLikeKeyboard(transcript: String): Boolean {
        val method = inputMethod ?: return false
        if (!method.currentInputStarted) return false
        val connection = method.currentInputConnection ?: return false
        if (method.currentInputEditorInfo.isPasswordField()) return false

        val around = connection.getSurroundingText(1, 1, 0)
        val before = around?.let { it.text.getOrNull(it.selectionStart - 1) }
        val after = around?.let { it.text.getOrNull(it.selectionEnd) }
        connection.commitText(TextInsertion.withSpacing(before, after, transcript), 1, null)
        return true
    }

    /** Fallback for Android 12 and older: rewrites the whole field with the transcript spliced in. */
    private fun replaceEditorText(transcript: String): Boolean {
        val editor = findFocusedEditor() ?: return false
        val currentText = TextInsertion.editableText(
            exposedText = editor.text,
            hintText = editor.hintText,
            isShowingHintText = editor.isShowingHintText,
            contentDescription = editor.contentDescription,
            className = editor.className,
            packageName = editor.packageName,
            selectionStart = editor.textSelectionStart,
            selectionEnd = editor.textSelectionEnd,
        )
        val start = editor.textSelectionStart.takeIf { it >= 0 } ?: currentText.length
        val end = editor.textSelectionEnd.takeIf { it >= 0 } ?: start
        val result = TextInsertion.atSelection(currentText, start, end, transcript)

        val setText = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result.text)
        }
        if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)) return false
        editor.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, result.cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, result.cursor)
            },
        )
        return true
    }

    private fun AccessibilityNodeInfo.isSafeEditor(): Boolean = isEditable && isEnabled && !isPassword

    private fun EditorInfo?.isPasswordField(): Boolean {
        val inputType = this?.inputType ?: return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_DELAY_MS = 40L
        const val MIN_REFRESH_INTERVAL_MS = 120L
        const val SETTLE_DELAY_MS = 350L
        const val HIDE_GRACE_MS = 250L
        const val NOTICE_VISIBLE_MS = 4_000L
    }
}
