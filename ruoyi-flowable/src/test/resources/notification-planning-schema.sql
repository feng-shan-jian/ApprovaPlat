create table wf_notification_policy (
  policy_id bigint primary key, event_type varchar(64), scope_type varchar(16),
  process_definition_key varchar(255), task_definition_key varchar(255), status varchar(16),
  recipient_rules varchar(255), channels varchar(64), sms_template_id varchar(64),
  title_template varchar(255), content_template varchar(1000), max_attempts int
);
create table sys_user (user_id bigint primary key, status char(1), del_flag char(1));
create table wf_notification_preference (user_id bigint primary key, inbox_enabled int, email_enabled int, sms_enabled int);
create table ACT_HI_COMMENT (TASK_ID_ varchar(64), TYPE_ varchar(64));
create table ACT_RU_TASK (ID_ varchar(64), REV_ int);
insert into sys_user values (1, '0', '0'), (2, '0', '0'), (3, '1', '0');
insert into ACT_RU_TASK values ('task', 7);
