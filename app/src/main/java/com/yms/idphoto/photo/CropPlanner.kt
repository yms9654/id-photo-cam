package com.yms.idphoto.photo

import com.yms.idphoto.spec.PhotoSpec
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** 원본 사진 좌표계의 한 점. */
data class Pt(val x: Float, val y: Float)

/**
 * 규격대로 잘라낼 영역. 좌표는 '수평 보정을 끝낸' 좌표계 기준이며,
 * 그 보정은 [pivot] 을 중심으로 [levelDeg] 만큼 되돌리는 회전이다.
 */
data class CropPlan(
    val levelDeg: Float,
    val pivot: Pt,
    val cropLeft: Float,
    val cropTop: Float,
    val cropWidth: Float,
    val cropHeight: Float,
    val headTopY: Float,
    val headPx: Float,
) {
    val headRatio: Float get() = headPx / cropHeight
    val topMarginRatio: Float get() = (headTopY - cropTop) / cropHeight
    fun scaleTo(outHeightPx: Int): Float = outHeightPx / cropHeight
}

/**
 * 규격 크롭 계산. 안드로이드 API 를 쓰지 않는 순수 계산이라 그대로 단위 테스트할 수 있다.
 *
 * 핵심은 두 가지다.
 *  - 머리 길이(정수리~턱)가 사진 세로의 규격 비율이 되도록 크롭 높이를 역산한다.
 *  - 정수리 위 여백도 규격 비율로 맞춰 세로 위치를 정한다.
 * 가로는 두 눈 중점을 사진 중앙에 둔다.
 */
object CropPlanner {

    /** 눈 선 기울기가 이보다 크면 검출 오류로 보고 수평 보정을 하지 않는다. */
    const val MAX_AUTO_LEVEL_DEG = 10f

    fun eyeAngleDegrees(leftEye: Pt?, rightEye: Pt?, fallbackDeg: Float): Float {
        if (leftEye == null || rightEye == null) return fallbackDeg
        var deg = Math.toDegrees(
            atan2((rightEye.y - leftEye.y).toDouble(), (rightEye.x - leftEye.x).toDouble())
        ).toFloat()
        if (deg > 90f) deg -= 180f
        if (deg < -90f) deg += 180f
        return deg
    }

    fun levelAngle(eyeAngleDeg: Float): Float =
        if (abs(eyeAngleDeg) <= MAX_AUTO_LEVEL_DEG) eyeAngleDeg else 0f

    fun rotate(point: Pt, pivot: Pt, degrees: Float): Pt {
        if (degrees == 0f) return point
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val dx = point.x - pivot.x
        val dy = point.y - pivot.y
        return Pt(pivot.x + dx * c - dy * s, pivot.y + dx * s + dy * c)
    }

    fun plan(
        spec: PhotoSpec,
        headTop: Pt,
        chin: Pt,
        center: Pt,
        pivot: Pt,
        levelDeg: Float,
    ): CropPlan {
        val headTopL = rotate(headTop, pivot, -levelDeg)
        val chinL = rotate(chin, pivot, -levelDeg)
        val centerL = rotate(center, pivot, -levelDeg)

        val headPx = max(1f, chinL.y - headTopL.y)
        val cropHeight = headPx / spec.headTargetRatio
        val cropWidth = cropHeight * spec.aspect
        val cropTop = headTopL.y - cropHeight * spec.topMarginTargetRatio
        val cropLeft = centerL.x - cropWidth / 2f

        return CropPlan(
            levelDeg = levelDeg,
            pivot = pivot,
            cropLeft = cropLeft,
            cropTop = cropTop,
            cropWidth = cropWidth,
            cropHeight = cropHeight,
            headTopY = headTopL.y,
            headPx = headPx,
        )
    }
}
