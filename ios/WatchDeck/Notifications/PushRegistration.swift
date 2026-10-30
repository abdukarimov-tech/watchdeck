import UIKit
import Foundation

extension Notification.Name { static let apnsRegistered = Notification.Name("watchdeck.apnsRegistered") }
final class PushRegistration: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        UserDefaults.standard.set(token, forKey: "apns_device_token")
        NotificationCenter.default.post(name: .apnsRegistered, object: nil)
    }
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // Simulator and unsigned builds may not support APNs. Never report a successful subscription here.
        NSLog("WatchDeck APNs registration failed: %@", error.localizedDescription)
    }
}
