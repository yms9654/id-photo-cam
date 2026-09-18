package com.yms.idphoto.lab

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 배경 제거 방식을 바꿔가며 비교하기 위한 실험용 코드. 앱에는 들어가지 않는다. */
data class Variant(
    val name: String,
    val erodeRadius: Int = 0,
    val low: Float = 0.35f,
    val high: Float = 0.65f,
    val decontaminate: Boolean = false,
    /** 0 이면 가이디드 필터를 쓰지 않는다. */
    val guidedRadius: Int = 0,
    val guidedEps: Float = 1e-4f,
)

class Mask(val values: FloatArray, val width: Int, val height: Int)

suspend fun <T> Task<T>.awaitLab(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
}

suspend fun segmentOf(bitmap: Bitmap): Mask {
    val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
    )
    val mask = segmenter.use { it.process(InputImage.fromBitmap(bitmap, 0)).awaitLab() }
    val values = FloatArray(mask.width * mask.height)
    mask.buffer.rewind()
    mask.buffer.asFloatBuffer().get(values)
    return Mask(values, mask.width, mask.height)
}

suspend fun faceOf(bitmap: Bitmap): Face? {
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
    )
    return detector.use {
        it.process(InputImage.fromBitmap(bitmap, 0)).awaitLab()
            .maxByOrNull { f -> f.boundingBox.width() * f.boundingBox.height() }
    }
}

fun erodeMask(mask: Mask, radius: Int): Mask {
    if (radius <= 0) return mask
    val w = mask.width
    val h = mask.height
    val tmp = FloatArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var m = Float.MAX_VALUE
            var k = max(0, x - radius)
            val end = min(w - 1, x + radius)
            while (k <= end) { val v = mask.values[row + k]; if (v < m) m = v; k++ }
            tmp[row + x] = m
        }
    }
    val out = FloatArray(w * h)
    for (x in 0 until w) {
        for (y in 0 until h) {
            var m = Float.MAX_VALUE
            var k = max(0, y - radius)
            val end = min(h - 1, y + radius)
            while (k <= end) { val v = tmp[k * w + x]; if (v < m) m = v; k++ }
            out[y * w + x] = m
        }
    }
    return Mask(out, w, h)
}

/** 적분영상 기반 박스 평균. 반지름과 무관하게 픽셀당 상수 시간. */
fun boxFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
    val integral = DoubleArray((w + 1) * (h + 1))
    for (y in 0 until h) {
        var rowSum = 0.0
        for (x in 0 until w) {
            rowSum += src[y * w + x]
            integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + rowSum
        }
    }
    val out = FloatArray(w * h)
    for (y in 0 until h) {
        val y0 = max(0, y - r)
        val y1 = min(h - 1, y + r)
        for (x in 0 until w) {
            val x0 = max(0, x - r)
            val x1 = min(w - 1, x + r)
            val sum = integral[(y1 + 1) * (w + 1) + (x1 + 1)] -
                integral[y0 * (w + 1) + (x1 + 1)] -
                integral[(y1 + 1) * (w + 1) + x0] +
                integral[y0 * (w + 1) + x0]
            out[y * w + x] = (sum / ((y1 - y0 + 1) * (x1 - x0 + 1))).toFloat()
        }
    }
    return out
}

private fun downsample(src: FloatArray, w: Int, h: Int, s: Int, dw: Int, dh: Int): FloatArray {
    val out = FloatArray(dw * dh)
    for (dy in 0 until dh) {
        for (dx in 0 until dw) {
            var sum = 0f
            var n = 0
            for (y in dy * s until min(h, (dy + 1) * s)) {
                for (x in dx * s until min(w, (dx + 1) * s)) {
                    sum += src[y * w + x]; n++
                }
            }
            out[dy * dw + dx] = if (n == 0) 0f else sum / n
        }
    }
    return out
}

private fun upsampleBilinear(src: FloatArray, dw: Int, dh: Int, w: Int, h: Int): FloatArray {
    val out = FloatArray(w * h)
    val scaleX = dw.toFloat() / w
    val scaleY = dh.toFloat() / h
    for (y in 0 until h) {
        val fy = (y + 0.5f) * scaleY - 0.5f
        val y0 = fy.toInt().coerceIn(0, dh - 1)
        val y1 = (y0 + 1).coerceAtMost(dh - 1)
        val wy = (fy - y0).coerceIn(0f, 1f)
        for (x in 0 until w) {
            val fx = (x + 0.5f) * scaleX - 0.5f
            val x0 = fx.toInt().coerceIn(0, dw - 1)
            val x1 = (x0 + 1).coerceAtMost(dw - 1)
            val wx = (fx - x0).coerceIn(0f, 1f)
            val top = src[y0 * dw + x0] * (1 - wx) + src[y0 * dw + x1] * wx
            val bot = src[y1 * dw + x0] * (1 - wx) + src[y1 * dw + x1] * wx
            out[y * w + x] = top * (1 - wy) + bot * wy
        }
    }
    return out
}

