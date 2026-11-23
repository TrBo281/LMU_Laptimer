package com.lmulaptimer

import com.lmulaptimer.data.TrainingStreak
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TrainingStreakTest {
    private val today = LocalDate.of(2026, 11, 23)

    @Test fun countsConsecutiveDaysEndingToday() {
        val days = listOf(today, today.minusDays(1), today.minusDays(2), today.minusDays(4))
        assertEquals(3, TrainingStreak.compute(days, today))
    }

    @Test fun yesterdayStillCountsWhenTodayIsEmpty() {
        assertEquals(2, TrainingStreak.compute(listOf(today.minusDays(1), today.minusDays(2)), today))
    }

    @Test fun duplicateSessionsOnOneDayCountOnce() {
        assertEquals(2, TrainingStreak.compute(listOf(today, today, today.minusDays(1)), today))
    }

    @Test fun oldTrainingDoesNotCount() {
        assertEquals(0, TrainingStreak.compute(listOf(today.minusDays(5)), today))
    }
}
