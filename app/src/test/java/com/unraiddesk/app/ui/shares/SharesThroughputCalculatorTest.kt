package com.unraiddesk.app.ui.shares

import com.unraiddesk.app.core.network.ArrayDisk
import com.unraiddesk.app.core.network.UnraidShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SharesThroughputCalculatorTest {

    @Test
    fun calculatesDeltaIoAcrossIncludedDisksExcludingExcludedDisks() {
        val share = UnraidShare(
            name = "media",
            include = listOf("disk1", "disk2"),
            exclude = listOf("disk2")
        )
        val prev = listOf(
            ArrayDisk(id = "d1", name = "disk1", numReads = 1000L, numWrites = 200L),
            ArrayDisk(id = "d2", name = "disk2", numReads = 5000L, numWrites = 500L)
        )
        val curr = listOf(
            ArrayDisk(id = "d1", name = "disk1", numReads = 1500L, numWrites = 300L),
            ArrayDisk(id = "d2", name = "disk2", numReads = 9000L, numWrites = 900L)
        )

        val tp = SharesThroughputCalculator.calculate(
            share = share,
            previousDisks = prev,
            currentDisks = curr,
            elapsedMillis = 5_000L
        )
        assertNotNull(tp)
        tp!!
        assertEquals(100.0, tp.readsPerSec, 0.01)
        assertEquals(20.0, tp.writesPerSec, 0.01)
    }

    @Test
    fun aggregatesArrayDisksWhenIncludeIsEmptyAndIgnoresCounterReset() {
        val share = UnraidShare(name = "appdata", include = emptyList(), exclude = emptyList())
        val prev = listOf(
            ArrayDisk(id = "d1", name = "disk1", numReads = 100L, numWrites = 50L, isCache = false),
            ArrayDisk(id = "d2", name = "disk2", numReads = 200L, numWrites = 100L, isCache = false),
            ArrayDisk(id = "c1", name = "cache", numReads = 999L, numWrites = 999L, isCache = true)
        )
        val curr = listOf(
            ArrayDisk(id = "d1", name = "disk1", numReads = 120L, numWrites = 70L, isCache = false),
            // Counter reset on disk2 should clamp to 0 instead of negative
            ArrayDisk(id = "d2", name = "disk2", numReads = 10L, numWrites = 10L, isCache = false),
            ArrayDisk(id = "c1", name = "cache", numReads = 2000L, numWrites = 2000L, isCache = true)
        )

        val tp = SharesThroughputCalculator.calculate(share, prev, curr, elapsedMillis = 2_000L)
        assertNotNull(tp)
        tp!!
        assertEquals("Array I/O (delta)", tp.scopeLabel)
        assertEquals(10.0, tp.readsPerSec, 0.01)
        assertEquals(10.0, tp.writesPerSec, 0.01)
    }

    @Test
    fun returnsNullWhenElapsedIsZeroOrNoBaseline() {
        val share = UnraidShare(name = "isos")
        assertNull(SharesThroughputCalculator.calculate(share, emptyList(), emptyList(), 1000L))
        assertNull(
            SharesThroughputCalculator.calculate(
                share,
                listOf(ArrayDisk(id = "d1", name = "disk1")),
                listOf(ArrayDisk(id = "d1", name = "disk1")),
                0L
            )
        )
    }
}
