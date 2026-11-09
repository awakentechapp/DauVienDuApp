// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SightseeingTravel",
    defaultLocalization: "vi",
    platforms: [
        .iOS(.v17)
    ],
    products: [
        .library(
            name: "SightseeingTravel",
            targets: ["SightseeingTravel"]
        )
    ],
    targets: [
        .target(
            name: "SightseeingTravel",
            path: "SightseeingTravel",
            exclude: ["Info.plist"]
        )
    ]
)
