-- 只读清点：必须在目标数据库执行；按原列排序规则分组，不归一化、不修复数据。
start transaction read only;
select version() as mysql_version, database() as target_database;
select table_name, column_name, column_type, is_nullable, character_set_name, collation_name,
       extra, generation_expression
from information_schema.columns
where table_schema = database() and table_name in ('sys_user', 'sys_config')
  and column_name in ('user_name', 'del_flag', 'active_user_name', 'config_key');
select table_name, index_name, non_unique, seq_in_index, column_name, sub_part, expression
from information_schema.statistics
where table_schema = database() and table_name in ('sys_user', 'sys_config')
order by table_name, index_name, seq_in_index;
select count(*) as active_user_rows from sys_user where del_flag = '0';
select count(*) as config_rows from sys_config;
-- 输出每个冲突成员，避免 GROUP_CONCAT 长度截断。停用账号仍参与有效账号唯一性。
select u.user_id, u.user_name, hex(u.user_name) as user_name_hex, u.status, u.del_flag
from sys_user u
join (select user_name from sys_user where del_flag = '0'
      group by user_name having count(*) > 1) conflicts on conflicts.user_name = u.user_name
where u.del_flag = '0' order by u.user_name, u.user_id;
select c.config_id, c.config_key, hex(c.config_key) as config_key_hex
from sys_config c
join (select config_key from sys_config where config_key is not null
      group by config_key having count(*) > 1) conflicts on conflicts.config_key = c.config_key
order by c.config_key, c.config_id;
rollback;
