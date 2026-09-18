package com.yms.idphoto

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yms.idphoto.photo.BackgroundStyle
import com.yms.idphoto.photo.EncodedJpeg
import com.yms.idphoto.photo.MediaSaver
import com.yms.idphoto.photo.PhotoFailure
import com.yms.idphoto.photo.PhotoProcessor
import com.yms.idphoto.photo.PhotoResult
import com.yms.idphoto.photo.encodeJpegUnderLimit
import com.yms.idphoto.spec.PhotoSpec
import com.yms.idphoto.update.UpdateChecker
import com.yms.idphoto.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Stage { CAMERA, PROCESSING, RESULT }

class MainViewModel : ViewModel() {

    var spec by mutableStateOf(PhotoSpec.ID_PHOTO)
        private set
    var stage by mutableStateOf(Stage.CAMERA)
        private set
    var background by mutableStateOf(BackgroundStyle.WHITE)
        private set
    var result by mutableStateOf<PhotoResult?>(null)
        private set
    var preview by mutableStateOf<Bitmap?>(null)
        private set
    var encoded by mutableStateOf<EncodedJpeg?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var toast by mutableStateOf<String?>(null)
        private set
    var update by mutableStateOf<UpdateInfo?>(null)
        private set
    var updateProgress by mutableStateOf<Float?>(null)
        private set

    fun selectSpec(next: PhotoSpec) {
        spec = next
    }

    fun onCaptured(jpeg: ByteArray, rotationDegrees: Int) {
        stage = Stage.PROCESSING
        errorMessage = null
        viewModelScope.launch {
            try {
                val processed = PhotoProcessor.process(jpeg, rotationDegrees, spec)
                clearResult()
                result = processed
                renderPreview(processed, background)
                stage = Stage.RESULT
            } catch (failure: PhotoFailure) {
                errorMessage = failure.message
                stage = Stage.CAMERA
            } catch (t: Throwable) {
                errorMessage = "사진을 처리하지 못했습니다: " + (t.message ?: t.javaClass.simpleName)
                stage = Stage.CAMERA
            }
        }
    }

    fun chooseBackground(style: BackgroundStyle) {
        background = style
        val current = result ?: return
        viewModelScope.launch { renderPreview(current, style) }
    }

    private suspend fun renderPreview(processed: PhotoResult, style: BackgroundStyle) {
        val bitmap = withContext(Dispatchers.Default) { processed.render(style) }
        val jpeg = withContext(Dispatchers.Default) {
            encodeJpegUnderLimit(bitmap, processed.spec.maxFileBytes)
        }
        preview?.recycle()
        preview = bitmap
        encoded = jpeg
    }

    fun retake() {
        clearResult()
        stage = Stage.CAMERA
    }

    fun save(context: Context) {
        val jpeg = encoded ?: return
        viewModelScope.launch {
            toast = try {
                withContext(Dispatchers.IO) {
                    MediaSaver.saveToGallery(context, jpeg.bytes, fileName())
                }
                "갤러리 > 증명사진 앨범에 저장했습니다"
            } catch (t: Throwable) {
                "저장 실패: " + (t.message ?: "알 수 없는 오류")
            }
        }
    }

    fun share(context: Context) {
        val jpeg = encoded ?: return
        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    MediaSaver.shareUri(context, jpeg.bytes, fileName())
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "사진 보내기"))
            } catch (t: Throwable) {
                toast = "공유 실패: " + (t.message ?: "알 수 없는 오류")
            }
        }
    }

    fun checkForUpdate(context: Context) {
        if (update != null || updateProgress != null) return
        val checker = UpdateChecker(context.applicationContext)
        viewModelScope.launch {
            update = runCatching { checker.check() }.getOrNull()
        }
    }

    /**
     * 사이드로드 앱은 스스로 설치를 끝낼 수 없다. 여기서는 APK 를 받아 시스템 설치 화면까지만 띄운다.
     * 안드로이드 8 이상은 앱마다 '알 수 없는 앱 설치' 허용이 따로 필요해서, 없으면 설정으로 보낸다.
     */
    fun installUpdate(context: Context) {
        val info = update ?: return
        if (updateProgress != null) return
        val app = context.applicationContext
        val checker = UpdateChecker(app)
        if (!checker.canInstallPackages()) {
            toast = "설정에서 이 앱의 '알 수 없는 앱 설치'를 허용해 주세요"
            app.startActivity(checker.unknownSourcesIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        viewModelScope.launch {
            updateProgress = 0f
            try {
                var lastShown = -1
                val file = checker.download(info) { fraction ->
                    val percent = (fraction * 100).toInt()
                    if (percent != lastShown) {
                        lastShown = percent
                        updateProgress = fraction
                    }
                }
                updateProgress = null
                app.startActivity(checker.installIntent(file))
            } catch (t: Throwable) {
                updateProgress = null
                toast = "업데이트 실패: " + (t.message ?: "알 수 없는 오류")
            }
        }
    }

    fun skipUpdate(context: Context) {
        val info = update ?: return
        UpdateChecker(context.applicationContext).skip(info)
        update = null
    }

    fun consumeToast() {
        toast = null
    }

    fun consumeError() {
        errorMessage = null
    }

    private fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
        return spec.label + "_" + stamp + ".jpg"
    }

    private fun clearResult() {
        preview?.recycle()
        preview = null
        result?.recycle()
        result = null
        encoded = null
    }

    override fun onCleared() {
        clearResult()
    }
}