/**
 * Fast guided filter. 분할 마스크는 256x256 모델 출력을 늘린 것이라 경계가 5~10px 폭으로 뭉개진다.
 * 원본 밝기를 길잡이 삼아 알파가 실제 머리카락 경계를 따라가도록 다시 세운다.
 * 계수는 1/[sub] 로 줄인 해상도에서 구하고 원본 크기로 올려 적용한다 — 결과는 거의 같고 메모리·시간은 크게 준다.
 */
fun guidedFilter(
    guide: FloatArray,
    input: FloatArray,
    w: Int,
    h: Int,
    r: Int,
    eps: Float,
    sub: Int = 4,
): FloatArray {
    val dw = (w + sub - 1) / sub
    val dh = (h + sub - 1) / sub
    val rs = max(1, r / sub)

    val gs = downsample(guide, w, h, sub, dw, dh)
    val ps = downsample(input, w, h, sub, dw, dh)
    val meanI = boxFilter(gs, dw, dh, rs)
    val meanP = boxFilter(ps, dw, dh, rs)
    val corrI = boxFilter(FloatArray(dw * dh) { gs[it] * gs[it] }, dw, dh, rs)
    val corrIp = boxFilter(FloatArray(dw * dh) { gs[it] * ps[it] }, dw, dh, rs)

    val a = FloatArray(dw * dh)
    val b = FloatArray(dw * dh)
    for (i in 0 until dw * dh) {
        val varI = corrI[i] - meanI[i] * meanI[i]
        val covIp = corrIp[i] - meanI[i] * meanP[i]
        a[i] = covIp / (varI + eps)
        b[i] = meanP[i] - a[i] * meanI[i]
    }
    val meanA = upsampleBilinear(boxFilter(a, dw, dh, rs), dw, dh, w, h)
    val meanB = upsampleBilinear(boxFilter(b, dw, dh, rs), dw, dh, w, h)
    return FloatArray(w * h) { (meanA[it] * guide[it] + meanB[it]).coerceIn(0f, 1f) }
}

/**
 * 경계 픽셀에 섞여 있는 원래 배경색을 걷어낸다.
 * 관측색 C = a·F + (1-a)·B 이므로, 주변에서 배경색 B 를 추정하면 F 를 되돌릴 수 있다.
 * B 는 32px 블록마다 '확실한 배경(a<0.1)' 픽셀의 평균으로 잡고, 빈 블록은 이웃에서 퍼뜨린다.
 */
fun estimateBackground(pixels: IntArray, alpha: FloatArray, w: Int, h: Int, block: Int = 32): Array<FloatArray> {
    val bw = (w + block - 1) / block
    val bh = (h + block - 1) / block
    val sum = Array(3) { FloatArray(bw * bh) }
    val count = IntArray(bw * bh)
    for (y in 0 until h) {
        val by = y / block
        for (x in 0 until w) {
            val i = y * w + x
            if (alpha[i] < 0.1f) {
                val bi = by * bw + x / block
                val p = pixels[i]
                sum[0][bi] += ((p shr 16) and 0xFF).toFloat()
                sum[1][bi] += ((p shr 8) and 0xFF).toFloat()
                sum[2][bi] += (p and 0xFF).toFloat()
                count[bi]++
            }
        }
    }
    val avg = Array(3) { FloatArray(bw * bh) }
    val known = BooleanArray(bw * bh)
    for (bi in 0 until bw * bh) {
        if (count[bi] >= block) {
            known[bi] = true
            for (c in 0..2) avg[c][bi] = sum[c][bi] / count[bi]
        }
    }
    // 값이 없는 블록은 이웃에서 반복적으로 채운다.
    repeat(bw + bh) {
        var changed = false
        for (by in 0 until bh) for (bx in 0 until bw) {
            val bi = by * bw + bx
            if (known[bi]) continue
            var n = 0
            val acc = FloatArray(3)
            for (dy in -1..1) for (dx in -1..1) {
                val nx = bx + dx; val ny = by + dy
                if (nx in 0 until bw && ny in 0 until bh) {
                    val ni = ny * bw + nx
                    if (known[ni]) { n++; for (c in 0..2) acc[c] += avg[c][ni] }
                }
            }
            if (n > 0) { for (c in 0..2) avg[c][bi] = acc[c] / n; known[bi] = true; changed = true }
        }
        if (!changed) return@repeat
    }
    return arrayOf(avg[0], avg[1], avg[2], FloatArray(2) { if (it == 0) bw.toFloat() else bh.toFloat() })
}

/** 변형 하나를 적용해 흰 배경 위에 합성한 결과. 알파 맵도 함께 돌려준다. */
class Composed(val bitmap: Bitmap, val alpha: FloatArray)

