import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'
import { DOMParser } from '@xmldom/xmldom'
import { expectAjaxSuccess, matchesEndpoint } from './support/http.js'
import {
  callWorkflowApi,
  cleanupWorkflowResources,
  closeWorkflowRoleSessions,
  createWorkflowCategory,
  findAssignedWorkflowTask,
  findStartableWorkflowDefinition,
  findWorkflowUserOption,
  openWorkflowRoleSession
} from './support/workflow-fixture.js'

test.describe.configure({ mode: 'serial' })

/**
 * 创建条件路由使用的正式表单并从真实列表回查主键。
 * @param {import('@playwright/test').Page} page 流程设计者真实登录页面。
 * @param {string} formName 本次运行唯一表单名称。
 * @param {{formId?: string}} resources finally 清理使用的正式资源登记簿。
 * @returns {Promise<string>} 已真实持久化的表单主键。
 */
async function createConditionForm(page, formName, resources) {
  const content = JSON.stringify({
    fields: [
      {
        type: 'text', placeholder: '请输入申请主题', clearable: true,
        __config__: { label: '申请主题', tag: 'el-input', span: 24, required: true, regList: [], layout: 'colFormItem' },
        __vModel__: 'requestTitle'
      },
      {
        placeholder: '请输入申请金额', min: 0, max: 1000000, step: 0.01, precision: 2,
        __config__: { label: '申请金额', tag: 'el-input-number', span: 24, required: false, regList: [], layout: 'colFormItem' },
        __vModel__: 'amount'
      },
      {
        placeholder: '请选择申请等级', clearable: true, filterable: false, multiple: false,
        __config__: { label: '申请等级', tag: 'el-select', span: 24, required: false, workflowWritable: true, workflowEnum: true, regList: [], layout: 'colFormItem' },
        __slot__: { options: [{ label: '普通', value: 'NORMAL' }, { label: '高级', value: 'HIGH' }] },
        __vModel__: 'level'
      },
      {
        __config__: { label: '是否紧急', tag: 'el-switch', span: 24, required: false, defaultValue: false, regList: [], layout: 'colFormItem' },
        __vModel__: 'urgent'
      },
      {
        type: 'textarea', rows: 3, placeholder: '请输入申请说明', maxlength: 300,
        __config__: { label: '申请说明', tag: 'el-input', span: 24, required: false, regList: [], layout: 'colFormItem' },
        __vModel__: 'description'
      }
    ],
    size: 'default', labelPosition: 'right', labelWidth: 100,
    gutter: 15, disabled: false, span: 24, formBtns: true
  })
  const created = await callWorkflowApi(page, 'POST', '/workflow/form', {
    data: { formName, content, remark: 'P0 条件分支真实浏览器验收' }
  })
  const formId = String(created.data?.formId || '')
  // 主键一旦返回立即登记，后续任何断言失败都必须清理正式表单。
  if (formId) resources.formId = formId
  expect(formId, '条件路由表单创建必须返回正式主键').not.toBe('')
  const listed = await callWorkflowApi(page, 'GET', '/workflow/form/list', {
    query: { formName, pageNum: 1, pageSize: 20 }
  })
  const rows = (listed.rows || []).filter(row => row.formName === formName)
  expect(rows, '条件路由表单必须从正式列表唯一回查').toHaveLength(1)
  expect(String(rows[0].formId)).toBe(formId)
  expect(rows[0].content, '设计器表单目录必须携带可解析的正式正文').toBe(content)
  return formId
}

/**
 * 生成不含任意表达式或受控条件属性的排他网关作者拓扑。
 * @param {{processKey:string,processName:string,formId:string,approverUserId:string}} input 流程、表单和办理人主键。
 * @returns {string} 带完整 BPMN DI 的 UTF-8 XML。
 */
