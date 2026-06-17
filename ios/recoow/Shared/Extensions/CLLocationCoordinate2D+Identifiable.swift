import CoreLocation

/// Map 标注需要稳定 id，坐标点用经纬度组合即可满足展示用途。
struct IdentifiableCoordinate: Identifiable, Hashable {
    let coordinate: CLLocationCoordinate2D

    var id: String {
        "\(coordinate.latitude),\(coordinate.longitude)"
    }

    static func == (lhs: IdentifiableCoordinate, rhs: IdentifiableCoordinate) -> Bool {
        lhs.coordinate.latitude == rhs.coordinate.latitude &&
            lhs.coordinate.longitude == rhs.coordinate.longitude
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(coordinate.latitude)
        hasher.combine(coordinate.longitude)
    }
}
