package com.yms.idphoto.update

import org.json.JSONObject

/**
 * 배포처에 올려 두는 버전 정보. 형태는 이렇다.
 *
 * ```json
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1",
 *   "notes": "머리카락 경계 처리 개선",
 *   "apk": {
 *     "arm64-v8a": "https://.../idphoto-arm64-v8a.apk",
 *     "armeabi-v7a": "https://.../idphoto-armeabi-v7a.apk",
 *     "universal": "https://.../idphoto-universal.apk"
 *   }
 * }
 * ```
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrls: Map<String, String>,
) {
    /** 기기가 지원하는 ABI 중 먼저 맞는 것을 고르고, 없으면 모든 ABI 가 들어간 파일로 떨어진다. */
    fun urlFor(supportedAbis: List<String>): String? =
        supportedAbis.firstNotNullOfOrNull { apkUrls[it] } ?: apkUrls["universal"]
}

object UpdateManifest {

    /** 형식이 어긋나면 null. 업데이트 확인 실패가 앱을 막아서는 안 된다. */
    fun parse(json: String): UpdateInfo? = try {
        val root = JSONObject(json)
        val apk = root.getJSONObject("apk")
        val urls = buildMap {
            apk.keys().forEach { key -> put(key, apk.getString(key)) }
        }
        val versionCode = root.getInt("versionCode")
        if (versionCode <= 0 || urls.isEmpty()) {
            null
        } else {
            UpdateInfo(
                versionCode = versionCode,
                versionName = root.optString("versionName", versionCode.toString()),
                notes = root.optString("notes", ""),
                apkUrls = urls,
            )
        }
    } catch (t: Throwable) {
        null
    }

    fun isNewer(info: UpdateInfo, installedVersionCode: Int): Boolean =
        info.versionCode > installedVersionCode
}
