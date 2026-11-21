package com.meteorsss.startrace

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Paths
import java.util.Random

/** Execute identical inputs against preserved Java bytecode and migrated Kotlin bytecode. */
class MigrationParityTest {
    private class World(private val loader: ClassLoader) {
        fun type(name: String): Class<*> = Class.forName("com.meteorsss.startrace.$name", true, loader)
        fun create(name: String, vararg args: Any): Any =
            type(name).declaredConstructors.single { it.parameterCount == args.size }.apply { isAccessible = true }.newInstance(*args)
        fun call(name: String, method: String, vararg args: Any?): Any? {
            val cls = type(name)
            cls.methods.firstOrNull { it.name == method && it.parameterCount == args.size && Modifier.isStatic(it.modifiers) }?.let {
                return it.invoke(null, *args)
            }
            val singleton = cls.fields.firstOrNull { it.name == "Companion" || it.name == "INSTANCE" }!!.get(null)
            return invoke(singleton, method, *args)
        }
        fun invoke(target: Any, name: String, vararg args: Any?): Any? {
            val names = listOf(name, "get" + name.replaceFirstChar { it.uppercase() }, "is" + name.replaceFirstChar { it.uppercase() })
            return target.javaClass.methods.single { it.name in names && it.parameterCount == args.size }.invoke(target, *args)
        }
        fun value(target: Any, name: String): Any? = target.javaClass.declaredFields.single { it.name == name }.apply { isAccessible = true }.get(target)
        fun point(lat: Double, lon: Double, height: Double, accuracy: Double, speed: Double, time: Long, elapsed: Long, segment: Int, accepted: Boolean, vertical: Double = 2.0, msl: Double = Double.NaN, mslError: Double = Double.NaN, speedError: Double = 0.5): Any =
            create("TrackPoint", lat, lon, height, accuracy, speed, time, elapsed, segment, accepted, vertical, msl, mslError, speedError)
    }
    private val baseline = World(URLClassLoader(arrayOf(Paths.get(System.getProperty("java.baseline.classes")).toUri().toURL()), ClassLoader.getSystemClassLoader().parent))
    private val migrated = World(javaClass.classLoader)

    private fun same(a: Any?, b: Any?) {
        if (a is Double && b is Double) assertEquals(java.lang.Double.doubleToLongBits(a), java.lang.Double.doubleToLongBits(b))
        else assertEquals(a, b)
    }
    private fun sameFields(a: Any, b: Any) {
        a.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.forEach { field ->
            same(baseline.value(a, field.name), migrated.value(b, field.name))
        }
    }

