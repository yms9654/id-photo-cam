package com.yms.idphoto.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.yms.idphoto.spec.PhotoSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class BackgroundStyle(val label: String, val color: Int) {
    WHITE("흰색", 0xFFFFFFFF.toInt()),
    GRAY("연회색", 0xFFEDEFF2.toInt()),
    SKY("하늘색", 0xFFDCE8F6.toInt()),
    ORIGINAL("원본 배경", 0xFFFFFFFF.toInt()),
}

class PhotoFailure(message: String) : Exception(message)

/**
 * 촬영 결과 한 장. 배경색을 바꿔도 다시 분석할 필요가 없도록
 * 인물(알파 적용)/원본 비트맵과 좌표 변환을 그대로 들고 있는다.
 */
class PhotoResult(
    val spec: PhotoSpec,
    private val source: Bitmap,
    private val person: Bitmap,
    private val transform: Matrix,
    val measurements: Measurements,
    val report: List<ValidationItem>,
) {
    fun render(background: BackgroundStyle): Bitmap {
        val out = Bitmap.createBitmap(spec.outWidthPx, spec.outHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true; isDither = true }
        canvas.drawColor(background.color)
        val layer = if (background == BackgroundStyle.ORIGINAL) source else person
        canvas.drawBitmap(layer, transform, paint)
        return out
    }

    fun recycle() {
        if (!source.isRecycled) source.recycle()
        if (person !== source && !person.isRecycled) person.recycle()
    }
}

object PhotoProcessor {

    /** 다듬은 마스크를 알파로 바꾸는 구간. */
    private const val MASK_SOFT_LOW = 0.35f
    private const val MASK_SOFT_HIGH = 0.80f

    /** 가이디드 필터 세기. 크게 잡을수록 마스크 모양을 존중하고, 작으면 밝기 경계를 세게 따라간다. */
    private const val GUIDE_EPS = 1e-2f
    /** 계수를 구할 때 줄이는 배수. 결과 차이는 거의 없고 메모리·시간이 크게 준다. */
    private const val GUIDE_SUBSAMPLE = 4
    /** 경계에서만 배경색을 걷어낸다. 안쪽까지 건드리면 사진 전체가 어두워진다. */
    private const val DECONTAMINATE_LOW = 0.05f
    private const val DECONTAMINATE_HIGH = 0.90f
    /** 배경색을 추정할 때 쓰는 블록 크기(px). */
    private const val BG_BLOCK = 16


    /** 마스크가 없을 때 얼굴 박스 위로 정수리를 추정하는 비율. */
    private const val HEAD_TOP_FALLBACK = 0.33f

    suspend fun process(
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        spec: PhotoSpec,
    ): PhotoResult = withContext(Dispatchers.Default) {
        val source = decodeScaledRotated(jpegBytes, rotationDegrees)
        try {
            val input = InputImage.fromBitmap(source, 0)
            val face = detectFace(input)
            val maskData = segment(input, source.width, source.height)
            buildResult(spec, source, face, maskData)
        } catch (t: Throwable) {
            if (!source.isRecycled) source.recycle()
            throw t
        }
    }

    // ---------- ML Kit ----------

