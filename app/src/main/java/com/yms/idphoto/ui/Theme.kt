package com.yms.idphoto.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 여권 표지(남색)와 금박 각인에서 가져온 팔레트.
 * 카메라 화면은 미리보기가 주인공이라 주변은 최대한 가라앉히고,
 * 강조는 '촬영 준비됨'을 알리는 금박 한 곳에만 쓴다.
 */
object Ink {
    val Deep = Color(0xFF0C1322)      // 미리보기 뒤 배경
    val Navy = Color(0xFF16233B)      // 패널/바
    val NavyLift = Color(0xFF203150)  // 눌린 상태·구분선
    val Paper = Color(0xFFF0EDE4)     // 여권 내지 종이색 = 기본 글자색
    val PaperDim = Color(0xFFA9B2C2)  // 보조 글자
    val Foil = Color(0xFFD4B36A)      // 금박: 준비 완료·주요 동작
    val Stamp = Color(0xFFB4483C)     // 입국 도장: 실패
    val Caution = Color(0xFFD9A441)   // 경고
    val Good = Color(0xFF6FA88A)      // 통과
}

private val AppTypography = Typography(
    displayLarge = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Normal, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun IdPhotoTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme() // 이 앱은 촬영 화면 특성상 항상 어두운 테마를 쓴다.
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.Foil,
            onPrimary = Ink.Deep,
            background = Ink.Deep,
            onBackground = Ink.Paper,
            surface = Ink.Navy,
            onSurface = Ink.Paper,
            error = Ink.Stamp,
        ),
        typography = AppTypography,
        content = content,
    )
}
