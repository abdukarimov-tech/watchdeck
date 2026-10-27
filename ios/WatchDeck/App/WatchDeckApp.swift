import SwiftUI
import LocalAuthentication
import PulseCore

@main
struct WatchDeckApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .tint(PulseTheme.accent)
                .onAppear { model.bootstrap() }
                .onChange(of: scenePhase) { _, phase in
                    model.handleScenePhase(phase)
                }
        }
    }
}

struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ZStack {
            if model.servers.isEmpty {
                OnboardingScreen()
            } else {
                ServerListScreen()
            }
            if model.appLockEnabled && !model.isUnlocked {
                LockScreen()
            }
            if let toast = model.toast {
                ToastView(message: toast)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .zIndex(10)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: model.toast)
    }
}

struct LockScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ZStack {
            Rectangle().fill(.ultraThinMaterial).ignoresSafeArea()
            VStack(spacing: 20) {
                Image(systemName: "lock.shield")
                    .font(.system(size: 56))
                    .foregroundStyle(PulseTheme.accent)
                Text("lock_title")
                    .font(.title2.bold())
                Text("lock_subtitle")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button {
                    unlock()
                } label: {
                    Text("lock_retry")
                        .font(.headline)
                        .padding(.horizontal, 28)
                        .padding(.vertical, 12)
                        .background(PulseTheme.accent.opacity(0.18), in: Capsule())
                }
            }
            .padding()
        }
        .onAppear { unlock() }
    }

    private func unlock() {
        let context = LAContext()
        var error: NSError?
        // биометрия или код устройства — аналог BIOMETRIC_WEAK|DEVICE_CREDENTIAL
        if context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) {
            context.evaluatePolicy(.deviceOwnerAuthentication,
                                   localizedReason: NSLocalizedString("lock_subtitle", comment: "")) { ok, _ in
                guard ok else { return }
                Task { @MainActor in model.isUnlocked = true }
            }
        } else {
            // защита на устройстве не настроена — не запираем
            model.isUnlocked = true
        }
    }
}

struct ToastView: View {
    let message: ToastMessage

    var body: some View {
        Text(message.text)
            .font(.footnote)
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(message.isError ? PulseTheme.error.opacity(0.92) : Color.black.opacity(0.85),
                        in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 24)
            .padding(.bottom, 36)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            .allowsHitTesting(false)
    }
}
