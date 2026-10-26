package com.amiigo.harmattan

import android.animation.TimeInterpolator
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Easing curves named in the Harmattan stylesheets ("outexpo", "inback", "overshotbezier", ...).
 * The standard ones are Qt 4.7 QEasingCurve formulas (what MeeGo Touch used);
 * "overshotbezier" is MeeGo's own MOvershotBezierEasingCurve.
 */
object Ease {
    val linear = TimeInterpolator { it }

    // Qt: (t==1) ? 1 : 1.001 * (1 - 2^(-10t))
    val outExpo = TimeInterpolator { t -> if (t >= 1f) 1f else 1.001f * (1f - 2f.pow(-10f * t)) }

    // Qt: (t==0 || t==1) ? t : 2^(10(t-1)) - 0.001
    val inExpo = TimeInterpolator { t -> if (t <= 0f || t >= 1f) t else 2f.pow(10f * (t - 1f)) - 0.001f }

    val inOutExpo = TimeInterpolator { t ->
        when {
            t <= 0f -> 0f
            t >= 1f -> 1f
            t < 0.5f -> 0.5f * 2f.pow(20f * t - 10f) - 0.0005f
            else -> 0.5f * 1.0005f * (2f - 2f.pow(-20f * t + 10f))
        }
    }

    /** Qt InBack; default overshoot 1.70158. The switcher uses overshoot 2. */
    fun inBack(s: Float = 1.70158f) = TimeInterpolator { t -> t * t * ((s + 1f) * t - s) }
    val inBack = inBack()

    val outQuint = TimeInterpolator { t -> val u = t - 1f; u * u * u * u * u + 1f }

    /** Qt OutBack: decelerates, overshoots by about s/10, settles. */
    fun outBack(s: Float = 1.70158f) = TimeInterpolator { t -> val u = t - 1f; u * u * ((s + 1f) * u + s) + 1f }
    val inQuint = TimeInterpolator { t -> t * t * t * t * t }
    val outSine = TimeInterpolator { t -> sin(t * PI / 2).toFloat() }

    /**
     * MOvershotBezierEasingCurve, libmeegotouchcore.so.0.25.9. The original installs
     * QEasingCurve::setCustomType(f) with f(t) = table[(int)(t * 99)], where table is
     * the 100 floats at 0x4404beb0 (copied verbatim below). We interpolate linearly
     * between the samples so it stays smooth on high-refresh displays; at each sample
     * the value is identical to the original.
     */
    private val OVERSHOT = floatArrayOf(
        0.000000f, 0.000900f, 0.003700f, 0.008500f, 0.015600f, 0.025200f, 0.037500f, 0.053000f, 0.071900f, 0.094900f,
        0.122600f, 0.155700f, 0.195400f, 0.243200f, 0.301400f, 0.373600f, 0.465800f, 0.586600f, 0.669800f, 0.726300f,
        0.770000f, 0.805700f, 0.835800f, 0.861800f, 0.884500f, 0.904600f, 0.922500f, 0.938400f, 0.952800f, 0.965700f,
        0.977300f, 0.987900f, 0.997400f, 1.006000f, 1.013800f, 1.020800f, 1.027100f, 1.032700f, 1.037700f, 1.042200f,
        1.046100f, 1.049600f, 1.052500f, 1.055100f, 1.057200f, 1.058900f, 1.060300f, 1.061300f, 1.061900f, 1.062300f,
        1.062300f, 1.062100f, 1.061500f, 1.060700f, 1.059700f, 1.058400f, 1.056800f, 1.055000f, 1.053000f, 1.050800f,
        1.048400f, 1.045800f, 1.043000f, 1.039900f, 1.036800f, 1.034000f, 1.031300f, 1.028900f, 1.026600f, 1.024400f,
        1.022400f, 1.020600f, 1.018800f, 1.017200f, 1.015700f, 1.014200f, 1.012900f, 1.011700f, 1.010500f, 1.009400f,
        1.008400f, 1.007500f, 1.006600f, 1.005800f, 1.005100f, 1.004400f, 1.003800f, 1.003200f, 1.002700f, 1.002300f,
        1.001900f, 1.001500f, 1.001200f, 1.000900f, 1.000600f, 1.000400f, 1.000300f, 1.000200f, 1.000100f, 1.000000f,
    )

    val overshotBezier = TimeInterpolator { t ->
        if (t <= 0f) 0f else if (t >= 1f) 1f else {
            val x = t * 99f
            val i = x.toInt()
            val f = x - i
            OVERSHOT[i] + (OVERSHOT[i + 1] - OVERSHOT[i]) * f
        }
    }
}
