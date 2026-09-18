package com.yms.idphoto.photo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yms.idphoto.spec.PhotoSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 실제 사진 한 장을 파이프라인 전체(얼굴 검출 → 인물 분할 → 규격 크롭 → 인코딩)에 통과시킨다.
 * ML Kit 모델이 필요하므로 기기/에뮬레이터에서 돌아간다.
 *
 * 결과 이미지는 갤러리(Pictures/증명사진)에 남겨 눈으로도 확인할 수 있다. 테스트가 끝나면
 * 앱은 지워지지만 갤러리 파일은 남는다.
 *   adb pull /sdcard/Pictures/증명사진/
 */
@RunWith(AndroidJUnit4::class)
class PhotoPipelineTest {

    private fun sampleBytes(asset: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(asset).use { it.readBytes() }

    /**
     * [preCropped] 는 이미 얼굴 위주로 바짝 잘려 있어 규격 크롭이 원본 밖으로 나가는 사진이라는 뜻이다.
     * 그런 사진은 가장자리가 배경색으로 채워지는 게 정상이므로 '프레임' 항목만 예외로 둔다.
     */
    private fun runPipeline(
        spec: PhotoSpec,
        name: String,
        asset: String = "portrait_sample.jpg",
        preCropped: Boolean = false,
    ): Unit = runBlocking {
        val started = System.currentTimeMillis()
        val result = PhotoProcessor.process(sampleBytes(asset), 0, spec)
        val elapsed = System.currentTimeMillis() - started
        try {
            val bitmap = result.render(BackgroundStyle.WHITE)
            assertEquals(spec.outWidthPx, bitmap.width)
            assertEquals(spec.outHeightPx, bitmap.height)

            // 네 모서리는 인물이 닿지 않는 자리다. 흰 배경 합성이 실제로 적용됐는지 본다.
            val white = 0xFFFFFFFF.toInt()
            assertEquals("좌상단 배경", white, bitmap.getPixel(4, 4))
            assertEquals("우상단 배경", white, bitmap.getPixel(bitmap.width - 5, 4))

            val jpeg = encodeJpegUnderLimit(bitmap, spec.maxFileBytes)
            assertTrue("용량 ${jpeg.bytes.size}B", jpeg.bytes.size <= spec.maxFileBytes)

            val m = result.measurements
            assertTrue("머리 ${m.headMm}mm", m.headMm in spec.headMinMm..spec.headMaxMm)
            assertTrue("여백 ${m.topMarginMm}mm", m.topMarginMm in spec.topMarginMinMm..spec.topMarginMaxMm)
            if (!preCropped) {
                assertTrue("프레임 밖 ${m.outsideRatio}", m.outsideRatio < 0.001f)
            }

            val failures = result.report
                .filter { it.status == Status.FAIL }
                .filterNot { preCropped && it.label == "프레임" }
            assertTrue("실패 항목: " + failures.joinToString { it.label + "(" + it.detail + ")" }, failures.isEmpty())

            val backgroundItem = result.report.first { it.label == "배경" }
            assertEquals("인물 분할 성공해야 함", Status.PASS, backgroundItem.status)

            // 에뮬레이터는 실기기보다 훨씬 느리다. 여기서 통과하면 실기기에서는 여유가 있다.
            android.util.Log.i("IdPhotoPerf", name + " 처리 " + elapsed + "ms")
            assertTrue("처리 시간 " + elapsed + "ms", elapsed < 15_000)

            // 저장 경로까지 실제로 한 번 태워 본다.
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MediaSaver.saveToGallery(context, jpeg.bytes, name)
        } finally {
            result.recycle()
        }
    }

    @Test
    fun 여권사진_규격으로_만든다() {
        runPipeline(PhotoSpec.PASSPORT, "verify_passport.jpg")
    }

    @Test
    fun 증명사진_규격으로_만든다() {
        runPipeline(PhotoSpec.ID_PHOTO, "verify_id.jpg")
    }

    @Test
    fun 머리모양이_달라도_규격을_맞춘다() {
        listOf(
            "portrait_long_dark.jpg",
            "portrait_short_dark.jpg",
            "portrait_short_black.jpg",
        ).forEach { asset ->
            runPipeline(PhotoSpec.PASSPORT, "verify_" + asset, asset, preCropped = true)
        }
    }
}
