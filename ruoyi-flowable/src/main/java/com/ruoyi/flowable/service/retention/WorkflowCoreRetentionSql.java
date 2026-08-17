package com.ruoyi.flowable.service.retention;

/**
 * 直接依赖 Flowable 历史终态或数据库级联关系的数据保留 SQL。
 */
final class WorkflowCoreRetentionSql
{
    static final String BPMN_EVENT_SELECT = "select audit.audit_id from wf_bpmn_event_audit audit "
            + "where audit.create_time<=? and exists (select 1 from ACT_HI_PROCINST history "
            + "where history.PROC_INST_ID_=audit.process_instance_id and history.END_TIME_ is not null) "
            + "order by audit.create_time,audit.audit_id limit ? for update skip locked";
    static final String BPMN_EVENT_DELETE_PREFIX = "delete from wf_bpmn_event_audit where audit_id in (";
    static final String BPMN_EVENT_DELETE_SUFFIX = ") and create_time<=? and exists "
            + "(select 1 from ACT_HI_PROCINST history where history.PROC_INST_ID_=wf_bpmn_event_audit.process_instance_id "
            + "and history.END_TIME_ is not null)";
    static final String BPMN_EVENT_OLDEST = "select min(audit.create_time) from wf_bpmn_event_audit audit "
            + "where exists (select 1 from ACT_HI_PROCINST history where history.PROC_INST_ID_=audit.process_instance_id "
            + "and history.END_TIME_ is not null)";

    static final String SLA_SELECT = "select sla_execution_id from wf_task_sla_execution "
            + "where status in ('COMPLETED','ESCALATED') and update_time<=? "
            + "order by update_time,sla_execution_id limit ? for update skip locked";
    static final String SLA_DELETE_PREFIX = "delete from wf_task_sla_execution where sla_execution_id in (";
    static final String SLA_DELETE_SUFFIX = ") and status in ('COMPLETED','ESCALATED') and update_time<=?";
    static final String SLA_OLDEST = "select min(update_time) from wf_task_sla_execution "
            + "where status in ('COMPLETED','ESCALATED')";

    static final String COPY_SELECT = "select copy_row.copy_id from wf_copy copy_row "
            + "where (copy_row.read_status='1' or copy_row.del_flag='2') and copy_row.create_time<=? "
            + "and exists (select 1 from ACT_HI_PROCINST history where history.PROC_INST_ID_=copy_row.instance_id "
            + "and history.END_TIME_ is not null) order by copy_row.create_time,copy_row.copy_id "
            + "limit ? for update skip locked";
    static final String COPY_DELETE_PREFIX = "delete from wf_copy where copy_id in (";
    static final String COPY_DELETE_SUFFIX = ") and (read_status='1' or del_flag='2') and create_time<=? "
            + "and exists (select 1 from ACT_HI_PROCINST history where history.PROC_INST_ID_=wf_copy.instance_id "
            + "and history.END_TIME_ is not null)";
    static final String COPY_OLDEST = "select min(copy_row.create_time) from wf_copy copy_row "
            + "where (copy_row.read_status='1' or copy_row.del_flag='2') and exists "
            + "(select 1 from ACT_HI_PROCINST history where history.PROC_INST_ID_=copy_row.instance_id "
            + "and history.END_TIME_ is not null)";

    static final String CONTROLLED_LOOP_SELECT = "select loop_row.execution_id from wf_controlled_loop_execution loop_row "
            + "where loop_row.create_time<=? and exists (select 1 from ACT_HI_PROCINST history "
            + "where history.PROC_INST_ID_=loop_row.process_instance_id and history.END_TIME_ is not null) "
            + "order by loop_row.create_time,loop_row.execution_id limit ? for update skip locked";
    static final String CONTROLLED_LOOP_DELETE_PREFIX = "delete from wf_controlled_loop_execution where execution_id in (";
    static final String CONTROLLED_LOOP_DELETE_SUFFIX = ") and create_time<=? and exists "
            + "(select 1 from ACT_HI_PROCINST history where history.PROC_INST_ID_=wf_controlled_loop_execution.process_instance_id "
            + "and history.END_TIME_ is not null)";
    static final String CONTROLLED_LOOP_OLDEST = "select min(loop_row.create_time) from wf_controlled_loop_execution loop_row "
            + "where exists (select 1 from ACT_HI_PROCINST history where history.PROC_INST_ID_=loop_row.process_instance_id "
            + "and history.END_TIME_ is not null)";

    /**
     * 禁止实例化固定 SQL 容器。
     * @return 无返回值，仅由 JVM 执行私有构造
     */
    private WorkflowCoreRetentionSql()
    {
    }
}
