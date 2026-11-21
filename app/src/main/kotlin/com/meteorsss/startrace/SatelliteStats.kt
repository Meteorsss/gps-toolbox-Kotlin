package com.meteorsss.startrace

/** A single snapshot: count satellites separately from Android status entries.  */
object SatelliteStats {
    fun of(signals: MutableList<out Signal>): Snapshot {
        val result = Snapshot()
        for (signal in signals) {
            result.total.add(signal)
            result.systems!!.computeIfAbsent(signal.system) { s: Int? -> Counts() }.add(signal)
        }
        return result
    }

    open class Signal {
        var system: Int = 0
        var id: Int = 0
        var azimuth: Float = 0f
        var elevation: Float = 0f
        var cn0: Float = 0f
        var frequency: Float = 0f
        var used: Boolean = false
        var hasFrequency: Boolean = false

        fun key(): String {
            return system.toString() + ":" + id
        }
    }

    class Counts {
        private val reportedIds: MutableSet<String?>? = HashSet<String?>()
        private val usedIds: MutableSet<String?> = HashSet<String?>()
        private val trackedIds: MutableSet<String?> = HashSet<String?>()
        private var entries = 0
        private var usedEntries = 0
        private var trackedEntries = 0

        private fun add(s: Signal) {
            entries++
            reportedIds!!.add(s.key())
            if (s.used) {
                usedIds.add(s.key())
                usedEntries++
            }
            if (java.lang.Float.isFinite(s.cn0) && s.cn0 > 0) {
                trackedIds.add(s.key())
                trackedEntries++
            }
        }

        fun received(): Int {
            return reportedIds!!.size
        }

        fun used(): Int {
            return usedIds.size
        }

        fun tracked(): Int {
            return trackedIds.size
        }

        fun entries(): Int {
            return entries
        }

        /** Number of current callback entries with positive carrier-to-noise density.  */
        fun trackedEntries(): Int {
            return trackedEntries
        }

        fun usedEntries(): Int {
            return usedEntries
        }
    }

    class Snapshot {
        val total: Counts = Counts()
        private val systems: MutableMap<Int?, Counts?>? = HashMap<Int?, Counts?>()

        fun system(type: Int): Counts {
            return systems!!.getOrDefault(type, Counts())!!
        }
    }
}
