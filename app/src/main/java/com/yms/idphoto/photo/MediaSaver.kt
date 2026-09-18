package com.yms.idphoto.photo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object MediaSaver {

    private const val ALBUM = "증명사진"

    /** 갤러리(Pictures/증명사진)에 저장하고 URI 를 돌려준다. */
    fun saveToGallery(context: Context, bytes: ByteArray, fileName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("갤러리에 저장할 수 없습니다")

        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("저장 스트림을 열 수 없습니다")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    /** 공유용 임시 파일 URI. 캐시에 두므로 앱 정리 시 함께 지워진다. */
    fun shareUri(context: Context, bytes: ByteArray, fileName: String): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }
}