fun compose(source: Bitmap, mask: Mask, v: Variant, background: Int = Color.WHITE): Composed {
    val w = source.width
    val h = source.height
    val pixels = IntArray(w * h)
    source.getPixels(pixels, 0, w, 0, 0, w, h)

    val used = erodeMask(mask, v.erodeRadius)
    val sx = used.width.toFloat() / w
    val sy = used.height.toFloat() / h
    var raw = FloatArray(w * h)
    for (y in 0 until h) {
        val my = (y * sy).toInt().coerceIn(0, used.height - 1)
        for (x in 0 until w) {
            val mx = (x * sx).toInt().coerceIn(0, used.width - 1)
            raw[y * w + x] = used.values[my * used.width + mx]
        }
    }

    if (v.guidedRadius > 0) {
        val guide = FloatArray(w * h) {
            val p = pixels[it]
            (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
        raw = guidedFilter(guide, raw, w, h, v.guidedRadius, v.guidedEps)
    }

    val alpha = FloatArray(w * h)
    val span = v.high - v.low
    for (i in 0 until w * h) {
        val t = ((raw[i] - v.low) / span).coerceIn(0f, 1f)
        alpha[i] = t * t * (3f - 2f * t)
    }

    var bgR: FloatArray? = null
    var bgG: FloatArray? = null
    var bgB: FloatArray? = null
    var bw = 0
    if (v.decontaminate) {
        val est = estimateBackground(pixels, alpha, w, h)
        bgR = est[0]; bgG = est[1]; bgB = est[2]; bw = est[3][0].toInt()
    }

    val br = (background shr 16) and 0xFF
    val bg = (background shr 8) and 0xFF
    val bb = background and 0xFF
    val out = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            val a = alpha[i]
            val p = pixels[i]
            var r = ((p shr 16) and 0xFF).toFloat()
            var g = ((p shr 8) and 0xFF).toFloat()
            var b = (p and 0xFF).toFloat()
            // 경계 구간에서만 배경색을 걷어낸다. 안쪽까지 건드리면 사진 전체가 어두워진다.
            if (v.decontaminate && a > 0.05f && a < 0.90f) {
                val bi = (y / 32) * bw + (x / 32)
                if (bi < bgR!!.size) {
                    r = ((r - (1 - a) * bgR[bi]) / a).coerceIn(0f, 255f)
                    g = ((g - (1 - a) * bgG!![bi]) / a).coerceIn(0f, 255f)
                    b = ((b - (1 - a) * bgB!![bi]) / a).coerceIn(0f, 255f)
                }
            }
            val outR = (a * r + (1 - a) * br).roundToInt().coerceIn(0, 255)
            val outG = (a * g + (1 - a) * bg).roundToInt().coerceIn(0, 255)
            val outB = (a * b + (1 - a) * bb).roundToInt().coerceIn(0, 255)
            out[i] = Color.rgb(outR, outG, outB)
        }
    }
    return Composed(Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888), alpha)
}

/** 경계 구간에 남은 '파란 배경 번짐'의 양. 머리카락은 갈색/검정이라 파랑이 강하면 배경 잔재다. */
fun blueBleed(composed: Composed, region: RectF): Float {
    val bmp = composed.bitmap
    val w = bmp.width
    val x0 = region.left.toInt().coerceIn(0, w - 1)
    val x1 = region.right.toInt().coerceIn(x0 + 1, w)
    val y0 = region.top.toInt().coerceIn(0, bmp.height - 1)
    val y1 = region.bottom.toInt().coerceIn(y0 + 1, bmp.height)
    var sum = 0.0
    var n = 0
    for (y in y0 until y1) {
        for (x in x0 until x1) {
            val a = composed.alpha[y * w + x]
            if (a <= 0.02f || a >= 0.85f) continue
            val p = bmp.getPixel(x, y)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += max(0f, b - (r + g) / 2f).toDouble()
            n++
        }
    }
    return if (n == 0) 0f else (sum / n).toFloat()
}

/** 머리 영역에서 인물로 잡힌 넓이. 침식이 머리카락을 깎으면 이 값이 줄어든다. */
fun coverage(composed: Composed, bmpWidth: Int, region: RectF): Float {
    var n = 0
    var total = 0
    for (y in region.top.toInt()..region.bottom.toInt()) {
        for (x in region.left.toInt()..region.right.toInt()) {
            val i = y * bmpWidth + x
            if (i < 0 || i >= composed.alpha.size) continue
            if (composed.alpha[i] > 0.5f) n++
            total++
        }
    }
    return if (total == 0) 0f else n.toFloat() / total
}

/** 얼굴 영역 평균 밝기. 배경색 제거가 사진 전체를 어둡게 만들지 않는지 확인용. */
fun faceLuma(composed: Composed, box: RectF): Float {
    val bmp = composed.bitmap
    var sum = 0.0
    var n = 0
    var y = box.top.toInt().coerceAtLeast(0)
    val yEnd = box.bottom.toInt().coerceAtMost(bmp.height - 1)
    while (y <= yEnd) {
        var x = box.left.toInt().coerceAtLeast(0)
        val xEnd = box.right.toInt().coerceAtMost(bmp.width - 1)
        while (x <= xEnd) {
            val p = bmp.getPixel(x, y)
            sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            n++
            x += 3
        }
        y += 3
    }
    return if (n == 0) 0f else (sum / n).toFloat()
}

