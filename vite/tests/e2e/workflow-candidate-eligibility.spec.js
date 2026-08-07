import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'
import {
  callWorkflowApi,
  cleanupWorkflowResources,
  closeWorkflowRoleSessions,
  createWorkflowCategory,
  createWorkflowForm,
  findAssignedWorkflowTask,
  findClaimableWorkflowTask,
  findStartableWorkflowDefinition,
  openWorkflowRoleSession,
  startWorkflowThroughUi
} from './support/workflow-fixture.js'

/**
 * 生成一个只配置静态候选角色的最小可执行流程。
 * @param {{processKey: string, processName: string, formId: string, candidateGroup: string}} input 流程、表单和规范候选组编码。
 * @returns {string} 带监听器和 BPMN DI 坐标的 UTF-8 XML 正文。
 */
function buildCandidateGroupBpmn({ processKey, processName, formId, candidateGroup }) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="http://ruoyi.example/workflow">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start" name="提交申请" flowable:formKey="key_${formId}" />
    <sequenceFlow id="flow_start_review" sourceRef="start" targetRef="candidateReview" />
    <userTask id="candidateReview" name="候选认领" flowable:candidateGroups="${candidateGroup}">
      <extensionElements>
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" />
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_review_end" sourceRef="candidateReview" targetRef="end" />
    <endEvent id="end" name="结束" />
  </process>
  <bpmndi:BPMNDiagram id="diagram_${processKey}">
    <bpmndi:BPMNPlane id="plane_${processKey}" bpmnElement="${processKey}">
      <bpmndi:BPMNShape id="shape_start" bpmnElement="start"><omgdc:Bounds x="100" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_review" bpmnElement="candidateReview"><omgdc:Bounds x="240" y="150" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_end" bpmnElement="end"><omgdc:Bounds x="440" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="edge_start_review" bpmnElement="flow_start_review"><omgdi:waypoint x="136" y="190" /><omgdi:waypoint x="240" y="190" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="edge_review_end" bpmnElement="flow_review_end"><omgdi:waypoint x="340" y="190" /><omgdi:waypoint x="440" y="190" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 通过正式查询接口确认被拒绝部署的流程没有留下部署、定义、实例或任务数据。
 * @param {{designer: import('@playwright/test').Page, admin: import('@playwright/test').Page}} pages 设计者和管理员真实登录页面。
 * @param {string} processKey 本用例唯一流程标识。
 * @returns {Promise<void>} 正式列表均不存在目标流程记录后结束。
 */
async function expectNoWorkflowResidues(pages, processKey) {
  const query = { processKey, pageNum: 1, pageSize: 200 }
  const [deployments, definitions, instances, assignedTasks, claimableTasks, completedTasks] =
    await Promise.all([
      callWorkflowApi(pages.designer, 'GET', '/workflow/deploy/list', { query }),
      callWorkflowApi(pages.designer, 'GET', '/workflow/deploy/publishList', {
        query
      }),
      callWorkflowApi(pages.admin, 'GET', '/workflow/process/manageList', {
        query
      }),
      callWorkflowApi(pages.admin, 'GET', '/workflow/process/todoList', {
        query
      }),
      callWorkflowApi(pages.admin, 'GET', '/workflow/process/claimList', {
        query
      }),
      callWorkflowApi(pages.admin, 'GET', '/workflow/process/finishedList', {
        query
      })
    ])

  // 唯一 processKey 由正式接口服务端过滤，total=0 同时覆盖当前页之外的潜在残留。
  expect(deployments.total, '拒绝或清理后不得残留目标流程部署').toBe(0)
  expect(definitions.total, '拒绝或清理后不得残留目标流程定义').toBe(0)
  expect(instances.total, '拒绝或清理后不得残留目标流程实例').toBe(0)
  const taskTotal =
    Number(assignedTasks.total) + Number(claimableTasks.total) + Number(completedTasks.total)
  expect(taskTotal, '拒绝或清理后不得残留目标流程活动或历史任务').toBe(0)
}

/**
 * 在负向断言失败时回查并登记意外创建的部署和实例，保证 finally 仍能按依赖顺序清理正式数据。
 * @param {{designer: import('@playwright/test').Page, admin: import('@playwright/test').Page}} pages 设计者和管理员真实登录页面。
 * @param {string} processKey 本用例唯一流程标识。
 * @param {{processInstanceIds: string[], deploymentIds: string[]}} resources finally 清理使用的正式资源登记簿。
 * @returns {Promise<void>} 意外资源主键全部写入清理登记簿后结束。
 */
