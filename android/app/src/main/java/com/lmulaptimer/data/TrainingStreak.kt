package com.lmulaptimer.data

import java.time.LocalDate

/** Consecutive training days. A missing today does not break the streak yet. */
object TrainingStreak {
    fun compute(days: Collection<LocalDate>, today: LocalDate = LocalDate.now()): Int {
        val trained = days.toSet()
        var cursor = if (today in trained) today else today.minusDays(1)
        var count = 0
        while (cursor in trained) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    fun lastTraining(days: Collection<LocalDate>): LocalDate? = days.maxOrNull()
}
