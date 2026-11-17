package com.screenrecorder.pro

import com.screenrecorder.pro.config.InternalRecordingConfig
import com.screenrecorder.pro.model.NormalizedRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingConfigTest {

    @Test
    fun fixedInternalConfigurationMatchesSpecification() {
        assertEquals(60, InternalRecordingConfig.FIXED_FRAME_RATE)
        assertEquals("video/avc", InternalRecordingConfig.FIXED_VIDEO_MIME_TYPE)
        assertEquals("c2.qti.avc.encoder", InternalRecordingConfig.PREFERRED_QUALCOMM_ENCODER)
        assertEquals("c2.mtk.avc.encoder", InternalRecordingConfig.PREFERRED_MEDIATEK_ENCODER)
    }

    @Test
    fun normalizedRegionClampingPreventsDegenerateBounds() {
        val raw = NormalizedRegion(left = -0.5f, top = 0.95f, right = 0.01f, bottom = 1.4f)
        val clamped = raw.clamped()
        assertTrue(clamped.left >= 0f)
        assertTrue(clamped.right <= 1f)
        assertTrue(clamped.right - clamped.left >= 0.11f)
        assertTrue(clamped.bottom - clamped.top >= 0.11f)
    }

    @Test
    fun fourQuarterRotationsRestoreOriginalNormalizedRegion() {
        val original = NormalizedRegion(0.15f, 0.2f, 0.75f, 0.65f)
        val fullTurn = original.rotatedBySteps(4)
        assertEquals(original.left, fullTurn.left, 0.001f)
        assertEquals(original.top, fullTurn.top, 0.001f)
        assertEquals(original.right, fullTurn.right, 0.001f)
        assertEquals(original.bottom, fullTurn.bottom, 0.001f)
    }
}
