package com.meteorsss.startrace

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.*
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import com.meteorsss.startrace.SatelliteStats.Signal
import java.util.*
import kotlin.math.max

class GnssEngine(private val app: App) : LocationListener, SensorEventListener {
    class Satellite : Signal() {
        fun label(): String {
            return systemName(system) + " " + id
        }
    }

    fun interface Observer {
        fun changed()
    }

    fun interface FixListener {
        fun fix(location: Location?)
    }

    private val manager: LocationManager
    private val sensors: SensorManager
    private val observers: MutableSet<Observer> = HashSet<Observer>()
    private var visible = false
    private var recording = false
    private var registered = false
    private var sensorRegistered = false
    private var compassActive = false
    private val rotationMatrix = FloatArray(9)
    private val adjustedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val windowManager: WindowManager?
    private var declination = 0f
    var orientationAt: Long = 0
    var statusRegistered: Boolean = false
    private var satelliteStats: SatelliteStats.Snapshot = SatelliteStats.of(mutableListOf<Signal>())
    var location: Location? = null
    var satellites: MutableList<Satellite> = ArrayList<Satellite>()
    var started: Long = 0
    var firstFix: Long = -1
    var satelliteAt: Long = 0
    var problem: String = ""
    var magneticHeading: Float = Float.NaN
    var sensorAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
    val hasCompass: Boolean
    var recorder: FixListener? = null

    fun precise(): Boolean {
        return (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED)
    }

    fun gpsEnabled(): Boolean {
        try {
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            return false
        }
    }

    fun enabled(): Boolean {
        return manager.isLocationEnabled()
    }

    fun fresh(): Boolean {
        return precise()
                && gpsEnabled()
                && location != null && SystemClock.elapsedRealtime() - location!!.getElapsedRealtimeNanos() / 1000000L >= 0 && SystemClock.elapsedRealtime() - location!!.getElapsedRealtimeNanos() / 1000000L < 15000
    }

    fun age(): Long {
        return if (location == null) Long.MAX_VALUE else max(
            0, SystemClock.elapsedRealtime() - location!!.getElapsedRealtimeNanos() / 1000000L
        )
    }

    fun observe(o: Observer?) {
        observers!!.add(o!!)
    }

    fun unobserve(o: Observer?) {
        observers!!.remove(o!!)
    }

    private fun notifyChanged() {
        for (o in ArrayList<Observer>(observers)) o.changed()
    }

    fun setVisible(on: Boolean) {
        visible = on
        reconcile()
        updateSensorSubscription()
    }

    fun setCompassActive(on: Boolean) {
        compassActive = on
        updateSensorSubscription()
    }

    fun compassSubscribed(): Boolean {
        return sensorRegistered
    }

    private fun updateSensorSubscription() {
        val wanted = visible && compassActive && hasCompass
        if (wanted && !sensorRegistered) {
            magneticHeading = Float.NaN
            orientationAt = 0
            sensorRegistered =
                sensors.registerListener(
                    this,
                    sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
                    SensorManager.SENSOR_DELAY_GAME
                )
        } else if (!wanted && sensorRegistered) {
            sensors.unregisterListener(this)
            sensorRegistered = false
            magneticHeading = Float.NaN
            orientationAt = 0
        }
    }

    fun setRecording(on: Boolean) {
        recording = on
        reconcile()
    }