function buildExclusiveDesignerBpmn({ processKey, processName, formId, approverUserId }) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="https://approvaplat.example/condition-e2e">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start" name="提交申请" flowable:formKey="key_${formId}" />
    <sequenceFlow id="flowStartIntake" sourceRef="start" targetRef="intake" />
    <userTask id="intake" name="条件预审" flowable:assignee="${approverUserId}"><extensionElements><flowable:taskListener event="create" delegateExpression="\${userTaskListener}" /><flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" /><flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" /></extensionElements></userTask>
    <sequenceFlow id="flowIntakeGateway" sourceRef="intake" targetRef="exclusiveRoute" />
    <exclusiveGateway id="exclusiveRoute" name="申请分流" />
    <sequenceFlow id="flowHigh" sourceRef="exclusiveRoute" targetRef="highReview" />
    <sequenceFlow id="flowText" sourceRef="exclusiveRoute" targetRef="textReview" />
    <sequenceFlow id="flowDefault" sourceRef="exclusiveRoute" targetRef="defaultReview" />
    <userTask id="highReview" name="大额紧急审批" flowable:assignee="${approverUserId}"><extensionElements><flowable:taskListener event="create" delegateExpression="\${userTaskListener}" /><flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" /><flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" /></extensionElements></userTask>
    <userTask id="textReview" name="重点说明审批" flowable:assignee="${approverUserId}"><extensionElements><flowable:taskListener event="create" delegateExpression="\${userTaskListener}" /><flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" /><flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" /></extensionElements></userTask>
    <userTask id="defaultReview" name="普通审批" flowable:assignee="${approverUserId}"><extensionElements><flowable:taskListener event="create" delegateExpression="\${userTaskListener}" /><flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" /><flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" /></extensionElements></userTask>
    <sequenceFlow id="flowHighEnd" sourceRef="highReview" targetRef="end" /><sequenceFlow id="flowTextEnd" sourceRef="textReview" targetRef="end" /><sequenceFlow id="flowDefaultEnd" sourceRef="defaultReview" targetRef="end" />
    <endEvent id="end" name="结束" />
  </process>
  <bpmndi:BPMNDiagram id="diagram_${processKey}"><bpmndi:BPMNPlane id="plane_${processKey}" bpmnElement="${processKey}">
    <bpmndi:BPMNShape id="shapeStart" bpmnElement="start"><omgdc:Bounds x="60" y="232" width="36" height="36" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shapeIntake" bpmnElement="intake"><omgdc:Bounds x="150" y="210" width="100" height="80" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shapeGateway" bpmnElement="exclusiveRoute" isMarkerVisible="true"><omgdc:Bounds x="310" y="225" width="50" height="50" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shapeHigh" bpmnElement="highReview"><omgdc:Bounds x="430" y="80" width="110" height="80" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shapeText" bpmnElement="textReview"><omgdc:Bounds x="430" y="210" width="110" height="80" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shapeDefault" bpmnElement="defaultReview"><omgdc:Bounds x="430" y="340" width="110" height="80" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shapeEnd" bpmnElement="end"><omgdc:Bounds x="650" y="232" width="36" height="36" /></bpmndi:BPMNShape>
    <bpmndi:BPMNEdge id="edgeStartIntake" bpmnElement="flowStartIntake"><omgdi:waypoint x="96" y="250" /><omgdi:waypoint x="150" y="250" /></bpmndi:BPMNEdge>
    <bpmndi:BPMNEdge id="edgeIntakeGateway" bpmnElement="flowIntakeGateway"><omgdi:waypoint x="250" y="250" /><omgdi:waypoint x="310" y="250" /></bpmndi:BPMNEdge>
    <bpmndi:BPMNEdge id="edgeHigh" bpmnElement="flowHigh"><omgdi:waypoint x="335" y="225" /><omgdi:waypoint x="335" y="120" /><omgdi:waypoint x="430" y="120" /></bpmndi:BPMNEdge>
    <bpmndi:BPMNEdge id="edgeText" bpmnElement="flowText"><omgdi:waypoint x="360" y="250" /><omgdi:waypoint x="430" y="250" /></bpmndi:BPMNEdge>
    <bpmndi:BPMNEdge id="edgeDefault" bpmnElement="flowDefault"><omgdi:waypoint x="335" y="275" /><omgdi:waypoint x="335" y="380" /><omgdi:waypoint x="430" y="380" /></bpmndi:BPMNEdge>
    <bpmndi:BPMNEdge id="edgeHighEnd" bpmnElement="flowHighEnd"><omgdi:waypoint x="540" y="120" /><omgdi:waypoint x="668" y="120" /><omgdi:waypoint x="668" y="232" /></bpmndi:BPMNEdge>
    <bpmndi:BPMNEdge id="edgeTextEnd" bpmnElement="flowTextEnd"><omgdi:waypoint x="540" y="250" /><omgdi:waypoint x="650" y="250" /></bpmndi:BPMNEdge>
    <bpmndi:BPMNEdge id="edgeDefaultEnd" bpmnElement="flowDefaultEnd"><omgdi:waypoint x="540" y="380" /><omgdi:waypoint x="668" y="380" /><omgdi:waypoint x="668" y="268" /></bpmndi:BPMNEdge>
  </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 生成不含条件配置的包容网关作者拓扑，用于验证多个真实分支同时命中。
 * @param {{processKey:string,processName:string,formId:string,approverUserId:string}} input 流程、表单和办理人主键。
 * @returns {string} 带完整 BPMN DI 的 UTF-8 XML。
 */
