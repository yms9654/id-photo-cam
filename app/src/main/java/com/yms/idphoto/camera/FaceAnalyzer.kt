package com.yms.idphoto.camera

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * 미리보기 프레임마다 얼굴을 찾아 [AnalysisFrame] 으로 넘긴다.
 * 실시간 경로이므로 FAST 모드 + 윤곽선 없음. 정밀 측정은 촬영 후에 따로 한다.
 */
class FaceAnalyzer(
    private val onFrame: (AnalysisFrame) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.2f)
            .build()
    )

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (media == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val upright = rotation == 90 || rotation == 270
        val frameW = if (upright) imageProxy.height else imageProxy.width
        val frameH = if (upright) imageProxy.width else imageProxy.height
        val luma = centerLuma(imageProxy)

        val input = InputImage.fromMediaImage(media, rotation)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val largest = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                onFrame(
                    AnalysisFrame(
                        frameWidth = frameW,
                        frameHeight = frameH,
                        faceCount = faces.size,
                        face = largest?.toRawFace(frameW.toFloat(), frameH.toFloat()),
                        luma = luma,
                    )
                )
            }
            .addOnFailureListener {
                onFrame(AnalysisFrame(frameW, frameH, 0, null, luma))
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    override fun close() {
        detector.close()
    }

    /** 프레임 가운데 절반 영역의 Y(밝기) 평균. 얼굴이 대개 가운데 있으므로 노출 판단에 쓴다. */
    private fun centerLuma(image: ImageProxy): Float? {
        val plane = image.planes.getOrNull(0) ?: return null
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return null

        val x0 = w / 4
        val x1 = w * 3 / 4
        val y0 = h / 4
        val y1 = h * 3 / 4
        val stepX = max(1, (x1 - x0) / 32)
        val stepY = max(1, (y1 - y0) / 32)

        var sum = 0L
        var count = 0
        var y = y0
        while (y < y1) {
            val rowStart = y * rowStride
            var x = x0
            while (x < x1) {
                val index = rowStart + x * pixelStride
                if (index < buffer.limit()) {
                    sum += (buffer.get(index).toInt() and 0xFF)
                    count++
                }
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) null else sum.toFloat() / count
    }
}

private fun Face.toRawFace(frameW: Float, frameH: Float): RawFace {
    val box = boundingBox
    val leftEye = getLandmark(FaceLandmark.LEFT_EYE)?.position
    val rightEye = getLandmark(FaceLandmark.RIGHT_EYE)?.position
    val eyeMidX = if (leftEye != null && rightEye != null) (leftEye.x + rightEye.x) / 2f / frameW else null
    val eyeMidY = if (leftEye != null && rightEye != null) (leftEye.y + rightEye.y) / 2f / frameH else null
    return RawFace(
        left = box.left / frameW,
        top = box.top / frameH,
        right = box.right / frameW,
        bottom = box.bottom / frameH,
        eulerY = headEulerAngleY,
        eulerX = headEulerAngleX,
        eulerZ = headEulerAngleZ,
        leftEyeOpen = leftEyeOpenProbability,
        rightEyeOpen = rightEyeOpenProbability,
        eyeMidX = eyeMidX?.coerceIn(0f, 1f),
        eyeMidY = eyeMidY?.coerceIn(0f, 1f),
    )
}

/** 마스크/박스에서 쓰는 소소한 보조. */
internal fun clamp01(v: Float) = min(1f, max(0f, v))
