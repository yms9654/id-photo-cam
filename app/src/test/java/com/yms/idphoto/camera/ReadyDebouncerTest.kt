package com.yms.idphoto.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyDebouncerTest {

    @Test
    fun `몇 프레임 연속으로 좋아야 준비 상태가 켜진다`() {
        val debouncer = ReadyDebouncer(enterFrames = 3, exitFrames = 6)
        assertFalse(debouncer.update(true))
        assertFalse(debouncer.update(true))
        assertTrue(debouncer.update(true))
    }

    @Test
    fun `한두 프레임 흔들려도 카운트다운이 꺼지지 않는다`() {
        val debouncer = ReadyDebouncer(enterFrames = 3, exitFrames = 6)
        repeat(3) { debouncer.update(true) }
        assertTrue(debouncer.update(false))
        assertTrue(debouncer.update(false))
        repeat(6) { debouncer.update(false) }
        assertFalse(debouncer.ready)
    }
}