function buildInclusiveDesignerBpmn({ processKey, processName, formId, approverUserId }) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="https://approvaplat.example/condition-e2e">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start" name="提交申请" flowable:formKey="key_${formId}" /><sequenceFlow id="flowStartRoute" sourceRef="start" targetRef="inclusiveRoute" />
    <inclusiveGateway id="inclusiveRoute" name="并行条件分流" />
    <sequenceFlow id="flowAmount" sourceRef="inclusiveRoute" targetRef="amountReview" /><sequenceFlow id="flowUrgent" sourceRef="inclusiveRoute" targetRef="urgentReview" /><sequenceFlow id="flowInclusiveDefault" sourceRef="inclusiveRoute" targetRef="inclusiveDefaultReview" />
    <userTask id="amountReview" name="金额复核" flowable:assignee="${approverUserId}"><extensionElements><flowable:taskListener event="create" delegateExpression="\${userTaskListener}" /></extensionElements></userTask>
    <userTask id="urgentReview" name="紧急复核" flowable:assignee="${approverUserId}"><extensionElements><flowable:taskListener event="create" delegateExpression="\${userTaskListener}" /></extensionElements></userTask>
    <userTask id="inclusiveDefaultReview" name="包容默认复核" flowable:assignee="${approverUserId}"><extensionElements><flowable:taskListener event="create" delegateExpression="\${userTaskListener}" /></extensionElements></userTask>
  </process>
  <bpmndi:BPMNDiagram id="diagram_${processKey}"><bpmndi:BPMNPlane id="plane_${processKey}" bpmnElement="${processKey}">
    <bpmndi:BPMNShape id="shapeStart" bpmnElement="start"><omgdc:Bounds x="70" y="232" width="36" height="36" /></bpmndi:BPMNShape><bpmndi:BPMNShape id="shapeRoute" bpmnElement="inclusiveRoute"><omgdc:Bounds x="180" y="225" width="50" height="50" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shapeAmount" bpmnElement="amountReview"><omgdc:Bounds x="340" y="80" width="110" height="80" /></bpmndi:BPMNShape><bpmndi:BPMNShape id="shapeUrgent" bpmnElement="urgentReview"><omgdc:Bounds x="340" y="210" width="110" height="80" /></bpmndi:BPMNShape><bpmndi:BPMNShape id="shapeDefault" bpmnElement="inclusiveDefaultReview"><omgdc:Bounds x="340" y="340" width="110" height="80" /></bpmndi:BPMNShape>
    <bpmndi:BPMNEdge id="edgeStartRoute" bpmnElement="flowStartRoute"><omgdi:waypoint x="106" y="250" /><omgdi:waypoint x="180" y="250" /></bpmndi:BPMNEdge><bpmndi:BPMNEdge id="edgeAmount" bpmnElement="flowAmount"><omgdi:waypoint x="205" y="225" /><omgdi:waypoint x="205" y="120" /><omgdi:waypoint x="340" y="120" /></bpmndi:BPMNEdge><bpmndi:BPMNEdge id="edgeUrgent" bpmnElement="flowUrgent"><omgdi:waypoint x="230" y="250" /><omgdi:waypoint x="340" y="250" /></bpmndi:BPMNEdge><bpmndi:BPMNEdge id="edgeDefault" bpmnElement="flowInclusiveDefault"><omgdi:waypoint x="205" y="275" /><omgdi:waypoint x="205" y="380" /><omgdi:waypoint x="340" y="380" /></bpmndi:BPMNEdge>
  </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 在 Element Plus 下拉中按可访问名称选择唯一选项。
 * @param {import('@playwright/test').Page} page 当前设计器页面。
 * @param {string} accessibleName 下拉输入的 aria-label。
 * @param {string} optionLabel 需要选择的可见选项文案。
 * @returns {Promise<void>} 真实下拉完成选择并关闭后结束。
 */
async function selectEditorOption(page, accessibleName, optionLabel) {
  const input = page.getByRole('combobox', { name: accessibleName })
  const select = input.locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " el-select ")][1]')
  await select.locator('.el-select__wrapper').click()
  await expect(input, `${accessibleName} 必须真实展开`).toHaveAttribute('aria-expanded', 'true')
  const listboxId = await input.getAttribute('aria-controls')
  expect(listboxId, `${accessibleName} 必须关联真实 listbox`).toBeTruthy()
  // Element Plus 会把菜单 Teleport 到页面根部，字段 option 的可访问名称还会附带类型标签。
  const escapedLabel = optionLabel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const options = page.getByRole('option').filter({ hasText: new RegExp(`^\\s*${escapedLabel}`) })
  await expect(options, `${accessibleName} 必须存在唯一选项 ${optionLabel}`).toHaveCount(1)
  await options.click()
}

/**
 * 点击 bpmn-js 为图形元素提供的真实命中层，并等待导入遮罩完全退出。
 * @param {import('@playwright/test').Page} page 当前设计器页面。
 * @param {string} elementId BPMN 元素稳定标识。
 * @returns {Promise<void>} 选择事件已进入 bpmn-js eventBus 后结束。
 */
