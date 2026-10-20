package dev.spendcodex.coreui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * A touch of orange that fades over [durationMs] each time [key] changes after the first composition: "this just changed".
 * The family's food diary counts its numbers up; money is not counted on screen (a figure in transit is not a fact),
 * only marked as fresh. Returns 0 (no tint) on first appearance and when animations are off.
 */
@Composable
fun rememberGlow(key: Any?, durationMs: Int = 900): Float {
    val glow = remember { Animatable(0f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(key) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        glow.snapTo(1f)
        glow.animateTo(0f, tween(durationMs, easing = LinearOutSlowInEasing))
    }
    return glow.value
}
