package com.meteorsss.startrace

import java.util.*
import java.util.regex.Pattern
import kotlin.math.*

object Geo {
    fun valid(lat: Double, lon: Double): Boolean {
        return java.lang.Double.isFinite(lat)
                && java.lang.Double.isFinite(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
    }

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a =
            (sin(dp / 2) * sin(dp / 2)
                    + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2))
        return (6371008.8
                * 2
                * atan2(sqrt(max(0.0, min(1.0, a))), sqrt(max(0.0, 1 - a))))
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val a = Math.toRadians(lat1)
        val b = Math.toRadians(lat2)
        val d = Math.toRadians(lon2 - lon1)
        return normalize(
            Math.toDegrees(
                atan2(
                    sin(d) * cos(b),
                    cos(a) * sin(b) - sin(a) * cos(b) * cos(d)
                )
            )
        )
    }

    fun normalize(v: Double): Double {
        return ((v % 360) + 360) % 360
    }

    fun dms(value: Double, latitude: Boolean): String? {
        val cs = Math.round(abs(value) * 360000)
        val deg = cs / 360000
        val min = (cs % 360000) / 6000
        val sec = (cs % 6000) / 100.0
        val h = if (latitude) (if (value < 0) "S" else "N") else (if (value < 0) "W" else "E")
        return String.format(Locale.US, "%d° %02d′ %05.2f″ %s", deg, min, sec, h)
    }

    fun parse(text: String, latitude: Boolean): Double {
        val s: String? = text.trim { it <= ' ' }.uppercase()
        val neg = s!!.startsWith("-") || s.matches(".*[SW南西].*".toRegex())
        require(!(latitude && s.matches(".*[EW东西].*".toRegex()))) { "纬度应使用 N/S" }
        require(!(!latitude && s.matches(".*[NS南北].*".toRegex()))) { "经度应使用 E/W" }
        val cleaned: String? = s.replace("[NSEW北南东西]".toRegex(), "").trim { it <= ' ' }
        require(cleaned!!.matches("[+\\-0-9.°º′'″\"\\s:]+".toRegex())) { "坐标格式无效" }
        val m = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?").matcher(cleaned)
        val h = Pattern.compile("[NSEW北南东西]").matcher(s)
        var hc = 0
        while (h.find()) hc++
        require(hc <= 1) { "仅允许一个方向标记" }
        require(!(hc == 1 && !s.matches("^(?:[NSEW北南东西].*|.*[NSEW北南东西])$".toRegex()))) { "方向标记只能位于开头或末尾" }
        require(!(cleaned.startsWith("-") && s.matches(".*[NE北东].*".toRegex()))) { "负号与方向标记冲突" }
        val v = DoubleArray(3)
        var n = 0
        var lastEnd = 0
        while (m.find()) {
            val gap: String? = cleaned.substring(lastEnd, m.start())
            require(!(n == 3 || (n == 0 && !gap!!.trim { it <= ' ' }
                .isEmpty()) || (n > 0 && !gap!!.matches("[\\s°º′'″\":]+".toRegex())))) { "坐标格式无效" }
            v[n++] = m.group().toDouble()
            lastEnd = m.end()
        }
        require(cleaned.substring(lastEnd).matches("[\\s°º′'″\"]*".toRegex())) { "坐标格式无效" }
        require(!(n > 1 && abs(v[0]) != floor(abs(v[0])))) { "度分秒的度必须是整数" }
        require(n != 0) { "请输入坐标" }
        require(!(n > 1 && (v[1] < 0 || v[1] >= 60))) { "分应在 0 到 60 之间" }
        require(!(n > 2 && (v[2] < 0 || v[2] >= 60))) { "秒应在 0 到 60 之间" }
        val r = (abs(v[0]) + v[1] / 60 + v[2] / 3600) * (if (neg) -1 else 1)
        require(!(!java.lang.Double.isFinite(r) || abs(r) > (if (latitude) 90 else 180))) { "坐标超出范围" }
        return r
    }

    fun meters(v: Double): String? {
        return if (v >= 1000) String.format(Locale.US, "%.2f km", v / 1000) else String.format(Locale.US, "%.0f m", v)
    }

    fun duration(millis: Long): String? {
        val t = max(0, millis / 1000)
        return String.format(Locale.US, "%02d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
    }
}
