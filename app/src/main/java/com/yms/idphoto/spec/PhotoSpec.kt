package com.yms.idphoto.spec

/**
 * 사진 규격. 길이는 모두 mm 기준으로 정의하고, 화면/출력에서는 세로 길이에 대한
 * 비율(ratio)로 환산해 쓴다. 이렇게 두면 미리보기 해상도나 출력 픽셀 수가 달라져도
 * 같은 규칙을 그대로 적용할 수 있다.
 *
 * headMm 은 "정수리 ~ 턱 끝" 길이(머리 길이)이고, topMarginMm 은 "사진 윗변 ~ 정수리" 여백이다.
 */
data class PhotoSpec(
    val id: String,
    val label: String,
    val summary: String,
    val widthMm: Float,
    val heightMm: Float,
    val outWidthPx: Int,
    val outHeightPx: Int,
    val headMinMm: Float,
    val headMaxMm: Float,
    val topMarginMinMm: Float,
    val topMarginMaxMm: Float,
    val maxFileBytes: Int,
    val notes: List<String>,
) {
    val aspect: Float get() = widthMm / heightMm

    val headMinRatio: Float get() = headMinMm / heightMm
    val headMaxRatio: Float get() = headMaxMm / heightMm
    val headTargetRatio: Float get() = (headMinRatio + headMaxRatio) / 2f

    val topMarginMinRatio: Float get() = topMarginMinMm / heightMm
    val topMarginMaxRatio: Float get() = topMarginMaxMm / heightMm
    val topMarginTargetRatio: Float get() = (topMarginMinRatio + topMarginMaxRatio) / 2f

    /** 출력 픽셀 1px 이 몇 mm 인지 (세로 기준). 검증 리포트에서 mm 로 되돌릴 때 사용. */
    val mmPerPx: Float get() = heightMm / outHeightPx

    companion object {
        val ID_PHOTO = PhotoSpec(
            id = "kr_id",
            label = "증명사진",
            summary = "35 × 45 mm · 413 × 531 px",
            widthMm = 35f,
            heightMm = 45f,
            outWidthPx = 413,
            outHeightPx = 531,
            headMinMm = 30f,
            headMaxMm = 36f,
            topMarginMinMm = 3f,
            topMarginMaxMm = 8f,
            maxFileBytes = 500_000,
            notes = listOf(
                "이력서·학생증·자격증 등 일반 증명사진 규격입니다.",
                "배경은 흰색으로 합성되며 500KB 이하 JPEG 으로 저장됩니다.",
            ),
        )

        val PASSPORT = PhotoSpec(
            id = "kr_passport",
            label = "여권사진",
            summary = "35 × 45 mm · 머리 32~36 mm",
            widthMm = 35f,
            heightMm = 45f,
            outWidthPx = 413,
            outHeightPx = 531,
            headMinMm = 32f,
            headMaxMm = 36f,
            topMarginMinMm = 3f,
            topMarginMaxMm = 5f,
            maxFileBytes = 500_000,
            notes = listOf(
                "정면·무표정, 입은 다물고 눈은 크게 뜬 상태로 촬영하세요.",
                "귀가 보이도록 머리카락을 정리하고, 앞머리로 눈썹을 가리지 마세요.",
                "제복·모자·컬러렌즈는 안 되며, 안경은 벗는 것을 권장합니다.",
                "이 앱은 구도(머리 크기·위치·기울기)만 검증합니다. 표정·복장은 직접 확인하세요.",
            ),
        )

        val ALL = listOf(ID_PHOTO, PASSPORT)

        fun byId(id: String): PhotoSpec = ALL.firstOrNull { it.id == id } ?: ID_PHOTO
    }
}
