import XCTest
@testable import TouchShare

final class SmokeTests: XCTestCase {
    func testRootScreenLoads() {
        let vc = RootViewController()
        vc.loadViewIfNeeded()
        XCTAssertTrue(vc.titleLabel.text?.contains("Touch Share") ?? false)
    }

    func testBundleIdentifierMatchesAndroidAppId() {
        XCTAssertEqual(Bundle(for: RootViewController.self).bundleIdentifier, "com.touchshare.app")
    }
}
