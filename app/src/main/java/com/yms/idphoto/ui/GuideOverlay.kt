package com.yms.idphoto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yms.idphoto.spec.PhotoSpec

/** 미리보기가 화면에 실제로 그려지는 영역(레터박스 제외). */
fun fitCenterRect(viewWidth: Float, viewHeight: Float, aspect: Float): Rect {
    var w = viewWidth
    var h = w / aspect
    if (h > viewHeight) {
        h = viewHeight
        w = h * aspect
    }
    val left = (viewWidth - w) / 2f
    val top = (viewHeight - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/** 규격 비율대로 잡은 촬영 가이드 프레임. */
fun guideFrameIn(preview: Rect, aspect: Float): Rect {
    var h = preview.height * 0.88f
    var w = h * aspect
    val maxW = preview.width * 0.80f
    if (w > maxW) {
        w = maxW
        h = w / aspect
    }
    val left = preview.left + (preview.width - w) / 2f
    val top = preview.top + (preview.height - h) * 0.38f
    return Rect(left, top, left + w, top + h)
}

/**
 * 촬영 가이드. 인화소에서 쓰는 재단 표시(트림 마크)와 머리 길이 눈금으로 규격 자체를 보여준다.
 * 조건이 모두 맞으면 표시가 금박색으로 바뀐다.
 */
@Composable
fun GuideOverlay(
    spec: PhotoSpec,
    ready: Boolean,
    modifier: Modifier = Modifier,
    frameProvider: (Size) -> Rect?,
) {
    Canvas(modifier = modifier) {
        val frame = frameProvider(size) ?: return@Canvas
        drawScrim(frame)
        val accent = if (ready) Ink.Foil else Ink.Paper.copy(alpha = 0.55f)
        drawHeadGuide(spec, frame, ready)
        drawTrimMarks(frame, accent)
        drawHeadScale(spec, frame, accent)
    }
}

private fun DrawScope.drawScrim(frame: Rect) {
    val outside = Path().apply {
        addRect(Rect(0f, 0f, size.width, size.height))
    }
    val inside = Path().apply { addRect(frame) }
    val scrim = Path().apply {
        op(outside, inside, PathOperation.Difference)
    }
    drawPath(scrim, Ink.Deep.copy(alpha = 0.68f))
}

private fun DrawScope.drawHeadGuide(spec: PhotoSpec, frame: Rect, ready: Boolean) {
    val headHeight = frame.height * spec.headTargetRatio
    val headWidth = headHeight * 0.72f
    val headTop = frame.top + frame.height * spec.topMarginTargetRatio
    val headRect = Rect(
        frame.center.x - headWidth / 2f,
        headTop,
        frame.center.x + headWidth / 2f,
        headTop + headHeight,
    )
    val color = if (ready) Ink.Foil.copy(alpha = 0.85f) else Ink.Paper.copy(alpha = 0.42f)
    drawOval(
        color = color,
        topLeft = headRect.topLeft,
        size = headRect.size,
        style = Stroke(
            width = 1.5.dp.toPx(),
            pathEffect = if (ready) null else PathEffect.dashPathEffect(floatArrayOf(14f, 12f)),
        ),
    )

    // 눈높이 눈금: 머리 위에서 약 45% 지점. 프레임 양쪽에 짧게만 긋는다.
    val eyeY = headRect.top + headRect.height * 0.45f
    val tick = frame.width * 0.07f
    drawLine(color, Offset(frame.left, eyeY), Offset(frame.left + tick, eyeY), 1.5.dp.toPx())
    drawLine(color, Offset(frame.right - tick, eyeY), Offset(frame.right, eyeY), 1.5.dp.toPx())
}

private fun DrawScope.drawTrimMarks(frame: Rect, color: Color) {
    val len = 26.dp.toPx()
    val gap = 7.dp.toPx()
    val stroke = 2.dp.toPx()
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(color, Offset(x + dx * gap, y), Offset(x + dx * (gap + len), y), stroke)
        drawLine(color, Offset(x, y + dy * gap), Offset(x, y + dy * (gap + len)), stroke)
    }
    corner(frame.left, frame.top, 1f, 1f)
    corner(frame.right, frame.top, -1f, 1f)
    corner(frame.left, frame.bottom, 1f, -1f)
    corner(frame.right, frame.bottom, -1f, -1f)
}

/** 프레임 왼쪽 바깥에 '정수리~턱' 규격 눈금을 세워 둔다. */
private fun DrawScope.drawHeadScale(spec: PhotoSpec, frame: Rect, color: Color) {
    val headHeight = frame.height * spec.headTargetRatio
    val top = frame.top + frame.height * spec.topMarginTargetRatio
    val x = frame.left - 16.dp.toPx()
    if (x < 8.dp.toPx()) return
    val serif = 6.dp.toPx()
    val stroke = 1.5.dp.toPx()
    drawLine(color, Offset(x, top), Offset(x, top + headHeight), stroke)
    drawLine(color, Offset(x - serif, top), Offset(x + serif, top), stroke)
    drawLine(color, Offset(x - serif, top + headHeight), Offset(x + serif, top + headHeight), stroke)

    val label = "%.0f~%.0f mm".format(spec.headMinMm, spec.headMaxMm)
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = 11.sp.toPx()
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.apply {
        save()
        rotate(-90f, x - 12.dp.toPx(), top + headHeight / 2f)
        drawText(label, x - 12.dp.toPx(), top + headHeight / 2f + paint.textSize / 3f, paint)
        restore()
    }
}
