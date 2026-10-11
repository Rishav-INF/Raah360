package com.raah360.core

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Small, dependency-free visual signature used for Visual Pin verification.
 * It is built from a centre crop of the camera frame and combines:
 *  - a grid of gradient-orientation histograms (structure: door frames, signs, edges)
 *  - a coarse colour histogram (frosted glass vs wood vs green booths ...)
 * Works on raw ARGB pixels so it runs both on Android bitmaps and in JVM unit tests.
 */
object ImageSignature {
    private const val N = 64          // working resolution (N x N grey)
    private const val CELLS = 4       // CELLS x CELLS grid for orientation histograms
    private const val BINS = 9
    private const val COLOR_BINS = 4  // per channel (HSV-ish) → 4*4*4 = 64
    const val HOG_LEN = CELLS * CELLS * BINS
    const val COLOR_LEN = COLOR_BINS * COLOR_BINS * COLOR_BINS
    const val THUMB = 12
    const val LEN = HOG_LEN + COLOR_LEN + THUMB * THUMB

    /** @param argb pixels row-major, [w] x [h]. @param cropFraction centre square fraction. */
    fun compute(argb: IntArray, w: Int, h: Int, cropFraction: Double = 0.85): FloatArray {
        val side = (min(w, h) * cropFraction).toInt().coerceAtLeast(8)
        val x0 = (w - side) / 2
        val y0 = (h - side) / 2
        val grey = DoubleArray(N * N)
        val rgb = Array(3) { DoubleArray(N * N) }
        // box-filter downsample
        for (gy in 0 until N) {
            val sy0 = y0 + gy * side / N
            val sy1 = max(sy0 + 1, y0 + (gy + 1) * side / N)
            for (gx in 0 until N) {
                val sx0 = x0 + gx * side / N
                val sx1 = max(sx0 + 1, x0 + (gx + 1) * side / N)
                var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
                var yy = sy0
                while (yy < sy1) {
                    var xx = sx0
                    val row = yy * w
                    while (xx < sx1) {
                        val c = argb[row + xx]
                        r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff
                        n++; xx += if (sx1 - sx0 > 4) 2 else 1
                    }
                    yy += if (sy1 - sy0 > 4) 2 else 1
                }
                r /= n; g /= n; b /= n
                val i = gy * N + gx
                rgb[0][i] = r; rgb[1][i] = g; rgb[2][i] = b
                grey[i] = 0.299 * r + 0.587 * g + 0.114 * b
            }
        }
        val out = FloatArray(LEN)

        // --- orientation histograms ---
        val cell = N / CELLS
        val hog = DoubleArray(HOG_LEN)
        for (y in 1 until N - 1) for (x in 1 until N - 1) {
            val gx = grey[y * N + x + 1] - grey[y * N + x - 1]
            val gy = grey[(y + 1) * N + x] - grey[(y - 1) * N + x]
            val mag = sqrt(gx * gx + gy * gy)
            if (mag < 1e-6) continue
            var ang = Math.toDegrees(atan2(gy, gx))
            if (ang < 0) ang += 180.0
            if (ang >= 180.0) ang -= 180.0
            val bf = ang / (180.0 / BINS)
            val b0 = bf.toInt() % BINS
            val b1 = (b0 + 1) % BINS
            val wgt = bf - bf.toInt()
            val c = (y / cell).coerceAtMost(CELLS - 1) * CELLS + (x / cell).coerceAtMost(CELLS - 1)
            hog[c * BINS + b0] += mag * (1 - wgt)
            hog[c * BINS + b1] += mag * wgt
        }
        normalize(hog)
        for (i in hog.indices) out[i] = hog[i].toFloat()

        // --- colour histogram (hue-ish via chroma ratios, robust to brightness) ---
        val col = DoubleArray(COLOR_LEN)
        for (i in 0 until N * N) {
            val r = rgb[0][i]; val g = rgb[1][i]; val b = rgb[2][i]
            val s = r + g + b + 1.0
            val rn = (r / s * COLOR_BINS * 1.5).toInt().coerceIn(0, COLOR_BINS - 1)
            val gn = (g / s * COLOR_BINS * 1.5).toInt().coerceIn(0, COLOR_BINS - 1)
            val v = (s / 3.0 / 256.0 * COLOR_BINS).toInt().coerceIn(0, COLOR_BINS - 1)
            col[(rn * COLOR_BINS + gn) * COLOR_BINS + v] += 1.0
        }
        val tot = col.sum()
        for (i in col.indices) out[HOG_LEN + i] = (col[i] / tot).toFloat()

        // --- tiny zero-mean thumbnail for normalised cross-correlation ---
        val t = DoubleArray(THUMB * THUMB)
        val step = N.toDouble() / THUMB
        for (ty in 0 until THUMB) for (tx in 0 until THUMB) {
            var s = 0.0; var n = 0
            for (yy in (ty * step).toInt() until ((ty + 1) * step).toInt()) for (xx in (tx * step).toInt() until ((tx + 1) * step).toInt()) {
                s += grey[yy * N + xx]; n++
            }
            t[ty * THUMB + tx] = s / n
        }
        val mean = t.average()
        for (i in t.indices) t[i] -= mean
        normalize(t)
        for (i in t.indices) out[HOG_LEN + COLOR_LEN + i] = t[i].toFloat()
        return out
    }

    private fun normalize(v: DoubleArray) {
        val n = l2(v)
        if (n > 1e-9) for (i in v.indices) v[i] /= n
    }

    /** 0..1, higher = more similar. */
    fun similarity(a: FloatArray, b: FloatArray): Double {
        if (a.size != LEN || b.size != LEN) return 0.0
        var hogDot = 0.0
        for (i in 0 until HOG_LEN) hogDot += a[i] * b[i]
        var inter = 0.0
        for (i in HOG_LEN until HOG_LEN + COLOR_LEN) inter += min(a[i], b[i])
        var ncc = 0.0
        for (i in HOG_LEN + COLOR_LEN until LEN) ncc += a[i] * b[i]
        val nccPos = ((ncc + 1.0) / 2.0)
        return (0.5 * hogDot + 0.25 * inter + 0.25 * nccPos).coerceIn(0.0, 1.0)
    }
}
