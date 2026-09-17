-- H2 dialect fixture. MySQL IT uses the unmodified fourteen production baseline scripts.
create table wf_business_calendar (
  calendar_id bigint auto_increment primary key,
  calendar_key varchar(64) not null unique,
  calendar_name varchar(128) not null,
  timezone varchar(64) not null,
  working_days varchar(13) not null,
  work_start time not null,
  work_end time not null,
  status varchar(16) not null,
  description varchar(500),
  create_by varchar(64) not null,
  create_time timestamp(3) not null,
  update_by varchar(64) not null,
  update_time timestamp(3),
  check (work_start < work_end),
  -- H2 2.4 constant IN sets retain the DDL connection; REGEXP keeps the same allowed values.
  check (status regexp '^(ENABLED|DISABLED)$')
);
create table wf_business_calendar_day (
  calendar_id bigint not null references wf_business_calendar(calendar_id) on delete cascade,
  calendar_date date not null,
  working_day boolean not null,
  day_name varchar(128),
  primary key (calendar_id, calendar_date)
);
-- Only the real notification writer's user columns are needed in this H2 fixture.
create table sys_user (
  user_id bigint primary key,
  user_name varchar(30),
  nick_name varchar(30),
  status char(1) not null,
  del_flag char(1) not null
);
create table wf_notification_inbox (
  notification_id bigint auto_increment primary key,
  notification_key char(64) not null,
  source_type varchar(16) not null,
  source_id varchar(191) not null,
  recipient_user_id bigint not null references sys_user(user_id),
  event_type varchar(40) not null,
  title varchar(160) not null,
  content varchar(700) not null,
  process_instance_id varchar(64) not null,
  task_id varchar(64),
  route_path varchar(500) not null,
  read_status varchar(16) not null,
  create_time timestamp(3) not null,
  read_time timestamp(3),
  unique (notification_key, recipient_user_id)
);
create alias date_format for 'com.ruoyi.flowable.service.task.WorkflowTaskSlaCalendarIntegrationTest.formatTime';
create alias sha2 for 'com.ruoyi.flowable.service.task.WorkflowTaskSlaCalendarIntegrationTest.sha2';
