package tech.illusion.stockquote.content

import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

enum class LabelAnchor { Start, Center, End }

/**
 * Axis/annotation text for the hand-drawn charts, via the platform [Paint] rather than Compose's
 * `TextMeasurer`: this module's `resolutionStrategy` excludes `androidx.compose.ui:ui-text` (see
 * `app/build.gradle.kts`) and picks those classes up from the PICO artifacts instead, so the
 * platform path is the one guaranteed to resolve on this classpath.
 *
 * Build the [Paint] once per `Canvas {}` pass with [chartLabelPaint] and reuse it — a fresh Paint per
 * label would allocate on every frame.
 */
fun chartLabelPaint(color: Color, sizePx: Float, anchor: LabelAnchor = LabelAnchor.Start): Paint =
    Paint().apply {
        this.color = color.toArgb()
        textSize = sizePx
        isAntiAlias = true
        textAlign = when (anchor) {
            LabelAnchor.Start -> Paint.Align.LEFT
            LabelAnchor.Center -> Paint.Align.CENTER
            LabelAnchor.End -> Paint.Align.RIGHT
        }
    }

fun DrawScope.drawChartLabel(text: String, x: Float, y: Float, paint: Paint) {
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}