async function registerUnexpectedWorkflowResources(pages, processKey, resources) {
  const query = { processKey, pageNum: 1, pageSize: 200 }
  const [deployments, instances] = await Promise.all([
    callWorkflowApi(pages.designer, 'GET', '/workflow/deploy/list', { query }),
    callWorkflowApi(pages.admin, 'GET', '/workflow/process/manageList', { query })
  ])

  // 只登记当前唯一流程标识，避免测试环境中其他并行用例的资源进入本用例清理范围。
  for (const row of deployments.rows || []) {
    const deploymentId = String(row.deploymentId || '')
    if (
      row.processKey === processKey &&
      deploymentId &&
      !resources.deploymentIds.includes(deploymentId)
    ) {
      resources.deploymentIds.push(deploymentId)
    }
  }
  for (const row of instances.rows || []) {
    const processInstanceId = String(row.processInstanceId || '')
    if (
      row.processKey === processKey &&
      processInstanceId &&
      !resources.processInstanceIds.includes(processInstanceId)
    ) {
      resources.processInstanceIds.push(processInstanceId)
    }
  }
}

/**
 * 验证无完整认领成员的有效角色在保存门禁被阻断，而合格候选角色可真实发起并认领任务。
 * @param {{browser: import('@playwright/test').Browser}} fixtures Playwright 浏览器夹具。
 * @returns {Promise<void>} 负向保存、正向认领和清理后零残留断言全部通过后结束。
 */
