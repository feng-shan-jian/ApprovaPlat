package com.ruoyi.flowable.service.process;

import java.util.Map;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowMySqlTestDatabase;

/** 专用 MySQL 实例中的随机独占正式基线，复用全部引擎存储和读取事务判据。 */
class WorkflowProcessVariableProjectionMySqlIT extends WorkflowProcessVariableProjectionIntegrationTest
{
    private WorkflowMySqlTestDatabase database;

    @Override protected WorkflowFlowableEngineTestSupport startDatabase() throws Exception
    {
        database = new WorkflowMySqlTestDatabase("C03 variable projection");
        return WorkflowFlowableEngineTestSupport.startOnExistingDatabase(database.dataSource(), Map.of());
    }

    @Override protected void closeDatabase()
    {
        if (database != null) database.close();
    }
}
