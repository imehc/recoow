import SwiftUI

extension View {
    func appNavigationStyle() -> some View {
        navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(AppPalette.background, for: .navigationBar)
            .tint(AppPalette.accent)
    }

    func appPageStyle() -> some View {
        background(AppPalette.background.ignoresSafeArea())
            .appNavigationStyle()
    }

    func appSectionHeaderStyle() -> some View {
        font(AppDesign.sectionTitleFont)
            .foregroundStyle(AppPalette.secondaryInk)
            .textCase(nil)
            .accessibilityAddTraits(.isHeader)
    }

    func appGroupedListStyle(topContentMargin: CGFloat = AppDesign.groupedContentTopMargin) -> some View {
        listStyle(.insetGrouped)
            .modifier(AppGroupedContentStyle(topContentMargin: topContentMargin))
    }

    func appFormStyle(topContentMargin: CGFloat = AppDesign.groupedContentTopMargin) -> some View {
        formStyle(.grouped)
            .modifier(AppGroupedContentStyle(topContentMargin: topContentMargin))
    }

    func appSheet<SheetContent: View>(
        isPresented: Binding<Bool>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping () -> SheetContent
    ) -> some View {
        sheet(isPresented: isPresented, onDismiss: onDismiss) {
            content()
                .modifier(AppSheetStyle())
        }
    }

    func appSheet<Item: Identifiable, SheetContent: View>(
        item: Binding<Item?>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping (Item) -> SheetContent
    ) -> some View {
        sheet(item: item, onDismiss: onDismiss) { item in
            content(item)
                .modifier(AppSheetStyle())
        }
    }
}

private struct AppGroupedContentStyle: ViewModifier {
    let topContentMargin: CGFloat

    func body(content: Content) -> some View {
        content
            .scrollContentBackground(.hidden)
            .contentMargins(.top, topContentMargin, for: .scrollContent)
            .listSectionSpacing(AppDesign.sectionSpacing)
            .environment(\.defaultMinListRowHeight, 44)
            .textCase(nil)
            .appPageStyle()
    }
}

private struct AppSheetStyle: ViewModifier {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func body(content: Content) -> some View {
        let sheet = content
            .tint(AppPalette.accent)
            .presentationBackground(AppPalette.background)
            .presentationCornerRadius(AppDesign.sheetCornerRadius)
            .presentationDragIndicator(.visible)

        if dynamicTypeSize.isAccessibilitySize {
            sheet.presentationDetents([.large])
        } else {
            sheet
        }
    }
}
