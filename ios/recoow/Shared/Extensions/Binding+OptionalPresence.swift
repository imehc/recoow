import SwiftUI

extension Binding where Value == Bool {
    static func isPresent<Wrapped>(_ optional: Binding<Wrapped?>) -> Binding<Bool> {
        Binding(
            get: { optional.wrappedValue != nil },
            set: { isPresented in
                if isPresented == false {
                    optional.wrappedValue = nil
                }
            }
        )
    }
}
