package com.meteorsss.startrace

/**
 * Monotonic location timestamps control saved points, not wall-clock timers or fabricated fixes.
 */
class RecordingCadence {
    private var lastSaved: Long = -1

    fun shouldSave(elapsedMs: Long, seconds: Int): Boolean {
        if (elapsedMs < 0 || (lastSaved >= 0 && elapsedMs <= lastSaved)) return false
        if (lastSaved >= 0 && elapsedMs - lastSaved < sanitize(seconds) * 1000L) return false
        lastSaved = elapsedMs
        return true
    }

    fun reset() {
        lastSaved = -1
    }

    companion object {
        val SECONDS: IntArray = intArrayOf(1, 2, 3, 5, 10, 15)
        fun sanitize(seconds: Int): Int {
            for (value in SECONDS) if (value == seconds) return seconds
            return 1
        }
    }
}
