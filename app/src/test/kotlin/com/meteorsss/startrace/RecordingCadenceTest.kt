package com.meteorsss.startrace

import org.junit.Assert
import org.junit.Test

class RecordingCadenceTest {
    @Test
    fun supportedIntervalsAndInvalidFallback() {
        Assert.assertArrayEquals(intArrayOf(1, 2, 3, 5, 10, 15), RecordingCadence.Companion.SECONDS)
        for (value in RecordingCadence.Companion.SECONDS) Assert.assertEquals(
            value.toLong(),
            RecordingCadence.Companion.sanitize(value).toLong()
        )
        Assert.assertEquals(1, RecordingCadence.Companion.sanitize(0).toLong())
        Assert.assertEquals(1, RecordingCadence.Companion.sanitize(30).toLong())
    }

    @Test
    fun everyIntervalThrottlesWithoutInventingPoints() {
        for (value in RecordingCadence.Companion.SECONDS) {
            val cadence = RecordingCadence()
            Assert.assertTrue(cadence.shouldSave(1000, value))
            Assert.assertFalse(cadence.shouldSave(1000 + value * 1000L - 1, value))
            Assert.assertTrue(cadence.shouldSave(1000 + value * 1000L, value))
            Assert.assertFalse(cadence.shouldSave(1000 + value * 1000L, value))
        }
    }

    @Test
    fun missingFixesSkipSlotsNotDuplicateLastLocation() {
        val cadence = RecordingCadence()
        Assert.assertTrue(cadence.shouldSave(0, 3))
        Assert.assertTrue(cadence.shouldSave(10000, 3))
        Assert.assertFalse(cadence.shouldSave(12000, 3))
        Assert.assertTrue(cadence.shouldSave(14000, 3))
    }

    @Test
    fun resetForResumeOrChangeAllowsFirstNewFix() {
        val cadence = RecordingCadence()
        Assert.assertTrue(cadence.shouldSave(1000, 10))
        Assert.assertFalse(cadence.shouldSave(2000, 10))
        cadence.reset()
        Assert.assertTrue(cadence.shouldSave(2000, 1))
    }

    @Test
    fun timestampsMustBeMonotonicAndNonnegative() {
        val cadence = RecordingCadence()
        Assert.assertFalse(cadence.shouldSave(-1, 1))
        Assert.assertTrue(cadence.shouldSave(5000, 1))
        Assert.assertFalse(cadence.shouldSave(4000, 1))
        Assert.assertTrue(cadence.shouldSave(6000, 1))
    }
}
