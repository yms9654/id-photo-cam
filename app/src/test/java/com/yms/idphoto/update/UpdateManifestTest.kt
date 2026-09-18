package com.yms.idphoto.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {

    private val valid = """
        {
          "versionCode": 5,
          "versionName": "1.4",
          "notes": "머리카락 경계 개선",
          "apk": {
            "arm64-v8a": "https://example.com/arm64.apk",
            "universal": "https://example.com/all.apk"
          }
        }
    """.trimIndent()

    @Test
    fun `버전 정보를 읽는다`() {
        val info = UpdateManifest.parse(valid)!!
        assertEquals(5, info.versionCode)
        assertEquals("1.4", info.versionName)
        assertEquals("머리카락 경계 개선", info.notes)
        assertEquals(2, info.apkUrls.size)
    }

    @Test
    fun `기기 ABI 에 맞는 파일을 고른다`() {
        val info = UpdateManifest.parse(valid)!!
        assertEquals("https://example.com/arm64.apk", info.urlFor(listOf("arm64-v8a", "armeabi-v7a")))
        // 맞는 ABI 가 없으면 전체 ABI 파일로 떨어진다.
        assertEquals("https://example.com/all.apk", info.urlFor(listOf("x86_64")))
    }

    @Test
    fun `맞는 파일이 아예 없으면 null`() {
        val info = UpdateManifest.parse(
            """{"versionCode":5,"apk":{"arm64-v8a":"https://example.com/a.apk"}}"""
        )!!
        assertNull(info.urlFor(listOf("x86_64")))
    }

    @Test
    fun `설치된 버전보다 높을 때만 새 버전으로 본다`() {
        val info = UpdateManifest.parse(valid)!!
        assertTrue(UpdateManifest.isNewer(info, 4))
        assertFalse(UpdateManifest.isNewer(info, 5))
        assertFalse(UpdateManifest.isNewer(info, 6))
    }

    @Test
    fun `형식이 깨져 있으면 조용히 포기한다`() {
        assertNull(UpdateManifest.parse(""))
        assertNull(UpdateManifest.parse("not json"))
        assertNull(UpdateManifest.parse("""{"versionCode":3}"""))
        assertNull(UpdateManifest.parse("""{"versionCode":0,"apk":{"universal":"https://x/a.apk"}}"""))
        assertNull(UpdateManifest.parse("""{"versionCode":3,"apk":{}}"""))
    }

    @Test
    fun `버전 이름이 없으면 번호로 대신한다`() {
        val info = UpdateManifest.parse("""{"versionCode":7,"apk":{"universal":"https://x/a.apk"}}""")!!
        assertEquals("7", info.versionName)
        assertEquals("", info.notes)
    }
}
