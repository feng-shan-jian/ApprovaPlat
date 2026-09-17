package com.ruoyi.flowable.service.task;

import org.springframework.test.context.ContextConfiguration;

/** 相同真实业务场景在专用 MySQL、正式 Mapper 和实际 InnoDB 事务上重放。 */
@ContextConfiguration(classes = WorkflowMultiInstanceMySqlHarness.class, inheritLocations = false)
class WorkflowMultiInstanceGroupReturnMySqlIT extends WorkflowMultiInstanceGroupReturnIntegrationTest { }

@ContextConfiguration(classes = WorkflowMultiInstanceMySqlHarness.class, inheritLocations = false)
class WorkflowMultiInstanceGroupResubmitMySqlIT extends WorkflowMultiInstanceGroupResubmitIntegrationTest { }

@ContextConfiguration(classes = WorkflowMultiInstanceMySqlHarness.class, inheritLocations = false)
class WorkflowMultiInstanceGroupRollbackMySqlIT extends WorkflowMultiInstanceGroupRollbackIntegrationTest { }

@ContextConfiguration(classes = WorkflowMultiInstanceMySqlHarness.class, inheritLocations = false)
class WorkflowMultiInstanceGroupConcurrencyMySqlIT extends WorkflowMultiInstanceGroupConcurrencyIntegrationTest { }

@ContextConfiguration(classes = WorkflowMultiInstanceMySqlHarness.class, inheritLocations = false)
class WorkflowMultiInstanceReturnedTerminationMySqlIT extends WorkflowMultiInstanceReturnedTerminationIntegrationTest { }

@ContextConfiguration(classes = WorkflowMultiInstanceMySqlHarness.class, inheritLocations = false)
class WorkflowMultiInstanceContinuousReturnMySqlIT extends WorkflowMultiInstanceContinuousReturnIntegrationTest { }

@ContextConfiguration(classes = WorkflowMultiInstanceMySqlHarness.class, inheritLocations = false)
class WorkflowProcessTerminalStateMySqlIT extends WorkflowProcessTerminalStateIntegrationTest { }
