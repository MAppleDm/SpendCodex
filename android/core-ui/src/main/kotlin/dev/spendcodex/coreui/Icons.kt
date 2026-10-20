package dev.spendcodex.coreui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/*
 * Icons are drawn as thin strokes instead of pulling in an icon library:
 * it keeps the APK small and every glyph has the same 1.5dp weight.
 */

private fun DrawScope.stroke() = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.line(color: Color, x1: Float, y1: Float, x2: Float, y2: Float) {
    val w = size.width
    drawLine(color, Offset(w * x1, w * y1), Offset(w * x2, w * y2), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
}

/** A closed padlock: the app is locked. */
@Composable
fun LockIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(24.dp)) {
    val w = size.width
    drawRoundRect(color, Offset(w * 0.2f, w * 0.44f), Size(w * 0.6f, w * 0.4f), CornerRadius(w * 0.08f), style = stroke())
    val shackle = Path().apply {
        moveTo(w * 0.32f, w * 0.44f)
        lineTo(w * 0.32f, w * 0.32f)
        cubicTo(w * 0.32f, w * 0.1f, w * 0.68f, w * 0.1f, w * 0.68f, w * 0.32f)
        lineTo(w * 0.68f, w * 0.44f)
    }
    drawPath(shackle, color, style = stroke())
    line(color, 0.5f, 0.57f, 0.5f, 0.69f)
}

@Composable
fun SendIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(24.dp)) {
    val w = size.width
    line(color, 0.5f, 0.84f, 0.5f, 0.18f)
    val head = Path().apply {
        moveTo(w * 0.24f, w * 0.44f)
        lineTo(w * 0.5f, w * 0.18f)
        lineTo(w * 0.76f, w * 0.44f)
    }
    drawPath(head, color, style = stroke())
}

/** Three dots: the "more" corner icon for settings. */
@Composable
fun MoreIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(24.dp)) {
    val w = size.width
    for (i in 0..2) drawCircle(color, radius = 1.6.dp.toPx(), center = Offset(w * 0.5f, w * (0.25f + 0.25f * i)))
}

@Composable
fun SearchIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(24.dp)) {
    val w = size.width
    drawCircle(color, radius = w * 0.24f, center = Offset(w * 0.44f, w * 0.44f), style = stroke())
    line(color, 0.62f, 0.62f, 0.82f, 0.82f)
}

/** Three lines of a form with a dot on each: the full form for a new operation. */
@Composable
fun FormIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(24.dp)) {
    val w = size.width
    val rows = floatArrayOf(0.28f, 0.5f, 0.72f)
    val knobs = floatArrayOf(0.62f, 0.36f, 0.56f)
    rows.forEachIndexed { i, y ->
        line(color, 0.18f, y, 0.82f, y)
        drawCircle(color, radius = w * 0.06f, center = Offset(w * knobs[i], w * y))
    }
}

@Composable
fun BackIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(24.dp)) {
    val w = size.width
    val chevron = Path().apply {
        moveTo(w * 0.62f, w * 0.2f)
        lineTo(w * 0.32f, w * 0.5f)
        lineTo(w * 0.62f, w * 0.8f)
    }
    drawPath(chevron, color, style = stroke())
}

/** A small chevron: down while a list is folded, up while it is open. */
@Composable
fun ChevronIcon(color: Color, up: Boolean, modifier: Modifier = Modifier) = Canvas(modifier.size(20.dp)) {
    val w = size.width
    val near = if (up) 0.62f else 0.38f
    val far = if (up) 0.38f else 0.62f
    val path = Path().apply {
        moveTo(w * 0.25f, w * near)
        lineTo(w * 0.5f, w * far)
        lineTo(w * 0.75f, w * near)
    }
    drawPath(path, color, style = stroke())
}

@Composable
fun CloseIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(24.dp)) {
    line(color, 0.25f, 0.25f, 0.75f, 0.75f)
    line(color, 0.75f, 0.25f, 0.25f, 0.75f)
}
