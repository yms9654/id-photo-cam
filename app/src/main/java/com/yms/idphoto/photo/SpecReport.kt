package com.yms.idphoto.photo

import com.yms.idphoto.spec.PhotoSpec
import kotlin.math.abs

/**
 * 결과 사진 검증 리포트.
 *
 * 머리 길이와 상단 여백은 규격값에 맞춰 **자동으로 잘라내므로** 사후 검증 대상이 아니라
 * "이렇게 맞췄다"는 안내다. 반대로 고개 각도·눈·노출·초점처럼 크롭으로 고칠 수 없는 것들이
 * 실제 통과/실패 판정 대상이다.
 */
object SpecReport {

    fun build(spec: PhotoSpec, m: Measurements, backgroundRemoved: Boolean): List<ValidationItem> {
        val items = mutableListOf<ValidationItem>()

        items += ValidationItem(
            label = "머리 길이",
            status = Status.PASS,
            detail = "%.1fmm 로 맞춤 (규격 %.0f~%.0fmm)".format(m.headMm, spec.headMinMm, spec.headMaxMm),
        )
        items += ValidationItem(
            label = "머리 위 여백",
            status = Status.PASS,
            detail = "%.1fmm 로 맞춤 (규격 %.0f~%.0fmm)".format(
                m.topMarginMm, spec.topMarginMinMm, spec.topMarginMaxMm
            ),
        )

        val absRoll = abs(m.rollDeg)
        items += ValidationItem(
            label = "수평",
            status = when {
                absRoll > 10f -> Status.FAIL
                absRoll > 5f -> Status.WARN
                else -> Status.PASS
            },
            detail = when {
                absRoll > 10f -> "고개가 %.1f° 기울었습니다. 다시 촬영하세요".format(absRoll)
                m.autoLevelDeg != 0f -> "%.1f° 자동 보정됨".format(abs(m.autoLevelDeg))
                else -> "똑바름"
            },
        )

        val pose = maxOf(abs(m.yawDeg), abs(m.pitchDeg))
        items += ValidationItem(
            label = "정면 응시",
            status = when {
                pose > 15f -> Status.FAIL
                pose > 8f -> Status.WARN
                else -> Status.PASS
            },
            detail = "좌우 %.0f° · 상하 %.0f°".format(abs(m.yawDeg), abs(m.pitchDeg)),
        )

        m.eyesOpen?.let { open ->
            items += ValidationItem(
                label = "눈 뜸",
                status = when {
                    open < 0.4f -> Status.FAIL
                    open < 0.7f -> Status.WARN
                    else -> Status.PASS
                },
                detail = if (open < 0.4f) "눈이 감긴 것으로 보입니다" else "%.0f%%".format(open * 100),
            )
        }

        items += ValidationItem(
            label = "밝기",
            status = when {
                m.faceLuma < 70f || m.faceLuma > 225f -> Status.FAIL
                m.faceLuma < 95f || m.faceLuma > 205f -> Status.WARN
                else -> Status.PASS
            },
            detail = when {
                m.faceLuma < 95f -> "얼굴이 어둡습니다 (%.0f)".format(m.faceLuma)
                m.faceLuma > 205f -> "얼굴이 너무 밝습니다 (%.0f)".format(m.faceLuma)
                else -> "적정 (%.0f)".format(m.faceLuma)
            },
        )

        items += ValidationItem(
            label = "초점",
            status = if (m.sharpness < 25f) Status.WARN else Status.PASS,
            detail = if (m.sharpness < 25f) "흔들렸을 수 있습니다" else "선명함",
        )

        items += ValidationItem(
            label = "해상도",
            status = when {
                m.sourceScale < 0.8f -> Status.FAIL
                m.sourceScale < 1f -> Status.WARN
                else -> Status.PASS
            },
            detail = if (m.sourceScale < 1f) {
                "원본이 작아 확대되었습니다 (×%.2f)".format(1f / m.sourceScale)
            } else {
                "%d × %d px".format(spec.outWidthPx, spec.outHeightPx)
            },
        )

        if (m.outsideRatio > 0.001f) {
            items += ValidationItem(
                label = "프레임",
                status = if (m.outsideRatio > 0.03f) Status.FAIL else Status.WARN,
                detail = "가장자리 %.1f%% 가 원본 밖이라 배경으로 채워졌습니다".format(m.outsideRatio * 100),
            )
        }

        items += ValidationItem(
            label = "배경",
            status = if (backgroundRemoved) Status.PASS else Status.WARN,
            detail = if (backgroundRemoved) "인물 분리 후 단색 합성" else "인물 분리 실패 — 원본 배경 그대로입니다",
        )

        return items
    }
}
