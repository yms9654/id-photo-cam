package com.yms.idphoto.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yms.idphoto.photo.BackgroundStyle
import com.yms.idphoto.photo.MediaSaver
import com.yms.idphoto.photo.PhotoProcessor
import com.yms.idphoto.photo.encodeJpegUnderLimit
import com.yms.idphoto.spec.PhotoSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * 결과 화면을 실제 사진으로 한 번 그려 보고 화면을 갤러리에 남긴다.
 * (에뮬레이터 카메라에는 얼굴이 없어 촬영 흐름으로는 이 화면까지 갈 수 없다.)
 */
@RunWith(AndroidJUnit4::class)
class ResultScreenShotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 결과화면을_그린다() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("portrait_sample.jpg").use { it.readBytes() }
        val result = runBlocking { PhotoProcessor.process(bytes, 0, PhotoSpec.PASSPORT) }
        val preview = result.render(BackgroundStyle.WHITE)
        val encoded = encodeJpegUnderLimit(preview, PhotoSpec.PASSPORT.maxFileBytes)

        compose.setContent {
            IdPhotoTheme {
                ResultScreen(
                    result = result,
                    preview = preview,
                    encoded = encoded,
                    background = BackgroundStyle.WHITE,
                    onBackgroundChange = {},
                    onSave = {},
                    onShare = {},
                    onRetake = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()

        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = ByteArrayOutputStream()
        shot.compress(Bitmap.CompressFormat.JPEG, 92, out)
        assertTrue(out.size() > 0)

        MediaSaver.saveToGallery(
            InstrumentationRegistry.getInstrumentation().targetContext,
            out.toByteArray(),
            "shot_result.jpg",
        )
        result.recycle()
    }
}
