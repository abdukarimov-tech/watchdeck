// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "PulseCore",
    platforms: [
        .iOS(.v17),
        .macOS(.v15),
    ],
    products: [
        .library(name: "PulseCore", targets: ["PulseCore"]),
    ],
    dependencies: [
        .package(url: "https://github.com/orlandos-nl/Citadel", from: "0.12.1"),
        .package(url: "https://github.com/Wellz26/swift-nio-ssh.git", "0.3.4"..<"0.4.0"),
    ],
    targets: [
        .target(
            name: "PulseCore",
            dependencies: [
                .product(name: "Citadel", package: "Citadel"),
                .product(name: "NIOSSH", package: "swift-nio-ssh"),
            ]
        ),
        .testTarget(name: "PulseCoreTests", dependencies: ["PulseCore"]),
    ],
    swiftLanguageModes: [.v5]
)
