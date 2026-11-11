import GRDB

extension QueryInterfaceRequest {
    /// 投影除指定列之外的所有列。用于把大体积二进制载荷（内嵌的 `image_data`
    /// 数据块）排除在统计页这类内存聚合之外，其余列保持原样。记录仍按
    /// `RowDecoder` 解码，被省略的列在可选属性上映射为 `nil`。
    func selectingAllColumns(except excluded: Set<String>, db: Database)
        throws -> QueryInterfaceRequest<RowDecoder>
    where RowDecoder: TableRecord {
        let columns = try db.columns(in: RowDecoder.databaseTableName)
            .filter { excluded.contains($0.name) == false }
            .map { Column($0.name) as any SQLSelectable }

        return select(columns, as: RowDecoder.self)
    }
}
