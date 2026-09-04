package dev.s2tmic.companion.accessibility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.s2tmic.companion.R
import dev.s2tmic.companion.dictation.DictationState
import kotlin.math.abs

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class DictationOverlayView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    onTap: () -> Unit,
    canDrag: () -> Boolean,
    onPositionChanged: (x: Int, y: Int) -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val status = TextView(context)
    private val mic = ImageView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.END
        setPadding(dp(3), dp(3), dp(3), dp(3))

        status.apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 3
            maxWidth = dp(290)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0xE6222222.toInt(), 16f)
        }
        addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })

        mic.apply {
            setImageResource(R.drawable.ic_overlay_mic)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            contentDescription = "Diktat starten"
            elevation = dp(4).toFloat()
            background = rounded(COLOR_IDLE, 20f)
        }
        addView(mic, LayoutParams(dp(40), dp(40)))

        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        mic.setOnClickListener { onTap() }
        mic.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = layoutParams.x
                    originY = layoutParams.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (canDrag()) {
                        val maxX = (resources.displayMetrics.widthPixels - dp(45)).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - dp(45)).coerceAtLeast(0)
                        layoutParams.x = (originX - (event.rawX - downX).toInt()).coerceIn(0, maxX)
                        layoutParams.y = (originY - (event.rawY - downY).toInt()).coerceIn(0, maxY)
                        runCatching { windowManager.updateViewLayout(this, layoutParams) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val wasTap = abs(event.rawX - downX) < dp(8) &&
                        abs(event.rawY - downY) < dp(8)
                    if (wasTap) {
                        mic.performClick()
                    } else if (canDrag()) {
                        onPositionChanged(layoutParams.x, layoutParams.y)
                    }
                    true
                }

                else -> false
            }
        }
    }

    fun render(state: DictationState) {
        val (message, color, description) = when (state) {
            DictationState.Idle -> Triple("", COLOR_IDLE, "Diktat starten")
            is DictationState.Listening -> Triple(
                "",
                COLOR_LISTENING,
                "Diktat beenden",
            )
            is DictationState.Finalizing -> Triple(
                "",
                COLOR_PROCESSING,
                "Transkript wird erstellt",
            )
            is DictationState.Failed -> Triple(state.message, COLOR_ERROR, "Fehler")
        }
        status.text = message
        status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        mic.background = rounded(color, 20f)
        mic.contentDescription = description
        mic.setImageResource(
            when (state) {
                is DictationState.Listening -> R.drawable.ic_overlay_stop
                is DictationState.Finalizing -> R.drawable.ic_overlay_processing
                else -> R.drawable.ic_overlay_mic
            },
        )
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * density
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val COLOR_IDLE = 0xD9202124.toInt()
        const val COLOR_LISTENING = 0xFFD32F2F.toInt()
        const val COLOR_PROCESSING = 0xFF6D5E0F.toInt()
        const val COLOR_ERROR = 0xFFB3261E.toInt()
    }
}
