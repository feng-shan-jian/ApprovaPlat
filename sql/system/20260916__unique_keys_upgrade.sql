-- MySQL 8.0+。先执行 audit，停写全部账号/配置入口并备份；失败不得发布新应用。
-- mysql 批处理执行，禁止 --force。DDL 隐式提交；两张表不是一个原子 DDL。
-- 仅添加派生列和命名唯一索引，不修改原列排序规则或任何业务数据。
delimiter $$
drop procedure if exists c12_upgrade_system_unique_keys$$
create procedure c12_upgrade_system_unique_keys()
begin
    declare user_index_count int default 0;
    declare config_index_count int default 0;
    declare projection_count int default 0;
    declare source_charset varchar(64);
    declare source_collation varchar(64);
    declare source_length int;
    declare changed boolean default false;
    if database() is null or
       (select count(*) from information_schema.tables where table_schema = database()
        and table_name in ('sys_user','sys_config') and engine = 'InnoDB') <> 2 then
        signal sqlstate '45000' set message_text = 'C12 BLOCKED: select schema with both InnoDB system tables';
    end if;
    select character_set_name, collation_name, character_maximum_length
      into source_charset, source_collation, source_length
    from information_schema.columns where table_schema = database()
      and table_name = 'sys_user' and column_name = 'user_name' and data_type = 'varchar';
    if source_charset is null or source_length <> 30 then
        signal sqlstate '45000' set message_text = 'C12 BLOCKED: unsupported user_name definition';
    end if;
    select count(*) into projection_count from information_schema.columns
      where table_schema = database() and table_name = 'sys_user' and column_name = 'active_user_name';
    if projection_count = 1 and not exists (
        select 1 from information_schema.columns where table_schema = database()
        and table_name = 'sys_user' and column_name = 'active_user_name'
        and data_type = 'varchar' and character_maximum_length = source_length
        and character_set_name = source_charset and collation_name = source_collation
        and is_nullable = 'YES' and extra = 'VIRTUAL GENERATED'
        and regexp_replace(regexp_replace(lower(replace(generation_expression, concat(char(92), char(39)), char(39))),
                    '[[:space:]`()]+', ''), '_[a-z0-9]+''0''', '''0''') = 'casewhendel_flag=''0''thenuser_nameelsenullend'
    ) then
        signal sqlstate '45000' set message_text = 'C12 BLOCKED: active_user_name has an unexpected definition';
    end if;
    select count(*) into user_index_count from information_schema.statistics
      where table_schema = database() and table_name = 'sys_user' and index_name = 'uk_sys_user_active_name';
    if user_index_count > 0 and (user_index_count <> 1 or not exists (
        select 1 from information_schema.statistics where table_schema = database()
        and table_name = 'sys_user' and index_name = 'uk_sys_user_active_name'
        and non_unique = 0 and column_name = 'active_user_name' and sub_part is null
        and expression is null and index_type = 'BTREE' and is_visible = 'YES'
    )) then
        signal sqlstate '45000' set message_text = 'C12 BLOCKED: uk_sys_user_active_name has an unexpected definition';
    end if;
    select count(*) into config_index_count from information_schema.statistics
      where table_schema = database() and table_name = 'sys_config' and index_name = 'uk_sys_config_key';
    if config_index_count > 0 and (config_index_count <> 1 or not exists (
        select 1 from information_schema.statistics where table_schema = database()
        and table_name = 'sys_config' and index_name = 'uk_sys_config_key'
        and non_unique = 0 and column_name = 'config_key' and sub_part is null
        and expression is null and index_type = 'BTREE' and is_visible = 'YES'
    )) then
        signal sqlstate '45000' set message_text = 'C12 BLOCKED: uk_sys_config_key has an unexpected definition';
    end if;
    if exists (select 1 from sys_user where del_flag = '0' group by user_name having count(*) > 1)
       or exists (select 1 from sys_config where config_key is not null group by config_key having count(*) > 1) then
        signal sqlstate '45000' set message_text = 'C12 BLOCKED: duplicate rows; run unique_keys_audit.sql; no data was changed';
    end if;
    -- 其他命名的同业务唯一索引会抢先报错或限制软删除复用，不能静默接受。
    if exists (select 1 from information_schema.statistics where table_schema = database()
        and non_unique = 0 and index_name <> 'PRIMARY'
        and ((table_name = 'sys_user' and index_name <> 'uk_sys_user_active_name'
              and (column_name in ('user_name','active_user_name') or expression is not null))
          or (table_name = 'sys_config' and index_name <> 'uk_sys_config_key'
              and (column_name = 'config_key' or expression is not null)))) then
        signal sqlstate '45000' set message_text = 'C12 BLOCKED: another name/key unique index exists; inspect before migration';
    end if;
    if projection_count = 0 then
        set @c12_ddl = concat('alter table sys_user add column active_user_name varchar(30) character set ',
            source_charset, ' collate ', source_collation,
            ' generated always as (case when del_flag = ''0'' then user_name else null end) virtual,',
            ' add unique key uk_sys_user_active_name (active_user_name)');
        prepare c12_statement from @c12_ddl;
        execute c12_statement;
        deallocate prepare c12_statement;
        set @c12_ddl = null;
        set changed = true;
    elseif user_index_count = 0 then
        alter table sys_user add unique key uk_sys_user_active_name (active_user_name);
        set changed = true;
    end if;
    if config_index_count = 0 then
        alter table sys_config add unique key uk_sys_config_key (config_key);
        set changed = true;
    end if;
    select if(changed, 'C12 INSTALLED', 'C12 ALREADY_INSTALLED_VERIFIED') as migration_result;
end$$
call c12_upgrade_system_unique_keys()$$
drop procedure c12_upgrade_system_unique_keys$$
delimiter ;
