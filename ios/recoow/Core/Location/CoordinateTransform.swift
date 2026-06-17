import CoreLocation
import Foundation

/// 坐标系转换工具。数据库保存 CoreLocation 原始坐标，地图展示时再按底图坐标系转换。
enum CoordinateTransform {
    private static let pi = Double.pi
    private static let a = 6378245.0
    private static let ee = 0.00669342162296594323

    /// 中国大陆 MapKit 底图通常与 GCJ-02 对齐，WGS-84 轨迹直接绘制会出现偏移。
    static func mapDisplayCoordinate(forWGS84 coordinate: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        guard isInMainlandChina(coordinate) else { return coordinate }
        return wgs84ToGCJ02(coordinate)
    }

    private static func isInMainlandChina(_ coordinate: CLLocationCoordinate2D) -> Bool {
        coordinate.longitude >= 72.004 &&
            coordinate.longitude <= 137.8347 &&
            coordinate.latitude >= 0.8293 &&
            coordinate.latitude <= 55.8271
    }

    private static func wgs84ToGCJ02(_ coordinate: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        let latitude = coordinate.latitude
        let longitude = coordinate.longitude
        var deltaLatitude = transformLatitude(longitude - 105.0, latitude - 35.0)
        var deltaLongitude = transformLongitude(longitude - 105.0, latitude - 35.0)
        let radLatitude = latitude / 180.0 * pi
        var magic = sin(radLatitude)
        magic = 1 - ee * magic * magic
        let sqrtMagic = sqrt(magic)
        deltaLatitude = (deltaLatitude * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * pi)
        deltaLongitude = (deltaLongitude * 180.0) / (a / sqrtMagic * cos(radLatitude) * pi)

        return CLLocationCoordinate2D(
            latitude: latitude + deltaLatitude,
            longitude: longitude + deltaLongitude
        )
    }

    private static func transformLatitude(_ x: Double, _ y: Double) -> Double {
        var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * pi) + 20.0 * sin(2.0 * x * pi)) * 2.0 / 3.0
        result += (20.0 * sin(y * pi) + 40.0 * sin(y / 3.0 * pi)) * 2.0 / 3.0
        result += (160.0 * sin(y / 12.0 * pi) + 320.0 * sin(y * pi / 30.0)) * 2.0 / 3.0
        return result
    }

    private static func transformLongitude(_ x: Double, _ y: Double) -> Double {
        var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * pi) + 20.0 * sin(2.0 * x * pi)) * 2.0 / 3.0
        result += (20.0 * sin(x * pi) + 40.0 * sin(x / 3.0 * pi)) * 2.0 / 3.0
        result += (150.0 * sin(x / 12.0 * pi) + 300.0 * sin(x / 30.0 * pi)) * 2.0 / 3.0
        return result
    }
}
