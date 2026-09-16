-- 仅在全部写入口停机、已准备恢复旧应用且核实没有额外依赖该列/索引时执行。
-- 先移除配置索引，再以单次 ALTER 移除账号索引及派生列；不删除或改写业务行。
-- mysql 批处理执行，禁止 --force。失败保持停写，修复原因后重执行。
delimiter $$
drop procedure if exists c12_rollback_system_unique_keys$$
create procedure c12_rollback_system_unique_keys()
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
    if exists (select 1 from information_schema.statistics where table_schema = database()
        and table_name = 'sys_user' and column_name = 'active_user_name'
        and index_name <> 'uk_sys_user_active_name') then
        signal sqlstate '45000' set message_text = 'C12 BLOCKED: another index depends on active_user_name; inspect before rollback';
    end if;
    if config_index_count = 1 then
        alter table sys_config drop index uk_sys_config_key;
        set changed = true;
    end if;
    if user_index_count = 1 then
        alter table sys_user drop index uk_sys_user_active_name, drop column active_user_name;
        set changed = true;
    elseif projection_count = 1 then
        alter table sys_user drop column active_user_name;
        set changed = true;
    end if;
    select if(changed, 'C12 ROLLED_BACK', 'C12 ALREADY_ROLLED_BACK_VERIFIED') as migration_result;
end$$
call c12_rollback_system_unique_keys()$$
drop procedure c12_rollback_system_unique_keys$$
delimiter ;
