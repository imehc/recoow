import Foundation
import RecoowAPI
import XCTest

final class GeneratedAPICompatibilityTests: XCTestCase {
    func testUnknownSnapshotFieldsRoundTripWithoutLosingPrecisionOrNull() throws {
        let data = Data(#"{"id":"r","created_at":1,"updated_at":2,"device_id":"d","title":"纪念日","category":"other","occurred_at":3,"date_calendar":"solar","is_yearly":false,"lead_time_minutes":0,"is_enabled":true,"reminder_time_minutes":0,"future_amount":9007199254740993,"future_null":null}"#.utf8)
        let snapshot = try JSONDecoder().decode(RWAPIAnniversariesSnapshot.self, from: data)
        let encoded = try JSONEncoder().encode(snapshot)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        XCTAssertEqual(object["future_amount"] as? Int64, 9_007_199_254_740_993)
        XCTAssertTrue(object["future_null"] is NSNull)
        XCTAssertEqual(object["title"] as? String, "纪念日")
    }

    func testDateFormatterRetainsAllSupportedDateFormats() throws {
        let formatter = CodableHelper().dateFormatter
        let milliseconds = try XCTUnwrap(formatter.date(from: "2026-11-24T00:00:00.123Z"))
        let seconds = try XCTUnwrap(formatter.date(from: "2026-11-24T00:00:00Z"))
        let day = try XCTUnwrap(formatter.date(from: "2026-11-24"))
        XCTAssertEqual(milliseconds.timeIntervalSince(seconds), 0.123, accuracy: 0.001)
        XCTAssertEqual(seconds, day)
        XCTAssertNil(formatter.date(from: "invalid"))
    }

    func testAsyncRequestSuccessAndFailureRetainBodyAndHTTPStatus() async throws {
        for status in [200, 401] {
            let builder = StubBuilder(method: "GET", URLString: "https://example.invalid/api/users/me",
                                      parameters: ["status": status], requiresAuthentication: false)
            do {
                let response = try await builder.execute()
                XCTAssertEqual(status, 200)
                XCTAssertEqual(response.body.accountId, "account")
            } catch {
                switch error {
                case .error(let code, _, _, _):
                    XCTAssertEqual(code, 401)
                    XCTAssertEqual(status, 401)
                }
            }
        }
    }
}

private final class StubBuilder: URLSessionDecodableRequestBuilder<RWAPIIdentityResponse>, @unchecked Sendable {
    override func createURLSession() -> URLSessionProtocol {
        StubSession(status: parameters?["status"] as! Int)
    }
}

private struct StubSession: URLSessionProtocol {
    let status: Int

    func dataTaskFromProtocol(with request: URLRequest, completionHandler: @escaping @Sendable (Data?, URLResponse?, Error?) -> Void) -> URLSessionDataTaskProtocol {
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        return StubTask(response: response, completion: completionHandler)
    }
}

private final class StubTask: URLSessionDataTaskProtocol {
    let taskIdentifier = Int.random(in: 1...Int.max)
    let progress = Progress(totalUnitCount: 1)
    let response: HTTPURLResponse
    let completion: @Sendable (Data?, URLResponse?, Error?) -> Void

    init(response: HTTPURLResponse, completion: @escaping @Sendable (Data?, URLResponse?, Error?) -> Void) {
        self.response = response
        self.completion = completion
    }

    func resume() {
        DispatchQueue.global().async { [response, completion] in
            completion(Data(#"{"user_id":"user","username":"name","account_id":"account"}"#.utf8), response, nil)
        }
    }

    func cancel() {}
}
