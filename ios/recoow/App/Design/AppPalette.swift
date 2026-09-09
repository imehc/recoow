import SwiftUI
import UIKit

enum AppPalette {
    static let background = adaptive(light: 0xF4F5F7, dark: 0x111214)
    static let surface = adaptive(light: 0xFFFFFF, dark: 0x1F2024)
    static let ink = adaptive(light: 0x1C2026, dark: 0xF3F4F6)
    static let secondaryInk = adaptive(light: 0x696F79, dark: 0xA7ABB4)
    static let accent = Color("AccentColor")
    static let accentForeground = adaptive(light: 0xFFFFFF, dark: 0x141A2C)
    static let green = adaptive(light: 0x067E5B, dark: 0x16A87B)
    static let teal = adaptive(light: 0x087B92, dark: 0x159DB6)
    static let blue = adaptive(light: 0x2868DA, dark: 0x4588EF)
    static let lavender = adaptive(light: 0x8250CD, dark: 0xA77BEC)
    static let rose = adaptive(light: 0xC5366F, dark: 0xE6689B)
    static let amber = adaptive(light: 0x9B6109, dark: 0xC2892A)
    static let clay = adaptive(light: 0xB85E1A, dark: 0xD57E33)
    static let red = adaptive(light: 0xCB3345, dark: 0xF06672)
    static let neutral = adaptive(light: 0x677180, dark: 0x737E8F)

    static func resolvedTint(_ tint: Color) -> Color {
        switch tint {
        case .blue:
            blue
        case .green, .mint:
            green
        case .teal, .cyan:
            teal
        case .purple, .indigo:
            lavender
        case .pink:
            rose
        case .orange, .yellow:
            amber
        case .brown:
            clay
        case .red:
            red
        case .gray, .secondary:
            neutral
        default:
            tint
        }
    }

    static func iconForeground(for tint: Color) -> Color {
        tint == accent || tint == Color.accentColor ? accentForeground : .white
    }

    private static func adaptive(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light

            return UIColor(
                red: CGFloat((hex >> 16) & 0xFF) / 255,
                green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255,
                alpha: 1
            )
        })
    }
}
