import SwiftUI

struct CalendarWeekDayButton: View {
    @Environment(\.locale) private var locale

    let date: Date
    let count: Int
    let isSelected: Bool
    let isToday: Bool
    let select: () -> Void

    var body: some View {
        Button(action: select) {
            VStack(spacing: 5) {
                Text(weekdayTitle)
                    .font(.caption2)
                    .fontWeight(isSelected ? .semibold : .regular)

                Text(dayTitle)
                    .font(.headline)
                    .fontWeight(isSelected ? .bold : .semibold)

                Text(countTitle)
                    .font(.caption2)
                    .fontWeight(.semibold)
                    .monospacedDigit()
                    .lineLimit(1)
                    .padding(.horizontal, 5)
                    .frame(height: 16)
                    .background(countBackgroundStyle, in: Capsule())
                    .opacity(hasEntries ? 1 : 0)
            }
            .frame(maxWidth: .infinity, minHeight: 64)
            .foregroundStyle(foregroundStyle)
            .background(backgroundStyle, in: RoundedRectangle(cornerRadius: AppDesign.cornerRadius))
            .overlay {
                RoundedRectangle(cornerRadius: AppDesign.cornerRadius)
                    .strokeBorder(borderStyle, lineWidth: borderWidth)
            }
            .opacity(hasEntries || isSelected || isToday ? 1 : 0.58)
            .contentShape(RoundedRectangle(cornerRadius: AppDesign.cornerRadius))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private var weekdayTitle: String {
        date.formatted(
            Date.FormatStyle()
                .weekday(.narrow)
                .locale(locale)
        )
    }

    private var dayTitle: String {
        date.formatted(
            Date.FormatStyle()
                .day(.defaultDigits)
                .locale(locale)
        )
    }

    private var countTitle: String {
        count > 99 ? "99+" : "\(count)"
    }

    private var hasEntries: Bool {
        count > 0
    }

    private var foregroundStyle: some ShapeStyle {
        if isSelected {
            return AnyShapeStyle(.white)
        }

        if isToday {
            return AnyShapeStyle(Color.accentColor)
        }

        return AnyShapeStyle(.primary)
    }

    private var backgroundStyle: some ShapeStyle {
        if isSelected {
            return AnyShapeStyle(Color.accentColor)
        }

        if hasEntries {
            return AnyShapeStyle(Color.accentColor.opacity(0.12))
        }

        return AnyShapeStyle(Color(.secondarySystemGroupedBackground))
    }

    private var borderStyle: some ShapeStyle {
        if isSelected {
            return AnyShapeStyle(Color.accentColor.opacity(0.7))
        }

        if hasEntries {
            return AnyShapeStyle(Color.accentColor.opacity(0.55))
        }

        return AnyShapeStyle(Color.accentColor.opacity(0.45))
    }

    private var countBackgroundStyle: some ShapeStyle {
        if isSelected {
            return AnyShapeStyle(.white.opacity(0.22))
        }

        return AnyShapeStyle(Color.accentColor.opacity(0.16))
    }

    private var borderWidth: CGFloat {
        if isSelected || isToday || hasEntries {
            return 1
        }

        return 0
    }

    private var accessibilityLabel: String {
        let dateTitle = date.formatted(
            Date.FormatStyle(date: .complete, time: .omitted)
                .locale(locale)
        )

        guard count > 0 else {
            return AppLocalization.format("%@，无记录", dateTitle)
        }

        return AppLocalization.format("%@，%d 条记录", dateTitle, count)
    }
}
