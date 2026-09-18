package com.yms.idphoto.camera

import com.yms.idphoto.spec.PhotoSpec
import kotlin.math.abs

/** 가이드 프레임(0~1) 기준으로 환산한 머리 위치·자세 측정값. */
data class HeadMetrics(
    val headTop: Float,
    val chin: Float,
    val centerX: Float,
    val eulerX: Float,
    val eulerY: Float,
    val eulerZ: Float,
    val eyesOpen: Float?,
    val luma: Float?,
) {
    val headHeight: Float get() = chin - headTop
}

data class Check(val label: String, val ok: Boolean)

data class Guidance(
    val ready: Boolean,
    val message: String,
    val checks: List<Check>,
)

/**
 * 실시간 촬영 가이드.
 *
 * 여기서 쓰는 허용 범위는 규격보다 **일부러 넉넉하다.** 최종 사진은 촬영 후에
 * 인물 분할 마스크로 정수리를 찾아 규격대로 다시 크롭하므로, 실시간 단계에서
 * 요구할 것은 "규격대로 잘라낼 수 있을 만큼 잘 찍혔는가" 뿐이다.
 * 대신 나중에 고칠 수 없는 것(고개 각도, 눈 감김, 노출)은 엄격하게 본다.
 */
object GuidanceRules {
    private const val SIZE_SLACK_UNDER = 0.10f
    private const val SIZE_SLACK_OVER = 0.08f
    private const val TOP_SLACK_UNDER = 0.06f
    private const val TOP_SLACK_OVER = 0.10f
    private const val CENTER_TOLERANCE = 0.08f

    private const val MAX_ROLL = 6f
    private const val MAX_YAW = 8f
    private const val MAX_PITCH = 8f
    private const val MIN_EYES_OPEN = 0.55f
    private const val MIN_LUMA = 75f
    private const val MAX_LUMA = 215f

    fun evaluate(spec: PhotoSpec, faceCount: Int, metrics: HeadMetrics?): Guidance {
        if (faceCount == 0 || metrics == null) {
            return Guidance(false, "얼굴이 보이지 않아요", emptyList())
        }
        if (faceCount > 1) {
            return Guidance(false, "화면에 한 사람만 나와야 해요", emptyList())
        }

        val minHead = spec.headMinRatio - SIZE_SLACK_UNDER
        val maxHead = spec.headMaxRatio + SIZE_SLACK_OVER
        val minTop = spec.topMarginMinRatio - TOP_SLACK_UNDER
        val maxTop = spec.topMarginMaxRatio + TOP_SLACK_OVER

        val sizeOk = metrics.headHeight in minHead..maxHead
        val verticalOk = metrics.headTop in minTop..maxTop
        val centerOk = abs(metrics.centerX - 0.5f) <= CENTER_TOLERANCE
        val poseOk = abs(metrics.eulerZ) <= MAX_ROLL &&
            abs(metrics.eulerY) <= MAX_YAW &&
            abs(metrics.eulerX) <= MAX_PITCH
        val eyesOk = metrics.eyesOpen?.let { it >= MIN_EYES_OPEN } ?: true
        val lightOk = metrics.luma?.let { it in MIN_LUMA..MAX_LUMA } ?: true

        val checks = listOf(
            Check("머리 크기", sizeOk),
            Check("얼굴 위치", verticalOk && centerOk),
            Check("정면·수평", poseOk),
            Check("눈 뜨기", eyesOk),
            Check("밝기", lightOk),
        )

        // 사용자에게는 한 번에 하나씩만 시킨다. 여러 개를 동시에 띄우면 아무것도 못 고친다.
        val message = when {
            !sizeOk && metrics.headHeight < minHead -> "조금 더 가까이 오세요"
            !sizeOk -> "조금 더 뒤로 가세요"
            !verticalOk && metrics.headTop < minTop -> "휴대폰을 조금 위로 올려주세요"
            !verticalOk -> "휴대폰을 조금 아래로 내려주세요"
            !centerOk && metrics.centerX < 0.5f -> "오른쪽으로 조금 이동하세요"
            !centerOk -> "왼쪽으로 조금 이동하세요"
            abs(metrics.eulerZ) > MAX_ROLL -> "고개를 수평으로 맞춰주세요"
            abs(metrics.eulerY) > MAX_YAW -> "정면을 바라보세요"
            abs(metrics.eulerX) > MAX_PITCH -> "턱을 당기고 정면을 보세요"
            !eyesOk -> "눈을 크게 떠주세요"
            !lightOk && (metrics.luma ?: 0f) < MIN_LUMA -> "더 밝은 곳에서 찍어주세요"
            !lightOk -> "빛이 너무 강해요. 조금 어두운 곳으로"
            else -> "좋아요! 그대로 계세요"
        }

        return Guidance(checks.all { it.ok }, message, checks)
    }
}