    fun reconcile() {
        val wanted = (visible || recording) && precise()
        if (wanted && !registered) {
            started = SystemClock.elapsedRealtime()
            firstFix = -1
            location = null
            problem = ""
            try {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000, 0f, this, Looper.getMainLooper()
                )
                statusRegistered = manager.registerGnssStatusCallback(app.getMainExecutor(), callback)
                if (!statusRegistered) problem = "系统未接受卫星状态订阅；计数暂不可用"
                registered = true
            } catch (e: SecurityException) {
                manager.removeUpdates(this)
                manager.unregisterGnssStatusCallback(callback)
                problem = "无法请求卫星定位：请检查精确位置权限与系统定位"
            } catch (e: IllegalArgumentException) {
                manager.removeUpdates(this)
                manager.unregisterGnssStatusCallback(callback)
                problem = "无法请求卫星定位：请检查精确位置权限与系统定位"
            }
        } else if (!wanted && registered) {
            manager.removeUpdates(this)
            manager.unregisterGnssStatusCallback(callback)
            registered = false
            satellites = ArrayList<Satellite>()
            satelliteAt = 0
            statusRegistered = false
            satelliteStats = EMPTY_STATS
        }
        notifyChanged()
    }

    private val callback: GnssStatus.Callback = object : GnssStatus.Callback() {
        override fun onFirstFix(ttff: Int) {
            firstFix = ttff.toLong()
            notifyChanged()
        }

        override fun onStopped() {
            satellites = ArrayList<Satellite>()
            satelliteAt = 0
            satelliteStats = EMPTY_STATS
            notifyChanged()
        }

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            acceptGnssStatus(status)
        }
    }

    init {
        manager = app.getSystemService<LocationManager>(LocationManager::class.java)
        sensors = app.getSystemService<SensorManager>(SensorManager::class.java)
        windowManager = app.getSystemService<WindowManager>(WindowManager::class.java)
        hasCompass = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
    }

    /** Preserve every frequency entry from one Android callback before calculating unique counts.  */
    fun acceptGnssStatus(status: GnssStatus) {
        val list: MutableList<Satellite> = ArrayList<Satellite>(status.getSatelliteCount())
        for (i in 0..<status.getSatelliteCount()) {
            val s = Satellite()
            s.system = status.getConstellationType(i)
            s.id = status.getSvid(i)
            s.azimuth = status.getAzimuthDegrees(i)
            s.elevation = status.getElevationDegrees(i)
            s.cn0 = status.getCn0DbHz(i)
            s.used = status.usedInFix(i)
            s.hasFrequency = status.hasCarrierFrequencyHz(i)
            if (s.hasFrequency) s.frequency = status.getCarrierFrequencyHz(i)
            list.add(s)
        }
        list.sortWith(Comparator { a: Satellite, b: Satellite? -> java.lang.Float.compare(b!!.cn0, a!!.cn0) })
        acceptSatelliteSnapshot(list)
    }

    fun acceptSatelliteSnapshot(list: MutableList<Satellite>) {
        satellites = Collections.unmodifiableList<Satellite>(ArrayList<Satellite>(list))
        satelliteStats = SatelliteStats.of(satellites)
        satelliteAt = SystemClock.elapsedRealtime()
        notifyChanged()
    }

    fun satelliteDataFresh(): Boolean {
        return precise()
                && gpsEnabled()
                && satelliteAt > 0 && SystemClock.elapsedRealtime() - satelliteAt <= 15000
    }

    fun currentSatellites(): MutableList<Satellite> {
        return if (satelliteDataFresh()) satellites else mutableListOf<Satellite>()
    }

    fun satelliteStats(): SatelliteStats.Snapshot {
        return if (satelliteDataFresh()) satelliteStats else EMPTY_STATS
    }

    fun uniqueCount(used: Boolean): Int {
        val counts = satelliteStats().total
        return if (used) counts.used() else counts.received()
    }

    override fun onLocationChanged(fix: Location) {
        if (!Geo.valid(fix.getLatitude(), fix.getLongitude())) return
        location = Location(fix)
        val field =
            GeomagneticField(
                location!!.getLatitude().toFloat(),
                location!!.getLongitude().toFloat(),
                if (location!!.hasAltitude()) location!!.getAltitude().toFloat() else 0f,
                location!!.getTime()
            )
        declination = field.getDeclination()
        if (firstFix < 0 && fresh()) firstFix = SystemClock.elapsedRealtime() - started
        if (recorder != null) recorder!!.fix(Location(fix))
        notifyChanged()
    }

    override fun onProviderEnabled(provider: String) {
        problem = ""
        notifyChanged()
    }

    override fun onProviderDisabled(provider: String) {
        location = null
        satellites = ArrayList<Satellite>()
        problem = "系统卫星定位已关闭"
        notifyChanged()
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val rotation = windowManager!!.getDefaultDisplay().getRotation()
        var x = SensorManager.AXIS_X
        var y = SensorManager.AXIS_Y
        if (rotation == Surface.ROTATION_90) {
            x = SensorManager.AXIS_Y
            y = SensorManager.AXIS_MINUS_X
        } else if (rotation == Surface.ROTATION_180) {
            x = SensorManager.AXIS_MINUS_X
            y = SensorManager.AXIS_MINUS_Y
        } else if (rotation == Surface.ROTATION_270) {
            x = SensorManager.AXIS_MINUS_Y
            y = SensorManager.AXIS_X
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, x, y, adjustedMatrix)
        SensorManager.getOrientation(adjustedMatrix, orientation)
        // Keep only the latest sensor value. The compass view interpolates on vsync, not the 500ms UI
        // tick.
        magneticHeading = Geo.normalize(Math.toDegrees(orientation[0].toDouble())).toFloat()
        orientationAt = SystemClock.elapsedRealtime()
        sensorAccuracy = event.accuracy
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        sensorAccuracy = accuracy
    }

    fun declination(): Float {
        return declination
    }

    fun trueHeading(): Float {
        return if (java.lang.Float.isNaN(magneticHeading) || !fresh()) Float.NaN else Geo.normalize((magneticHeading + declination).toDouble())
            .toFloat()
    }

    companion object {
        private val EMPTY_STATS: SatelliteStats.Snapshot = SatelliteStats.of(mutableListOf<Signal>())
        fun systemName(system: Int): String {
            when (system) {
                GnssStatus.CONSTELLATION_GPS -> return "GPS"
                GnssStatus.CONSTELLATION_BEIDOU -> return "北斗"
                GnssStatus.CONSTELLATION_GALILEO -> return "Galileo"
                GnssStatus.CONSTELLATION_GLONASS -> return "GLONASS"
                GnssStatus.CONSTELLATION_QZSS -> return "QZSS"
                GnssStatus.CONSTELLATION_IRNSS -> return "NavIC"
                GnssStatus.CONSTELLATION_SBAS -> return "SBAS"
                else -> return "未知"
            }
        }
    }
}
