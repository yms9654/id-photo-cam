package com.yms.idphoto.lab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yms.idphoto.photo.MediaSaver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 배경 제거 방식 비교 기록.
 *
 * 처음에는 마스크를 침식(erode)해 배경 띠를 없앴는데, 잔머리까지 깎여 머리카락이 유령처럼 보였다.
 * 지금 방식(가이디드 필터로 알파를 다시 세우고 경계에서 배경색을 걷어내기)이 그보다
 * 머리카락을 더 남기면서 색 번짐도 늘지 않는다는 것을 네 가지 머리 모양에서 확인한다.
 *
 * 여기 있는 합성 코드는 비교용 별도 구현이라 PhotoProcessor 를 그대로 검증하지는 않는다.
 * 방식 선택의 근거를 남기고, 파라미터를 다시 만질 때 눈으로 확인할 시트를 만드는 것이 목적이다.
 * (시트는 갤러리에 lab_*.png 로 저장된다.)
 */
@RunWith(AndroidJUnit4::class)
class MattingLabTest {

    private val old = Variant("옛 방식(침식)", erodeRadius = 2, low = 0.50f, high = 0.85f)
    private val current = Variant(
        "지금 방식(가이디드+배경색제거)",
        low = 0.35f,
        high = 0.80f,
        guidedRadius = 16,
        guidedEps = 1e-2f,
        decontaminate = true,
    )
    private val variants = listOf(old, current)

    private val assets = listOf(
        "portrait_sample.jpg",
        "portrait_long_dark.jpg",
        "portrait_short_dark.jpg",
        "portrait_short_black.jpg",
    )

    @Test
    fun 지금_방식이_머리카락을_더_남긴다() {
        assets.forEach { name ->
            val (oldScore, newScore) = buildSheet(name)
            assertTrue(
                name + ": 머리 넓이 " + oldScore.coverage + " → " + newScore.coverage,
                newScore.coverage >= oldScore.coverage,
            )
            assertTrue(
                name + ": 파랑 번짐 " + oldScore.bleed + " → " + newScore.bleed,
                newScore.bleed <= oldScore.bleed + 0.5f,
            )
        }
    }

    private data class Score(val bleed: Float, val coverage: Float)

    private fun buildSheet(assetName: String): Pair<Score, Score> = runBlocking {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open(assetName).use { it.readBytes() }
        val source = decode(bytes, 1400)
        val face = faceOf(source) ?: error("얼굴 없음: " + assetName)
        val mask = segmentOf(source)
        val box = RectF(face.boundingBox)

        val headRegion = Rect(
            (box.left - box.width() * 0.45f).toInt().coerceAtLeast(0),
            (box.top - box.height() * 0.55f).toInt().coerceAtLeast(0),
            (box.right + box.width() * 0.45f).toInt().coerceAtMost(source.width),
            (box.bottom + box.height() * 0.15f).toInt().coerceAtMost(source.height),
        )
        // 마스크에서 실제 머리카락 경계를 찾아 그 지점을 확대한다.
        val patchSide = (box.width() * 0.35f).toInt()
        val probeY = (box.top + box.height() * 0.05f).toInt().coerceIn(0, source.height - 1)
        val my = (probeY * mask.height / source.height).coerceIn(0, mask.height - 1)
        var boundaryX = box.left.toInt()
        var x = box.left.toInt()
        while (x > 0) {
            val mx = (x * mask.width / source.width).coerceIn(0, mask.width - 1)
            if (mask.values[my * mask.width + mx] < 0.5f) { boundaryX = x; break }
            x--
        }
        val patch = Rect(
            (boundaryX - patchSide / 2).coerceIn(0, source.width - patchSide),
            (probeY - patchSide / 2).coerceIn(0, source.height - patchSide),
            0, 0,
        ).also {
            it.right = it.left + patchSide
            it.bottom = it.top + patchSide
        }

        val cell = 460
        val headH = (cell * headRegion.height() / headRegion.width().toFloat()).toInt()
        val labelH = 46
        val sheet = Bitmap.createBitmap(
            cell * variants.size,
            labelH + headH + cell + labelH,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(18, 22, 30))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }
        val small = Paint(text).apply { textSize = 17f; color = Color.rgb(200, 190, 150) }

        val scores = mutableListOf<Score>()
        variants.forEachIndexed { index, variant ->
            val composed = compose(source, mask, variant)
            val x = index * cell
            canvas.drawText(variant.name, (x + cell / 2).toFloat(), 32f, text)

            val head = Bitmap.createBitmap(
                composed.bitmap, headRegion.left, headRegion.top, headRegion.width(), headRegion.height()
            )
            canvas.drawBitmap(head, null, Rect(x, labelH, x + cell, labelH + headH), null)
            head.recycle()

            val zoom = Bitmap.createBitmap(composed.bitmap, patch.left, patch.top, patch.width(), patch.height())
            canvas.drawBitmap(zoom, null, Rect(x, labelH + headH, x + cell, labelH + headH + cell), null)
            zoom.recycle()

            val bleed = blueBleed(composed, RectF(headRegion))
            val cover = coverage(composed, source.width, RectF(headRegion))
            scores += Score(bleed, cover)
            val luma = faceLuma(composed, box)
            canvas.drawText(
                "파랑번짐 %.1f · 머리넓이 %.3f · 얼굴밝기 %.0f".format(bleed, cover, luma),
                (x + cell / 2).toFloat(),
                (labelH + headH + cell + 30).toFloat(),
                small,
            )
            composed.bitmap.recycle()
        }

        val out = ByteArrayOutputStream()
        sheet.compress(Bitmap.CompressFormat.PNG, 100, out)
        MediaSaver.saveToGallery(
            InstrumentationRegistry.getInstrumentation().targetContext,
            out.toByteArray(),
            "lab_" + assetName.removeSuffix(".jpg") + ".png",
        )
        sheet.recycle()
        source.recycle()
        scores[0] to scores[1]
    }

    private fun decode(bytes: ByteArray, longEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longEdge) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }
}
