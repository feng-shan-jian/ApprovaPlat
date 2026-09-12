CREATE TABLE wf_collaboration_message (
 message_id VARCHAR(36) PRIMARY KEY,
 delivery_mode VARCHAR(16) NOT NULL DEFAULT 'COLLABORATION',
 credential_id BIGINT NOT NULL, actor_user_id VARCHAR(64) NOT NULL,
 channel_id VARCHAR(64) NOT NULL, sequence_no BIGINT NOT NULL,
 message_name VARCHAR(255) NOT NULL, source_process_definition_key VARCHAR(255) NOT NULL,
 target_process_definition_key VARCHAR(255) NOT NULL, correlation_key VARCHAR(255),
 target_process_instance_id VARCHAR(64), matched_process_instance_id VARCHAR(64), target_execution_id VARCHAR(64),
 variables_json CLOB NOT NULL, payload_sha256 VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL, attempt_count INT NOT NULL DEFAULT 0, max_attempts INT NOT NULL DEFAULT 5,
 compensation_count INT NOT NULL DEFAULT 0, revision_no INT NOT NULL DEFAULT 0,
 last_error_code VARCHAR(64), last_error_summary VARCHAR(512),
 create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), next_attempt_time TIMESTAMP(3), complete_time TIMESTAMP(3),
 UNIQUE(channel_id, sequence_no),
 FOREIGN KEY(channel_id) REFERENCES wf_collaboration_channel(channel_id),
 CHECK(delivery_mode = 'COLLABORATION' OR delivery_mode = 'POINT_TO_POINT'),
 CHECK(attempt_count BETWEEN 0 AND max_attempts AND max_attempts BETWEEN 1 AND 20),
 CHECK((correlation_key IS NULL) <> (target_process_instance_id IS NULL)),
 CHECK(((status = 'RECEIVED' OR status = 'RETRYING') AND complete_time IS NULL)
    OR ((status = 'PROCESSED' OR status = 'DEAD_LETTER') AND complete_time IS NOT NULL))
);
