import Foundation

@main
enum SyncValueChecks {
    static func main() throws {
        var effects = try StoredValueBalanceEffects.decode("{\"card\":1000}")
        try StoredValueBalanceEffects.adding(0, accountID: "card", to: &effects)
        precondition(effects["card"] == 1000)
        try StoredValueBalanceEffects.adding(-1000, accountID: "card", to: &effects)
        precondition(effects.isEmpty)
        try StoredValueBalanceEffects.adding(-200, accountID: "card", to: &effects)
        try StoredValueBalanceEffects.adding(-300, accountID: "card", to: &effects)
        precondition(effects["card"] == -500)
        let roundTrip = try StoredValueBalanceEffects.decode(StoredValueBalanceEffects.encode(effects))
        precondition(roundTrip == effects)
        effects = ["card": Int64.max]
        do {
            try StoredValueBalanceEffects.adding(1, accountID: "card", to: &effects)
            preconditionFailure("overflow was accepted")
        } catch {
            precondition(effects["card"] == Int64.max)
        }
        effects = ["card": Int64.min]
        do {
            try StoredValueBalanceEffects.adding(-1, accountID: "card", to: &effects)
            preconditionFailure("underflow was accepted")
        } catch {
            precondition(effects["card"] == Int64.min)
        }
        do {
            _ = try StoredValueBalanceEffects.decode("{\"card\":1.5}")
            preconditionFailure("fractional money was accepted")
        } catch {}
    }
}
