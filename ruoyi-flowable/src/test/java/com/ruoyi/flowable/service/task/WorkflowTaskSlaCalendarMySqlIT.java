package com.ruoyi.flowable.service.task;

import java.util.Map;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowMySqlTestDatabase;

/** 正式十四份 MySQL 基线、独占 schema，复用实际 timer / SLA / 必达通知与事务判据。 */
class WorkflowTaskSlaCalendarMySqlIT extends WorkflowTaskSlaCalendarIntegrationTest
{
    private WorkflowMySqlTestDatabase database;
    @Override protected WorkflowFlowableEngineTestSupport startDatabase() throws Exception
    {
        database = new WorkflowMySqlTestDatabase("C11 SLA calendar and timer lifecycle");
        return WorkflowFlowableEngineTestSupport.startOnExistingDatabase(database.dataSource(), Map.of());
    }
    @Override protected void prepareSchema() { }
    @Override protected void closeDatabase() { if (database != null) database.close(); }
}
