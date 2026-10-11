package com.raah360.ar

import android.graphics.Bitmap
import android.media.Image

/** ARGB pixels in display orientation. */
class ArgbImage(val pixels: IntArray, val width: Int, val height: Int) {
    fun toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

object CameraImages {

    /**
     * Converts a YUV_420_888 CPU camera image to ARGB, downsampled so that the longer side is
     * about [targetLong] pixels, and rotated clockwise by [rotationDeg] (0/90/180/270)
     * so it matches what the user sees on screen.
     */
    fun toArgb(img: Image, targetLong: Int, rotationDeg: Int): ArgbImage {
        val w = img.width
        val h = img.height
        val step = maxOf(1, maxOf(w, h) / targetLong)
        val ow = w / step
        val oh = h / step
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yBuf = yP.buffer; val uBuf = uP.buffer; val vBuf = vP.buffer
        val yRow = yP.rowStride; val yPix = yP.pixelStride
        val uvRow = uP.rowStride; val uvPix = uP.pixelStride
        val vRow = vP.rowStride; val vPix = vP.pixelStride
        val src = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val sy = oy * step
            for (ox in 0 until ow) {
                val sx = ox * step
                val y = (yBuf.get(sy * yRow + sx * yPix).toInt() and 0xff)
                val cx = sx / 2; val cy = sy / 2
                val u = (uBuf.get(cy * uvRow + cx * uvPix).toInt() and 0xff) - 128
                val v = (vBuf.get(cy * vRow + cx * vPix).toInt() and 0xff) - 128
                var r = (y + 1.370705f * v).toInt()
                var g = (y - 0.337633f * u - 0.698001f * v).toInt()
                var b = (y + 1.732446f * u).toInt()
                r = r.coerceIn(0, 255); g = g.coerceIn(0, 255); b = b.coerceIn(0, 255)
                src[oy * ow + ox] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return rotate(src, ow, oh, rotationDeg)
    }

    fun rotate(src: IntArray, w: Int, h: Int, rotationDeg: Int): ArgbImage {
        return when (((rotationDeg % 360) + 360) % 360) {
            90 -> {
                val out = IntArray(w * h)
                // new width = h, new height = w ; (x,y) -> (h-1-y, x)
                for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = src[y * w + x]
                ArgbImage(out, h, w)
            }
            180 -> {
                val out = IntArray(w * h)
                for (i in 0 until w * h) out[w * h - 1 - i] = src[i]
                ArgbImage(out, w, h)
            }
            270 -> {
                val out = IntArray(w * h)
                // (x,y) -> (y, w-1-x)
                for (y in 0 until h) for (x in 0 until w) out[(w - 1 - x) * h + y] = src[y * w + x]
                ArgbImage(out, h, w)
            }
            else -> ArgbImage(src, w, h)
        }
    }

    /** Centre square crop, used for landmark photos (signs, posters) and pin thumbnails. */
    fun centerCrop(bmp: Bitmap, fraction: Float): Bitmap {
        val side = (minOf(bmp.width, bmp.height) * fraction).toInt().coerceAtLeast(16)
        return Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
    }
}
