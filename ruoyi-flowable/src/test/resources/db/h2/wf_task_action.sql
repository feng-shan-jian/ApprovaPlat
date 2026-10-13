-- Only identity, task-copy and Inbox collaborators used by task actions.
-- Lock and MySQL dialect contracts run against the formal empty-database baseline.
CREATE TABLE sys_user (user_id BIGINT PRIMARY KEY, dept_id BIGINT, user_name VARCHAR(64), nick_name VARCHAR(64), status CHAR(1), del_flag CHAR(1));
CREATE TABLE sys_dept (dept_id BIGINT PRIMARY KEY, dept_name VARCHAR(64), status CHAR(1), del_flag CHAR(1));
CREATE TABLE sys_role (role_id BIGINT PRIMARY KEY, role_name VARCHAR(64), role_key VARCHAR(64), role_sort INT, status CHAR(1), del_flag CHAR(1));
CREATE TABLE sys_user_role (user_id BIGINT, role_id BIGINT, PRIMARY KEY(user_id, role_id));
CREATE TABLE sys_menu (menu_id BIGINT PRIMARY KEY, menu_name VARCHAR(64), perms VARCHAR(100), menu_type CHAR(1), status CHAR(1));
CREATE TABLE sys_role_menu (role_id BIGINT, menu_id BIGINT, PRIMARY KEY(role_id, menu_id));
CREATE TABLE wf_copy (
 copy_id BIGINT AUTO_INCREMENT PRIMARY KEY, copy_event_id VARCHAR(128) NOT NULL,
 title VARCHAR(255), process_id VARCHAR(64), process_name VARCHAR(255), category_id VARCHAR(64),
 deployment_id VARCHAR(64), instance_id VARCHAR(64), task_id VARCHAR(64), user_id BIGINT,
 originator_id BIGINT, originator_name VARCHAR(64), source_type VARCHAR(16), trigger_type VARCHAR(32),
 trigger_node_id VARCHAR(64), trigger_node_name VARCHAR(255), read_status CHAR(1), read_time TIMESTAMP(3),
 create_by VARCHAR(64), create_time TIMESTAMP(3), update_by VARCHAR(64), update_time TIMESTAMP(3),
 remark VARCHAR(255), del_flag CHAR(1), UNIQUE(copy_event_id, user_id));
CREATE TABLE wf_notification_policy (
 policy_id BIGINT AUTO_INCREMENT PRIMARY KEY, scope_type VARCHAR(16), process_definition_key VARCHAR(255),
 task_definition_key VARCHAR(255), event_type VARCHAR(40), recipient_rules VARCHAR(128), channels VARCHAR(32),
 sms_template_id VARCHAR(64), title_template VARCHAR(160), content_template VARCHAR(700), max_attempts INT,
 status VARCHAR(16));
CREATE TABLE wf_notification_preference (user_id BIGINT PRIMARY KEY, inbox_enabled INT, email_enabled INT, sms_enabled INT);
CREATE TABLE wf_notification_inbox (
 notification_id BIGINT AUTO_INCREMENT PRIMARY KEY, notification_key VARCHAR(64) NOT NULL,
 source_type VARCHAR(16), source_id VARCHAR(191), recipient_user_id BIGINT, event_type VARCHAR(40),
 title VARCHAR(160), content VARCHAR(700), process_instance_id VARCHAR(64), task_id VARCHAR(64),
 route_path VARCHAR(500), read_status VARCHAR(16), create_time TIMESTAMP(3), read_time TIMESTAMP(3),
 UNIQUE(notification_key, recipient_user_id));
CREATE TABLE wf_notification_outbox (
 outbox_id BIGINT AUTO_INCREMENT PRIMARY KEY, idempotency_key VARCHAR(64), source_type VARCHAR(16),
 source_id VARCHAR(191), event_type VARCHAR(40), channel VARCHAR(16), recipient_user_id BIGINT,
 process_definition_key VARCHAR(255), process_instance_id VARCHAR(64), task_id VARCHAR(64), task_definition_key VARCHAR(255),
 actor_user_id VARCHAR(64), title VARCHAR(160), content VARCHAR(700), sms_template_id VARCHAR(64), route_path VARCHAR(500),
 status VARCHAR(16), delivery_cycle INT DEFAULT 1, attempt_count INT, total_attempt_count INT DEFAULT 0,
 max_attempts INT, next_attempt_at TIMESTAMP(3), lease_owner VARCHAR(128), lease_expires_at TIMESTAMP(3),
 last_error_code VARCHAR(64), last_error_summary VARCHAR(500), revision INT, create_time TIMESTAMP(3), processed_time TIMESTAMP(3),
 UNIQUE(idempotency_key));
CREATE ALIAS SHA2 FOR 'com.ruoyi.flowable.service.task.WorkflowTaskActionTransactionTest.sha2';
