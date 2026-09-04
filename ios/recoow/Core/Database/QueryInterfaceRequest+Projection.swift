import GRDB

extension QueryInterfaceRequest {
    /// Projects every column except the named ones. Used to keep large binary
    /// payloads (embedded `image_data` blobs) out of in-memory aggregates such as
    /// the statistics screen, while leaving every other column untouched. The
    /// record is still decoded as `RowDecoder`; omitted columns map to `nil` for
    /// optional properties.
    func selectingAllColumns(except excluded: Set<String>, db: Database)
        throws -> QueryInterfaceRequest<RowDecoder>
    where RowDecoder: TableRecord {
        let columns = try db.columns(in: RowDecoder.databaseTableName)
            .filter { excluded.contains($0.name) == false }
            .map { Column($0.name) as any SQLSelectable }

        return select(columns, as: RowDecoder.self)
    }
}