async function selectDiagramElement(page, elementId) {
  await expect(page.locator('.process-designer .el-loading-mask:visible'), '设计器加载遮罩必须完全退出').toHaveCount(0)
  await expect(page.locator('.el-overlay:visible'), '设计器对话框遮罩必须完全退出').toHaveCount(0)
  const element = page.locator(`.djs-element[data-element-id="${elementId}"]`)
  await expect(element, `BPMN 图元 ${elementId} 必须唯一挂载`).toHaveCount(1)
  // 连线的可交互几何由透明 .djs-hit 路径承载；从多个路径位置选择未被 lint 标记遮挡的屏幕坐标。
  const hit = element.locator('.djs-hit')
  if (await hit.count()) {
    const point = await hit.evaluate(path => {
      const matrix = path.getScreenCTM()
      if (!matrix) throw new Error('BPMN 连线缺少屏幕坐标矩阵')
      const totalLength = path.getTotalLength()
      const candidates = [0.2, 0.5, 0.8].map(ratio => {
        const local = path.getPointAtLength(totalLength * ratio)
        const screen = new DOMPoint(local.x, local.y).matrixTransform(matrix)
        const hitIndex = document.elementsFromPoint(screen.x, screen.y).indexOf(path)
        return { x: screen.x, y: screen.y, hitIndex: hitIndex < 0 ? Number.MAX_SAFE_INTEGER : hitIndex }
      })
      return candidates.sort((left, right) => left.hitIndex - right.hitIndex)[0]
    })
    // 用真实鼠标事件走完 bpmn-js 指针链路，避免直接 dispatchEvent 偶发丢失选择状态。
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      await page.mouse.click(point.x, point.y)
      try {
        await expect(element).toHaveClass(/selected/, { timeout: 1000 })
        return
      } catch {
        await expect(page.locator('.process-designer .el-loading-mask:visible')).toHaveCount(0)
        await expect(page.locator('.el-overlay:visible')).toHaveCount(0)
      }
    }
    const hitStack = await page.evaluate(({ x, y }) => document.elementsFromPoint(x, y).slice(0, 6)
      .map(node => `${node.tagName}.${node.getAttribute('class') || ''}`), point)
    throw new Error(`BPMN 连线 ${elementId} 必须通过真实鼠标选中，坐标 ${point.x},${point.y}，命中：${hitStack.join(' > ')}`)
  } else {
    await expect(element).toBeVisible()
    await element.click()
  }
}

/**
 * 通过真实属性面板为当前选中顺序流创建或更新一组类型化 AND 规则。
 * @param {import('@playwright/test').Page} page 已打开模型设计器的页面。
 * @param {string} flowId 当前顺序流 BPMN 标识。
 * @param {string} branchName 分支可见名称。
 * @param {Array<{fieldLabel:string,operatorLabel:string,valueLabel?:string,value?:string|number}>} rules 规则字段、运算符和值。
 * @returns {Promise<void>} 规则应用到 bpmn-js 命令栈后结束。
 */
async function configureRuleBranch(page, flowId, branchName, rules) {
  await selectDiagramElement(page, flowId)
  const editor = page.locator('.condition-editor')
  await expect(editor).toBeVisible()
  await editor.getByPlaceholder('例如：金额超过 5000 元').fill(branchName)
  for (let index = 1; index <= rules.length; index += 1) {
    const fieldRows = editor.getByRole('combobox', { name: /^规则组 1 字段 \d+$/ })
    // 保存重开会回显既有规则，仅在目标行尚不存在时通过真实按钮扩充。
    if (await fieldRows.count() < index) {
      await editor.getByRole('button', { name: '添加规则', exact: true }).click()
    }
    const rule = rules[index - 1]
    await selectEditorOption(page, `规则组 1 字段 ${index}`, rule.fieldLabel)
    await selectEditorOption(page, `规则组 1 运算符 ${index}`, rule.operatorLabel)
    if (rule.valueLabel !== undefined) {
      await selectEditorOption(page, `规则组 1 条件值 ${index}`, rule.valueLabel)
    } else if (typeof rule.value === 'number') {
      await editor.getByRole('spinbutton', { name: `规则组 1 数值 ${index}` }).fill(String(rule.value))
    } else {
      await editor.getByRole('textbox', { name: `规则组 1 文本值 ${index}` }).fill(String(rule.value ?? ''))
    }
  }
  await editor.getByRole('button', { name: '应用规则', exact: true }).click()
  await expect(editor.getByText(branchName, { exact: true })).toBeVisible()
}

/**
 * 通过真实属性面板把当前顺序流设为唯一默认分支并保存名称。
 * @param {import('@playwright/test').Page} page 已打开模型设计器的页面。
 * @param {string} flowId 当前顺序流 BPMN 标识。
 * @param {string} branchName 默认分支名称。
 * @returns {Promise<void>} 网关 default 引用和固定默认配置均写入命令栈后结束。
 */
async function configureDefaultBranch(page, flowId, branchName) {
  await selectDiagramElement(page, flowId)
  const editor = page.locator('.condition-editor')
  await expect(editor).toBeVisible()
  await editor.getByRole('button', { name: '设为默认分支', exact: true }).click()
  await expect(editor.getByText('默认分支', { exact: true }).last()).toBeVisible()
  await editor.getByPlaceholder('例如：金额超过 5000 元').fill(branchName)
  await editor.getByRole('button', { name: '保存分支名称', exact: true }).click()
  await expect(editor.getByText('默认分支已设置', { exact: true })).toBeVisible()
}

/**
 * 从设计器 XML 预览读取当前真实 bpmn-js 作者资源。
 * @param {import('@playwright/test').Page} page 已打开设计器的页面。
 * @returns {Promise<string>} 当前作者 XML 正文。
 */
async function readDesignerXml(page) {
  await page.getByRole('button', { name: '预览流程源码' }).click()
  await page.getByRole('menuitem', { name: 'XML 预览' }).click()
  const dialog = page.getByRole('dialog', { name: 'XML 预览' })
  await expect(dialog).toBeVisible()
  const xml = await dialog.getByRole('textbox').inputValue()
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  return xml
}

/**
 * 从 bpmn-js 作者 XML 的目标顺序流读取平台受控条件配置。
 * @param {string} xml 当前设计器导出的完整 BPMN XML。
 * @param {string} flowId 顺序流稳定 BPMN 标识。
 * @returns {object} 解析后的 approva.conditionRule.config JSON。
 */
