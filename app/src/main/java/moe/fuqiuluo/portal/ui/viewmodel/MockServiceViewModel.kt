package moe.fuqiuluo.portal.ui.viewmodel

import android.app.Activity
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.fuqiuluo.portal.android.coro.CoroutineController
import moe.fuqiuluo.portal.android.coro.CoroutineRouteMock
import moe.fuqiuluo.portal.ext.Loc4j
import moe.fuqiuluo.portal.ext.accuracy
import moe.fuqiuluo.portal.ext.altitude
import moe.fuqiuluo.portal.ext.reportDuration
import moe.fuqiuluo.portal.ext.speed
import moe.fuqiuluo.portal.service.MockServiceHelper
import moe.fuqiuluo.portal.ui.mock.HistoricalLocation
import moe.fuqiuluo.portal.ui.mock.HistoricalRoute
import moe.fuqiuluo.portal.ui.mock.Rocker
import moe.fuqiuluo.xposed.utils.FakeLoc
import net.sf.geographiclib.Geodesic
import kotlin.math.min

class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker
    private lateinit var rockerJob: Job
    private lateinit var routeMockJob: Job
    private var lastKnownRouteLocation: Pair<Double, Double>? = null
    var isRockerLocked = false
    var routeStage = 0
    val rockerCoroutineController = CoroutineController()
    val routeMockCoroutine = CoroutineRouteMock()

    var isRouteStart = false

    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null)
                MockServiceHelper.tryInitService(value)
        }

    var selectedLocation: HistoricalLocation? = null
    var selectedRoute: HistoricalRoute? = null


    fun initRocker(activity: Activity): Rocker {
        if (!::rocker.isInitialized) {
            rocker = Rocker(activity)
        }

        if (!::rockerJob.isInitialized || rockerJob.isCancelled) {
            rockerCoroutineController.pause()
            val delayTime = activity.reportDuration.toLong()
            val applicationContext = activity.applicationContext
            rockerJob = GlobalScope.launch {
                do {
                    rockerCoroutineController.controlledCoroutine()
                    delay(delayTime)

                    val distancePerTick = calculateDistancePerTick(delayTime)
                    if (distancePerTick <= 0.0) {
                        Log.w(
                            "MockServiceViewModel",
                            "Skip rocker movement because calculated distance is $distancePerTick"
                        )
                        continue
                    }

                    CrashReport.setUserSceneTag(applicationContext, 261773)
                    if(!MockServiceHelper.move(locationManager!!, distancePerTick, FakeLoc.bearing)) {
                        Log.e("MockServiceViewModel", "Failed to move")
                    }

//                    if (MockServiceHelper.broadcastLocation(locationManager!!)) {
//                        Log.d("MockServiceViewModel", "Broadcast location")
//                    } else {
//                        Log.e("MockServiceViewModel", "Failed to broadcast location")
//                    }
                } while (isActive)
            }
        }

        FakeLoc.speed = activity.speed
        FakeLoc.altitude = activity.altitude
        FakeLoc.accuracy = activity.accuracy

        if (!::routeMockJob.isInitialized || routeMockJob.isCancelled) {
            routeMockCoroutine.pause()
            val delayTime = activity.reportDuration.toLong()
            routeMockJob = GlobalScope.launch {
                do {
                    routeMockCoroutine.routeMockCoroutine()
                    delay(delayTime)

                    val currentRoute = selectedRoute ?: continue
                    val route = currentRoute.route
                    if (route.isEmpty()) {
                        Log.w("MockServiceViewModel", "Selected route has no waypoints, skip movement")
                        continue
                    }

                    if (routeStage == 0) {
                        val startPoint = route.first()
                        if (!MockServiceHelper.setLocation(
                                locationManager!!,
                                startPoint.first,
                                startPoint.second
                            )
                        ) {
                            Log.e("MockServiceViewModel", "Failed to set starting point for route mock")
                            continue
                        }
                        recordRouteLocation(startPoint.first, startPoint.second)
                        routeStage++
                    }

                    val distancePerTick = calculateDistancePerTick(delayTime)
                    if (distancePerTick <= 0.0) {
                        Log.w(
                            "MockServiceViewModel",
                            "Skip route movement because calculated distance is $distancePerTick"
                        )
                        continue
                    }

                    val validatedCurrentLocation = validateRouteLocation(
                        MockServiceHelper.getLocation(locationManager!!),
                        distancePerTick
                    )
                    if (validatedCurrentLocation == null) {
                        val fallback = lastKnownRouteLocation
                        if (fallback == null) {
                            Log.e(
                                "MockServiceViewModel",
                                "Unable to obtain a valid current location and no fallback is available"
                            )
                            continue
                        }
                        Log.w(
                            "MockServiceViewModel",
                            "Using fallback route location $fallback because reported value is invalid"
                        )
                        recordRouteLocation(fallback.first, fallback.second)
                    }

                    var currentLat = (validatedCurrentLocation ?: lastKnownRouteLocation)!!.first
                    var currentLon = (validatedCurrentLocation ?: lastKnownRouteLocation)!!.second
                    if (validatedCurrentLocation != null) {
                        recordRouteLocation(currentLat, currentLon)
                    }

                    // 处理所有已到达的阶段
                    while (routeStage < route.size) {
                        val target = route[routeStage]
                        val inverse = Geodesic.WGS84.Inverse(
                            currentLat,
                            currentLon,
                            target.first,
                            target.second
                        )
                        val shouldSnap = inverse.s12 < 1.0 || inverse.s12 <= distancePerTick
                        if (shouldSnap) {
                            if (!MockServiceHelper.setLocation(
                                    locationManager!!,
                                    target.first,
                                    target.second
                                )
                            ) {
                                Log.e("MockServiceViewModel", "Failed to snap to route waypoint $routeStage")
                                break
                            }
                            currentLat = target.first
                            currentLon = target.second
                            recordRouteLocation(currentLat, currentLon)
                            routeStage++
                        } else {
                            break
                        }
                    }

                    // 检查是否已完成所有阶段
                    if (routeStage >= route.size) {
                        routeMockCoroutine.pause()
                        rocker.autoStatus = false
                        // 重设阶段
                        routeStage = 0
                        lastKnownRouteLocation = null
                        break // 退出循环
                    }

                    val target = route[routeStage]
                    val refreshedLocation = validateRouteLocation(
                        MockServiceHelper.getLocation(locationManager!!),
                        distancePerTick
                    )
                    if (refreshedLocation == null) {
                        val fallback = lastKnownRouteLocation
                        if (fallback == null) {
                            Log.e(
                                "MockServiceViewModel",
                                "Unable to refresh route location and no fallback is available"
                            )
                            continue
                        }
                        Log.w(
                            "MockServiceViewModel",
                            "Falling back to cached route location $fallback after invalid refresh"
                        )
                        currentLat = fallback.first
                        currentLon = fallback.second
                    } else {
                        currentLat = refreshedLocation.first
                        currentLon = refreshedLocation.second
                        recordRouteLocation(currentLat, currentLon)
                    }

                    val inverse = Geodesic.WGS84.Inverse(
                        currentLat,
                        currentLon,
                        target.first,
                        target.second
                    )
                    var azimuth = inverse.azi1
                    if (azimuth < 0) {
                        azimuth += 360
                    }

                    val travelDistance = min(distancePerTick, inverse.s12)
                    if (travelDistance <= 0.0 || !travelDistance.isFinite()) {
                        Log.w(
                            "MockServiceViewModel",
                            "Calculated travel distance is invalid ($travelDistance), skip this tick"
                        )
                        continue
                    }

                    val destination = Geodesic.WGS84.Direct(
                        currentLat,
                        currentLon,
                        azimuth,
                        travelDistance
                    )

                    Log.d(
                        "MockServiceViewModel",
                        "从 $currentLat, $currentLon 移动到 ${destination.lat2}, ${destination.lon2}, 方位角: $azimuth, 距离: $travelDistance"
                    )

                    if (!MockServiceHelper.setLocation(
                            locationManager!!,
                            destination.lat2,
                            destination.lon2
                        )
                    ) {
                        Log.e("MockServiceViewModel", "更新路线位置失败")
                        continue
                    }
                    recordRouteLocation(destination.lat2, destination.lon2)

                    if (!MockServiceHelper.setBearing(locationManager!!, azimuth)) {
                        Log.e("MockServiceViewModel", "更新方位角失败")
                    } else {
                        FakeLoc.bearing = azimuth
                        FakeLoc.hasBearings = true
                    }
                } while (isActive)
            }
        }

        return rocker
    }

    fun isServiceStart(): Boolean {
        return locationManager != null && MockServiceHelper.isServiceInit() && MockServiceHelper.isMockStart(
            locationManager!!
        )
    }

    private fun calculateDistancePerTick(delayTime: Long): Double {
        if (delayTime <= 0) {
            return 0.0
        }
        val distance = FakeLoc.speed * (delayTime / 1000.0) / 0.85
        return if (distance.isFinite() && distance > 0.0) distance else 0.0
    }

    private fun validateRouteLocation(
        reportedLocation: Pair<Double, Double>?,
        distancePerTick: Double
    ): Pair<Double, Double>? {
        if (reportedLocation == null) {
            return null
        }
        val (lat, lon) = reportedLocation
        if (!lat.isFinite() || !lon.isFinite()) {
            return null
        }
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return null
        }
        val previous = lastKnownRouteLocation ?: return reportedLocation
        val delta = Geodesic.WGS84.Inverse(previous.first, previous.second, lat, lon).s12
        val tolerance = (if (distancePerTick.isFinite() && distancePerTick > 0) distancePerTick else 0.0) * 5 + 5.0
        return if (delta <= tolerance) reportedLocation else null
    }

    private fun recordRouteLocation(lat: Double, lon: Double) {
        if (!lat.isFinite() || !lon.isFinite()) {
            return
        }
        lastKnownRouteLocation = lat to lon
    }
}
