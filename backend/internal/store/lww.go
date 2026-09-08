package store

// candidateWins 判断一条新来的变更（候选）是否应替换当前已采纳的镜像状态
// （同一 table+id）。
//
// 当前状态由它“胜出的那次写入”描述：(updatedAtMs, deviceID)。对墓碑行而言，
// updatedAtMs 等于删除操作的时间戳。判定规则：
//
//   - 时间戳更新者胜；
//   - 时间戳相同且 device_id 不同时，取字典序更大者（结果确定、与到达顺序无关）；
func candidateWins(candUpdatedAtMs, curUpdatedAtMs int64, candDevice, curDevice string) bool {
	if candUpdatedAtMs != curUpdatedAtMs {
		return candUpdatedAtMs > curUpdatedAtMs
	}
	if candDevice == curDevice {
		return false
	}
	return candDevice > curDevice
}
