// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SilenceApp",
    platforms: [.iOS(.v16)],
    dependencies: [
        .package(url: "https://github.com/stasel/WebRTC.git", from: "125.0.0"),
        .package(url: "https://github.com/a2/MessagePack.swift.git", from: "4.0.0"),
        .package(url: "https://github.com/google/tink.git", from: "1.11.0"),
    ],
    targets: [
        .executableTarget(
            name: "SilenceApp",
            dependencies: [
                .product(name: "WebRTC", package: "WebRTC"),
                .product(name: "MessagePack", package: "MessagePack.swift"),
                .product(name: "Tink", package: "tink"),
            ],
            linkerSettings: [.linkedFramework("CryptoKit")]
            ],
            path: "Sources/SilenceApp"
        )
    ]
)
