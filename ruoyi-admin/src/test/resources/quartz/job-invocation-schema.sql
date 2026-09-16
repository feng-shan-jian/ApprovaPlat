-- H2 adaptation of sys_job / sys_job_log in sql/ry_20260417.sql.
SET MODE MySQL;
SET BUILTIN_ALIAS_OVERRIDE TRUE;
-- Only the clock function differs; production Mapper SQL is loaded unchanged.
CREATE ALIAS SYSDATE FOR "com.ruoyi.quartz.JobInvocationTest.sysdate";
CREATE TABLE sys_job (
    job_id BIGINT AUTO_INCREMENT,
    job_name VARCHAR(64) DEFAULT '',
    job_group VARCHAR(64) DEFAULT 'DEFAULT',
    invoke_target VARCHAR(500) NOT NULL,
    cron_expression VARCHAR(255) DEFAULT '',
    misfire_policy VARCHAR(20) DEFAULT '3',
    concurrent CHAR(1) DEFAULT '1',
    status CHAR(1) DEFAULT '0',
    create_by VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP,
    update_by VARCHAR(64) DEFAULT '',
    update_time TIMESTAMP,
    remark VARCHAR(500) DEFAULT '',
    PRIMARY KEY (job_id, job_name, job_group)
);
CREATE TABLE sys_job_log (
    job_log_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_name VARCHAR(64) NOT NULL,
    job_group VARCHAR(64) NOT NULL,
    invoke_target VARCHAR(500) NOT NULL,
    job_message VARCHAR(500),
    status CHAR(1) DEFAULT '0',
    exception_info VARCHAR(2000) DEFAULT '',
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    create_time TIMESTAMP
);
