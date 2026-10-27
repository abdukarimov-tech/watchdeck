import Foundation
import UserNotifications
import PulseCore

/// Локальные уведомления: watchdog "релей недоступен" и алерты событий.
/// (Push через FCM на iOS не работает — релей пока умеет только FCM; см. README.)
final class LocalNotifier: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    static let shared = LocalNotifier()

    func bootstrap() {
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func post(title: String, body: String, identifier: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    // Показывать баннеры и когда приложение на переднем плане.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
