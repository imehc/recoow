import CoreFoundation
import Foundation
import GRDB

nonisolated enum SyncFieldType {
    case text, int, real, bool, blob
}

nonisolated struct SyncField {
    let name: String
    let type: SyncFieldType
    var required = false
    var nullable = false
    var binary = false
    var derived = false
    var defaultJSON: String?
    var enumValues: [String] = []
    var jsonText = false

    init(_ name: String, _ type: SyncFieldType, required: Bool = false, nullable: Bool = false,
         binary: Bool = false, derived: Bool = false, defaultJSON: String? = nil,
         enumValues: [String] = [], jsonText: Bool = false) {
        self.name = name
        self.type = type
        self.required = required
        self.nullable = nullable
        self.binary = binary
        self.derived = derived
        self.defaultJSON = defaultJSON
        self.enumValues = enumValues
        self.jsonText = jsonText
    }
}

nonisolated struct SyncDependency {
    let field: String
    let table: String
}

nonisolated struct SyncEntityDefinition {
    let table: String
    let version: Int
    let fields: [SyncField]
    let dependencies: [SyncDependency]

    func upgrade(_ snapshot: [String: Any], from version: Int) throws -> [String: Any] {
        guard version > 0, version <= self.version else {
            throw CloudAPIError.invalidResponse(AppLocalization.format("%@ 契约版本 %d 不受支持，请更新客户端", table, version))
        }
        var result = snapshot
        for field in fields where !field.binary && !field.derived {
            if result[field.name] == nil, version < self.version, let defaultJSON = field.defaultJSON {
                result[field.name] = try JSONSerialization.jsonObject(with: Data(defaultJSON.utf8), options: [.fragmentsAllowed])
            }
            guard let value = result[field.name] else {
                if field.required { throw CloudAPIError.invalidResponse(AppLocalization.format("%@.%@ 缺少必填值", table, field.name)) }
                continue
            }
            if value is NSNull {
                guard field.nullable else { throw CloudAPIError.invalidResponse(AppLocalization.format("%@.%@ 不可为空", table, field.name)) }
                continue
            }
            let valid: Bool
            switch field.type {
            case .text:
                valid = value is String
            case .int:
                valid = (value as? NSNumber).map { CFGetTypeID($0) != CFBooleanGetTypeID() && Int64($0.stringValue) != nil } ?? false
            case .real:
                valid = (value as? NSNumber).map { CFGetTypeID($0) != CFBooleanGetTypeID() && $0.doubleValue.isFinite } ?? false
            case .bool:
                valid = (value as? NSNumber).map { CFGetTypeID($0) == CFBooleanGetTypeID() } ?? false
            case .blob:
                valid = false
            }
            guard valid else { throw CloudAPIError.invalidResponse(AppLocalization.format("%@.%@ 类型不正确", table, field.name)) }
            if !field.enumValues.isEmpty, let text = value as? String, !field.enumValues.contains(text) {
                throw CloudAPIError.invalidResponse(AppLocalization.format("%@.%@ 枚举不受支持", table, field.name))
            }
            if field.jsonText, let text = value as? String {
                _ = try JSONSerialization.jsonObject(with: Data(text.utf8), options: [.fragmentsAllowed])
            }
        }
        return result
    }
}

extension SyncEntityCatalog {
    nonisolated static var tables: Set<String> { Set(entities.map(\.table)) }
    nonisolated static var contracts: [String: Int] { Dictionary(uniqueKeysWithValues: entities.map { ($0.table, $0.version) }) }

    nonisolated static func entity(_ table: String) throws -> SyncEntityDefinition {
        guard let entity = entities.first(where: { $0.table == table }) else {
            throw CloudAPIError.invalidResponse(AppLocalization.format("未知同步实体 %@，数据已保留在 inbox", table))
        }
        return entity
    }

    nonisolated static var sqlOrder: String {
        "CASE table_name " + entities.enumerated().map { "WHEN '\($0.element.table)' THEN \($0.offset)" }.joined(separator: " ") + " ELSE 999 END"
    }

    nonisolated static func snapshot(row: Row, entity: SyncEntityDefinition) -> [String: Any] {
        var object: [String: Any] = [:]
        for field in entity.fields {
            if field.type == .blob {
                object[field.name] = (row[field.name] as Data?)?.base64EncodedString() ?? (NSNull() as Any)
            } else if field.type == .text {
                object[field.name] = (row[field.name] as String?) ?? (NSNull() as Any)
            } else if field.type == .real {
                object[field.name] = (row[field.name] as Double?) ?? (NSNull() as Any)
            } else if field.type == .bool {
                object[field.name] = (row[field.name] as Bool?) ?? (NSNull() as Any)
            } else {
                object[field.name] = (row[field.name] as Int64?) ?? (NSNull() as Any)
            }
        }
        return object
    }
}
