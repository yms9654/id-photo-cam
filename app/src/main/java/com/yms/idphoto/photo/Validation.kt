package com.yms.idphoto.photo

enum class Status { PASS, WARN, FAIL }

data class ValidationItem(
    val label: String,
    val status: Status,
    val detail: String,
)

/** 촬영본에서 잰 값들. 길이는 최종 출력(규격) 기준 mm. */
data class Measurements(
    val headMm: Float,
    val topMarginMm: Float,
    val rollDeg: Float,
    val autoLevelDeg: Float,
    val yawDeg: Float,
    val pitchDeg: Float,
    val eyesOpen: Float?,
    val faceLuma: Float,
    val sharpness: Float,
    /** 출력 프레임 중 원본 밖이라 배경색으로 채워진 비율(0~1). */
    val outsideRatio: Float,
    /** 잘라낸 영역의 원본 픽셀 높이 ÷ 출력 높이. 1 미만이면 확대된 것. */
    val sourceScale: Float,
)

val List<ValidationItem>.worst: Status
    get() = when {
        any { it.status == Status.FAIL } -> Status.FAIL
        any { it.status == Status.WARN } -> Status.WARN
        else -> Status.PASS
    }
