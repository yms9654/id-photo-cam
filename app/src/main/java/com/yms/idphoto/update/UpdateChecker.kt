package com.yms.idphoto.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 새 버전 확인과 내려받기.
 *
 * 안드로이드는 사이드로드 앱이 사용자 확인 없이 자기 자신을 바꾸는 것을 허용하지 않는다.
 * 그래서 여기까지가 한계다 — 버전을 확인해 알려주고, APK 를 받아 시스템 설치 화면을 띄운다.
 * 설치를 누르는 것은 사용자 몫이다.
 *
 * 통신은 [manifestUrl] 과 거기에 적힌 APK 주소뿐이다. 사진은 어디로도 보내지 않는다.
 */
class UpdateChecker(
    private val context: Context,
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
) {
    companion object {
        const val DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/yms9654/id-photo-cam/main/update.json"

        private const val PREFS = "update"
        private const val KEY_LAST_CHECK = "lastCheckedAt"
        private const val KEY_SKIPPED = "skippedVersionCode"

        /** 실행할 때마다 물어보면 성가시다. 하루 두 번이면 충분하다. */
        private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    val installedVersionCode: Int
        get() = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        } catch (t: Throwable) {
            Int.MAX_VALUE // 못 읽으면 업데이트를 권하지 않는다.
        }

    /**
     * 새 버전이 있으면 돌려준다. 없거나, 확인에 실패했거나, 사용자가 건너뛴 버전이면 null.
     * [force] 가 false 면 마지막 확인이 최근일 때 통신 없이 넘어간다.
     */
    suspend fun check(force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) {
            return@withContext null
        }
        val body = fetch(manifestUrl) ?: return@withContext null
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        val info = UpdateManifest.parse(body) ?: return@withContext null
        if (!UpdateManifest.isNewer(info, installedVersionCode)) return@withContext null
        if (!force && prefs.getInt(KEY_SKIPPED, 0) >= info.versionCode) return@withContext null
        if (info.urlFor(Build.SUPPORTED_ABIS.toList()) == null) return@withContext null
        info
    }

    fun skip(info: UpdateInfo) {
        prefs.edit().putInt(KEY_SKIPPED, info.versionCode).apply()
    }

    /** APK 를 캐시에 받는다. [onProgress] 는 0~1, 전체 크기를 모르면 호출되지 않는다. */
    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val url = info.urlFor(Build.SUPPORTED_ABIS.toList())
                ?: error("이 기기에 맞는 설치 파일이 없습니다")
            val dir = File(context.cacheDir, "updates").apply {
                mkdirs()
                // 이전에 받다 만 파일이 쌓이지 않게 한다.
                listFiles()?.forEach { it.delete() }
            }
            val target = File(dir, "idphoto-" + info.versionCode + ".apk")

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            try {
                if (connection.responseCode !in 200..299) {
                    error("내려받기 실패 (HTTP " + connection.responseCode + ")")
                }
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            target
        }

    /** 설치 화면을 띄우는 인텐트. 설치 여부는 사용자가 정한다. */
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** 안드로이드 8 이상은 앱마다 '이 출처의 앱 설치'를 따로 허용해야 한다. */
    fun canInstallPackages(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))

    private fun fetch(url: String): String? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (t: Throwable) {
        null
    }
}
