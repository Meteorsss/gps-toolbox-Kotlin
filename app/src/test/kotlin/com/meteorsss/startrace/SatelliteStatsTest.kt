package com.meteorsss.startrace

import com.meteorsss.startrace.SatelliteStats.Signal
import org.junit.Assert
import org.junit.Test
import java.util.*

class SatelliteStatsTest {
    private fun signal(system: Int, id: Int, used: Boolean, cn0: Float): Signal {
        val s = Signal()
        s.system = system
        s.id = id
        s.used = used
        s.cn0 = cn0
        return s
    }

    @Test
    fun multiFrequencyCountsOneSatelliteAndOrsUsed() {
        val s =
            SatelliteStats.of(Arrays.asList<Signal?>(signal(5, 10, false, 30f), signal(5, 10, true, 20f)))
        Assert.assertEquals(1, s.total.received().toLong())
        Assert.assertEquals(1, s.total.used().toLong())
        Assert.assertEquals(2, s.total.entries().toLong())
        Assert.assertEquals(1, s.total.usedEntries().toLong())
    }

    @Test
    fun sameNumberInDifferentSystemsIsNotMerged() {
        val s =
            SatelliteStats.of(Arrays.asList<Signal?>(signal(5, 1, true, 30f), signal(1, 1, false, 25f)))
        Assert.assertEquals(2, s.total.received().toLong())
        Assert.assertEquals(1, s.system(5).received().toLong())
        Assert.assertEquals(1, s.system(5).used().toLong())
        Assert.assertEquals(0, s.system(1).used().toLong())
    }

    @Test
    fun includesZeroCn0InReportedButNotTracked() {
        val s =
            SatelliteStats.of(Arrays.asList<Signal?>(signal(1, 1, false, 0f), signal(1, 2, true, 30f)))
        Assert.assertEquals(2, s.total.received().toLong())
        Assert.assertEquals(1, s.total.tracked().toLong())
    }

    @Test
    fun moreThanTwentyNeverTruncated() {
        val all: MutableList<Signal?>? = ArrayList<Signal?>()
        for (i in 1..50) all!!.add(signal(if (i % 2 == 0) 5 else 1, i, i < 30, 25f))
        val s = SatelliteStats.of(all)
        Assert.assertEquals(50, s.total.received().toLong())
        Assert.assertEquals(29, s.total.used().toLong())
        Assert.assertEquals(25, s.system(5).received().toLong())
        Assert.assertEquals(25, s.system(1).received().toLong())
    }

    @Test
    fun snapshotDoesNotAccumulateOldSatellites() {
        Assert.assertEquals(1, SatelliteStats.of(Arrays.asList<Signal?>(signal(1, 1, true, 30f))).total.used().toLong())
        Assert.assertEquals(0, SatelliteStats.of(mutableListOf<Signal?>()).total.used().toLong())
    }

    @Test
    fun emptyAndUnknownAreCounted() {
        val s = SatelliteStats.of(Arrays.asList<Signal?>(signal(0, 10, false, Float.NaN)))
        Assert.assertEquals(1, s.system(0).received().toLong())
        Assert.assertEquals(0, s.total.tracked().toLong())
        Assert.assertEquals(0, s.system(7).received().toLong())
        Assert.assertEquals(0, s.total.used().toLong())
    }

    @Test
    fun positiveEntriesAreDifferentFromPositiveSatellites() {
        val s =
            SatelliteStats.of(
                Arrays.asList<Signal?>(
                    signal(4, 194, true, 35f),
                    signal(4, 194, true, 25f),
                    signal(4, 195, true, 40f),
                    signal(4, 195, false, 0f)
                )
            )
        Assert.assertEquals(4, s.total.entries().toLong())
        Assert.assertEquals(3, s.total.usedEntries().toLong())
        Assert.assertEquals(2, s.total.received().toLong())
        Assert.assertEquals(2, s.total.used().toLong())
        Assert.assertEquals(3, s.total.trackedEntries().toLong())
        Assert.assertEquals(2, s.total.tracked().toLong())
    }

    @Test
    fun constellationEntrySumsMatchTotal() {
        val s =
            SatelliteStats.of(
                Arrays.asList<Signal?>(
                    signal(5, 6, true, 30f),
                    signal(5, 6, true, 38f),
                    signal(5, 6, false, 0f),
                    signal(4, 194, true, 35f),
                    signal(4, 194, true, 25f),
                    signal(4, 195, true, 40f),
                    signal(4, 195, false, 0f)
                )
            )
        Assert.assertEquals(7, s.total.entries().toLong())
        Assert.assertEquals(5, s.total.usedEntries().toLong())
        Assert.assertEquals(3, s.total.received().toLong())
        Assert.assertEquals(3, s.total.used().toLong())
        Assert.assertEquals(s.total.entries().toLong(), (s.system(4).entries() + s.system(5).entries()).toLong())
        Assert.assertEquals(
            s.total.usedEntries().toLong(),
            (s.system(4).usedEntries() + s.system(5).usedEntries()).toLong()
        )
        Assert.assertEquals(s.total.received().toLong(), (s.system(4).received() + s.system(5).received()).toLong())
    }

    @Test
    fun zeroSignalUsedFlagIsPreservedRatherThanGuessed() {
        val s = SatelliteStats.of(Arrays.asList<Signal?>(signal(1, 9, true, 0f)))
        Assert.assertEquals(1, s.total.usedEntries().toLong())
        Assert.assertEquals(1, s.total.used().toLong())
        Assert.assertEquals(0, s.total.trackedEntries().toLong())
    }

    @Test
    fun missingFrequencyDoesNotDropEntries() {
        val a = signal(1, 10, true, 20f)
        val b = signal(1, 10, false, 15f)
        a.hasFrequency = false
        b.hasFrequency = false
        val s = SatelliteStats.of(Arrays.asList<Signal?>(a, b))
        Assert.assertEquals(2, s.total.entries().toLong())
        Assert.assertEquals(1, s.total.received().toLong())
    }
}
