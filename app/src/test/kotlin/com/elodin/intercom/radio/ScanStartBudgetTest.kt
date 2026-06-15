package com.elodin.intercom.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanStartBudgetTest {
    @Test
    fun delaysWhenStartBudgetIsExhausted() {
        var nowMs = 0L
        val budget =
            ScanStartBudget(
                windowMs = 30_000L,
                maxStarts = 4,
                nowMs = { nowMs },
            )

        repeat(4) {
            assertEquals(0L, budget.delayUntilAvailableMs())
            budget.recordStart()
            nowMs += 100L
        }

        assertEquals(29_600L, budget.delayUntilAvailableMs())
    }

    @Test
    fun freesBudgetWhenOldestStartLeavesWindow() {
        var nowMs = 0L
        val budget =
            ScanStartBudget(
                windowMs = 30_000L,
                maxStarts = 4,
                nowMs = { nowMs },
            )
        repeat(4) {
            budget.recordStart()
            nowMs += 100L
        }

        nowMs = 30_000L

        assertEquals(0L, budget.delayUntilAvailableMs())
    }

    @Test
    fun cooldownStatusShowsTenthsOfASecond() {
        assertEquals(
            "Rapid role changes — Bluetooth scan cooldown 3.2s",
            scanCooldownStatus(3_200L),
        )
        assertEquals(
            "Rapid role changes — Bluetooth scan cooldown 0.1s",
            scanCooldownStatus(1L),
        )
    }
}