    private suspend fun detectFace(input: InputImage): Face {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )
        return detector.use {
            val faces = it.process(input).await()
            faces.maxByOrNull { f -> f.boundingBox.width() * f.boundingBox.height() }
                ?: throw PhotoFailure("사진에서 얼굴을 찾지 못했습니다. 다시 촬영해 주세요.")
        }
    }

    private class MaskData(val values: FloatArray, val width: Int, val height: Int, val srcW: Int, val srcH: Int) {
        private val scaleX = width.toFloat() / srcW
        private val scaleY = height.toFloat() / srcH
        fun atSource(x: Int, y: Int): Float {
            val mx = (x * scaleX).toInt().coerceIn(0, width - 1)
            val my = (y * scaleY).toInt().coerceIn(0, height - 1)
            return values[my * width + mx]
        }
        fun toSourceX(mx: Float) = mx / scaleX
        fun toSourceY(my: Float) = my / scaleY
        fun fromSourceY(y: Float) = y * scaleY
        fun fromSourceX(x: Float) = x * scaleX
    }

    private suspend fun segment(input: InputImage, srcW: Int, srcH: Int): MaskData? {
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
        val mask: SegmentationMask = try {
            segmenter.use { it.process(input).await() }
        } catch (t: Throwable) {
            return null // 배경 제거는 실패해도 촬영 자체는 살린다.
        }
        val w = mask.width
        val h = mask.height
        val values = FloatArray(w * h)
        val buffer = mask.buffer
        buffer.rewind()
        buffer.asFloatBuffer().get(values)
        return MaskData(values, w, h, srcW, srcH)
    }

    // ---------- 구도 계산 ----------

    private fun buildResult(
        spec: PhotoSpec,
        source: Bitmap,
        face: Face,
        mask: MaskData?,
    ): PhotoResult {
        val box = RectF(face.boundingBox)
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

        // 1) 눈 선 기울기 → 자동 수평 보정 각도
        val eyeAngle = CropPlanner.eyeAngleDegrees(
            leftEye = leftEye?.let { Pt(it.x, it.y) },
            rightEye = rightEye?.let { Pt(it.x, it.y) },
            fallbackDeg = -face.headEulerAngleZ,
        )
        val levelDeg = CropPlanner.levelAngle(eyeAngle)

        // 2) 턱 끝: 얼굴 윤곽선의 가장 아래 점이 박스 하단보다 정확하다.
        val faceContour = face.getContour(FaceContour.FACE)?.points
        val chinY = faceContour?.maxOfOrNull { it.y } ?: box.bottom
        val chinX = faceContour
            ?.filter { it.y > chinY - box.height() * 0.08f }
            ?.takeIf { it.isNotEmpty() }
            ?.map { it.x }?.average()?.toFloat()
            ?: box.centerX()

        // 3) 정수리: 인물 분할 마스크에서 머리 위 첫 행을 찾는다. (마스크가 없으면 박스로 추정)
        val headTop = mask?.let { findHeadTop(it, box) }
            ?: Pt(box.centerX(), box.top - box.height() * HEAD_TOP_FALLBACK)

        // 4) 수평 보정 좌표계로 옮겨 규격대로 자른다.
        val center = if (leftEye != null && rightEye != null) {
            Pt((leftEye.x + rightEye.x) / 2f, (leftEye.y + rightEye.y) / 2f)
        } else {
            Pt(box.centerX(), box.centerY())
        }
        val pivot = Pt(box.centerX(), box.centerY())
        val plan = CropPlanner.plan(spec, headTop, Pt(chinX, chinY), center, pivot, levelDeg)
        val scale = plan.scaleTo(spec.outHeightPx)

        val transform = Matrix().apply {
            postTranslate(-plan.pivot.x, -plan.pivot.y)
            postRotate(-plan.levelDeg)
            postTranslate(plan.pivot.x, plan.pivot.y)
            postTranslate(-plan.cropLeft, -plan.cropTop)
            postScale(scale, scale)
        }

        // 5) 원본 밖으로 나간 비율 (배경색으로 채워지는 영역)
        val outsideRatio = outsideRatio(source.width, source.height, transform, spec)

        // 6) 인물만 남긴 비트맵
        val person = mask?.let { buildPersonLayer(source, it) } ?: source

        val measurements = Measurements(
            headMm = plan.headRatio * spec.heightMm,
            topMarginMm = plan.topMarginRatio * spec.heightMm,
            rollDeg = eyeAngle,
            autoLevelDeg = levelDeg,
            yawDeg = face.headEulerAngleY,
            pitchDeg = face.headEulerAngleX,
            eyesOpen = eyesOpen(face),
            faceLuma = regionLuma(source, box),
            sharpness = sharpness(source, box),
            outsideRatio = outsideRatio,
            sourceScale = plan.cropHeight / spec.outHeightPx,
        )

        return PhotoResult(
            spec = spec,
            source = source,
            person = person,
            transform = transform,
            measurements = measurements,
            report = SpecReport.build(spec, measurements, mask != null),
        )
    }

    /**
     * 마스크에서 머리 꼭대기를 찾는다. 얼굴 박스 좌우로 조금 넓힌 열 범위만 보고,
     * 위에서 내려오며 인물 픽셀이 의미 있게 나타나는 첫 행을 정수리로 삼는다.
     */
    private fun findHeadTop(mask: MaskData, box: RectF): Pt? {
        val x0 = mask.fromSourceX(box.left - box.width() * 0.2f).toInt().coerceIn(0, mask.width - 1)
        val x1 = mask.fromSourceX(box.right + box.width() * 0.2f).toInt().coerceIn(0, mask.width - 1)
        if (x1 <= x0) return null

        val faceTopM = mask.fromSourceY(box.top)
        val faceHM = mask.fromSourceY(box.height())
        // 머리카락까지 고려해도 얼굴 박스 높이만큼 위를 넘어가진 않는다. 그 위는 배경/팔 등 오검출.
        val yStart = max(0f, faceTopM - faceHM).toInt()
        val yEnd = faceTopM.toInt().coerceIn(0, mask.height - 1)
        val span = x1 - x0 + 1
        val minCount = max(3, (span * 0.06f).roundToInt())

        for (my in yStart..yEnd) {
            var count = 0
            var sumX = 0L
            for (mx in x0..x1) {
                if (mask.values[my * mask.width + mx] > 0.6f) {
                    count++
                    sumX += mx
                }
            }
            if (count >= minCount) {
                return Pt(mask.toSourceX(sumX.toFloat() / count), mask.toSourceY(my.toFloat()))
            }
        }
        return null
    }

    /**
     * 인물만 남긴 비트맵을 만든다.
     *
     * 분할 마스크는 256×256 모델 출력을 원본 크기로 늘린 것이라, 경계가 원본에서 5~10px 폭으로
     * 뭉개져 있다. 그대로 쓰면 머리카락이 유령처럼 흐려지고, 반대로 깎아내면(침식) 잔머리가 잘린다.
     * 그래서 두 단계를 거친다.
     *
     *  1) 가이디드 필터 — 원본 밝기를 길잡이 삼아 알파가 실제 머리카락 경계를 따라가게 다시 세운다.
     *  2) 배경색 제거 — 경계 픽셀에는 원래 배경색이 섞여 있다(C = a·F + (1-a)·B).
     *     주변에서 B 를 추정해 F 를 되돌리면, 흰 배경에 얹었을 때 남던 색 띠가 사라진다.
     */
    private fun buildPersonLayer(source: Bitmap, mask: MaskData): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val guide = FloatArray(w * h)
        val alpha = FloatArray(w * h)
        val scaleX = mask.width.toFloat() / w
        val scaleY = mask.height.toFloat() / h
        for (y in 0 until h) {
            val my = (y * scaleY).toInt().coerceIn(0, mask.height - 1)
            val row = y * w
            for (x in 0 until w) {
                val p = pixels[row + x]
                guide[row + x] = (0.299f * ((p shr 16) and 0xFF) +
                    0.587f * ((p shr 8) and 0xFF) +
                    0.114f * (p and 0xFF)) / 255f
                val mx = (x * scaleX).toInt().coerceIn(0, mask.width - 1)
                alpha[row + x] = mask.values[my * mask.width + mx]
            }
        }

        refineAlpha(guide, alpha, w, h, max(8, max(w, h) / 90))

        val span = MASK_SOFT_HIGH - MASK_SOFT_LOW
        for (i in alpha.indices) {
            val t = ((alpha[i] - MASK_SOFT_LOW) / span).coerceIn(0f, 1f)
            alpha[i] = t * t * (3f - 2f * t)
        }

        val background = estimateBackground(pixels, alpha, w, h)
        val blocksX = (w + BG_BLOCK - 1) / BG_BLOCK
        val blocksY = (h + BG_BLOCK - 1) / BG_BLOCK
        val bg = FloatArray(3)

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val p = pixels[i]
                sampleBackground(background, blocksX, blocksY, x, y, bg)

                val a = alpha[i]
                var r = ((p shr 16) and 0xFF).toFloat()
                var g = ((p shr 8) and 0xFF).toFloat()
                var b = (p and 0xFF).toFloat()
                if (a > DECONTAMINATE_LOW && a < DECONTAMINATE_HIGH) {
                    r = ((r - (1 - a) * bg[0]) / a).coerceIn(0f, 255f)
                    g = ((g - (1 - a) * bg[1]) / a).coerceIn(0f, 255f)
                    b = ((b - (1 - a) * bg[2]) / a).coerceIn(0f, 255f)
                }
                pixels[i] = ((a * 255f).toInt().coerceIn(0, 255) shl 24) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Fast guided filter. 계수 a, b 를 1/[GUIDE_SUBSAMPLE] 크기에서 구한 뒤 원본 좌표에서 이중선형으로
     * 읽어 적용한다. 원본 크기 중간 배열을 만들지 않아 메모리를 아낀다. [alpha] 를 제자리에서 고친다.
     */
    private fun refineAlpha(guide: FloatArray, alpha: FloatArray, w: Int, h: Int, radius: Int) {
        val sub = GUIDE_SUBSAMPLE
        val dw = (w + sub - 1) / sub
        val dh = (h + sub - 1) / sub
        val rs = max(1, radius / sub)

        val gs = downsample(guide, w, h, sub, dw, dh)
        val ps = downsample(alpha, w, h, sub, dw, dh)
        val meanI = boxFilter(gs, dw, dh, rs)
        val meanP = boxFilter(ps, dw, dh, rs)
        val corrI = boxFilter(FloatArray(dw * dh) { gs[it] * gs[it] }, dw, dh, rs)
        val corrIp = boxFilter(FloatArray(dw * dh) { gs[it] * ps[it] }, dw, dh, rs)

        val a = FloatArray(dw * dh)
        val b = FloatArray(dw * dh)
        for (i in 0 until dw * dh) {
            val varI = corrI[i] - meanI[i] * meanI[i]
            val covIp = corrIp[i] - meanI[i] * meanP[i]
            a[i] = covIp / (varI + GUIDE_EPS)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = boxFilter(a, dw, dh, rs)
        val meanB = boxFilter(b, dw, dh, rs)

        val stepX = dw.toFloat() / w
        val stepY = dh.toFloat() / h
        for (y in 0 until h) {
            val fy = (y + 0.5f) * stepY - 0.5f
            val y0 = fy.toInt().coerceIn(0, dh - 1)
            val y1 = (y0 + 1).coerceAtMost(dh - 1)
            val wy = (fy - y0).coerceIn(0f, 1f)
            val row = y * w
            for (x in 0 until w) {
                val fx = (x + 0.5f) * stepX - 0.5f
                val x0 = fx.toInt().coerceIn(0, dw - 1)
                val x1 = (x0 + 1).coerceAtMost(dw - 1)
                val wx = (fx - x0).coerceIn(0f, 1f)
                val ca = bilinear(meanA, dw, x0, x1, y0, y1, wx, wy)
                val cb = bilinear(meanB, dw, x0, x1, y0, y1, wx, wy)
                alpha[row + x] = (ca * guide[row + x] + cb).coerceIn(0f, 1f)
            }
        }
    }

    /** 블록 격자를 이중선형으로 읽어 부드러운 배경색을 얻는다. 블록 경계가 각지게 드러나지 않는다. */
    private fun sampleBackground(bg: Array<FloatArray>, bw: Int, bh: Int, x: Int, y: Int, out: FloatArray) {
        val fx = (x.toFloat() / BG_BLOCK - 0.5f).coerceIn(0f, (bw - 1).toFloat())
        val fy = (y.toFloat() / BG_BLOCK - 0.5f).coerceIn(0f, (bh - 1).toFloat())
        val x0 = fx.toInt().coerceIn(0, bw - 1)
        val y0 = fy.toInt().coerceIn(0, bh - 1)
        val x1 = (x0 + 1).coerceAtMost(bw - 1)
        val y1 = (y0 + 1).coerceAtMost(bh - 1)
        val wx = fx - x0
        val wy = fy - y0
        for (c in 0..2) {
            val top = bg[c][y0 * bw + x0] * (1 - wx) + bg[c][y0 * bw + x1] * wx
            val bottom = bg[c][y1 * bw + x0] * (1 - wx) + bg[c][y1 * bw + x1] * wx
            out[c] = top * (1 - wy) + bottom * wy
        }
    }

    private fun bilinear(
        src: FloatArray,
        w: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
        wx: Float,
        wy: Float,
    ): Float {
        val top = src[y0 * w + x0] * (1 - wx) + src[y0 * w + x1] * wx
        val bottom = src[y1 * w + x0] * (1 - wx) + src[y1 * w + x1] * wx
        return top * (1 - wy) + bottom * wy
    }

    private fun downsample(src: FloatArray, w: Int, h: Int, sub: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        for (dy in 0 until dh) {
            for (dx in 0 until dw) {
                var sum = 0f
                var count = 0
                var y = dy * sub
                val yEnd = min(h, (dy + 1) * sub)
                while (y < yEnd) {
                    var x = dx * sub
                    val xEnd = min(w, (dx + 1) * sub)
                    while (x < xEnd) {
                        sum += src[y * w + x]
                        count++
                        x++
                    }
                    y++
                }
                out[dy * dw + dx] = if (count == 0) 0f else sum / count
            }
        }
        return out
    }

    /** 적분영상 기반 박스 평균. 반지름과 무관하게 픽셀당 상수 시간. */
    private fun boxFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
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

    /**
     * 블록마다 '확실한 배경(알파가 거의 0)' 픽셀의 평균색을 구한다.
     * 배경이 하나도 없는 블록은 이웃에서 퍼뜨려 채운다.
     */
    private fun estimateBackground(pixels: IntArray, alpha: FloatArray, w: Int, h: Int): Array<FloatArray> {
        val bw = (w + BG_BLOCK - 1) / BG_BLOCK
        val bh = (h + BG_BLOCK - 1) / BG_BLOCK
        val sums = Array(3) { FloatArray(bw * bh) }
        val counts = IntArray(bw * bh)
        for (y in 0 until h) {
            val blockRow = (y / BG_BLOCK) * bw
            val row = y * w
            for (x in 0 until w) {
                if (alpha[row + x] >= 0.1f) continue
                val bi = blockRow + x / BG_BLOCK
                val p = pixels[row + x]
                sums[0][bi] += ((p shr 16) and 0xFF).toFloat()
                sums[1][bi] += ((p shr 8) and 0xFF).toFloat()
                sums[2][bi] += (p and 0xFF).toFloat()
                counts[bi]++
            }
        }
        val avg = Array(3) { FloatArray(bw * bh) }
        val known = BooleanArray(bw * bh)
        for (bi in 0 until bw * bh) {
            if (counts[bi] >= BG_BLOCK / 2) {
                known[bi] = true
                for (c in 0..2) avg[c][bi] = sums[c][bi] / counts[bi]
            }
        }
        repeat(bw + bh) {
            var changed = false
            for (by in 0 until bh) {
                for (bx in 0 until bw) {
                    val bi = by * bw + bx
                    if (known[bi]) continue
                    var n = 0
                    val acc = FloatArray(3)
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = bx + dx
                            val ny = by + dy
                            if (nx in 0 until bw && ny in 0 until bh) {
                                val ni = ny * bw + nx
                                if (known[ni]) {
                                    n++
                                    for (c in 0..2) acc[c] += avg[c][ni]
                                }
                            }
                        }
                    }
                    if (n > 0) {
                        for (c in 0..2) avg[c][bi] = acc[c] / n
                        known[bi] = true
                        changed = true
                    }
                }
            }
            if (!changed) return@repeat
        }
        // 그래도 못 채운 블록은 흰색으로 둔다(보정이 사실상 없는 셈).
        for (bi in 0 until bw * bh) {
            if (!known[bi]) for (c in 0..2) avg[c][bi] = 255f
        }
        return avg
    }

    private fun outsideRatio(srcW: Int, srcH: Int, transform: Matrix, spec: PhotoSpec): Float {
        val mapped = RectF(0f, 0f, srcW.toFloat(), srcH.toFloat())
        transform.mapRect(mapped)
        val frame = RectF(0f, 0f, spec.outWidthPx.toFloat(), spec.outHeightPx.toFloat())
        val covered = RectF(frame)
        if (!covered.intersect(mapped)) return 1f
        val frameArea = frame.width() * frame.height()
        return 1f - (covered.width() * covered.height()) / frameArea
    }

    private fun eyesOpen(face: Face): Float? {
        val l = face.leftEyeOpenProbability
        val r = face.rightEyeOpenProbability
        return when {
            l != null && r != null -> min(l, r)
            l != null -> l
            r != null -> r
            else -> null
        }
    }

    private fun regionLuma(bitmap: Bitmap, box: RectF): Float {
        val rect = clampRect(box, bitmap.width, bitmap.height) ?: return 128f
        val w = rect.width()
        val h = rect.height()
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, rect.left, rect.top, w, h)
        var sum = 0.0
        val step = max(1, pixels.size / 20000)
        var count = 0
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            count++
            i += step
        }
        return if (count == 0) 128f else (sum / count).toFloat()
    }

    /**
     * 얼굴 영역을 일정 크기로 줄인 뒤 라플라시안 분산으로 초점을 가늠한다.
     * 절대 기준이 아니라 상대 지표라서 경고까지만 쓰고 실패 판정에는 쓰지 않는다.
     */
    private fun sharpness(bitmap: Bitmap, box: RectF): Float {
        val rect = clampRect(box, bitmap.width, bitmap.height) ?: return 0f
        val patch = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        val size = 100
        val small = Bitmap.createScaledBitmap(patch, size, size, true)
        if (patch !== small) patch.recycle()
        val pixels = IntArray(size * size)
        small.getPixels(pixels, 0, size, 0, 0, size, size)
        small.recycle()

        val gray = FloatArray(size * size)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF))
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until size - 1) {
            for (x in 1 until size - 1) {
                val i = y * size + x
                val lap = gray[i - size] + gray[i + size] + gray[i - 1] + gray[i + 1] - 4f * gray[i]
                sum += lap
                sumSq += lap.toDouble() * lap
                n++
            }
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat()
    }

    private fun clampRect(box: RectF, w: Int, h: Int): Rect? {
        val left = box.left.toInt().coerceIn(0, w - 1)
        val top = box.top.toInt().coerceIn(0, h - 1)
        val right = box.right.toInt().coerceIn(left + 1, w)
        val bottom = box.bottom.toInt().coerceIn(top + 1, h)
        if (right - left < 2 || bottom - top < 2) return null
        return Rect(left, top, right, bottom)
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
    addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
    addOnCanceledListener { cont.cancel() }
}