    @Test fun randomizedCoordinatesAndFormattingAreBitIdentical() {
        val random = Random(0x475053)
        repeat(10000) {
            val lat = random.nextDouble() * 180 - 90
            val lon = random.nextDouble() * 360 - 180
            val lat2 = random.nextDouble() * 180 - 90
            val lon2 = random.nextDouble() * 360 - 180
            for (method in listOf("distance", "bearing")) same(baseline.call("Geo", method, lat, lon, lat2, lon2), migrated.call("Geo", method, lat, lon, lat2, lon2))
            same(baseline.call("Geo", "dms", lat, true), migrated.call("Geo", "dms", lat, true))
            same(baseline.call("Geo", "dms", lon, false), migrated.call("Geo", "dms", lon, false))
            sameFields(baseline.call("Gcj02", "fromWgs84", lat, lon)!!, migrated.call("Gcj02", "fromWgs84", lat, lon)!!)
        }
        for (n in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.0, 0.0, -90.0, 90.0, 180.0)) {
            same(baseline.call("Geo", "normalize", n), migrated.call("Geo", "normalize", n))
            same(baseline.call("Geo", "valid", n, n), migrated.call("Geo", "valid", n, n))
        }
    }

    @Test fun recordingFiltersStatisticsAndSpeedSessionMatch() {
        val random = Random(20261121)
        val ja = baseline.create("TrackAnalytics"); val ka = migrated.create("TrackAnalytics")
        val js = baseline.create("SpeedSession", 1000L); val ks = migrated.create("SpeedSession", 1000L)
        var jp: Any? = null; var kp: Any? = null
        repeat(5000) { i ->
            val elapsed = 1000L + i * 1000L
            val lat = 30.0 + i * 0.00002
            val lon = 120.0 + i * 0.00001
            val height = 100.0 + kotlin.math.sin(i / 15.0) * 50
            val accuracy = if (i % 19 == 0) 90.0 else 3.0
            val speed = if (i % 11 == 0) Double.NaN else random.nextDouble() * 4
            val accepted = i % 23 != 0
            val p = baseline.point(lat, lon, height, accuracy, speed, 1700000000000L + elapsed, elapsed, i / 200, accepted)
            val q = migrated.point(lat, lon, height, accuracy, speed, 1700000000000L + elapsed, elapsed, i / 200, accepted)
            same(baseline.call("TrackFilter", "usable", p, elapsed), migrated.call("TrackFilter", "usable", q, elapsed))
            same(baseline.call("TrackFilter", "plausible", jp, p), migrated.call("TrackFilter", "plausible", kp, q))
            same(baseline.call("TrackFilter", "distanceContribution", jp, p), migrated.call("TrackFilter", "distanceContribution", kp, q))
            baseline.invoke(ja, "add", p); migrated.invoke(ka, "add", q)
            baseline.invoke(js, "add", p, i % 31 == 0, elapsed); migrated.invoke(ks, "add", q, i % 31 == 0, elapsed)
            if (i % 200 == 100) { baseline.invoke(js, "pause", elapsed); migrated.invoke(ks, "pause", elapsed) }
            if (i % 200 == 120) { baseline.invoke(js, "resume", elapsed); migrated.invoke(ks, "resume", elapsed) }
            sameFields(baseline.invoke(js, "snapshot", elapsed)!!, migrated.invoke(ks, "snapshot", elapsed)!!)
            sameFields(baseline.invoke(ja, "finish", i * 2.5, elapsed)!!, migrated.invoke(ka, "finish", i * 2.5, elapsed)!!)
            jp = p; kp = q
        }
    }

    @Test fun exportsAreByteIdenticalIncludingUnicodeAndTrackBreaks() {
        val title = "迁移 <&> \" ' 🚀"
        same(baseline.call("Gpx", "header", title), migrated.call("Gpx", "header", title))
        same(baseline.call("Gpx", "footer"), migrated.call("Gpx", "footer"))
        same(baseline.call("Kml", "escape", title + 1.toChar()), migrated.call("Kml", "escape", title + 1.toChar()))
        val left = StringWriter(); val right = StringWriter()
        val jw = baseline.create("Kml\$Exporter", left, title); val kw = migrated.create("Kml\$Exporter", right, title)
        repeat(1000) { i ->
            val p = baseline.point(30.0, 120.0 + i / 10000.0, 100.0, 3.0, 2.0, 1700000000000L + i * 1000, i * 1000L, i / 100, i % 13 != 0)
            val q = migrated.point(30.0, 120.0 + i / 10000.0, 100.0, 3.0, 2.0, 1700000000000L + i * 1000, i * 1000L, i / 100, i % 13 != 0)
            same(baseline.call("Gpx", "point", p), migrated.call("Gpx", "point", q))
            baseline.invoke(jw, "add", p); migrated.invoke(kw, "add", q)
        }
        baseline.invoke(jw, "finish"); migrated.invoke(kw, "finish")
        same(left.toString(), right.toString())
    }

    @Test fun compassAndRecordingCadenceMatch() {
        val ja = baseline.create("HeadingSmoother"); val ka = migrated.create("HeadingSmoother")
        val jc = baseline.create("RecordingCadence"); val kc = migrated.create("RecordingCadence")
        val random = Random(42)
        repeat(10000) { i ->
            val heading = if (i % 97 == 0) Double.NaN else random.nextDouble() * 2000 - 1000
            val nanos = i * 16666667L
            same(baseline.invoke(ja, "update", heading, nanos), migrated.invoke(ka, "update", heading, nanos))
            val seconds = listOf(-1, 1, 2, 3, 5, 10, 15, 30)[i % 8]
            same(baseline.invoke(jc, "shouldSave", nanos / 1000000, seconds), migrated.invoke(kc, "shouldSave", nanos / 1000000, seconds))
            if (i % 101 == 0) { baseline.invoke(ja, "reset"); migrated.invoke(ka, "reset"); baseline.invoke(jc, "reset"); migrated.invoke(kc, "reset") }
        }
    }
}