function readAuthorConditionConfig(xml, flowId) {
  const document = new DOMParser().parseFromString(xml, 'application/xml')
  const flows = [...document.getElementsByTagNameNS('*', 'sequenceFlow')]
  const flow = flows.find(element => element.getAttribute('id') === flowId)
  expect(flow, `作者 XML 必须包含顺序流 ${flowId}`).toBeTruthy()
  const properties = [...flow.getElementsByTagNameNS('*', 'property')]
  const property = properties.find(element => element.getAttribute('name') === 'approva.conditionRule.config')
  expect(property, `顺序流 ${flowId} 必须保存受控条件属性`).toBeTruthy()
  return JSON.parse(property.getAttribute('value'))
}

/**
 * 保存设计器当前作者 XML，并返回后端实际持久化的模型版本主键。
 * @param {import('@playwright/test').Page} page 已打开设计器的页面。
 * @returns {Promise<string>} 保存 API 返回的真实模型主键。
 */
async function saveDesigner(page) {
  const validationPromise = page.waitForResponse(response => matchesEndpoint(response, '/workflow/model/validate', 'POST'))
  const savePromise = page.waitForResponse(response => matchesEndpoint(response, '/workflow/model/save', 'POST'))
  await page.getByRole('button', { name: '保存', exact: true }).click()
  const validation = await expectAjaxSuccess(await validationPromise, '/workflow/model/validate')
  expect(validation.data?.valid, JSON.stringify(validation.data?.issues || [])).toBe(true)
  const saved = await expectAjaxSuccess(await savePromise, '/workflow/model/save')
  const modelId = String(saved.data?.modelId || '')
  expect(modelId, '设计器保存必须返回真实模型主键').not.toBe('')
  return modelId
}

/**
 * 通过真实发起页填写四种类型字段并提交，支持同步双击幂等验证。
 * @param {import('@playwright/test').Page} page 流程发起人页面。
 * @param {{definitionId:string,deploymentId:string}} definition 可发起定义和部署关系。
 * @param {string} formName 部署快照表单名称。
 * @param {{businessKey:string,title:string,amount?:number,level?:string,urgent?:boolean,description?:string,doubleClick?:boolean}} values 表单值与提交模式。
 * @param {string[]} registry finally 清理使用的实例主键集合。
 * @returns {Promise<string>} 正式流程实例主键。
 */
async function startConditionThroughUi(page, definition, formName, values, registry) {
  await page.goto(`/workflow/process-start/${encodeURIComponent(definition.definitionId)}?deploymentId=${encodeURIComponent(definition.deploymentId)}`)
  await expect(page.getByRole('heading', { name: formName })).toBeVisible()
  await page.getByPlaceholder('可选').fill(values.businessKey)
  await page.getByPlaceholder('请输入申请主题').fill(values.title)
  if (values.amount !== undefined) {
    await page.locator('.el-form-item').filter({ hasText: '申请金额' }).getByRole('spinbutton').fill(String(values.amount))
  }
  if (values.level !== undefined) {
    const item = page.locator('.el-form-item').filter({ hasText: '申请等级' })
    await item.locator('.el-select__wrapper').click()
    await page.locator('.el-select-dropdown:visible').getByText(values.level === 'HIGH' ? '高级' : '普通', { exact: true }).click()
  }
  const urgentItem = page.locator('.el-form-item').filter({ hasText: '是否紧急' })
  const urgentInput = urgentItem.getByRole('switch')
  // Element Plus 的语义 input 被视觉隐藏，真实用户实际点击可见开关容器。
  if (Boolean(values.urgent) !== ((await urgentInput.getAttribute('aria-checked')) === 'true')) {
    await urgentItem.locator('.el-switch').click()
  }
  if (values.description !== undefined) await page.getByPlaceholder('请输入申请说明').fill(values.description)

  const endpoint = `/workflow/process/start/${encodeURIComponent(definition.definitionId)}`
  let requestCount = 0
  const countRequest = request => {
    const pathname = new URL(request.url()).pathname
    if (pathname.endsWith(endpoint) && request.method() === 'POST') requestCount += 1
  }
  page.on('request', countRequest)
  try {
    const responsePromise = page.waitForResponse(response => matchesEndpoint(response, endpoint, 'POST'))
    const submit = page.getByRole('button', { name: '提交申请', exact: true })
    if (values.doubleClick) await submit.evaluate(button => { button.click(); button.click() })
    else await submit.click()
    const payload = await expectAjaxSuccess(await responsePromise, endpoint)
    const processInstanceId = String(payload.data?.id || payload.data?.processInstanceId || payload.data?.procInsId || '')
    if (processInstanceId) registry.push(processInstanceId)
    expect(processInstanceId, '发起响应必须返回正式实例主键').not.toBe('')
    await expect(page).toHaveURL(/\/workflow\/process-detail\/[^/?]+/)
    if (values.doubleClick) {
      await page.waitForTimeout(500)
      expect(requestCount, '同步双击只能产生一个正式发起请求').toBe(1)
    }
    return processInstanceId
  } finally {
    page.off('request', countRequest)
  }
}

