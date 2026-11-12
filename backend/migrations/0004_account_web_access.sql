-- 默认保持只读；授权属于账号设置，清空业务数据集时保留。
ALTER TABLE account ADD COLUMN web_record_writes BOOLEAN NOT NULL DEFAULT false;
