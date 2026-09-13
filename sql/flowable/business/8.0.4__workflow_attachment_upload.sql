-- 在文件 IO 前独立提交；上传事务持锁，成功时与 wf_attachment 一起消费。
-- claim_count 永不归零，已被清理器接手的对象键不能重新开始写入。
CREATE TABLE IF NOT EXISTS wf_attachment_upload (
    storage_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    claim_count INT NOT NULL DEFAULT 0,
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(3) NULL,
    next_retry_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_wf_attachment_upload_cleanup (next_retry_time, storage_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='附件未提交文件的持久清理账本';
