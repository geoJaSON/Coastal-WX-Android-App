package com.example.devtools

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.widget.WidgetState
import com.example.widget.buildCompactViews
import com.example.widget.buildLargeViews
import com.example.widget.loadWidgetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only harness that renders the real widget RemoteViews at exact dp sizes so their
 * layout and size-gating can be inspected without dragging things around a launcher.
 *
 * It calls RemoteViews.apply(), which is the same inflation path a launcher uses and applies
 * the same @RemoteView class filter - a view type that would crash on a home screen crashes
 * here too, rather than silently looking fine.
 */
class WidgetPreviewActivity : Activity() {

    private data class Case(
        val label: String,
        val large: Boolean,
        val widthDp: Int,
        val heightDp: Int
    )

    private val cases = listOf(
        Case("COMPACT 4x1 default   250 x 72", false, 250, 72),
        Case("COMPACT squeezed      190 x 64", false, 190, 64),
        Case("COMPACT min resize    150 x 54", false, 150, 54),
        Case("COMPACT wide/tall     330 x 96", false, 330, 96),
        Case("LARGE 4x3 default     320 x 220", true, 320, 220),
        Case("LARGE medium          300 x 180", true, 300, 180),
        Case("LARGE min resize      220 x 130", true, 220, 130),
        Case("LARGE tall            340 x 280", true, 340, 280)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF0E1216"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        setContentView(ScrollView(this).apply { addView(column) })

        (application as? android.app.Application)
        kotlinx.coroutines.MainScope().launch {
            val state: WidgetState = withContext(Dispatchers.IO) {
                loadWidgetState(this@WidgetPreviewActivity)
            }
            column.addView(label("state = ${state::class.java.simpleName}"))
            cases.forEach { case ->
                column.addView(label(case.label))
                column.addView(render(case, state))
            }
        }
    }

    private fun render(case: Case, state: WidgetState): FrameLayout {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(case.widthDp), dp(case.heightDp))
                .apply { bottomMargin = dp(14) }
            clipChildren = true
        }
        val views = if (case.large) {
            buildLargeViews(this, PREVIEW_WIDGET_ID, state, case.widthDp, case.heightDp)
        } else {
            buildCompactViews(this, PREVIEW_WIDGET_ID, state, case.widthDp, case.heightDp)
        }
        frame.addView(
            views.apply(applicationContext, frame),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        return frame
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#FF8FA0AE"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(0, 0, 0, dp(3))
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private companion object {
        const val PREVIEW_WIDGET_ID = 9999
    }
}
