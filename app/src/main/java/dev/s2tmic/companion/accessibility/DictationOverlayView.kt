package dev.s2tmic.companion.accessibility

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.s2tmic.companion.R
import dev.s2tmic.companion.dictation.DictationState
import kotlin.math.abs

/**
 * The floating microphone: a 40 dp circle inside a 48 dp touch target, with an error bubble above it.
 * A tap toggles dictation, a long press discards it and a drag moves the window.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class DictationOverlayView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val onTap: () -> Unit,
    /** Returns true when there was something to discard. */
    private val onLongPress: () -> Boolean,
    private val canDrag: () -> Boolean,
    private val onDragEnd: (x: Int, y: Int) -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** A finger has to travel this far before a touch becomes a drag, so sloppy taps still count as taps. */
    private val dragSlop = touchSlop * 2
    private val windowBounds: Rect
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val metrics = resources.displayMetrics
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    private val status = TextView(context)
    private val mic = ImageView(context)
    private val button = FrameLayout(context)
    private val pulse = ObjectAnimator.ofFloat(mic, View.ALPHA, 1f, 0.35f).apply {
        duration = 550
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
    }
    private var rendered: DictationState = DictationState.Idle
    private var notice: String? = null

    private var downX = 0f
    private var downY = 0f
    private var originX = 0
    private var originY = 0
    private var longPressed = false
    private val longPress = Runnable {
        if (onLongPress()) {
            longPressed = true
            button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** True while the user moves the window; its position must not be changed from outside meanwhile. */
    var isDragging = false
        private set

    init {
        orientation = VERTICAL
        gravity = Gravity.END

        status.apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 4
            maxWidth = dp(290)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0xE6222222.toInt(), 16f)
        }
        addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(2)
            marginEnd = dp(CIRCLE_INSET_DP)
        })

        mic.apply {
            setImageResource(R.drawable.ic_overlay_mic)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            elevation = dp(3).toFloat()
            background = rounded(COLOR_IDLE, CIRCLE_SIZE_DP / 2f)
        }
        button.addView(mic, FrameLayout.LayoutParams(dp(CIRCLE_SIZE_DP), dp(CIRCLE_SIZE_DP), Gravity.CENTER))
        button.contentDescription = "Diktat starten"
        button.setOnClickListener { onTap() }
        button.setOnTouchListener { _, event -> handleTouch(event) }
        addView(button, LayoutParams(dp(TOUCH_SIZE_DP), dp(TOUCH_SIZE_DP)))
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                originX = layoutParams.x
                originY = layoutParams.y
                isDragging = false
                longPressed = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!isDragging && !longPressed && canDrag() && (abs(dx) > dragSlop || abs(dy) > dragSlop)) {
                    isDragging = true
                    removeCallbacks(longPress)
                }
                if (isDragging) {
                    // The window is anchored bottom-right, so its offsets grow against the finger's direction.
                    val bounds = windowBounds
                    val maxX = (bounds.width() - button.width).coerceAtLeast(0)
                    val maxY = (bounds.height() - button.height).coerceAtLeast(0)
                    layoutParams.x = (originX - dx.toInt()).coerceIn(0, maxX)
                    layoutParams.y = (originY - dy.toInt()).coerceIn(0, maxY)
                    runCatching { windowManager.updateViewLayout(this, layoutParams) }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                when {
                    isDragging -> {
                        isDragging = false
                        onDragEnd(layoutParams.x, layoutParams.y)
                    }

                    // With a locked position the finger may slide; it counts as long as it lifts on the button.
                    !longPressed && !cancelled && event.isWithin(button, touchSlop) -> button.performClick()
                }
            }

            else -> return false
        }
        return true
    }

    fun render(state: DictationState) {
        val (color, description, icon) = when (state) {
            DictationState.Idle -> Triple(COLOR_IDLE, "Diktat starten", R.drawable.ic_overlay_mic)
            DictationState.Listening -> Triple(COLOR_LISTENING, "Diktat beenden", R.drawable.ic_overlay_stop)
            DictationState.Finalizing ->
                Triple(COLOR_PROCESSING, "Transkript wird erstellt", R.drawable.ic_overlay_processing)

            is DictationState.Failed -> Triple(
                COLOR_ERROR,
                if (state.canRetry) "Erneut senden" else "Diktat starten",
                R.drawable.ic_overlay_mic,
            )
        }
        showStatus(state)
        mic.background = rounded(color, CIRCLE_SIZE_DP / 2f)
        mic.setImageResource(icon)
        button.contentDescription = description

        if (state == DictationState.Finalizing) {
            if (isAttachedToWindow && !pulse.isStarted) pulse.start()
        } else {
            pulse.cancel()
            mic.alpha = 1f
        }
        if (state != rendered) signalChange(state)
        rendered = state
    }

    /**
     * Shows [text] in the bubble until it is cleared with null. Used instead of a toast, which Android
     * drops for apps in the background that may not post notifications.
     */
    fun showNotice(text: String?) {
        notice = text
        showStatus(rendered)
    }

    private fun showStatus(state: DictationState) {
        val message = (state as? DictationState.Failed)?.message ?: notice.orEmpty()
        status.text = message
        status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    /** A short vibration tells the user without looking that recording began, stopped or failed. */
    private fun signalChange(state: DictationState) {
        val feedback = when {
            state == DictationState.Listening -> HapticFeedbackConstants.KEYBOARD_TAP
            state == DictationState.Finalizing && rendered == DictationState.Listening ->
                HapticFeedbackConstants.KEYBOARD_TAP

            state is DictationState.Failed -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }

            else -> return
        }
        button.performHapticFeedback(feedback)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        isDragging = false
        pulse.cancel()
        mic.alpha = 1f
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (rendered == DictationState.Finalizing && !pulse.isStarted) pulse.start()
    }

    private fun MotionEvent.isWithin(view: View, slop: Int): Boolean =
        x >= -slop && y >= -slop && x <= view.width + slop && y <= view.height + slop

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * density
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        /** Side length of the touch target, which is also the size of the window while no error is shown. */
        const val TOUCH_SIZE_DP = 48
        const val CIRCLE_SIZE_DP = 40

        /** Gap between the edge of the touch target and the visible circle. */
        const val CIRCLE_INSET_DP = (TOUCH_SIZE_DP - CIRCLE_SIZE_DP) / 2

        private const val COLOR_IDLE = 0xD9202124.toInt()
        private const val COLOR_LISTENING = 0xFFD32F2F.toInt()
        private const val COLOR_PROCESSING = 0xFF6D5E0F.toInt()
        private const val COLOR_ERROR = 0xFFB3261E.toInt()
    }
}
