import Foundation

/// Compile with the production layout and catalog files. No fixtures are linked into the app.
@main
enum DecisionTurntableChecks {
    static func main() throws {
        let counts = [0, 1, 2, 6, 8, 10, 11, 40, 41, 101, 1001, 10_000]
        for count in counts {
            for limit in [8, 10] {
                for cursor in [-103.25, -10, -0.001, 0, 0.999, 11, 99.75, 10001] {
                    let placements = DecisionTurntableLayout.placements(count: count, limit: limit, cursor: cursor)
                    precondition(placements.count == min(count, limit))
                    precondition(Set(placements.map(\.slot)).count == placements.count)
                    precondition(Set(placements.map(\.optionIndex)).count == placements.count)
                    precondition(placements.allSatisfy { (0..<count).contains($0.optionIndex) && $0.scale >= 0 && $0.scale <= 1 })
                }
                // Every candidate, including ones never instantiated on entry, can reach the pointer.
                for index in 0..<count {
                    let placements = DecisionTurntableLayout.placements(count: count, limit: limit, cursor: Double(index))
                    let front = placements.first { abs(atan2(sin($0.angle), cos($0.angle))) < 0.000001 }
                    precondition(front?.optionIndex == index)
                }
                for index in [0, max(0, count / 2), max(0, count - 1)] where count > 0 {
                    for cursor in [-17.8, 0, 4.2, 112.5] {
                        let browse = DecisionTurntableLayout.browseTarget(index: index, count: count, cursor: cursor)
                        precondition(DecisionTurntableLayout.focusedIndex(cursor: browse, count: count) == index)
                        precondition(abs(browse - cursor) <= Double(count) / 2 + 0.000001)
                        for input in [DecisionSpinInput(), DecisionSpinInput(velocity: -2400), DecisionSpinInput(velocity: 2400)] {
                            let target = DecisionTurntableLayout.drawTarget(index: index, count: count, limit: limit, cursor: cursor, input: input)
                            precondition(DecisionTurntableLayout.focusedIndex(cursor: target, count: count) == index)
                            if count > 1 {
                                precondition(input.direction * (target - cursor) >= Double(min(count, limit)) * 1.8)
                            }
                        }
                    }
                }
            }
        }
        for gesture in [false, true] {
            let progress = (0...1000).map { DecisionTurntableLayout.spinProgress(Double($0) / 1000, fromGesture: gesture) }
            precondition(progress.first == 0 && progress.last == 1)
            precondition(zip(progress, progress.dropFirst()).allSatisfy { $0 <= $1 })
        }
        let url = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? "ios/recoow/Resources/DecisionModels/decision-model-catalog.json")
        let catalog = try JSONDecoder().decode(DecisionModelCatalog.self, from: Data(contentsOf: url))
        for (title, key) in ["火锅": "hotpot", "串串": "skewers", "麻辣烫": "malatang", "烧烤": "barbecue", "螺蛳粉": "luosifen", "柳州螺蛳粉": "luosifen", "干锅虾": "drypotshrimp", "香辣干锅虾": "drypotshrimp", " ＢＢＱ ": "barbecue", "Hot   Pot": "hotpot", "不吃火锅": "default", "今天在家做": "default"] {
            precondition(catalog.modelKey(title: title) == key)
        }
        precondition(catalog.modelKey(title: "火锅", explicitKey: "sushi") == "sushi")
        precondition(catalog.modelKey(title: "火锅", explicitKey: "unavailable") == "hotpot")
        let directory = url.deletingLastPathComponent()
        for filename in catalog.assets.map(\.usdz) + [catalog.stage.usdz, catalog.pad.usdz] {
            precondition(FileManager.default.fileExists(atPath: directory.appendingPathComponent(filename).path))
        }
        print("PASS: dynamic pools 0…10,000, bounded slot reuse, every candidate reachable, bidirectional landing, easing, exact aliases and native resources")
    }
}
