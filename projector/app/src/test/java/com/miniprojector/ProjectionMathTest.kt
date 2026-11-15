package com.miniprojector

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionMathTest {

    @Test
    fun defaultLensSettingFlipsOnlyVertically() {
        val t = ProjectionMath.stageTransform(flipHorizontal = false, flipVertical = true, zoom = 1f, panX = 0f, panY = 0f)
        assertEquals(1f, t.scaleX, 0f)
        assertEquals(-1f, t.scaleY, 0f)
    }

    @Test
    fun bothFlipsTurnThePictureRound() {
        val t = ProjectionMath.stageTransform(true, true, 2f, 10f, -5f)
        assertEquals(-2f, t.scaleX, 0f)
        assertEquals(-2f, t.scaleY, 0f)
        assertEquals(10f, t.translationX, 0f)
        assertEquals(-5f, t.translationY, 0f)
    }

    @Test
    fun zoomIsClamped() {
        assertEquals(ProjectionMath.MAX_ZOOM, ProjectionMath.stageTransform(false, false, 50f, 0f, 0f).scaleX, 0f)
        assertEquals(-ProjectionMath.MIN_ZOOM, ProjectionMath.stageTransform(true, false, 0.01f, 0f, 0f).scaleX, 0f)
    }

    @Test
    fun keystoneIsClamped() {
        assertEquals(30f, ProjectionMath.clampKeystone(45f), 0f)
        assertEquals(-30f, ProjectionMath.clampKeystone(-90f), 0f)
        assertEquals(12f, ProjectionMath.clampKeystone(12f), 0f)
    }

    @Test
    fun wideVideoOnWideScreenFillsTheWidth() {
        // 16:9 video on a 2400 x 1080 screen: full height, bars left and right.
        val (sx, sy) = ProjectionMath.videoFitScale(1920, 1080, 2400, 1080)
        assertEquals(1920f / 2400f, sx, 1e-4f)
        assertEquals(1f, sy, 1e-4f)
    }

    @Test
    fun portraitVideoOnLandscapeScreenKeepsItsShape() {
        val (sx, sy) = ProjectionMath.videoFitScale(1080, 1920, 2400, 1080)
        // Displayed size = 607.5 x 1080 pixels.
        assertEquals(607.5f / 2400f, sx, 1e-4f)
        assertEquals(1f, sy, 1e-4f)
    }

    @Test
    fun unknownVideoSizeLeavesTheViewAlone() {
        assertEquals(1f to 1f, ProjectionMath.videoFitScale(0, 0, 2400, 1080))
    }

    @Test
    fun bigPhotoIsDownsampled() {
        // 8000 x 6000 (48 MP) for a 2400 x 1080 screen: 4000 x 3000 still covers it, 2000 x 1500 would not.
        assertEquals(2, ProjectionMath.sampleSize(8000, 6000, 2400, 1080))
        // Portrait photo, landscape screen: compared long side to long side.
        assertEquals(2, ProjectionMath.sampleSize(6000, 8000, 2400, 1080))
        assertEquals(4, ProjectionMath.sampleSize(16000, 12000, 2400, 1080))
    }

    @Test
    fun smallPhotoIsNotDownsampled() {
        assertEquals(1, ProjectionMath.sampleSize(1600, 1200, 2400, 1080))
        assertEquals(1, ProjectionMath.sampleSize(0, 0, 2400, 1080))
    }
}