test('候选组阻断无人可办配置并允许合格角色真实认领', async ({ browser }) => {
  test.setTimeout(180_000)
  const runId = `p3dead_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  const processKey = `p3dead_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`
  // 负向模型使用独立主键和流程标识，避免全局防重提交将随后的正向部署误判为重复请求。
  const rejectedProcessKey = `${processKey}_rejected`
  const residueProcessKeys = [rejectedProcessKey, processKey]
  const resources = {
    processInstanceIds: [],
    deploymentIds: [],
    modelIds: [],
    formId: '',
    categoryId: ''
  }
  const sessions = []
  const pages = {}
  let primaryError = null

  try {
    for (const roleKey of [
      'workflow_designer',
      'workflow_starter',
      'workflow_approver',
      'workflow_admin'
    ]) {
      const session = await openWorkflowRoleSession(browser, roleKey)
      sessions.push(session)
      pages[roleKey.replace('workflow_', '')] = session.page
    }

    const roleDirectory = await callWorkflowApi(
      pages.designer,
      'GET',
      '/workflow/identity/options',
      {
        query: {
          type: 'role',
          keyword: 'workflow_starter',
          pageNum: 1,
          pageSize: 20
        }
      }
    )
    const starterRoles = (roleDirectory.rows || []).filter(
      option => option.type === 'role' && /^ROLE[1-9]\d*$/.test(String(option.value || ''))
    )
    expect(starterRoles, '设计器通用身份目录必须唯一返回发起人角色').toHaveLength(1)
    const candidateGroup = String(starterRoles[0].value)

    const claimRoleDirectory = await callWorkflowApi(
      pages.designer,
      'GET',
      '/workflow/identity/options',
      {
        query: {
          type: 'role',
          capability: 'claim',
          keyword: 'workflow_starter',
          pageNum: 1,
          pageSize: 20
        }
      }
    )
    const claimableStarterRoles = (claimRoleDirectory.rows || []).filter(
      option => option.type === 'role' && String(option.value || '') === candidateGroup
    )
    expect(claimableStarterRoles, '没有完整认领成员的发起人角色不得进入候选角色目录').toHaveLength(
      0
    )

    const claimApproverDirectory = await callWorkflowApi(
      pages.designer,
      'GET',
      '/workflow/identity/options',
      {
        query: {
          type: 'role',
          capability: 'claim',
          keyword: 'workflow_approver',
          pageNum: 1,
          pageSize: 20
        }
      }
    )
    const claimableApproverRoles = (claimApproverDirectory.rows || []).filter(
      option => option.type === 'role' && /^ROLE[1-9]\d*$/.test(String(option.value || ''))
    )
    expect(claimableApproverRoles, '审批人角色必须作为唯一合格候选角色返回').toHaveLength(1)
    const claimableCandidateGroup = String(claimableApproverRoles[0].value)

    const categoryCode = `p3dead_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`
    const formName = `P3候选资格表单-${runId}`
    const processName = `P3候选资格-${runId}`
    const rejectedProcessName = `${processName}-无资格`
    resources.categoryId = await createWorkflowCategory(
      pages.designer,
      processName,
      categoryCode,
      resources
    )
    resources.formId = await createWorkflowForm(pages.designer, formName, resources)

    const rejectedCreated = await callWorkflowApi(pages.designer, 'POST', '/workflow/model', {
      data: {
        modelName: rejectedProcessName,
        modelKey: rejectedProcessKey,
        category: categoryCode,
        description: '候选角色完整认领资格负向回归',
        formType: 0,
        formId: Number(resources.formId)
      }
    })
    const rejectedModelId = String(rejectedCreated.data?.modelId || '')
    expect(rejectedModelId, '负向回归模型创建必须返回正式主键').not.toBe('')
    // 模型创建成功后立即登记，后续保存门禁断言失败时仍由 finally 删除正式半成品。
    resources.modelIds.push(rejectedModelId)
    await callWorkflowApi(pages.designer, 'POST', '/workflow/model/save', {
      expectedCode: 409,
      data: {
        requestId: randomUUID(),
        modelId: rejectedModelId,
        bpmnXml: buildCandidateGroupBpmn({
          processKey: rejectedProcessKey,
          processName: rejectedProcessName,
          formId: resources.formId,
          candidateGroup
        }),
        newVersion: false
      }
    })
    await expectNoWorkflowResidues(pages, rejectedProcessKey)

    // 正向模型使用不同 modelId，证明资格门禁不会阻断可真实认领的正常任务。
    const created = await callWorkflowApi(pages.designer, 'POST', '/workflow/model', {
      data: {
        modelName: processName,
        modelKey: processKey,
        category: categoryCode,
        description: '候选角色完整认领资格正向回归',
        formType: 0,
        formId: Number(resources.formId)
      }
    })
    const modelId = String(created.data?.modelId || '')
    expect(modelId, '正向回归模型创建必须返回正式主键').not.toBe('')
    resources.modelIds.push(modelId)
    await callWorkflowApi(pages.designer, 'POST', '/workflow/model/save', {
      data: {
        requestId: randomUUID(),
        modelId,
        bpmnXml: buildCandidateGroupBpmn({
          processKey,
          processName,
          formId: resources.formId,
          candidateGroup: claimableCandidateGroup
        }),
        newVersion: false
      }
    })
    const deployed = await callWorkflowApi(pages.designer, 'POST', '/workflow/model/deploy', {
      query: { modelId }
    })
    const deploymentId = String(deployed.data?.deploymentId || '')
    expect(deploymentId, '合格候选角色模型部署必须返回正式主键').not.toBe('')
    resources.deploymentIds.push(deploymentId)

    const definition = await findStartableWorkflowDefinition(pages.starter, processKey)
    expect(definition.deploymentId, '可发起定义必须来自本次合格候选部署').toBe(deploymentId)
    const processInstanceId = await startWorkflowThroughUi(
      pages.starter,
      definition,
      formName,
      `BUS-${runId}`,
      `候选资格正向对照-${runId}`,
      resources.processInstanceIds
    )
    const candidateTask = await findClaimableWorkflowTask(
      pages.approver,
      processKey,
      'candidateReview',
      processInstanceId
    )
    await callWorkflowApi(pages.approver, 'POST', '/workflow/task/claim', {
      data: { taskId: candidateTask.taskId }
    })
    const assignedTask = await findAssignedWorkflowTask(
      pages.approver,
      processKey,
      'candidateReview',
      processInstanceId
    )
    expect(String(assignedTask.taskId), '真实认领后待办任务主键必须保持不变').toBe(
      String(candidateTask.taskId)
    )
  } catch (error) {
    primaryError = error
  } finally {
    const discoveryErrors = []
    if (primaryError && pages.designer && pages.admin) {
      for (const residueProcessKey of residueProcessKeys) {
        try {
          await registerUnexpectedWorkflowResources(pages, residueProcessKey, resources)
        } catch (error) {
          discoveryErrors.push(
            `流程 ${residueProcessKey} 意外资源回查失败：${error.message || error}`
          )
        }
      }
    }
    const cleanupErrors = await cleanupWorkflowResources(pages, resources)
    let residueError = null
    if (pages.designer && pages.admin) {
      for (const residueProcessKey of residueProcessKeys) {
        try {
          await expectNoWorkflowResidues(pages, residueProcessKey)
        } catch (error) {
          if (residueError) {
            residueError.message += ` | 流程 ${residueProcessKey}：${error.message || error}`
          } else {
            residueError = error
          }
        }
      }
    }
    const sessionErrors = await closeWorkflowRoleSessions(sessions)
    const finalErrors = [
      ...discoveryErrors,
      ...cleanupErrors,
      ...(residueError ? [`零残留复核失败：${residueError.message || residueError}`] : []),
      ...sessionErrors
    ]
    if (primaryError) {
      if (finalErrors.length) primaryError.message += `；收尾失败：${finalErrors.join(' | ')}`
      throw primaryError
    }
    if (residueError) {
      if (cleanupErrors.length || sessionErrors.length) {
        residueError.message += `；收尾异常：${[...cleanupErrors, ...sessionErrors].join(' | ')}`
      }
      throw residueError
    }
    expect(finalErrors, '候选资格夹具和登录态必须全部清理').toEqual([])
  }
})
