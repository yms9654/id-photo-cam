package com.yms.idphoto.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 촬영 원본은 1000만 화소를 넘기도 한다. 최종 출력은 413×531 이므로 긴 변 [MAX_LONG_EDGE]
 * 정도로 줄여도 화질 손해가 없고, 분할·얼굴 검출 속도와 메모리는 크게 아낄 수 있다.
 */
const val MAX_LONG_EDGE = 1800

fun decodeScaledRotated(bytes: ByteArray, rotationDegrees: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longEdge = max(bounds.outWidth, bounds.outHeight)

    var sample = 1
    while (longEdge / (sample * 2) >= MAX_LONG_EDGE) sample *= 2

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: error("사진을 읽을 수 없습니다")

    if (rotationDegrees % 360 == 0) return decoded
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (rotated !== decoded) decoded.recycle()
    return rotated
}

data class EncodedJpeg(val bytes: ByteArray, val quality: Int) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * 용량 상한을 지키는 JPEG 인코딩. 품질을 높은 쪽부터 낮춰가며 상한 이하가 되는 첫 값을 쓴다.
 * (증명사진 제출처는 대개 500KB 이하를 요구한다.)
 */
fun encodeJpegUnderLimit(bitmap: Bitmap, maxBytes: Int): EncodedJpeg {
    val qualities = intArrayOf(96, 92, 88, 84, 80, 74, 68, 60, 52, 44, 36, 28, 20)
    var last: EncodedJpeg? = null
    for (q in qualities) {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, q, out)
        val bytes = out.toByteArray()
        last = EncodedJpeg(bytes, q)
        if (bytes.size <= maxBytes) return last
    }
    return last!!
}
