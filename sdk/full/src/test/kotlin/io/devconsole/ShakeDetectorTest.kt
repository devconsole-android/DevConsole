/**
 * @author Shakib
 * @since 07/08/26
 */
package io.devconsole

import io.devconsole.api.ShakeIntensity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {
    private var nowMs = 0L
    private val detector = ShakeDetector { nowMs }

    private fun sample(
        magnitudeG: Float,
        atMs: Long,
    ): Boolean {
        nowMs = atMs
        return detector.onSample(magnitudeG)
    }

    @Test
    fun `below-threshold samples never fire`() {
        assertFalse(sample(2.4f, atMs = 1_000))
        assertFalse(sample(2.4f, atMs = 1_100))
        assertFalse(sample(2.4f, atMs = 1_200))
    }

    @Test
    fun `a single over-threshold spike does not fire`() {
        assertFalse(sample(3.0f, atMs = 1_000))
    }

    @Test
    fun `two peaks within the window fire exactly one shake`() {
        assertFalse(sample(3.0f, atMs = 1_000))
        assertTrue(sample(3.0f, atMs = 1_300))
    }

    @Test
    fun `a second peak exactly at the window edge still counts`() {
        assertFalse(sample(3.0f, atMs = 1_000))
        assertTrue(sample(3.0f, atMs = 1_000 + ShakeDetector.PEAK_WINDOW_MS))
    }

    @Test
    fun `peaks further apart than the window restart the count`() {
        assertFalse(sample(3.0f, atMs = 1_000))
        assertFalse(sample(3.0f, atMs = 1_401))
        // The 1401 peak became the new first peak; a partner inside its window completes the shake.
        assertTrue(sample(3.0f, atMs = 1_700))
    }

    @Test
    fun `cooldown swallows shakes until it lapses`() {
        assertFalse(sample(3.0f, atMs = 1_000))
        assertTrue(sample(3.0f, atMs = 1_200))
        // 1500ms cooldown runs until 2700; a full two-peak shake inside it must not fire.
        assertFalse(sample(3.0f, atMs = 1_400))
        assertFalse(sample(3.0f, atMs = 1_600))
        assertFalse(sample(3.0f, atMs = 2_699))
        assertFalse(sample(3.0f, atMs = 2_800))
        assertTrue(sample(3.0f, atMs = 2_900))
    }

    @Test
    fun `reset drops a half-counted shake`() {
        assertFalse(sample(3.0f, atMs = 1_000))
        detector.reset()
        assertFalse(sample(3.0f, atMs = 1_200))
        assertTrue(sample(3.0f, atMs = 1_400))
    }

    @Test
    fun `light intensity fires on a gentle wobble`() {
        detector.updateIntensity(ShakeIntensity.LIGHT)
        assertFalse(sample(2.0f, atMs = 1_000))
        assertTrue(sample(2.0f, atMs = 1_200))
    }

    @Test
    fun `medium intensity ignores what light would accept`() {
        detector.updateIntensity(ShakeIntensity.MEDIUM)
        assertFalse(sample(2.0f, atMs = 1_000))
        assertFalse(sample(2.0f, atMs = 1_200))
        assertFalse(sample(2.6f, atMs = 2_000))
        assertTrue(sample(2.6f, atMs = 2_200))
    }

    @Test
    fun `firm intensity demands a deliberate hard shake`() {
        detector.updateIntensity(ShakeIntensity.FIRM)
        assertFalse(sample(3.0f, atMs = 1_000))
        assertFalse(sample(3.0f, atMs = 1_200))
        assertFalse(sample(3.3f, atMs = 2_000))
        assertTrue(sample(3.3f, atMs = 2_200))
    }
}
