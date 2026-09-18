package com.yms.idphoto.photo

import com.yms.idphoto.spec.PhotoSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropPlannerTest {

    private val spec = PhotoSpec.PASSPORT

    @Test
    fun `크롭은 머리 길이와 상단 여백을 규격 비율로 맞춘다`() {
        val plan = CropPlanner.plan(
            spec = spec,
            headTop = Pt(500f, 200f),
            chin = Pt(500f, 600f),
            center = Pt(500f, 350f),
            pivot = Pt(500f, 400f),
            levelDeg = 0f,
        )

        assertEquals(spec.headTargetRatio, plan.headRatio, 1e-4f)
        assertEquals(spec.topMarginTargetRatio, plan.topMarginRatio, 1e-4f)
        assertEquals(spec.aspect, plan.cropWidth / plan.cropHeight, 1e-4f)
    }

    @Test
    fun `머리 길이가 규격 범위 안에 들어온다`() {
        val plan = CropPlanner.plan(spec, Pt(0f, 0f), Pt(0f, 340f), Pt(0f, 100f), Pt(0f, 170f), 0f)
        val headMm = plan.headRatio * spec.heightMm
        assertTrue("머리 ${headMm}mm", headMm in spec.headMinMm..spec.headMaxMm)

        val topMm = plan.topMarginRatio * spec.heightMm
        assertTrue("여백 ${topMm}mm", topMm in spec.topMarginMinMm..spec.topMarginMaxMm)
    }

    @Test
    fun `두 눈 중점이 가로 중앙에 온다`() {
        val plan = CropPlanner.plan(spec, Pt(400f, 100f), Pt(400f, 500f), Pt(420f, 300f), Pt(400f, 300f), 0f)
        val centerOfCrop = plan.cropLeft + plan.cropWidth / 2f
        assertEquals(420f, centerOfCrop, 1e-3f)
    }

    @Test
    fun `기울어져 찍혀도 수평 보정 후 같은 결과가 나온다`() {
        val pivot = Pt(500f, 400f)
        val headTop = Pt(500f, 200f)
        val chin = Pt(500f, 600f)
        val center = Pt(500f, 350f)
        val straight = CropPlanner.plan(spec, headTop, chin, center, pivot, 0f)

        val tilt = 6f
        val tilted = CropPlanner.plan(
            spec = spec,
            headTop = CropPlanner.rotate(headTop, pivot, tilt),
            chin = CropPlanner.rotate(chin, pivot, tilt),
            center = CropPlanner.rotate(center, pivot, tilt),
            pivot = pivot,
            levelDeg = tilt,
        )

        assertEquals(straight.cropWidth, tilted.cropWidth, 1e-2f)
        assertEquals(straight.cropHeight, tilted.cropHeight, 1e-2f)
        assertEquals(straight.cropTop, tilted.cropTop, 1e-2f)
        assertEquals(straight.cropLeft, tilted.cropLeft, 1e-2f)
    }

    @Test
    fun `눈 선 기울기를 각도로 바꾼다`() {
        assertEquals(0f, CropPlanner.eyeAngleDegrees(Pt(100f, 100f), Pt(200f, 100f), 99f), 1e-3f)
        assertEquals(5.71f, CropPlanner.eyeAngleDegrees(Pt(100f, 100f), Pt(200f, 110f), 99f), 0.01f)
        // 좌우 눈이 뒤바뀌어 들어와도 같은 기울기로 읽는다.
        assertEquals(5.71f, CropPlanner.eyeAngleDegrees(Pt(200f, 110f), Pt(100f, 100f), 99f), 0.01f)
    }

    @Test
    fun `눈을 못 찾으면 대체 각도를 쓴다`() {
        assertEquals(3f, CropPlanner.eyeAngleDegrees(null, Pt(200f, 110f), 3f), 1e-3f)
    }

    @Test
    fun `기울기가 지나치게 크면 검출 오류로 보고 보정하지 않는다`() {
        assertEquals(8f, CropPlanner.levelAngle(8f), 1e-3f)
        assertEquals(0f, CropPlanner.levelAngle(24f), 1e-3f)
    }

    @Test
    fun `증명사진 규격도 같은 규칙을 만족한다`() {
        val idSpec = PhotoSpec.ID_PHOTO
        val plan = CropPlanner.plan(idSpec, Pt(0f, 0f), Pt(0f, 300f), Pt(0f, 120f), Pt(0f, 150f), 0f)
        assertEquals(idSpec.aspect, plan.cropWidth / plan.cropHeight, 1e-4f)
        val headMm = plan.headRatio * idSpec.heightMm
        assertTrue(headMm in idSpec.headMinMm..idSpec.headMaxMm)
    }
}
