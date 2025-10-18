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

                    var currentLocation = MockServiceHelper.getLocation(locationManager!!)
                    if (currentLocation == null) {
                        Log.e("MockServiceViewModel", "Failed to obtain current location for route mock")
                        continue
                    }

                    var currentLat = currentLocation.first
                    var currentLon = currentLocation.second

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
                        break // 退出循环
                    }

                    val target = route[routeStage]
                    currentLocation = MockServiceHelper.getLocation(locationManager!!)
                    if (currentLocation == null) {
                        Log.e("MockServiceViewModel", "Failed to refresh location for route mock movement")
                        continue
                    }

                    currentLat = currentLocation.first
                    currentLon = currentLocation.second

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
}
