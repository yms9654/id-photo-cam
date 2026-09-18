package com.yms.idphoto.camera

/**
 * 미리보기 한 프레임의 분석 결과.
 * 좌표는 모두 "회전 보정이 끝난(세워진) 프레임" 기준의 0~1 정규화 값이다.
 * 이렇게 두면 분석 해상도와 화면 크기가 달라도 그대로 쓸 수 있다.
 */
data class AnalysisFrame(
    val frameWidth: Int,
    val frameHeight: Int,
    val faceCount: Int,
    val face: RawFace?,
    /** 화면 중앙부 평균 밝기 (0~255). 구할 수 없으면 null. */
    val luma: Float?,
)

data class RawFace(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** 좌우 회전(도). 0 이면 정면. */
    val eulerY: Float,
    /** 상하 끄덕임(도). */
    val eulerX: Float,
    /** 고개 기울기(도). */
    val eulerZ: Float,
    val leftEyeOpen: Float?,
    val rightEyeOpen: Float?,
    val eyeMidX: Float?,
    val eyeMidY: Float?,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = eyeMidX ?: ((left + right) / 2f)
}

/**
 * ML Kit 이 주는 얼굴 박스는 대략 '이마 ~ 턱' 범위라 정수리(머리카락 위)를 포함하지 않는다.
 * 실시간 가이드용으로는 박스 높이에 이 비율만큼 위쪽을 더해 머리 길이를 추정한다.
 * (촬영 후 최종 크롭에서는 인물 분할 마스크로 실제 정수리를 찾으므로 여기 값은 안내용일 뿐이다.)
 */
const val HEAD_TOP_EXTENSION = 0.33f