/**
 * 完成排他网关前的预审任务，并按期望业务码校验事务结果。
 * @param {import('@playwright/test').Page} page 真实办理人页面。
 * @param {string} taskId 当前预审任务主键。
 * @param {number} expectedCode 预期 AjaxResult 业务码。
 * @returns {Promise<void>} 完成请求返回预期业务码后结束。
 */
async function completeIntake(page, taskId, expectedCode = 200) {
  await callWorkflowApi(page, 'POST', '/workflow/task/complete', {
    expectedCode,
    data: { taskId, comment: '条件分支预审完成', variables: {}, copyUserIds: [], nextUserIds: [] }
  })
}

test('条件规则由设计器配置并在真实 MySQL 流程中安全路由', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  const sessions = []
  const pages = {}
  const resources = { categoryId: '', formId: '', modelIds: [], deploymentIds: [], processInstanceIds: [] }
  const evidence = []
  try {
    for (const roleKey of ['workflow_designer', 'workflow_starter', 'workflow_approver', 'workflow_admin']) {
      const session = await openWorkflowRoleSession(browser, roleKey)
      sessions.push(session)
      pages[roleKey.replace('workflow_', '')] = session.page
    }
    const approver = await findWorkflowUserOption(pages.designer, 'workflow_approver')
    expect(approver, '条件路由必须取得真实审批办理人').toBeTruthy()
    const suffix = randomUUID().replaceAll('-', '').slice(0, 12)
    const categoryCode = `condition_${suffix}`
    const formName = `条件路由表单-${suffix}`
    const exclusiveKey = `conditionExclusive${suffix}`
    const inclusiveKey = `conditionInclusive${suffix}`
    await createWorkflowCategory(pages.designer, `条件路由分类-${suffix}`, categoryCode, resources)
    const formId = await createConditionForm(pages.designer, formName, resources)

    const exclusiveCreated = await callWorkflowApi(pages.designer, 'POST', '/workflow/model', {
      data: { modelName: `排他条件-${suffix}`, modelKey: exclusiveKey, category: categoryCode, description: '排他条件真实浏览器验收', formType: 0, formId: Number(formId) }
    })
    const exclusiveModelId = String(exclusiveCreated.data?.modelId || '')
    expect(exclusiveModelId).not.toBe('')
    resources.modelIds.push(exclusiveModelId)
    await pages.designer.goto(`/workflow/model-design/${exclusiveModelId}`)
    await expect(pages.designer.locator('.process-designer__canvas')).toBeVisible()
    const exclusiveAuthorXml = buildExclusiveDesignerBpmn({ processKey: exclusiveKey, processName: `排他条件-${suffix}`, formId, approverUserId: String(approver.value) })
    expect(exclusiveAuthorXml).not.toContain('conditionExpression')
    expect(exclusiveAuthorXml).not.toContain('approva.conditionRule.config')
    await pages.designer.locator('input.process-designer__file-input').setInputFiles({ name: `${exclusiveKey}.bpmn`, mimeType: 'application/xml', buffer: Buffer.from(exclusiveAuthorXml, 'utf8') })
    const importedXml = await readDesignerXml(pages.designer)
    expect(importedXml, '导入后必须保留正式表单稳定主键').toContain(`flowable:formKey="key_${formId}"`)
    await configureRuleBranch(pages.designer, 'flowHigh', '大额高级紧急采购', [
      { fieldLabel: '申请金额（amount）', operatorLabel: '大于等于', value: 5000 },
      { fieldLabel: '申请等级（level）', operatorLabel: '等于', valueLabel: '高级' },
      { fieldLabel: '是否紧急（urgent）', operatorLabel: '等于', valueLabel: '是' },
      { fieldLabel: '申请说明（description）', operatorLabel: '包含', value: '采购' }
    ])
    await configureRuleBranch(pages.designer, 'flowText', '重点说明', [
      { fieldLabel: '申请说明（description）', operatorLabel: '开头是', value: '重点' }
    ])
    await configureDefaultBranch(pages.designer, 'flowDefault', '普通默认')
    const configuredV1Xml = await readDesignerXml(pages.designer)
    expect(configuredV1Xml).toContain('approva.conditionRule.config')
    expect(configuredV1Xml).not.toContain('conditionExpression')
    await saveDesigner(pages.designer)

    // 保存重开必须从正式模型资源恢复结构化规则和唯一默认状态。
    await pages.designer.goto(`/workflow/model-design/${exclusiveModelId}`)
    await selectDiagramElement(pages.designer, 'flowHigh')
    await expect(pages.designer.locator('.condition-editor')).toContainText('大额高级紧急采购')
    await selectDiagramElement(pages.designer, 'flowDefault')
    await expect(pages.designer.locator('.condition-editor')).toContainText('默认分支已设置')

    await callWorkflowApi(pages.starter, 'POST', '/workflow/model/save', {
      expectedCode: 403,
      data: { requestId: randomUUID(), modelId: exclusiveModelId, bpmnXml: configuredV1Xml, newVersion: false }
    })
    const exclusiveV1Deploy = await callWorkflowApi(pages.designer, 'POST', '/workflow/model/deploy', { query: { modelId: exclusiveModelId } })
    const exclusiveV1DeploymentId = String(exclusiveV1Deploy.data?.deploymentId || '')
    expect(exclusiveV1DeploymentId).not.toBe('')
    resources.deploymentIds.push(exclusiveV1DeploymentId)
    const exclusiveV1Definition = await findStartableWorkflowDefinition(pages.starter, exclusiveKey)
    expect(exclusiveV1Definition.deploymentId).toBe(exclusiveV1DeploymentId)

    const scenarios = [
      { name: 'high', target: 'highReview', values: { amount: 6000, level: 'HIGH', urgent: true, description: '采购电脑' } },
      { name: 'text', target: 'textReview', values: { amount: 100, level: 'NORMAL', urgent: false, description: '重点关注合同' } },
      { name: 'default', target: 'defaultReview', values: { amount: 100, level: 'NORMAL', urgent: false, description: '普通申请' } },
      { name: 'double', target: 'defaultReview', values: { amount: 50, level: 'NORMAL', urgent: false, description: '双击验证', doubleClick: true } },
      { name: 'conflict', target: null, values: { amount: 6000, level: 'HIGH', urgent: true, description: '重点采购' }, expectedCode: 409 },
      { name: 'missing', target: null, values: { level: 'HIGH', urgent: true, description: '采购但缺少金额' }, expectedCode: 409 },
      { name: 'freeze', target: 'highReview', values: { amount: 6000, level: 'HIGH', urgent: true, description: '采购冻结版本' }, defer: true }
    ]
    for (const scenario of scenarios) {
      const businessKey = `COND-${suffix}-${scenario.name}`
      const processInstanceId = await startConditionThroughUi(pages.starter, exclusiveV1Definition, formName, {
        businessKey, title: `条件场景-${scenario.name}`, ...scenario.values
      }, resources.processInstanceIds)
      const intake = await findAssignedWorkflowTask(pages.approver, exclusiveKey, 'intake', processInstanceId)
      scenario.processInstanceId = processInstanceId
      scenario.intakeTaskId = String(intake.taskId)
      if (scenario.defer) continue
      await completeIntake(pages.approver, intake.taskId, scenario.expectedCode || 200)
      if (scenario.target) {
        await findAssignedWorkflowTask(pages.approver, exclusiveKey, scenario.target, processInstanceId)
      } else {
        const rolledBack = await findAssignedWorkflowTask(pages.approver, exclusiveKey, 'intake', processInstanceId)
        expect(String(rolledBack.taskId), `${scenario.name} 失败必须回滚到同一预审任务`).toBe(String(intake.taskId))
      }
      evidence.push({ scenario: scenario.name, target: scenario.target, code: scenario.expectedCode || 200 })
    }

    const invalidBusinessKey = `COND-${suffix}-type-error`
    await callWorkflowApi(pages.starter, 'POST', `/workflow/process/start/${encodeURIComponent(exclusiveV1Definition.definitionId)}`, {
      expectedCode: 400,
      data: { businessKey: invalidBusinessKey, variables: { requestTitle: '类型错误', amount: '6000', level: 'HIGH', urgent: true, description: '采购' } }
    })
    const ownAfterTypeError = await callWorkflowApi(pages.starter, 'GET', '/workflow/process/ownList', {
      query: { processKey: exclusiveKey, businessKey: invalidBusinessKey, pageNum: 1, pageSize: 20 }
    })
    expect((ownAfterTypeError.rows || []).filter(row => row.businessKey === invalidBusinessKey), '类型错误不得创建部分实例').toHaveLength(0)

    // V1 已部署后重新通过同一规则编辑器提高金额阈值，保存会创建并返回真实 V2 模型。
    await pages.designer.goto(`/workflow/model-design/${exclusiveModelId}`)
    await configureRuleBranch(pages.designer, 'flowHigh', '超大额高级紧急采购', [
      { fieldLabel: '申请金额（amount）', operatorLabel: '大于等于', value: 10000 },
      { fieldLabel: '申请等级（level）', operatorLabel: '等于', valueLabel: '高级' },
      { fieldLabel: '是否紧急（urgent）', operatorLabel: '等于', valueLabel: '是' },
      { fieldLabel: '申请说明（description）', operatorLabel: '包含', value: '采购' }
    ])
    const exclusiveV2ModelId = await saveDesigner(pages.designer)
    expect(exclusiveV2ModelId).not.toBe(exclusiveModelId)
    resources.modelIds.push(exclusiveV2ModelId)
    const exclusiveV2Deploy = await callWorkflowApi(pages.designer, 'POST', '/workflow/model/deploy', { query: { modelId: exclusiveV2ModelId } })
    const exclusiveV2DeploymentId = String(exclusiveV2Deploy.data?.deploymentId || '')
    expect(exclusiveV2DeploymentId).not.toBe('')
    resources.deploymentIds.push(exclusiveV2DeploymentId)
    const exclusiveV2Definition = await findStartableWorkflowDefinition(pages.starter, exclusiveKey)
    expect(exclusiveV2Definition.definitionId).not.toBe(exclusiveV1Definition.definitionId)

    const frozen = scenarios.find(scenario => scenario.name === 'freeze')
    await completeIntake(pages.approver, frozen.intakeTaskId)
    await findAssignedWorkflowTask(pages.approver, exclusiveKey, 'highReview', frozen.processInstanceId)
    const v2DefaultInstance = await startConditionThroughUi(pages.starter, exclusiveV2Definition, formName, {
      businessKey: `COND-${suffix}-v2`, title: 'V2 阈值', amount: 6000, level: 'HIGH', urgent: true, description: '采购冻结版本'
    }, resources.processInstanceIds)
    const v2Intake = await findAssignedWorkflowTask(pages.approver, exclusiveKey, 'intake', v2DefaultInstance)
    await completeIntake(pages.approver, v2Intake.taskId)
    await findAssignedWorkflowTask(pages.approver, exclusiveKey, 'defaultReview', v2DefaultInstance)
    evidence.push({ scenario: 'version-freeze', v1Target: 'highReview', v2Target: 'defaultReview' })

    const inclusiveCreated = await callWorkflowApi(pages.designer, 'POST', '/workflow/model', {
      data: { modelName: `包容条件-${suffix}`, modelKey: inclusiveKey, category: categoryCode, description: '包容条件真实浏览器验收', formType: 0, formId: Number(formId) }
    })
    const inclusiveModelId = String(inclusiveCreated.data?.modelId || '')
    expect(inclusiveModelId).not.toBe('')
    resources.modelIds.push(inclusiveModelId)
    await pages.designer.goto(`/workflow/model-design/${inclusiveModelId}`)
    const inclusiveAuthorXml = buildInclusiveDesignerBpmn({ processKey: inclusiveKey, processName: `包容条件-${suffix}`, formId, approverUserId: String(approver.value) })
    await pages.designer.locator('input.process-designer__file-input').setInputFiles({ name: `${inclusiveKey}.bpmn`, mimeType: 'application/xml', buffer: Buffer.from(inclusiveAuthorXml, 'utf8') })
    await configureRuleBranch(pages.designer, 'flowAmount', '金额达到五千', [{ fieldLabel: '申请金额（amount）', operatorLabel: '大于等于', value: 5000 }])
    await configureRuleBranch(pages.designer, 'flowUrgent', '紧急申请', [{ fieldLabel: '是否紧急（urgent）', operatorLabel: '等于', valueLabel: '是' }])
    await configureDefaultBranch(pages.designer, 'flowInclusiveDefault', '包容默认')
    const inclusiveConfiguredXml = await readDesignerXml(pages.designer)
    const amountRules = readAuthorConditionConfig(inclusiveConfiguredXml, 'flowAmount').groups[0].rules
    const urgentRules = readAuthorConditionConfig(inclusiveConfiguredXml, 'flowUrgent').groups[0].rules
    expect(amountRules, JSON.stringify({ amountRules, urgentRules })).toEqual([
      { field: 'amount', operator: 'GTE', value: 5000 }
    ])
    expect(urgentRules, JSON.stringify({ amountRules, urgentRules })).toEqual([
      { field: 'urgent', operator: 'EQ', value: true }
    ])
    await saveDesigner(pages.designer)
    const inclusiveDeploy = await callWorkflowApi(pages.designer, 'POST', '/workflow/model/deploy', { query: { modelId: inclusiveModelId } })
    const inclusiveDeploymentId = String(inclusiveDeploy.data?.deploymentId || '')
    expect(inclusiveDeploymentId).not.toBe('')
    resources.deploymentIds.push(inclusiveDeploymentId)
    const inclusiveDefinition = await findStartableWorkflowDefinition(pages.starter, inclusiveKey)
    const inclusiveInstance = await startConditionThroughUi(pages.starter, inclusiveDefinition, formName, {
      businessKey: `COND-${suffix}-inclusive`, title: '包容多命中', amount: 8000, level: 'NORMAL', urgent: true, description: '包容路由'
    }, resources.processInstanceIds)
    await findAssignedWorkflowTask(pages.approver, inclusiveKey, 'amountReview', inclusiveInstance)
    await findAssignedWorkflowTask(pages.approver, inclusiveKey, 'urgentReview', inclusiveInstance)
    const inclusiveDefaultList = await callWorkflowApi(pages.approver, 'GET', '/workflow/process/todoList', { query: { processKey: inclusiveKey, pageNum: 1, pageSize: 100 } })
    expect((inclusiveDefaultList.rows || []).filter(row => row.taskDefinitionKey === 'inclusiveDefaultReview' && String(row.processInstanceId) === inclusiveInstance), '包容多命中时不得同时进入默认分支').toHaveLength(0)
    evidence.push({ scenario: 'inclusive-multi-match', targets: ['amountReview', 'urgentReview'] })

    await testInfo.attach('workflow-condition-routing-evidence.json', {
      body: Buffer.from(JSON.stringify({ suffix, evidence }, null, 2)),
      contentType: 'application/json'
    })
  } finally {
    const cleanupErrors = await cleanupWorkflowResources({ admin: pages.admin, designer: pages.designer }, resources)
    const sessionErrors = await closeWorkflowRoleSessions(sessions)
    expect([...cleanupErrors, ...sessionErrors], '条件分支 E2E 清理和会话注销必须成功').toEqual([])
  }
})
