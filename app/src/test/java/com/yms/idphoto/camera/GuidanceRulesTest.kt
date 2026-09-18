package com.yms.idphoto.camera

import com.yms.idphoto.spec.PhotoSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceRulesTest {

    private val spec = PhotoSpec.PASSPORT

    /** 가이드 프레임 한가운데에 규격대로 들어온 상태. */
    private fun good() = HeadMetrics(
        headTop = spec.topMarginTargetRatio,
        chin = spec.topMarginTargetRatio + spec.headTargetRatio,
        centerX = 0.5f,
        eulerX = 1f,
        eulerY = -2f,
        eulerZ = 0.5f,
        eyesOpen = 0.95f,
        luma = 140f,
    )

    @Test
    fun `조건이 모두 맞으면 촬영 준비 상태가 된다`() {
        val guidance = GuidanceRules.evaluate(spec, 1, good())
        assertTrue(guidance.ready)
        assertEquals("좋아요! 그대로 계세요", guidance.message)
        assertTrue(guidance.checks.all { it.ok })
    }

    @Test
    fun `얼굴이 없거나 여러 명이면 촬영하지 않는다`() {
        assertFalse(GuidanceRules.evaluate(spec, 0, null).ready)
        assertEquals("얼굴이 보이지 않아요", GuidanceRules.evaluate(spec, 0, null).message)
        assertEquals("화면에 한 사람만 나와야 해요", GuidanceRules.evaluate(spec, 2, good()).message)
    }

    @Test
    fun `머리가 작으면 가까이, 크면 뒤로 안내한다`() {
        val small = good().let { it.copy(chin = it.headTop + 0.45f) }
        assertEquals("조금 더 가까이 오세요", GuidanceRules.evaluate(spec, 1, small).message)

        val big = good().let { it.copy(chin = it.headTop + 0.95f) }
        assertEquals("조금 더 뒤로 가세요", GuidanceRules.evaluate(spec, 1, big).message)
    }

    @Test
    fun `좌우로 치우치면 반대쪽으로 안내한다`() {
        assertEquals("오른쪽으로 조금 이동하세요", GuidanceRules.evaluate(spec, 1, good().copy(centerX = 0.3f)).message)
        assertEquals("왼쪽으로 조금 이동하세요", GuidanceRules.evaluate(spec, 1, good().copy(centerX = 0.7f)).message)
    }

    @Test
    fun `자세와 눈, 밝기는 촬영 전에 막는다`() {
        assertEquals("고개를 수평으로 맞춰주세요", GuidanceRules.evaluate(spec, 1, good().copy(eulerZ = 12f)).message)
        assertEquals("정면을 바라보세요", GuidanceRules.evaluate(spec, 1, good().copy(eulerY = 20f)).message)
        assertEquals("눈을 크게 떠주세요", GuidanceRules.evaluate(spec, 1, good().copy(eyesOpen = 0.2f)).message)
        assertEquals("더 밝은 곳에서 찍어주세요", GuidanceRules.evaluate(spec, 1, good().copy(luma = 40f)).message)
    }

    @Test
    fun `밝기나 눈 정보를 못 받으면 그 항목은 통과로 본다`() {
        val guidance = GuidanceRules.evaluate(spec, 1, good().copy(eyesOpen = null, luma = null))
        assertTrue(guidance.ready)
    }
}
