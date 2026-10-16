package com.kiransaj.diyamonitor.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.kiransaj.diyamonitor.core.LumaFrame
import com.kiransaj.diyamonitor.core.NormRect
import com.kiransaj.diyamonitor.core.Rotation
import java.io.ByteArrayOutputStream

/**
 * Converts CameraX YUV_420_888 frames into:
 *  - an upright, downsampled [LumaFrame] (plus V channel) for analysis, and
 *  - a retained NV21 copy of the latest frame, used to encode alert photos.
 * Not thread-safe: use from the analysis thread only.
 */
class FrameConverter {
    private var nv21 = ByteArray(0)
    private var width = 0
    private var height = 0
    private var rotation = 0

    val hasFrame: Boolean get() = width > 0

    fun convert(image: ImageProxy, step: Int = 2): LumaFrame {
        width = image.width
        height = image.height
        rotation = Rotation.normalise(image.imageInfo.rotationDegrees)
        copyToNv21(image)

        val w = width
        val h = height
        val swap = rotation == 90 || rotation == 270
        val outW = (if (swap) h else w) / step
        val outH = (if (swap) w else h) / step
        val luma = ByteArray(outW * outH)
        val chroma = ByteArray(outW * outH)
        val chromaBase = w * h
        var o = 0
        for (j in 0 until outH) {
            val v = j * step
            for (i in 0 until outW) {
                val u = i * step
                val x: Int
                val y: Int
                when (rotation) {
                    90 -> { x = v; y = h - 1 - u }
                    180 -> { x = w - 1 - u; y = h - 1 - v }
                    270 -> { x = w - 1 - v; y = u }
                    else -> { x = u; y = v }
                }
                luma[o] = nv21[y * w + x]
                // NV21 chroma: interleaved V,U at half resolution; V comes first.
                chroma[o] = nv21[chromaBase + (y / 2) * w + (x / 2) * 2]
                o++
            }
        }
        return LumaFrame(outW, outH, luma, chroma)
    }

    /** JPEG of the latest frame cropped to [upright] (normalised, upright coordinates), rotated upright. */
    fun jpeg(upright: NormRect, quality: Int = 85): ByteArray? {
        if (!hasFrame) return null
        val raw = Rotation.uprightRectToRaw(upright.clamped(), rotation)
        val left = ((raw.left * width).toInt() and 1.inv()).coerceIn(0, width - 2)
        val top = ((raw.top * height).toInt() and 1.inv()).coerceIn(0, height - 2)
        val right = (raw.right * width).toInt().coerceIn(left + 2, width)
        val bottom = (raw.bottom * height).toInt().coerceIn(top + 2, height)
        val cropped = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(left, top, right, bottom), 95, cropped)
        val bytes = cropped.toByteArray()
        if (rotation == 0) return bytes
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (rotated !== bmp) rotated.recycle()
        bmp.recycle()
        return out.toByteArray()
    }

    private fun copyToNv21(image: ImageProxy) {
        val w = width
        val h = height
        val size = w * h + 2 * ((w + 1) / 2) * ((h + 1) / 2)
        if (nv21.size != size) nv21 = ByteArray(size)

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        for (row in 0 until h) {
            val src = row * yRow
            val dst = row * w
            if (yPix == 1) {
                yBuf.position(src)
                yBuf.get(nv21, dst, w)
            } else {
                for (col in 0 until w) nv21[dst + col] = yBuf.get(src + col * yPix)
            }
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride
        var o = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                nv21[o++] = vBuf.get(row * vRow + col * vPix)
                nv21[o++] = uBuf.get(row * uRow + col * uPix)
            }
        }
    }
}
