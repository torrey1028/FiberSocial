import XCTest
import ComposeApp

/// Hosted XCTests for the iOS KeyValueStore implementations (issue #117). Running
/// inside the app host gives the Keychain tests a real keychain daemon — the bare
/// Kotlin/Native test runner has none and skips its write-path tests.
final class KeychainKeyValueStoreTests: XCTestCase {

    private let store = KeychainKeyValueStore(service: "xctest_keychain")
    private let other = KeychainKeyValueStore(service: "xctest_other_keychain")

    override func tearDown() async throws {
        for s in [store, other] {
            try await s.remove(key: "k")
            try await s.remove(key: "k2")
        }
    }

    func testMissingKeyIsNil() async throws {
        let value = try await store.getString(key: "k")
        XCTAssertNil(value)
    }

    func testPutThenGetRoundTrips() async throws {
        try await store.putString(key: "k", value: "{\"accessToken\":\"abc\",\"sessionCookie\":\"_ravelry_session=x\"}")
        let value = try await store.getString(key: "k")
        XCTAssertEqual(value, "{\"accessToken\":\"abc\",\"sessionCookie\":\"_ravelry_session=x\"}")
    }

    func testPutOverwritesExistingItem() async throws {
        // Exercises the SecItemAdd -> errSecDuplicateItem -> SecItemUpdate path.
        try await store.putString(key: "k", value: "first")
        try await store.putString(key: "k", value: "second")
        let value = try await store.getString(key: "k")
        XCTAssertEqual(value, "second")
    }

    func testRemoveDeletesAndIsIdempotent() async throws {
        try await store.putString(key: "k", value: "v")
        try await store.remove(key: "k")
        let value = try await store.getString(key: "k")
        XCTAssertNil(value)
        try await store.remove(key: "k")
    }

    func testServicesAreNamespaced() async throws {
        try await store.putString(key: "k", value: "mine")
        let before = try await other.getString(key: "k")
        XCTAssertNil(before)
        try await other.putString(key: "k", value: "theirs")
        let mine = try await store.getString(key: "k")
        let theirs = try await other.getString(key: "k")
        XCTAssertEqual(mine, "mine")
        XCTAssertEqual(theirs, "theirs")
    }

    func testUnicodeSurvivesTheUtf8Bridge() async throws {
        try await store.putString(key: "k2", value: "füzzy yårn 🧶")
        let value = try await store.getString(key: "k2")
        XCTAssertEqual(value, "füzzy yårn 🧶")
    }
}

final class NsUserDefaultsKeyValueStoreTests: XCTestCase {

    private let store = NsUserDefaultsKeyValueStore(name: "xctest_store")
    private let other = NsUserDefaultsKeyValueStore(name: "xctest_other_store")

    override func tearDown() async throws {
        for s in [store, other] {
            try await s.remove(key: "k")
        }
    }

    func testPutThenGetRoundTrips() async throws {
        try await store.putString(key: "k", value: "{\"json\":\"blob\"}")
        let value = try await store.getString(key: "k")
        XCTAssertEqual(value, "{\"json\":\"blob\"}")
    }

    func testStoresAreNamespaced() async throws {
        try await store.putString(key: "k", value: "mine")
        try await other.putString(key: "k", value: "theirs")
        let mine = try await store.getString(key: "k")
        let theirs = try await other.getString(key: "k")
        XCTAssertEqual(mine, "mine")
        XCTAssertEqual(theirs, "theirs")
    }
}
