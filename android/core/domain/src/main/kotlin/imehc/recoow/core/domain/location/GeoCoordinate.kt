package imehc.recoow.core.domain.location

import kotlin.math.*

enum class MapCoordinateSystem { WGS84, GCJ02 }

data class GeoCoordinate(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }

    /** 只转换用于显示的副本，存储坐标始终保持 WGS84。 */
    fun forDisplay(system: MapCoordinateSystem): GeoCoordinate {
        if (system == MapCoordinateSystem.WGS84 || longitude !in 72.004..137.8347 || latitude !in 0.8293..55.8271) return this
        val x = longitude - 105.0
        val y = latitude - 35.0
        var dLat = -100 + 2*x + 3*y + 0.2*y*y + 0.1*x*y + 0.2*sqrt(abs(x))
        dLat += (20*sin(6*x*PI) + 20*sin(2*x*PI))*2/3
        dLat += (20*sin(y*PI) + 40*sin(y/3*PI))*2/3
        dLat += (160*sin(y/12*PI) + 320*sin(y*PI/30))*2/3
        var dLon = 300 + x + 2*y + 0.1*x*x + 0.1*x*y + 0.1*sqrt(abs(x))
        dLon += (20*sin(6*x*PI) + 20*sin(2*x*PI))*2/3
        dLon += (20*sin(x*PI) + 40*sin(x/3*PI))*2/3
        dLon += (150*sin(x/12*PI) + 300*sin(x/30*PI))*2/3
        val rad = latitude / 180 * PI
        val magic = 1 - 0.00669342162296594323 * sin(rad).pow(2)
        val root = sqrt(magic)
        dLat = dLat*180 / ((6378245.0*(1-0.00669342162296594323))/(magic*root)*PI)
        dLon = dLon*180 / (6378245.0/root*cos(rad)*PI)
        return GeoCoordinate(latitude + dLat, longitude + dLon)
    }
}
