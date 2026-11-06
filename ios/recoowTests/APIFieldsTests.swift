import Foundation
import XCTest
@testable import recoow

@MainActor
final class APIFieldsTests: XCTestCase {
    func testCanonicalResponseRetainsBusinessKeysAndInt64Values() throws {
        let data = Data(#"{"account_id":"a","origin":{"device_id":"d"},"snapshot":{"deviceId":"business","amount":9007199254740993,"future":null},"record_counts":{"diary_entries":1}}"#.utf8)
        let legacy = try APIFields.data(data, legacy: true)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: legacy) as? [String: Any])
        XCTAssertEqual(object["accountId"] as? String,"a")
        let snapshot = try XCTUnwrap(object["snapshot"] as? [String: Any])
        XCTAssertEqual(snapshot["deviceId"] as? String,"business")
        XCTAssertEqual(snapshot["amount"] as? Int64,9007199254740993)
        XCTAssertTrue(snapshot["future"] is NSNull)
        let canonical = try APIFields.data(legacy, legacy: false)
        let result = try XCTUnwrap(JSONSerialization.jsonObject(with: canonical) as? [String: Any])
        XCTAssertEqual(result["account_id"] as? String,"a")
        XCTAssertNil(result["accountId"])
        XCTAssertEqual((result["record_counts"] as? [String: Int])?["diary_entries"],1)
    }

    func testMixedAliasesFailAndOldResponseIsReadable() throws {
        XCTAssertThrowsError(try APIFields.data(Data(#"{"accountId":"a","account_id":"b"}"#.utf8), legacy: true))
        let data = try APIFields.data(Data(#"{"accountId":"a"}"#.utf8),legacy:false)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:String])
        XCTAssertEqual(object["account_id"],"a")
    }
}
