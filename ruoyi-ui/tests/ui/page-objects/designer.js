import { expect } from '@playwright/test'
import { DOMParser } from '@xmldom/xmldom'
import { expectAjaxSuccess, matchesEndpoint } from '../../e2e/support/http.js'

export class WorkflowDesignerPage {
  /**
   * 创建 BPMN 设计器页面对象。
   * @param {import('@playwright/test').Page} page 已进入真实模型设计器的浏览器页面。
   */
  constructor(page) {
    this.page = page
    this.properties = page.locator('.designer-properties-panel')
  }

  /**
   * 通过开始节点属性面板绑定正式发起表单。
   * @param {string} formName 已由 UI 创建的正式表单名称。
   * @returns {Promise<void>} `flowable:formKey` 经 bpmn-js 命令栈写入后结束。
   */
  async configureStartForm(formName) {
    await this.page.locator('[data-element-id="start"]').click()
    await expect(this.properties.getByText('开始节点', { exact: true })).toBeVisible()
    const formLabel = this.properties.locator('.el-form-item__label').filter({ hasText: '发起表单' })
    await expect(formLabel, '开始节点发起表单标签必须唯一').toHaveCount(1)
    const formSelect = formLabel.locator('..').locator('.el-select')
    await formSelect.click()
    await this.page.getByRole('option', { name: formName, exact: true }).click()
  }

  /**
   * 通过指定 Element Plus combobox 关联的唯一弹层选择选项。
   * @param {import('@playwright/test').Locator} input 带稳定 aria-label 的下拉输入框。
   * @param {string} optionLabel 需要选择的可见选项文案。
   * @param {string} description 断言失败时使用的业务控件说明。
   * @returns {Promise<void>} 选项写入组件且当前弹层关闭后结束。
   */
  async selectLinkedOption(input, optionLabel, description) {
    await expect(input, `${description}必须唯一`).toHaveCount(1)
    const select = input.locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " el-select ")][1]')
    const wrapper = select.locator('.el-select__wrapper')
    await wrapper.click()
    await expect(input, `${description}必须真实展开`).toHaveAttribute('aria-expanded', 'true')
    const listboxId = await input.getAttribute('aria-controls')
    expect(listboxId, `${description}必须关联唯一选项列表`).toBeTruthy()
    const listbox = this.page.locator(`[id="${listboxId}"]`)
    await expect(listbox, `${description}关联的选项列表必须可见`).toBeVisible()
    const option = listbox.getByRole('option', { name: optionLabel, exact: true })
    await expect(option, `${description}必须包含选项 ${optionLabel}`).toHaveCount(1)
    await option.click()

    // 选择会触发字段策略原子重建，必须重新通过可访问标签取得当前组件再核对最终状态。
    await expect(input, `${description}选择完成后必须关闭`).toHaveAttribute('aria-expanded', 'false')
    await expect(select.locator('.el-select__selected-item').getByText(optionLabel, { exact: true }),
      `${description}必须回显 ${optionLabel}`).toBeVisible()
    await expect(listbox, `${description}选择完成后关联选项列表必须隐藏`).toBeHidden()
  }

  /**
   * 通过节点属性面板绑定正式表单并配置批量默认及逐字段权限。
   * @param {{elementId:string,formName:string,defaultMode:'隐藏'|'只读'|'可编辑'|'必填',fieldModes:Record<string,'隐藏'|'只读'|'可编辑'|'必填'>}} options BPMN 节点、正式表单和字段权限策略。
   * @returns {Promise<void>} 表单引用和完整权限策略经 bpmn-js 命令栈写入后结束。
   */
  async configureFormPermissionsForElement(options) {
    const { elementId, formName, defaultMode, fieldModes } = options
    await this.selectCanvasShape(elementId)

    const nodeFormLabel = elementId === 'start' ? '发起表单' : '节点表单'
    const formLabels = this.properties.locator('.el-form-item__label').filter({ hasText: nodeFormLabel })
    await expect(formLabels, `${nodeFormLabel}标签必须唯一`).toHaveCount(1)
    const formSelect = formLabels.first().locator('..').locator('.el-select')
    await formSelect.click()
    await this.page.getByRole('option', { name: formName, exact: true }).click()
    await expect(this.properties.locator('.designer-properties-panel__context code'),
      '绑定表单后属性面板必须仍属于目标节点').toHaveText(elementId)

    const editor = this.properties.getByRole('region', { name: '节点字段权限' })
    await expect(editor).toBeVisible()
    const batchPermission = editor.getByLabel('批量默认字段权限', { exact: true })
    await this.selectLinkedOption(batchPermission, defaultMode, '批量默认字段权限选择器')
    await editor.getByRole('button', { name: '应用', exact: true }).click()
    await expect(editor.getByText(`默认 ${defaultMode}`, { exact: true }), '批量默认字段权限必须完成应用').toBeVisible()
    for (const [fieldLabel, mode] of Object.entries(fieldModes)) {
      // 逐字段覆盖必须使用用户可见标签，避免依赖生成器内部变量或组件实例。
      const fieldRow = editor.locator('.form-field-permission-editor__field').filter({ hasText: fieldLabel })
      await expect(fieldRow, `${fieldLabel}字段权限行必须唯一`).toHaveCount(1)
      const fieldPermission = fieldRow.getByLabel(`${fieldLabel}字段权限`, { exact: true })
      await this.selectLinkedOption(fieldPermission, mode, `${fieldLabel}字段权限选择器`)
    }
  }

  /**
   * 通过用户任务属性面板配置受控整改循环。
   * @param {{elementId:string,maxIterations:number,decisionFieldLabel:string,repeatLabel:string,exitLabel:string}} options 目标任务、轮次上限和判断值。
   * @returns {Promise<void>} 受控循环配置进入 bpmn-js 命令栈并在画布回显后结束。
   */
  async configureControlledApprovalLoop(options) {
    const { elementId, maxIterations, decisionFieldLabel, repeatLabel, exitLabel } = options
    await this.selectCanvasShape(elementId)
    const execution = this.properties.locator('.el-collapse-item').filter({ hasText: '执行配置' })
    await expect(execution, '用户任务必须提供唯一执行配置面板').toHaveCount(1)
    if (!await execution.locator('.el-collapse-item__wrap').isVisible().catch(() => false)) {
      await execution.locator('.el-collapse-item__header').click()
    }

    const selectByLabel = async (label, option) => {
      const item = execution.locator('.el-form-item').filter({ hasText: label })
      await expect(item, `执行配置 ${label} 必须唯一`).toHaveCount(1)
      const input = item.getByRole('combobox')
      await this.selectLinkedOption(input, option, `执行配置 ${label}`)
    }
    await selectByLabel('循环方式', '整改循环（受控）')
    const maxInput = execution.locator('.el-form-item').filter({ hasText: '最大办理轮次' }).getByRole('spinbutton')
    await maxInput.fill(String(maxIterations))
    await selectByLabel('循环判断字段', decisionFieldLabel)
    await selectByLabel('再次进入条件', repeatLabel)
    await selectByLabel('退出条件', exitLabel)
    const applyButton = execution.getByRole('button', { name: '应用整改循环配置', exact: true })
    await expect(applyButton, '完整受控循环配置必须允许应用').toBeEnabled()
    await applyButton.click()
    await expect(this.page.getByText(`整改循环 · 最多 ${maxIterations} 轮`, { exact: true }),
      '画布必须回显受控循环配置').toBeVisible()
  }

  /**
   * 通过属性检查器把默认用户任务配置为候选角色审批。
   * @param {string} roleName 正式角色显示名称。
   * @param {string} taskName 用户任务名称。
   * @returns {Promise<void>} BPMN 命令栈完成任务名称和办理规则更新后结束。
   */
  async configureCandidateRole(roleName, taskName) {
    await this.configureCandidateRoleForElement('review', roleName, taskName)
  }

  /**
   * 删除画布上的指定 BPMN 元素及其关联连线。
   * @param {string} elementId 当前画布元素标识。
   * @returns {Promise<void>} 元素经键盘删除命令移出画布后结束。
   */
  async deleteElement(elementId) {
    const element = this.page.locator(`.djs-shape[data-element-id="${elementId}"]`)
    await expect(element, `画布元素 ${elementId} 必须存在`).toHaveCount(1)
    await element.click()
    await this.page.keyboard.press('Delete')
    await expect(element, `画布元素 ${elementId} 必须被删除`).toHaveCount(0)
  }

  /**
   * 通过画布真实命中层选择并删除指定顺序流。
   * @param {string} flowId 当前画布顺序流稳定标识。
   * @returns {Promise<void>} 顺序流经 bpmn-js 删除命令移出画布后结束。
   */
  async deleteSequenceFlow(flowId) {
    const connection = this.page.locator(`.djs-connection[data-element-id="${flowId}"]`)
    await expect(connection, `顺序流 ${flowId} 必须存在`).toHaveCount(1)
    await this.selectSequenceFlow(flowId)
    await this.page.keyboard.press('Delete')
    await expect(connection, `顺序流 ${flowId} 必须被删除`).toHaveCount(0)
  }

  /**
   * 通过 bpmn-js 上下文菜单在指定节点后追加排他网关并配置稳定标识。
   * @param {string} sourceElementId 上游 BPMN 元素标识。
   * @param {string} stableElementId 网关持久化标识。
   * @param {string} gatewayName 网关显示名称。
   * @returns {Promise<string>} 最终持久化的排他网关标识。
   */
  async appendExclusiveGatewayAfter(sourceElementId, stableElementId, gatewayName) {
    const existingIds = new Set(await this.canvasShapeIds())
    // 上一次建模动作可能留下其他节点的上下文菜单，追加前必须重新选择调用方指定的源节点。
    await this.selectCanvasShape(sourceElementId)
    const appendAction = this.page.locator('.djs-context-pad:visible [data-action="append.gateway"]')
    await expect(appendAction, `图形 ${sourceElementId} 必须提供追加网关入口`).toBeVisible()
    await appendAction.click()
    await expect.poll(async () => {
      const currentIds = await this.canvasShapeIds()
      return currentIds.find(id => !existingIds.has(id)) || ''
    }, { message: `${sourceElementId} 后必须生成新的排他网关` }).not.toBe('')
    const appendedId = (await this.canvasShapeIds()).find(id => !existingIds.has(id))
    if (!appendedId) throw new Error(`${sourceElementId} 后的新排他网关缺少元素标识`)
    await this.page.keyboard.press('Escape')
    await expect(this.page.locator('.djs-direct-editing-parent:visible')).toHaveCount(0)
    return this.configureElementIdentity(appendedId, stableElementId, gatewayName)
  }

  /**
   * 通过 bpmn-js 更改元素菜单切换网关类型。
   * @param {string} elementId 当前网关稳定标识。
   * @param {'inclusive'|'parallel'|'exclusive'|'event-based'} gatewayType 目标标准 BPMN 网关类型。
   * @returns {Promise<void>} 网关通过真实替换命令更新且保持原标识后结束。
   */
  async replaceGatewayType(elementId, gatewayType) {
    expect(['inclusive', 'parallel', 'exclusive', 'event-based'], '测试只允许替换为支持的标准网关').toContain(gatewayType)
    await this.selectCanvasShape(elementId)
    const replaceAction = this.page.locator('.djs-context-pad:visible [data-action="replace"]')
    await expect(replaceAction, `网关 ${elementId} 必须提供更改元素入口`).toBeVisible()
    await replaceAction.click()
    const replaceMenu = this.page.locator('.djs-popup:visible')
    await replaceMenu.locator(`[data-id="replace-with-${gatewayType}-gateway"]`).click()
    await expect(replaceMenu).toHaveCount(0)
    await expect(this.page.locator(`.djs-shape[data-element-id="${elementId}"]`)).toHaveCount(1)
    await expect(this.properties.locator('.designer-properties-panel__context code')).toHaveText(elementId)
  }

  /**
   * 通过事件网关原生上下文动作追加消息或定时捕获事件，并保留自动生成的顺序流。
   * @param {string} sourceElementId 上游事件网关的稳定元素标识。
   * @param {'message'|'timer'} eventType 需要追加的中间捕获事件类型。
   * @returns {Promise<string>} bpmn-js 为新增捕获事件生成的临时元素标识。
   */
  async appendIntermediateCatchEventAfter(sourceElementId, eventType) {
    const actionByType = {
      message: 'append.message-intermediate-event',
      timer: 'append.timer-intermediate-event'
    }
    expect(Object.keys(actionByType), '测试只允许追加事件网关支持的捕获事件').toContain(eventType)
    const existingIds = new Set(await this.canvasShapeIds())
    const beforeXml = await this.readDesignerXml()
    const beforeDocument = new DOMParser().parseFromString(beforeXml, 'application/xml')
    const beforeFlowIds = new Set([...beforeDocument.getElementsByTagNameNS('*', 'sequenceFlow')]
      .map(flow => flow.getAttribute('id')).filter(Boolean))

    // 每次都重新确认事件网关选中态，避免上一分支的新增节点继续占用上下文菜单。
    await this.selectCanvasShape(sourceElementId)
    const actionName = actionByType[eventType]
    const appendAction = this.page.locator(`.djs-context-pad:visible [data-action="${actionName}"]`)
    await expect(appendAction, `事件网关 ${sourceElementId} 必须提供 ${eventType} 捕获事件入口`).toBeVisible()
    await appendAction.click()

    await expect.poll(async () => {
      const currentIds = await this.canvasShapeIds()
      return currentIds.find(id => !existingIds.has(id)) || ''
    }, { message: `${sourceElementId} 后必须生成新的 ${eventType} 捕获事件` }).not.toBe('')
    const appendedId = (await this.canvasShapeIds()).find(id => !existingIds.has(id))
    if (!appendedId) throw new Error(`${sourceElementId} 后的新 ${eventType} 捕获事件缺少元素标识`)

    // 原生追加动作必须同时创建事件网关出线，防止测试只生成孤立节点后误判建模成功。
    await expect.poll(async () => {
      const xml = await this.readDesignerXml()
      const document = new DOMParser().parseFromString(xml, 'application/xml')
      return [...document.getElementsByTagNameNS('*', 'sequenceFlow')]
        .filter(flow => flow.getAttribute('sourceRef') === sourceElementId
          && flow.getAttribute('targetRef') === appendedId
          && !beforeFlowIds.has(flow.getAttribute('id')))
        .length
    }, { message: `${sourceElementId} 必须由原生追加动作自动连到 ${appendedId}` }).toBe(1)
    await this.page.keyboard.press('Escape')
    await expect(this.page.locator('.djs-direct-editing-parent:visible')).toHaveCount(0)
    return appendedId
  }

  /**
   * 从高级流程元素面板启动真实拖放命令，并把元素放到指定上游节点附近。
   * @param {{paletteLabel:string,sourceElementId:string,stableElementId:string,elementName:string,offsetX:number,offsetY:number,expectedLocalName?:string}} options 面板文案、参考节点、稳定标识、名称、屏幕偏移及可选 BPMN XML 元素名。
   * @returns {Promise<string>} 已经通过属性面板写入稳定标识的新增 BPMN 图形。
   */
  async createAdvancedElement(options) {
    const {
      paletteLabel, sourceElementId, stableElementId, elementName, offsetX, offsetY, expectedLocalName = ''
    } = options
    const existingIds = new Set(await this.canvasShapeIds())
    const sourceVisual = this.page.locator(`.djs-shape[data-element-id="${sourceElementId}"] .djs-visual`)
    await expect(sourceVisual, `高级元素参考图形 ${sourceElementId} 必须存在`).toHaveCount(1)
    const canvas = this.page.locator('.process-designer__canvas')
    const dropPoint = await Promise.all([sourceVisual.boundingBox(), canvas.boundingBox()]).then(([source, bounds]) => {
      if (!source || !bounds) throw new Error('高级元素拖放缺少画布或参考图形坐标')
      // 将目标点约束在画布可见安全区，避免属性面板、Palette 和边缘滚动条截获鼠标释放。
      return {
        x: Math.min(bounds.x + bounds.width - 90, Math.max(bounds.x + 110, source.x + source.width / 2 + offsetX)),
        y: Math.min(bounds.y + bounds.height - 90, Math.max(bounds.y + 90, source.y + source.height / 2 + offsetY))
      }
    })
    await this.page.getByRole('button', { name: '高级流程元素', exact: true }).click()
    const menu = this.page.getByRole('menu', { name: '高级流程元素' })
    await expect(menu).toBeVisible()
    const item = menu.getByRole('menuitem', { name: paletteLabel, exact: true })
    const itemBounds = await item.boundingBox()
    if (!itemBounds) throw new Error(`高级元素 ${paletteLabel} 缺少可拖放区域`)
    await this.page.mouse.move(itemBounds.x + itemBounds.width / 2, itemBounds.y + itemBounds.height / 2)
    await this.page.mouse.down()
    await this.page.mouse.move(dropPoint.x, dropPoint.y, { steps: 12 })
    await this.page.mouse.up()
    await expect(menu).toBeHidden()
    let createdId = ''
    await expect.poll(async () => {
      const currentIds = await this.canvasShapeIds()
      const addedIds = currentIds.filter(id => !existingIds.has(id))
      if (!expectedLocalName) {
        createdId = addedIds[0] || ''
        return createdId
      }
      // 展开容器会同时生成内部开始事件，必须按 XML 类型选中容器本身。
      const xml = await this.readDesignerXml()
      const document = new DOMParser().parseFromString(xml, 'application/xml')
      const typedIds = new Set([...document.getElementsByTagNameNS('*', expectedLocalName)]
        .map(element => element.getAttribute('id')).filter(Boolean))
      createdId = addedIds.find(id => typedIds.has(id)) || ''
      return createdId
    }, { message: `高级元素 ${paletteLabel} 必须通过真实拖放进入画布` }).not.toBe('')
    if (!createdId) throw new Error(`高级元素 ${paletteLabel} 缺少新增图形标识`)
    return this.configureElementIdentity(createdId, stableElementId, elementName)
  }

  /**
   * 通过高级元素面板把业务或补偿边界事件真实拖放到指定活动边缘。
   * @param {{paletteLabel:'错误边界'|'升级边界'|'补偿边界',hostElementId:string,stableElementId:string,elementName:string,eventDefinitionLocalName:'errorEventDefinition'|'escalationEventDefinition'|'compensateEventDefinition'}} options 边界类型、附着活动、稳定标识、名称和事件定义类型。
   * @returns {Promise<string>} 已确认 attachedToRef 和事件定义类型的边界事件标识。
   */
  async attachBoundaryEvent(options) {
    const {
      paletteLabel, hostElementId, stableElementId, elementName, eventDefinitionLocalName
    } = options
    const hostVisual = this.page.locator(`.djs-shape[data-element-id="${hostElementId}"] .djs-visual`)
    await expect(hostVisual, `边界附着活动 ${hostElementId} 必须存在`).toHaveCount(1)
    const hostBounds = await hostVisual.boundingBox()
    if (!hostBounds) throw new Error(`边界附着活动 ${hostElementId} 缺少屏幕坐标`)
    // 优先使用活动底边，随后尝试左右边缘；每次失败都通过真实删除命令清理未附着图形。
    const dropPoints = [
      { x: hostBounds.x + hostBounds.width * 0.72, y: hostBounds.y + hostBounds.height - 2 },
      { x: hostBounds.x + hostBounds.width * 0.28, y: hostBounds.y + hostBounds.height - 2 },
      { x: hostBounds.x + hostBounds.width - 2, y: hostBounds.y + hostBounds.height * 0.5 },
      { x: hostBounds.x + 2, y: hostBounds.y + hostBounds.height * 0.5 }
    ]
    let attachedId = ''
    for (const dropPoint of dropPoints) {
      const existingIds = new Set(await this.canvasShapeIds())
      await this.page.getByRole('button', { name: '高级流程元素', exact: true }).click()
      const menu = this.page.getByRole('menu', { name: '高级流程元素' })
      await expect(menu).toBeVisible()
      const item = menu.getByRole('menuitem', { name: paletteLabel, exact: true })
      const itemBounds = await item.boundingBox()
      if (!itemBounds) throw new Error(`高级元素 ${paletteLabel} 缺少可拖放区域`)
      await this.page.mouse.move(itemBounds.x + itemBounds.width / 2, itemBounds.y + itemBounds.height / 2)
      await this.page.mouse.down()
      await this.page.mouse.move(dropPoint.x, dropPoint.y, { steps: 12 })
      await this.page.mouse.up()
      await expect(menu).toBeHidden()

      let addedIds = []
      const created = await expect.poll(async () => {
        addedIds = (await this.canvasShapeIds()).filter(id => !existingIds.has(id))
        return addedIds.length
      }, { timeout: 3_000, message: `${paletteLabel} 当前落点必须完成一次真实拖放建模命令` })
        .toBeGreaterThan(0)
        .then(() => true, () => false)
      if (!created) {
        // bpmn-js 会拒绝未命中活动边缘的释放；显式退出该次创建状态后再尝试下一个真实边缘。
        await this.page.keyboard.press('Escape')
        continue
      }
      const xml = await this.readDesignerXml()
      const document = new DOMParser().parseFromString(xml, 'application/xml')
      const attached = [...document.getElementsByTagNameNS('*', 'boundaryEvent')]
        .find(element => addedIds.includes(element.getAttribute('id'))
          && element.getAttribute('attachedToRef') === hostElementId
          && element.getElementsByTagNameNS('*', eventDefinitionLocalName).length === 1)
      if (attached) {
        attachedId = attached.getAttribute('id') || ''
        break
      }
      for (const addedId of addedIds) {
        if (!await this.page.locator(`.djs-shape[data-element-id="${addedId}"]`).count()) continue
        await this.selectCanvasShape(addedId)
        await this.page.keyboard.press('Delete')
        await expect(this.page.locator(`.djs-shape[data-element-id="${addedId}"]`),
          `未附着的边界候选 ${addedId} 必须通过画布删除`).toHaveCount(0)
      }
    }
    expect(attachedId, `${paletteLabel} 必须附着到活动 ${hostElementId}`).not.toBe('')
    await this.configureElementIdentity(attachedId, stableElementId, elementName)
    const finalXml = await this.readDesignerXml()
    const finalDocument = new DOMParser().parseFromString(finalXml, 'application/xml')
    const finalBoundary = [...finalDocument.getElementsByTagNameNS('*', 'boundaryEvent')]
      .filter(element => element.getAttribute('id') === stableElementId)
    expect(finalBoundary, `边界事件 ${stableElementId} 必须唯一`).toHaveLength(1)
    expect(finalBoundary[0].getAttribute('attachedToRef')).toBe(hostElementId)
    return stableElementId
  }

  /**
   * 通过 bpmn-js 正式“更改元素”菜单把已附着边界事件替换为 Timer 边界。
   * @param {string} elementId 已附着活动的边界事件稳定标识。
   * @param {boolean} interrupting 是否使用中断附着活动的 Timer 语义。
   * @returns {Promise<void>} Timer 定义和 cancelActivity 语义写入作者模型后结束。
   */
  async replaceBoundaryWithTimer(elementId, interrupting) {
    await this.selectCanvasShape(elementId)
    const replaceAction = this.page.locator('.djs-context-pad:visible [data-action="replace"]')
    await expect(replaceAction, `边界事件 ${elementId} 必须提供更改元素入口`).toBeVisible()
    await replaceAction.click()
    const replaceMenu = this.page.locator('.djs-popup:visible')
    const actionId = interrupting
      ? 'replace-with-timer-boundary'
      : 'replace-with-non-interrupting-timer-boundary'
    const timerAction = replaceMenu.locator(`[data-id="${actionId}"]`)
    await expect(timerAction, `边界事件 ${elementId} 必须提供目标 Timer 语义`).toBeVisible()
    await timerAction.click()
    await expect(replaceMenu).toHaveCount(0)
    await this.selectCanvasShape(elementId)
    await expect(this.properties.getByText('边界事件', { exact: true })).toBeVisible()

    // 替换动作必须保留附着关系和稳定标识，只改变标准事件定义及中断语义。
    const xml = await this.readDesignerXml()
    const document = new DOMParser().parseFromString(xml, 'application/xml')
    const boundaries = [...document.getElementsByTagNameNS('*', 'boundaryEvent')]
      .filter(element => element.getAttribute('id') === elementId)
    expect(boundaries, `Timer 边界事件 ${elementId} 必须唯一`).toHaveLength(1)
    expect(boundaries[0].getElementsByTagNameNS('*', 'timerEventDefinition')).toHaveLength(1)
    expect(boundaries[0].getAttribute('cancelActivity') !== 'false',
      `Timer 边界事件 ${elementId} 的中断语义必须与替换菜单一致`).toBe(interrupting)
  }

  /**
   * 通过 bpmn-js “更改元素”菜单把已附着边界事件转换为事务 Cancel Boundary。
   * @param {string} elementId 已附着事务容器的边界事件稳定标识。
   * @returns {Promise<void>} Cancel 定义、附着关系和中断语义全部写入作者 BPMN 后结束。
   */
  async replaceBoundaryWithCancel(elementId) {
    const beforeXml = await this.readDesignerXml()
    const beforeDocument = new DOMParser().parseFromString(beforeXml, 'application/xml')
    const beforeBoundary = [...beforeDocument.getElementsByTagNameNS('*', 'boundaryEvent')]
      .find(element => element.getAttribute('id') === elementId)
    expect(beforeBoundary, `待替换边界事件 ${elementId} 必须存在`).toBeTruthy()
    const attachedToRef = beforeBoundary?.getAttribute('attachedToRef') || ''
    expect(attachedToRef, `待替换边界事件 ${elementId} 必须已经附着活动`).not.toBe('')

    await this.selectCanvasShape(elementId)
    const replaceAction = this.page.locator('.djs-context-pad:visible [data-action="replace"]')
    await expect(replaceAction, `边界事件 ${elementId} 必须提供更改元素入口`).toBeVisible()
    await replaceAction.click()
    const replaceMenu = this.page.locator('.djs-popup:visible')
    const cancelAction = replaceMenu.locator('[data-id="replace-with-cancel-boundary"]')
    await expect(cancelAction, `边界事件 ${elementId} 必须提供 Cancel Boundary`).toBeVisible()
    await cancelAction.click()
    await expect(replaceMenu).toHaveCount(0)
    await this.selectCanvasShape(elementId)
    await expect(this.properties.getByText('边界事件', { exact: true })).toBeVisible()

    const xml = await this.readDesignerXml()
    const document = new DOMParser().parseFromString(xml, 'application/xml')
    const boundaries = [...document.getElementsByTagNameNS('*', 'boundaryEvent')]
      .filter(element => element.getAttribute('id') === elementId)
    expect(boundaries, `Cancel Boundary ${elementId} 必须唯一`).toHaveLength(1)
    expect(boundaries[0].getAttribute('attachedToRef')).toBe(attachedToRef)
    expect(boundaries[0].getAttribute('cancelActivity') !== 'false',
      'Cancel Boundary 必须中断事务作用域').toBe(true)
    expect(boundaries[0].getElementsByTagNameNS('*', 'cancelEventDefinition')).toHaveLength(1)
  }

  /**
   * 通过边界事件属性面板选择时间类型并提交 Timer 表达式。
   * @param {{elementId:string,timerTypeLabel:'指定时间'|'持续时间'|'周期',expression:string}} options Timer 边界标识、用户可见时间类型和正式表达式。
   * @returns {Promise<{visibleValueAfterCommit:string}>} change 事件完成后输入框的真实回显，作者 XML 由调用方统一核验。
   */
  async configureTimerBoundary(options) {
    const { elementId, timerTypeLabel, expression } = options
    await this.selectCanvasShape(elementId)
    await expect(this.properties.getByText('边界事件', { exact: true })).toBeVisible()
    const typeInput = this.properties.locator('.el-form-item').filter({ hasText: '时间类型' })
      .getByRole('combobox')
    await this.selectLinkedOption(typeInput, timerTypeLabel, `Timer 边界事件 ${elementId} 时间类型`)
    const expressionInput = this.properties.getByLabel('时间表达式')
    await expect(expressionInput, `Timer 边界事件 ${elementId} 必须提供时间表达式输入框`).toHaveCount(1)
    await expressionInput.fill(expression)
    await expect(expressionInput, `Timer 边界事件 ${elementId} 必须允许用户输入表达式`).toHaveValue(expression)
    await expressionInput.press('Tab')
    return { visibleValueAfterCommit: await expressionInput.inputValue() }
  }

  /**
   * 通过 bpmn-js 更改元素菜单把现有任务替换为服务任务。
   * @param {string} elementId 当前任务元素标识。
   * @returns {Promise<void>} 原标识保留且属性面板切换为服务任务后结束。
   */
  async replaceTaskWithServiceTask(elementId) {
    await this.selectCanvasShape(elementId)
    const replaceAction = this.page.locator('.djs-context-pad:visible [data-action="replace"]')
    await expect(replaceAction, `任务 ${elementId} 必须提供更改元素入口`).toBeVisible()
    await replaceAction.click()
    const replaceMenu = this.page.locator('.djs-popup:visible')
    await replaceMenu.locator('[data-id="replace-with-service-task"]').click()
    await expect(replaceMenu).toHaveCount(0)
    await expect(this.page.locator(`.djs-shape[data-element-id="${elementId}"]`)).toHaveCount(1)
    await expect(this.properties.getByText('服务任务', { exact: true })).toBeVisible()
  }

  /**
   * 通过 bpmn-js 更改元素菜单把现有任务转换为业务规则任务。
   * @param {string} elementId 当前普通任务或其他可替换任务的稳定标识。
   * @returns {Promise<void>} 原标识和已有顺序流保留，属性面板切换到业务规则任务后结束。
   */
  async replaceTaskWithBusinessRuleTask(elementId) {
    await this.selectCanvasShape(elementId)
    const replaceAction = this.page.locator('.djs-context-pad:visible [data-action="replace"]')
    await expect(replaceAction, `任务 ${elementId} 必须提供更改元素入口`).toBeVisible()
    await replaceAction.click()
    const replaceMenu = this.page.locator('.djs-popup:visible')
    const businessRuleAction = replaceMenu.locator('[data-id="replace-with-rule-task"]')
    await expect(businessRuleAction, `任务 ${elementId} 必须提供业务规则任务类型`).toBeVisible()
    await businessRuleAction.click()
    await expect(replaceMenu).toHaveCount(0)
    await expect(this.page.locator(`.djs-shape[data-element-id="${elementId}"]`)).toHaveCount(1)
    await expect(this.properties.getByText('业务规则任务', { exact: true })).toBeVisible()
  }

  /**
   * 通过设计器工具栏撤销最近一次完整建模命令。
   * @returns {Promise<void>} 撤销按钮完成一次真实点击且命令栈允许重做后结束。
   */
  async undo() {
    const undoButton = this.page.getByRole('button', { name: '撤销' })
    await expect(undoButton, '设计器必须提供可用撤销入口').toBeEnabled()
    await undoButton.click()
    await expect(this.page.getByRole('button', { name: '重做' }), '撤销后必须允许重做').toBeEnabled()
  }

  /**
   * 通过设计器工具栏重做最近一次被撤销的完整建模命令。
   * @returns {Promise<void>} 重做按钮完成一次真实点击且命令栈再次允许撤销后结束。
   */
  async redo() {
    const redoButton = this.page.getByRole('button', { name: '重做' })
    await expect(redoButton, '设计器必须提供可用重做入口').toBeEnabled()
    await redoButton.click()
    await expect(this.page.getByRole('button', { name: '撤销' }), '重做后必须允许再次撤销').toBeEnabled()
  }

  /**
   * 通过业务规则任务属性面板选择一个正式 DMN 精确版本。
   * @param {{elementId:string,optionLabel:string,decisionId:string}} options 业务规则任务标识、用户可见选项和正式 decisionId。
   * @returns {Promise<string>} 已确认 `flowable:rules` 精确绑定后的作者 BPMN XML。
   */
  async configureDmnDecision(options) {
    const { elementId, optionLabel, decisionId } = options
    await this.selectCanvasShape(elementId)
    await expect(this.properties.getByText('业务规则任务', { exact: true })).toBeVisible()
    const decisionItem = this.properties.locator('.el-form-item').filter({ hasText: 'DMN 决策版本' })
    const decisionInput = decisionItem.getByRole('combobox')
    await this.selectLinkedOption(decisionInput, optionLabel, `业务规则任务 ${elementId} 的 DMN 决策版本`)

    // 可见选择必须同步写入 bpmn-js 命令栈；只验证下拉回显不足以证明部署会冻结目标版本。
    const xml = await this.readDesignerXml()
    const document = new DOMParser().parseFromString(xml, 'application/xml')
    const task = [...document.getElementsByTagNameNS('*', 'businessRuleTask')]
      .find(element => element.getAttribute('id') === elementId)
    expect(task, `业务规则任务 ${elementId} 必须存在于作者 BPMN`).toBeTruthy()
    expect(task.getAttributeNS('http://flowable.org/bpmn', 'rules'),
      `业务规则任务 ${elementId} 必须绑定精确 decisionId`).toBe(decisionId)
    return xml
  }

  /**
   * 把服务任务绑定到正式 HTTP 扩展和已启用端点，并在任何产品断言前保留作者证据。
   * @param {{elementId:string,stableElementId:string,taskName:string,endpointName:string,endpointRevision:number,path:string,bodyVariable:string,statusVariable:string}} options 节点、端点修订和变量映射。
   * @param {import('@playwright/test').TestInfo|null} testInfo 当前用例证据上下文；为空时只返回证据。
   * @returns {Promise<{selectedOptionName:string,handlerVisibleValue:string,editorCount:number,asyncChecked:boolean,delegateExpression:string,extensionKey:string,extensionConfig:string,xml:string}>} HTTP 扩展选择后的控件状态和作者 XML 证据。
   */
  async configureHttpConnectorService(options, testInfo = null) {
    const {
      elementId,
      stableElementId,
      taskName,
      endpointName,
      endpointRevision,
      path,
      bodyVariable,
      statusVariable
    } = options
    await this.selectCanvasShape(elementId)
    await expect(this.properties.getByText('服务任务', { exact: true })).toBeVisible()
    await this.configureElementIdentity(elementId, stableElementId, taskName)

    const handlerItem = this.properties.locator('.el-form-item').filter({ hasText: '受控处理器' })
    const handlerInput = handlerItem.getByRole('combobox')
    await expect(handlerInput, 'HTTP 受控处理器必须唯一').toHaveCount(1)
    await handlerItem.locator('.el-select__wrapper').click()
    await expect(handlerInput).toHaveAttribute('aria-expanded', 'true')
    const handlerListboxId = await handlerInput.getAttribute('aria-controls')
    expect(handlerListboxId, 'HTTP 受控处理器必须关联唯一选项列表').toBeTruthy()
    const handlerOption = this.page.locator(`[id="${handlerListboxId}"]`)
      .getByRole('option', { name: 'HTTP 受控连接器 · HTTP · v1', exact: true })
    await expect(handlerOption, '正式扩展目录必须包含 HTTP 受控连接器').toHaveCount(1)
    await handlerOption.click()
    const editor = this.properties.locator('.http-connector-editor')
    const asyncItem = this.properties.locator('.el-form-item').filter({ hasText: '进入前异步' })
    const xml = await this.readDesignerXml()
    const document = new DOMParser().parseFromString(xml, 'application/xml')
    const serviceTask = [...document.getElementsByTagNameNS('*', 'serviceTask')]
      .find(element => element.getAttribute('id') === stableElementId)
    const extensionFields = [...(serviceTask?.getElementsByTagNameNS('*', 'field') || [])]
    const fieldValue = name => extensionFields.find(field => field.getAttribute('name') === name)
      ?.getAttribute('stringValue') || ''
    // 先固化用户点击后的可见状态和 BPMN 作者状态，避免后续断言失败时丢失产品缺陷证据。
    const evidence = {
      selectedOptionName: 'HTTP 受控连接器 · HTTP · v1',
      handlerVisibleValue: await handlerItem.locator('.el-select__selected-item').textContent().catch(() => ''),
      editorCount: await editor.count(),
      asyncChecked: await asyncItem.getByRole('switch').isChecked().catch(() => false),
      delegateExpression: serviceTask?.getAttributeNS('http://flowable.org/bpmn', 'delegateExpression') || '',
      extensionKey: fieldValue('approvaExtensionKey'),
      extensionConfig: fieldValue('approvaExtensionConfig'),
      xml
    }
    if (testInfo) {
      await testInfo.attach('http-author-evidence.json', {
        body: Buffer.from(JSON.stringify(evidence, null, 2)),
        contentType: 'application/json'
      })
    }
    expect(evidence.handlerVisibleValue.trim(),
      '选择 HTTP 受控处理器后控件必须保留用户选择').toContain('HTTP 受控连接器')
    expect(evidence.editorCount,
      '选择 HTTP 受控处理器后必须显示唯一结构化编辑器').toBe(1)
    await expect(editor).toBeVisible()
    await this.selectLinkedOption(
      editor.locator('.el-form-item').filter({ hasText: '连接端点' }).getByRole('combobox'),
      `${endpointName} · R${endpointRevision}`,
      'HTTP 连接端点'
    )
    const pathInput = editor.locator('.el-form-item').filter({ hasText: '相对路径' }).getByRole('textbox')
    await pathInput.fill(path)
    await pathInput.press('Tab')
    const bodyInput = editor.locator('.el-form-item').filter({ hasText: '正文变量' }).getByRole('textbox')
    await bodyInput.fill(bodyVariable)
    await bodyInput.press('Tab')
    const statusInput = editor.locator('.el-form-item').filter({ hasText: '状态变量' }).getByRole('textbox')
    await statusInput.fill(statusVariable)
    await statusInput.press('Tab')

    await expect(asyncItem.getByRole('switch'), 'HTTP 扩展必须自动启用进入前异步').toBeChecked()
    const configuredXml = await this.readDesignerXml()
    const configuredDocument = new DOMParser().parseFromString(configuredXml, 'application/xml')
    const configuredServiceTask = [...configuredDocument.getElementsByTagNameNS('*', 'serviceTask')]
      .find(element => element.getAttribute('id') === stableElementId)
    expect(configuredServiceTask, `HTTP 服务任务 ${stableElementId} 必须写入作者 XML`).toBeTruthy()
    expect(configuredServiceTask.getAttributeNS('http://flowable.org/bpmn', 'async')).toBe('true')
    expect(configuredXml).toContain('approva.http-connector')
    expect(configuredXml).toContain(`&quot;path&quot;:&quot;${path}&quot;`)
    if (bodyVariable) expect(configuredXml).toContain(`&quot;bodyVariable&quot;:&quot;${bodyVariable}&quot;`)
    expect(configuredXml).toContain(`&quot;statusVariable&quot;:&quot;${statusVariable}&quot;`)
    return { ...evidence, xml: configuredXml }
  }

  /**
   * 通过 bpmn-js “更改元素”菜单把普通任务转换为 ManualTask。
   * @param {string} elementId 当前普通任务或其他可替换任务的稳定标识。
   * @returns {Promise<void>} 任务类型转换为标准 ManualTask 后结束。
   */
  async replaceTaskWithManualTask(elementId) {
    await this.selectCanvasShape(elementId)
    const replaceAction = this.page.locator('.djs-context-pad:visible [data-action="replace"]')
    await expect(replaceAction, `任务 ${elementId} 必须提供更改元素入口`).toBeVisible()
    await replaceAction.click()
    const replaceMenu = this.page.locator('.djs-popup:visible')
    const manualAction = replaceMenu.locator('[data-id="replace-with-manual-task"]')
    await expect(manualAction, `任务 ${elementId} 必须提供手工任务类型`).toBeVisible()
    await manualAction.click()
    await expect(replaceMenu).toHaveCount(0)
    await this.selectCanvasShape(elementId)
    await expect(this.properties.getByText('手工任务', { exact: true })).toBeVisible()
    const xml = await this.readDesignerXml()
    const document = new DOMParser().parseFromString(xml, 'application/xml')
    expect([...document.getElementsByTagNameNS('*', 'manualTask')]
      .filter(element => element.getAttribute('id') === elementId)).toHaveLength(1)
  }

  /**
   * 从正式扩展目录配置一个始终产生 BPMN Error 或 Escalation 的受控服务任务。
   * @param {{elementId:string,eventType:'ERROR'|'ESCALATION',eventOptionLabel:string,sourceLabel?:string,operatorLabel?:string,messageVariable?:string}} options 服务任务、事件类型、正式目录项、业务来源、触发条件和可选消息变量。
   * @returns {Promise<void>} 扩展键和结构化事件配置经 bpmn-js 命令栈写入后结束。
   */
  async configureBpmnEventRaiseService(options) {
    const {
      elementId,
      eventType,
      eventOptionLabel,
      sourceLabel = '服务任务',
      operatorLabel = '始终触发',
      messageVariable = ''
    } = options
    await this.selectCanvasShape(elementId)
    await expect(this.properties.getByText('服务任务', { exact: true })).toBeVisible()
    const handlerInput = this.properties.locator('.el-form-item').filter({ hasText: '受控处理器' })
      .getByRole('combobox')
    await this.selectLinkedOption(
      handlerInput, '产生 BPMN 业务错误或升级 · JAVA · v1', '受控 BPMN 事件处理器'
    )
    const editor = this.properties.locator('.bpmn-event-raise-editor')
    await expect(editor).toBeVisible()
    const eventTypeLabel = eventType === 'ERROR' ? '业务错误' : '业务升级'
    await editor.locator('.el-segmented__item').filter({ hasText: eventTypeLabel }).click()
    await expect(editor.locator('.el-segmented__item.is-selected').filter({ hasText: eventTypeLabel }),
      '受控产生器必须回显事件类型').toHaveCount(1)
    await this.selectLinkedOption(
      editor.locator('.el-form-item').filter({ hasText: '业务编码' }).getByRole('combobox'),
      eventOptionLabel,
      '受控产生器业务编码'
    )
    await this.selectLinkedOption(
      editor.locator('.el-form-item').filter({ hasText: '业务来源' }).getByRole('combobox'),
      sourceLabel,
      '受控产生器业务来源'
    )
    await this.selectLinkedOption(
      editor.locator('.el-form-item').filter({ hasText: '触发条件' }).getByRole('combobox'),
      operatorLabel,
      '受控产生器触发条件'
    )
    const messageInput = editor.getByLabel('消息变量')
    await messageInput.fill(messageVariable)
    await messageInput.press('Tab')
    const xml = await this.readDesignerXml()
    expect(xml, '受控 BPMN 事件服务任务必须保存正式扩展键').toContain('approva.raise-bpmn-event')
    expect(xml, '受控 BPMN 事件服务任务必须保存正式事件编码').toContain(eventOptionLabel.split(' · ').at(-1))
  }

  /**
   * 通过边界事件属性面板选择正式业务编码并配置中断语义。
   * @param {{elementId:string,eventOptionLabel:string,interrupting:boolean}} options 边界事件、正式目录项和中断标志。
   * @returns {Promise<void>} Definitions 根引用和 cancelActivity 均写入作者 BPMN 后结束。
   */
  async configureBusinessBoundaryEvent(options) {
    const { elementId, eventOptionLabel, interrupting } = options
    await this.selectCanvasShape(elementId)
    await expect(this.properties.getByText('边界事件', { exact: true })).toBeVisible()
    const businessCodeInput = this.properties.locator('.el-form-item').filter({ hasText: '业务编码' })
      .getByRole('combobox')
    await expect(businessCodeInput, '边界事件业务编码选择器必须唯一').toHaveCount(1)
    const businessCodeSelect = businessCodeInput
      .locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " el-select ")][1]')
    await businessCodeSelect.locator('.el-select__wrapper').click()
    await expect(businessCodeInput, '边界事件业务编码选择器必须真实展开')
      .toHaveAttribute('aria-expanded', 'true')
    const listboxId = await businessCodeInput.getAttribute('aria-controls')
    expect(listboxId, '边界事件业务编码必须关联唯一选项列表').toBeTruthy()
    const listbox = this.page.locator(`[id="${listboxId}"]`)
    await expect(listbox, '边界事件业务编码选项列表必须可见').toBeVisible()
    const option = listbox.getByRole('option', { name: eventOptionLabel, exact: true })
    await expect(option, `边界事件业务编码必须包含 ${eventOptionLabel}`).toHaveCount(1)
    await expect(option, `边界事件业务编码 ${eventOptionLabel} 必须对用户可见`).toBeVisible()
    await option.click()
    await expect(businessCodeInput, '边界事件业务编码选择完成后必须关闭')
      .toHaveAttribute('aria-expanded', 'false')
    await expect(listbox, '边界事件业务编码选择完成后选项列表必须隐藏').toBeHidden()

    // 该 filterable 选择器不会稳定暴露 Element Plus 选中文案，以作者 XML 作为命令栈提交的权威证据。
    const eventCode = eventOptionLabel.split(' · ').at(-1)
    const selectedXml = await this.readDesignerXml()
    const selectedDocument = new DOMParser().parseFromString(selectedXml, 'application/xml')
    const boundary = [...selectedDocument.getElementsByTagNameNS('*', 'boundaryEvent')]
      .filter(element => element.getAttribute('id') === elementId)
    expect(boundary, `边界事件 ${elementId} 必须唯一`).toHaveLength(1)
    const referenceConfigurations = [
      {
        definitionLocalName: 'errorEventDefinition', referenceAttribute: 'errorRef',
        rootLocalName: 'error', codeAttribute: 'errorCode'
      },
      {
        definitionLocalName: 'escalationEventDefinition', referenceAttribute: 'escalationRef',
        rootLocalName: 'escalation', codeAttribute: 'escalationCode'
      }
    ]
    const referenceConfiguration = referenceConfigurations.find(configuration =>
      boundary[0].getElementsByTagNameNS('*', configuration.definitionLocalName).length === 1)
    expect(referenceConfiguration, '边界事件必须保留 Error 或 Escalation 正式定义').toBeTruthy()
    const eventDefinition = boundary[0]
      .getElementsByTagNameNS('*', referenceConfiguration.definitionLocalName)[0]
    const rootReferenceId = eventDefinition.getAttribute(referenceConfiguration.referenceAttribute)
    expect(rootReferenceId, '边界事件定义必须引用 Definitions 根业务事件').toBeTruthy()
    const rootReferences = [...selectedDocument.getElementsByTagNameNS('*', referenceConfiguration.rootLocalName)]
      .filter(root => root.getAttribute('id') === rootReferenceId
        && root.getAttribute(referenceConfiguration.codeAttribute) === eventCode)
    expect(rootReferences, `Definitions 必须保存正式业务编码 ${eventCode}`).toHaveLength(1)

    const interruptingItem = this.properties.locator('.el-form-item').filter({ hasText: '中断附着活动' })
    const interruptingSwitch = interruptingItem.getByRole('switch')
    await expect(interruptingSwitch, '边界事件中断状态控件必须唯一').toHaveCount(1)
    if (await interruptingSwitch.isChecked() !== interrupting) {
      await expect(interruptingItem.locator('.el-switch'), '边界事件中断开关必须可操作').toBeEnabled()
      await interruptingItem.locator('.el-switch').click()
    }
    await expect(interruptingSwitch).toHaveAttribute('aria-checked', String(interrupting))
    const finalXml = await this.readDesignerXml()
    const finalDocument = new DOMParser().parseFromString(finalXml, 'application/xml')
    const finalBoundary = [...finalDocument.getElementsByTagNameNS('*', 'boundaryEvent')]
      .filter(element => element.getAttribute('id') === elementId)
    expect(finalBoundary, `边界事件 ${elementId} 的中断语义必须可回读`).toHaveLength(1)
    expect(finalBoundary[0].getAttribute('cancelActivity') !== 'false',
      '边界事件作者 XML 的 cancelActivity 必须与可见开关一致').toBe(interrupting)
  }

  /**
   * 通过调用活动属性面板选择正式子流程版本，并配置结构化输入输出变量映射。
   * @param {{elementId:string,targetOption:{processName:string,processKey:string,version:number,status:'启用'|'停用'},versionPolicy:'发布时最新版'|'固定所选版本',businessKeyPolicy?:'继承父流程'|'不设置',inheritVariables?:boolean,processInstanceName?:string,inputMappings?:Array<{sourceLabel:string,targetLabel:string}>,outputMappings?:Array<{sourceLabel:string,targetLabel:string}>,outputScope?:'父流程变量'|'调用节点局部变量'}} options 调用节点、目录项、版本策略和字段映射。
   * @returns {Promise<void>} 目录引用、Flowable 原生 in/out 映射和变量作用域全部进入 bpmn-js 命令栈后结束。
   */
  async configureCallActivity(options) {
    const {
      elementId,
      targetOption,
      versionPolicy,
      businessKeyPolicy = '继承父流程',
      inheritVariables = false,
      processInstanceName = '',
      inputMappings = [],
      outputMappings = [],
      outputScope = '父流程变量'
    } = options
    expect(['发布时最新版', '固定所选版本']).toContain(versionPolicy)
    expect(['继承父流程', '不设置']).toContain(businessKeyPolicy)
    expect(['父流程变量', '调用节点局部变量']).toContain(outputScope)
    expect(targetOption?.processName, '调用活动目标流程名称不能为空').toBeTruthy()
    expect(targetOption?.processKey, '调用活动目标流程 key 不能为空').toBeTruthy()
    expect(Number(targetOption?.version), '调用活动目标流程版本必须为正整数').toBeGreaterThan(0)
    expect(['启用', '停用']).toContain(targetOption?.status)
    await this.selectCanvasShape(elementId)
    await expect(this.properties.getByText('调用活动', { exact: true })).toBeVisible()

    const selectSegment = async (fieldLabel, optionLabel) => {
      const item = this.properties.locator('.el-form-item').filter({ hasText: fieldLabel }).first()
      await expect(item, `调用活动 ${fieldLabel} 必须可见`).toBeVisible()
      const option = item.locator('.el-segmented__item').filter({ hasText: optionLabel })
      await expect(option, `调用活动 ${fieldLabel} 必须包含 ${optionLabel}`).toHaveCount(1)
      await option.click()
      await expect(item.locator('.el-segmented__item.is-selected').filter({ hasText: optionLabel }),
        `调用活动 ${fieldLabel} 必须选中 ${optionLabel}`).toHaveCount(1)
    }

    const targetItem = this.properties.locator('.el-form-item').filter({ hasText: '已发布子流程' }).first()
    await expect(targetItem, '调用活动必须提供已发布子流程目录').toBeVisible()
    const targetInput = targetItem.getByRole('combobox')
    await expect(targetInput, '调用活动子流程目录必须唯一').toHaveCount(1)
    const targetSelect = targetInput.locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " el-select ")][1]')
    await targetSelect.locator('.el-select__wrapper').click()
    await expect(targetInput, '调用活动子流程目录必须真实展开').toHaveAttribute('aria-expanded', 'true')
    const targetListboxId = await targetInput.getAttribute('aria-controls')
    expect(targetListboxId, '调用活动子流程目录必须关联唯一选项列表').toBeTruthy()
    const targetListbox = this.page.locator(`[id="${targetListboxId}"]`)
    // 目录项使用自定义分栏渲染，可访问名称不会保留 el-option label 中的分隔符，按用户可见四项信息核对。
    await targetInput.fill(targetOption.processKey)
    const targetRow = targetListbox.getByRole('option').filter({ hasText: targetOption.processKey })
    await expect(targetRow, `调用活动子流程目录必须唯一命中 ${targetOption.processKey}`).toHaveCount(1)
    await expect(targetRow.getByText(targetOption.processName, { exact: true }), '目录项必须显示目标流程名称').toBeVisible()
    await expect(targetRow.locator('code'), '目录项必须显示目标流程 key').toHaveText(targetOption.processKey)
    await expect(targetRow.getByText(`v${targetOption.version} · ${targetOption.status}`, { exact: true }),
      '目录项必须显示目标版本和状态').toBeVisible()
    await targetRow.click()
    await expect(targetInput, '调用活动子流程目录选择完成后必须关闭').toHaveAttribute('aria-expanded', 'false')
    await expect(targetSelect.locator('.el-select__selected-item.el-select__placeholder'),
      '调用活动子流程目录必须回显目标流程')
      .toContainText(targetOption.processName)
    await expect(targetListbox, '调用活动子流程目录选择完成后选项列表必须隐藏').toBeHidden()
    await selectSegment('版本绑定策略', versionPolicy)
    await selectSegment('业务键策略', businessKeyPolicy)

    const inheritItem = this.properties.locator('.el-form-item').filter({ hasText: '继承父流程变量' }).first()
    const inheritSwitch = inheritItem.getByRole('switch')
    await expect(inheritSwitch, '调用活动继承变量原生状态控件必须唯一').toHaveCount(1)
    await expect(inheritItem.locator('.el-switch'), '调用活动继承变量可见开关必须可操作').toBeVisible()
    if (await inheritSwitch.isChecked() !== inheritVariables) {
      // Element Plus 原生 checkbox 隐藏，真实用户操作可见开关容器。
      await inheritItem.locator('.el-switch').click()
    }
    if (processInstanceName) {
      const nameInput = this.properties.getByLabel('子流程实例名称')
      await nameInput.fill(processInstanceName)
      await nameInput.press('Tab')
    }

    const addMappings = async (fieldLabel, buttonLabel, mappings) => {
      const item = this.properties.locator('.el-form-item').filter({ hasText: fieldLabel }).first()
      await expect(item, `调用活动 ${fieldLabel} 编辑器必须可见`).toBeVisible()
      for (const [mappingIndex, mapping] of mappings.entries()) {
        const rows = item.locator('.call-activity-mapping-row')
        const beforeCount = await rows.count()
        await item.getByRole('button', { name: buttonLabel, exact: true }).click()
        await expect(rows, `${fieldLabel} 必须新增映射行`).toHaveCount(beforeCount + 1)
        const row = rows.nth(beforeCount)
        const selects = row.locator('.el-select')
        await expect(selects, `${fieldLabel} 第 ${mappingIndex + 1} 行必须包含来源和目标`).toHaveCount(2)
        await this.selectLinkedOption(selects.nth(0).getByRole('combobox'), mapping.sourceLabel,
          `${fieldLabel} 第 ${mappingIndex + 1} 行来源`)
        await this.selectLinkedOption(selects.nth(1).getByRole('combobox'), mapping.targetLabel,
          `${fieldLabel} 第 ${mappingIndex + 1} 行目标`)
      }
    }
    await addMappings('输入变量映射', '添加输入映射', inputMappings)
    await addMappings('输出变量映射', '添加输出映射', outputMappings)
    await selectSegment('输出变量作用域', outputScope)
  }

  /**
   * 从当前作者 XML 中定位一个容器的唯一直接子元素。
   * @param {string} containerId 子流程或事务容器稳定标识。
   * @param {string} childLocalName 子元素 BPMN XML 本地名称，例如 startEvent。
   * @returns {Promise<string>} 唯一直接子元素的稳定标识。
   */
  async nestedDirectChildId(containerId, childLocalName) {
    const xml = await this.readDesignerXml()
    const document = new DOMParser().parseFromString(xml, 'application/xml')
    const containers = [...document.getElementsByTagNameNS('*', '*')]
      .filter(element => element.getAttribute('id') === containerId)
    expect(containers, `容器 ${containerId} 必须在作者 XML 中唯一存在`).toHaveLength(1)
    // 仅接受直接子元素，避免嵌套容器中同名事件被错误当作当前容器入口。
    const childIds = [...containers[0].childNodes]
      .filter(node => node.nodeType === 1 && node.localName === childLocalName)
      .map(node => node.getAttribute('id')).filter(Boolean)
    expect(childIds, `容器 ${containerId} 必须包含唯一直接 ${childLocalName}`).toHaveLength(1)
    return childIds[0]
  }

  /**
   * 通过 bpmn-js “更改元素”菜单把事件子流程内部开始事件转换为 Signal Start。
   * @param {string} elementId 事件子流程内部开始事件的当前元素标识。
   * @param {boolean} interrupting 是否中断事件子流程所属主流程作用域。
   * @returns {Promise<void>} Signal 事件定义和中断语义写入作者 BPMN 后结束。
   */
  async replaceEventSubProcessStartWithSignal(elementId, interrupting) {
    await this.selectCanvasShape(elementId)
    const replaceAction = this.page.locator('.djs-context-pad:visible [data-action="replace"]')
    await expect(replaceAction, `事件子流程开始事件 ${elementId} 必须提供更改元素入口`).toBeVisible()
    await replaceAction.click()
    const replaceMenu = this.page.locator('.djs-popup:visible')
    const actionId = interrupting
      ? 'replace-with-signal-start'
      : 'replace-with-non-interrupting-signal-start'
    const signalAction = replaceMenu.locator(`[data-id="${actionId}"]`)
    await expect(signalAction, `事件子流程开始事件 ${elementId} 必须提供目标 Signal 语义`).toBeVisible()
    await signalAction.click()
    await expect(replaceMenu).toHaveCount(0)
    await this.selectCanvasShape(elementId)
    await expect(this.properties.getByText('开始节点', { exact: true })).toBeVisible()

    // 替换动作必须保留内部开始事件标识，只增加 Signal 定义并冻结中断语义。
    const xml = await this.readDesignerXml()
    const document = new DOMParser().parseFromString(xml, 'application/xml')
    const startEvents = [...document.getElementsByTagNameNS('*', 'startEvent')]
      .filter(element => element.getAttribute('id') === elementId)
    expect(startEvents, `事件子流程开始事件 ${elementId} 必须唯一`).toHaveLength(1)
    expect(startEvents[0].getElementsByTagNameNS('*', 'signalEventDefinition')).toHaveLength(1)
    expect(startEvents[0].getAttribute('isInterrupting') !== 'false',
      `事件子流程开始事件 ${elementId} 的中断语义必须与替换菜单一致`).toBe(interrupting)
  }

  /**
   * 通过事件属性面板配置消息或信号捕获事件的稳定引用。
   * @param {string} elementId 捕获事件稳定标识。
   * @param {string} eventReference 消息或信号的稳定业务 key。
   * @param {'捕获事件'|'开始节点'} expectedTitle 当前事件在属性检查器中的用户可见类型名称。
   * @returns {Promise<void>} 根事件引用经 bpmn-js 命令栈写入作者 XML 后结束。
   */
  async configureEventReference(elementId, eventReference, expectedTitle = '捕获事件') {
    await this.selectCanvasShape(elementId)
    await expect(this.properties.getByText(expectedTitle, { exact: true })).toBeVisible()
    const input = this.properties.getByLabel('事件引用')
    await expect(input).toBeVisible()
    await input.fill(eventReference)
    await input.press('Tab')
    await expect(input).toHaveValue(eventReference)
  }

  /**
   * 通过事件属性面板配置定时捕获事件的时间类型和 ISO-8601 表达式。
   * @param {string} elementId 定时捕获事件稳定标识。
   * @param {'指定时间'|'持续时间'|'周期'} definitionType 用户可见时间类型。
   * @param {string} expression Flowable 支持的定时表达式。
   * @returns {Promise<void>} 定时定义经 bpmn-js 命令栈写入作者 XML 后结束。
   */
  async configureTimerEvent(elementId, definitionType, expression) {
    await this.selectCanvasShape(elementId)
    await expect(this.properties.getByText('捕获事件', { exact: true })).toBeVisible()
    const typeItem = this.properties.locator('.el-form-item').filter({ hasText: '时间类型' })
    await expect(typeItem, '定时事件时间类型必须唯一').toHaveCount(1)
    await typeItem.locator('.el-select__wrapper').click()
    await this.page.locator('.el-select-dropdown:visible').getByRole('option', { name: definitionType, exact: true }).click()
    const expressionInput = this.properties.getByLabel('时间表达式')
    await expressionInput.fill(expression)
    await expressionInput.press('Tab')
    await expect(expressionInput).toHaveValue(expression)
  }

  /**
   * 通过 bpmn-js 上下文菜单的连接工具创建两个现有图形之间的顺序流。
   * @param {string} sourceElementId 上游 BPMN 图形标识。
   * @param {string} targetElementId 下游 BPMN 图形标识。
   * @returns {Promise<string>} 真实鼠标连线后生成的唯一顺序流标识。
   */
  async connectShapes(sourceElementId, targetElementId) {
    const beforeXml = await this.readDesignerXml()
    const beforeDocument = new DOMParser().parseFromString(beforeXml, 'application/xml')
    const beforeFlowIds = new Set([...beforeDocument.getElementsByTagNameNS('*', 'sequenceFlow')]
      .map(flow => flow.getAttribute('id')).filter(Boolean))
    await this.selectCanvasShape(sourceElementId)
    const connectAction = this.page.locator('.djs-context-pad:visible [data-action="connect"]')
    await expect(connectAction, `图形 ${sourceElementId} 必须提供连接入口`).toBeVisible()
    await connectAction.click()
    const targetPoints = await this.page.locator(`.djs-shape[data-element-id="${targetElementId}"] .djs-visual`)
      .evaluate((visual, targetId) => {
        const bounds = visual.getBoundingClientRect()
        if (bounds.width <= 0 || bounds.height <= 0) throw new Error('目标 BPMN 图形缺少可连接区域')
        // 展开容器中心可能被内部事件或任务覆盖，只使用真实顶层仍属于目标图形的点。
        return [
          [0.03, 0.03], [0.5, 0.03], [0.97, 0.03],
          [0.03, 0.5], [0.97, 0.5],
          [0.03, 0.97], [0.5, 0.97], [0.97, 0.97],
          [0.25, 0.25], [0.75, 0.25], [0.25, 0.75], [0.75, 0.75], [0.5, 0.5]
        ].map(([xRatio, yRatio]) => {
          const x = bounds.left + bounds.width * xRatio
          const y = bounds.top + bounds.height * yRatio
          const topNode = document.elementFromPoint(x, y)
          const topElementId = topNode?.closest?.('.djs-element')?.getAttribute('data-element-id') || ''
          return { x, y, topElementId }
        }).filter(point => point.topElementId === targetId)
      }, targetElementId)
    expect(targetPoints.length, `目标图形 ${targetElementId} 必须存在未被内部图形覆盖的连接点`).toBeGreaterThan(0)
    const targetPoint = targetPoints[0]
    // bpmn-js 的连接工具在上下文按钮 click 后进入拖动状态，移动并再次点击目标图形完成建模命令。
    await this.page.mouse.move(targetPoint.x, targetPoint.y, { steps: 8 })
    await this.page.mouse.click(targetPoint.x, targetPoint.y)
    await expect.poll(async () => {
      const xml = await this.readDesignerXml()
      const document = new DOMParser().parseFromString(xml, 'application/xml')
      return [...document.getElementsByTagNameNS('*', 'sequenceFlow')]
        .filter(flow => flow.getAttribute('sourceRef') === sourceElementId
          && flow.getAttribute('targetRef') === targetElementId)
        .map(flow => flow.getAttribute('id')).filter(Boolean)
    }, { message: `${sourceElementId} 必须通过真实连接工具连到 ${targetElementId}` }).toHaveLength(1)
    const afterXml = await this.readDesignerXml()
    const afterDocument = new DOMParser().parseFromString(afterXml, 'application/xml')
    const createdFlows = [...afterDocument.getElementsByTagNameNS('*', 'sequenceFlow')]
      .filter(flow => flow.getAttribute('sourceRef') === sourceElementId
        && flow.getAttribute('targetRef') === targetElementId
        && !beforeFlowIds.has(flow.getAttribute('id')))
    expect(createdFlows, '真实连接动作必须只新增一条目标顺序流').toHaveLength(1)
    return createdFlows[0].getAttribute('id') || ''
  }

  /**
   * 通过补偿边界的可见连接工具创建指向补偿活动的标准 Association。
   * @param {string} boundaryElementId 带 CompensateEventDefinition 的边界事件标识。
   * @param {string} compensationActivityId 同一作用域内的目标补偿活动标识。
   * @returns {Promise<string>} bpmn-js 真实鼠标连接后生成的 Association 标识。
   */
  async connectCompensationAssociation(boundaryElementId, compensationActivityId) {
    const beforeXml = await this.readDesignerXml()
    const beforeDocument = new DOMParser().parseFromString(beforeXml, 'application/xml')
    const beforeAssociationIds = new Set([...beforeDocument.getElementsByTagNameNS('*', 'association')]
      .map(association => association.getAttribute('id')).filter(Boolean))
    await this.selectCanvasShape(boundaryElementId)
    const connectAction = this.page.locator('.djs-context-pad:visible [data-action="connect"]')
    await expect(connectAction, `补偿边界 ${boundaryElementId} 必须提供连接入口`).toBeVisible()
    await connectAction.click()
    const targetPoints = await this.page
      .locator(`.djs-shape[data-element-id="${compensationActivityId}"] .djs-visual`)
      .evaluate((visual, targetId) => {
        const bounds = visual.getBoundingClientRect()
        if (bounds.width <= 0 || bounds.height <= 0) throw new Error('补偿活动缺少可连接区域')
        return [
          [0.03, 0.03], [0.5, 0.03], [0.97, 0.03],
          [0.03, 0.5], [0.97, 0.5],
          [0.03, 0.97], [0.5, 0.97], [0.97, 0.97],
          [0.5, 0.5], [0.25, 0.5], [0.75, 0.5], [0.5, 0.25], [0.5, 0.75],
          [0.25, 0.25], [0.75, 0.25], [0.25, 0.75], [0.75, 0.75]
        ].map(([xRatio, yRatio]) => {
          const x = bounds.left + bounds.width * xRatio
          const y = bounds.top + bounds.height * yRatio
          const topNode = document.elementFromPoint(x, y)
          const topElementId = topNode?.closest?.('.djs-element')?.getAttribute('data-element-id') || ''
          return { x, y, topElementId }
        }).filter(point => point.topElementId === targetId)
      }, compensationActivityId)
    expect(targetPoints.length, `补偿活动 ${compensationActivityId} 必须存在可连接点`).toBeGreaterThan(0)
    await this.page.mouse.move(targetPoints[0].x, targetPoints[0].y, { steps: 8 })
    await this.page.mouse.click(targetPoints[0].x, targetPoints[0].y)

    await expect.poll(async () => {
      const xml = await this.readDesignerXml()
      const document = new DOMParser().parseFromString(xml, 'application/xml')
      return [...document.getElementsByTagNameNS('*', 'association')]
        .filter(association => association.getAttribute('sourceRef') === boundaryElementId
          && association.getAttribute('targetRef') === compensationActivityId)
        .map(association => association.getAttribute('id')).filter(Boolean)
    }, { message: `${boundaryElementId} 必须通过真实连接工具关联 ${compensationActivityId}` })
      .toHaveLength(1)
    const afterXml = await this.readDesignerXml()
    const afterDocument = new DOMParser().parseFromString(afterXml, 'application/xml')
    const createdAssociations = [...afterDocument.getElementsByTagNameNS('*', 'association')]
      .filter(association => association.getAttribute('sourceRef') === boundaryElementId
        && association.getAttribute('targetRef') === compensationActivityId
        && !beforeAssociationIds.has(association.getAttribute('id')))
    expect(createdAssociations, '补偿连接必须只新增一条目标 Association').toHaveLength(1)
    expect(createdAssociations[0].getAttribute('associationDirection')).toBe('One')
    return createdAssociations[0].getAttribute('id') || ''
  }

  /**
   * 通过节点上下文菜单追加普通任务，再通过替换菜单改为真实 UserTask。
   * @param {string} sourceElementId 上游 BPMN 元素标识。
   * @returns {Promise<string>} bpmn-js 为新用户任务生成的临时元素标识。
   */
  async appendUserTaskAfter(sourceElementId) {
    const existingIds = new Set(await this.canvasShapeIds())
    // 不能只判断追加按钮可见性，因为它可能属于上一节点的上下文菜单。
    await this.selectCanvasShape(sourceElementId)
    const appendAction = this.page.locator('.djs-context-pad:visible [data-action="append.append-task"]')
    await expect(appendAction, `图形 ${sourceElementId} 必须提供追加任务入口`).toBeVisible()
    await appendAction.click()

    await expect.poll(async () => {
      const currentIds = await this.canvasShapeIds()
      return currentIds.find(id => !existingIds.has(id)) || ''
    }, { message: `${sourceElementId} 后必须生成新的任务节点` }).not.toBe('')
    const appendedId = (await this.canvasShapeIds()).find(id => !existingIds.has(id))
    if (!appendedId) throw new Error(`${sourceElementId} 后的新任务节点缺少元素标识`)

    // bpmn-js 追加任务后会自动进入名称内联编辑；先按真实键盘操作退出，避免编辑层拦截替换菜单。
    await this.page.keyboard.press('Escape')
    await expect(this.page.locator('.djs-direct-editing-parent:visible')).toHaveCount(0)
    const replaceAction = this.page.locator('.djs-context-pad:visible [data-action="replace"]')
    if (!await replaceAction.isVisible().catch(() => false)) {
      await this.selectCanvasShape(appendedId)
    }
    await replaceAction.click()
    const replaceMenu = this.page.locator('.djs-popup:visible')
    await replaceMenu.locator('[data-id="replace-with-user-task"]').click()
    await expect(replaceMenu).toHaveCount(0)
    if (!await this.properties.getByText('用户任务', { exact: true }).isVisible().catch(() => false)) {
      await this.selectCanvasShape(appendedId)
    }
    await expect(this.properties.getByText('用户任务', { exact: true })).toBeVisible()
    return appendedId
  }

  /**
   * 通过节点上下文菜单追加普通任务，再用可见替换菜单转换为 ManualTask。
   * @param {string} sourceElementId 上游 BPMN 元素标识。
   * @returns {Promise<string>} bpmn-js 为新手工任务生成的临时元素标识。
   */
  async appendManualTaskAfter(sourceElementId) {
    const existingIds = new Set(await this.canvasShapeIds())
    await this.selectCanvasShape(sourceElementId)
    const appendAction = this.page.locator('.djs-context-pad:visible [data-action="append.append-task"]')
    await expect(appendAction, `图形 ${sourceElementId} 必须提供追加任务入口`).toBeVisible()
    await appendAction.click()
    await expect.poll(async () => {
      const currentIds = await this.canvasShapeIds()
      return currentIds.find(id => !existingIds.has(id)) || ''
    }, { message: `${sourceElementId} 后必须生成新的任务节点` }).not.toBe('')
    const appendedId = (await this.canvasShapeIds()).find(id => !existingIds.has(id))
    if (!appendedId) throw new Error(`${sourceElementId} 后的新手工任务缺少元素标识`)
    await this.page.keyboard.press('Escape')
    await expect(this.page.locator('.djs-direct-editing-parent:visible')).toHaveCount(0)
    await this.replaceTaskWithManualTask(appendedId)
    return appendedId
  }

  /**
   * 通过节点上下文菜单追加结束事件并保留 bpmn-js 自动创建的顺序流。
   * @param {string} sourceElementId 上游 BPMN 元素标识。
   * @returns {Promise<string>} 新结束事件的元素标识。
   */
  async appendEndEventAfter(sourceElementId) {
    const existingIds = new Set(await this.canvasShapeIds())
    // 始终将上下文菜单绑定到指定源节点，避免从仍选中的相邻节点追加结束事件。
    await this.selectCanvasShape(sourceElementId)
    const appendAction = this.page.locator('.djs-context-pad:visible [data-action="append.end-event"]')
    await expect(appendAction, `图形 ${sourceElementId} 必须提供追加结束事件入口`).toBeVisible()
    await appendAction.click()
    await expect.poll(async () => {
      const currentIds = await this.canvasShapeIds()
      return currentIds.find(id => !existingIds.has(id)) || ''
    }, { message: `${sourceElementId} 后必须生成新的结束事件` }).not.toBe('')
    const appendedId = (await this.canvasShapeIds()).find(id => !existingIds.has(id))
    if (!appendedId) throw new Error(`${sourceElementId} 后的新结束事件缺少元素标识`)
    await this.page.keyboard.press('Escape')
    await expect(this.page.locator('.djs-direct-editing-parent:visible')).toHaveCount(0)
    await expect(this.properties.getByText('结束节点', { exact: true })).toBeVisible()
    return appendedId
  }

  /**
   * 通过 bpmn-js “更改元素”菜单把结束事件转换为事务 Cancel End。
   * @param {string} elementId 事务内部结束事件的稳定标识。
   * @returns {Promise<void>} CancelEventDefinition 写入作者 BPMN 后结束。
   */
  async replaceEndWithCancel(elementId) {
    await this.selectCanvasShape(elementId)
    const replaceAction = this.page.locator('.djs-context-pad:visible [data-action="replace"]')
    await expect(replaceAction, `结束事件 ${elementId} 必须提供更改元素入口`).toBeVisible()
    await replaceAction.click()
    const replaceMenu = this.page.locator('.djs-popup:visible')
    const cancelAction = replaceMenu.locator('[data-id="replace-with-cancel-end"]')
    await expect(cancelAction, `结束事件 ${elementId} 必须提供 Cancel End`).toBeVisible()
    await cancelAction.click()
    await expect(replaceMenu).toHaveCount(0)
    await this.selectCanvasShape(elementId)
    await expect(this.properties.getByText('结束节点', { exact: true })).toBeVisible()
    const xml = await this.readDesignerXml()
    const document = new DOMParser().parseFromString(xml, 'application/xml')
    const endEvents = [...document.getElementsByTagNameNS('*', 'endEvent')]
      .filter(element => element.getAttribute('id') === elementId)
    expect(endEvents, `Cancel End ${elementId} 必须唯一`).toHaveLength(1)
    expect(endEvents[0].getElementsByTagNameNS('*', 'cancelEventDefinition')).toHaveLength(1)
  }

  /**
   * 通过执行配置中的可见开关设置活动是否属于补偿处理器。
   * @param {string} elementId 需要设置补偿语义的活动标识。
   * @param {boolean} enabled 是否启用 isForCompensation。
   * @returns {Promise<void>} 可见开关和作者 BPMN 属性均与目标状态一致后结束。
   */
  async configureCompensationActivity(elementId, enabled = true) {
    await this.selectCanvasShape(elementId)
    const item = this.properties.locator('.el-form-item').filter({ hasText: '补偿活动' })
    await expect(item, `活动 ${elementId} 必须提供补偿活动开关`).toHaveCount(1)
    const switchInput = item.getByRole('switch')
    await expect(switchInput).toBeEnabled()
    if (await switchInput.isChecked() !== enabled) {
      // Element Plus 原生 input 隐藏在可见容器内，真实用户点击外层开关完成 change 提交。
      await item.locator('.el-switch').click()
    }
    await expect(switchInput).toHaveJSProperty('checked', enabled)
    const xml = await this.readDesignerXml()
    const document = new DOMParser().parseFromString(xml, 'application/xml')
    const activities = [...document.getElementsByTagNameNS('*', '*')]
      .filter(element => element.getAttribute('id') === elementId)
    expect(activities, `补偿活动 ${elementId} 必须在作者 BPMN 中唯一`).toHaveLength(1)
    expect(activities[0].getAttribute('isForCompensation') === 'true').toBe(enabled)
  }

  /**
   * 通过属性检查器配置任意画布元素的稳定标识和显示名称。
   * @param {string} elementId 当前 BPMN 元素标识。
   * @param {string} stableElementId 需要持久化的新标识。
   * @param {string} elementName 用户可见名称。
   * @returns {Promise<string>} 最终持久化的元素标识。
   */
  async configureElementIdentity(elementId, stableElementId, elementName) {
    const currentElementId = await this.properties.locator('.designer-properties-panel__context code')
      .textContent().catch(() => '')
    if (currentElementId?.trim() !== elementId) await this.selectCanvasShape(elementId)
    await expect(this.properties.getByLabel('元素标识')).toBeVisible()
    if (stableElementId !== elementId) {
      const idInput = this.properties.getByLabel('元素标识')
      await idInput.fill(stableElementId)
      await idInput.press('Tab')
      await expect(this.page.locator(`.djs-element[data-element-id="${elementId}"]`)).toHaveCount(0)
      await expect(this.page.locator(`.djs-element[data-element-id="${stableElementId}"]`)).toHaveCount(1)
    }
    const nameInput = this.properties.getByLabel('元素名称')
    await nameInput.fill(elementName)
    await nameInput.press('Tab')
    return stableElementId
  }

  /**
   * 选择画布图形的非文字安全区域，恢复 bpmn-js 当前选中元素。
   * @param {string} elementId BPMN 图形稳定标识。
   * @returns {Promise<void>} 属性检查器切换到目标元素后结束。
   */
  async selectCanvasShape(elementId) {
    // 属性弹窗关闭后必须等待遮罩动画结束，否则真实鼠标事件会被透明退出层吞掉。
    await expect(this.page.locator('.process-designer .el-loading-mask:visible')).toHaveCount(0)
    await expect(this.page.locator('.el-overlay:visible')).toHaveCount(0)
    const element = this.page.locator(`.djs-shape[data-element-id="${elementId}"]`)
    await expect(element, `画布图形 ${elementId} 必须存在`).toHaveCount(1)
    const currentElementId = await this.properties.locator('.designer-properties-panel__context code')
      .textContent().catch(() => '')
    if (currentElementId?.trim() === elementId) return
    await this.page.keyboard.press('Escape')
    const points = await element.locator('.djs-visual').evaluate((visual, targetElementId) => {
      // 外层图元矩形可能包含名称标签或其他图形；只保留目标自身位于最上层的真实可点击点。
      const bounds = visual.getBoundingClientRect()
      if (bounds.width <= 0 || bounds.height <= 0) throw new Error('BPMN 图形缺少可点击区域')
      return [
        [0.5, 0.5], [0.25, 0.5], [0.75, 0.5], [0.5, 0.25], [0.5, 0.75],
        [0.25, 0.25], [0.75, 0.25], [0.25, 0.75], [0.75, 0.75]
      ].map(([xRatio, yRatio]) => {
        const x = bounds.left + bounds.width * xRatio
        const y = bounds.top + bounds.height * yRatio
        const topNode = document.elementFromPoint(x, y)
        const topElementId = topNode?.closest?.('.djs-element')?.getAttribute('data-element-id') || ''
        return { x, y, topElementId, targetVisible: topElementId === targetElementId }
      }).filter(candidate => candidate.targetVisible)
    }, elementId)
    expect(points.length, `画布图形 ${elementId} 必须至少存在一个未被其他图形遮挡的点击点`).toBeGreaterThan(0)
    const contextCode = this.properties.locator('.designer-properties-panel__context code')
    for (const point of points) {
      await this.page.mouse.click(point.x, point.y)
      if ((await contextCode.textContent().catch(() => ''))?.trim() === elementId) return
    }
    await expect(contextCode).toHaveText(elementId)
  }

  /**
   * 选择画布连线的真实 SVG 命中层，避免条件属性被写入错误元素。
   * @param {string} flowId 顺序流稳定标识。
   * @returns {Promise<void>} 顺序流进入 bpmn-js 选中态且条件编辑器可见后结束。
   */
  async selectSequenceFlow(flowId) {
    await expect(this.page.locator('.process-designer .el-loading-mask:visible')).toHaveCount(0)
    await expect(this.page.locator('.el-overlay:visible')).toHaveCount(0)
    const flow = this.page.locator(`.djs-connection[data-element-id="${flowId}"]`)
    await expect(flow, `顺序流 ${flowId} 必须唯一挂载`).toHaveCount(1)
    const point = await flow.locator('.djs-hit').evaluate(path => {
      const matrix = path.getScreenCTM()
      if (!matrix) throw new Error('BPMN 顺序流缺少屏幕坐标矩阵')
      const totalLength = path.getTotalLength()
      const candidates = [0.2, 0.5, 0.8].map(ratio => {
        const local = path.getPointAtLength(totalLength * ratio)
        const screen = new DOMPoint(local.x, local.y).matrixTransform(matrix)
        const hitIndex = document.elementsFromPoint(screen.x, screen.y).indexOf(path)
        return { x: screen.x, y: screen.y, hitIndex: hitIndex < 0 ? Number.MAX_SAFE_INTEGER : hitIndex }
      })
      return candidates.sort((left, right) => left.hitIndex - right.hitIndex)[0]
    })
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      await this.page.mouse.click(point.x, point.y)
      if (await flow.evaluate(element => element.classList.contains('selected'))) break
    }
    await expect(flow).toHaveClass(/selected/u)
    await expect(this.properties.locator('.designer-properties-panel__context code')).toHaveText(flowId)
  }

  /**
   * 在条件分支编辑器中选择一个 Element Plus 下拉选项。
   * @param {string} accessibleName 下拉输入的可访问名称。
   * @param {string} optionLabel 需要选择的可见选项文案。
   * @returns {Promise<void>} 选项进入规则草稿并关闭下拉后结束。
   */
  async selectConditionOption(accessibleName, optionLabel) {
    const input = this.page.getByRole('combobox', { name: accessibleName })
    const select = input.locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " el-select ")][1]')
    await select.locator('.el-select__wrapper').click()
    await expect(input, `${accessibleName} 必须真实展开`).toHaveAttribute('aria-expanded', 'true')
    const escapedLabel = optionLabel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const option = this.page.locator('.el-select-dropdown:visible').getByRole('option')
      .filter({ hasText: new RegExp(`^\\s*${escapedLabel}`) })
    await expect(option, `${accessibleName} 必须存在唯一选项 ${optionLabel}`).toHaveCount(1)
    await option.click()
  }

  /**
   * 为排他或包容网关的顺序流配置一组类型化 AND 规则。
   * @param {{flowId:string,branchName:string,rules:Array<{fieldLabel:string,operatorLabel:string,value:string|number,valueLabel?:string}>}} options 分支标识、名称和规则。
   * @returns {Promise<void>} 受控规则通过属性面板写入 bpmn-js 命令栈后结束。
   */
  async configureConditionRuleBranch(options) {
    const { flowId, branchName, rules } = options
    await this.selectSequenceFlow(flowId)
    const editor = this.page.locator('.condition-editor')
    await expect(editor).toBeVisible()
    await editor.getByPlaceholder('例如：金额超过 5000 元').fill(branchName)
    for (let index = 1; index <= rules.length; index += 1) {
      if (await editor.getByRole('combobox', { name: /^规则组 1 字段 \d+$/u }).count() < index) {
        await editor.getByRole('button', { name: '添加规则', exact: true }).click()
      }
      const rule = rules[index - 1]
      await this.selectConditionOption(`规则组 1 字段 ${index}`, rule.fieldLabel)
      await this.selectConditionOption(`规则组 1 运算符 ${index}`, rule.operatorLabel)
      if (rule.valueLabel !== undefined) {
        await this.selectConditionOption(`规则组 1 条件值 ${index}`, rule.valueLabel)
      } else if (typeof rule.value === 'number') {
        await editor.getByRole('spinbutton', { name: `规则组 1 数值 ${index}` }).fill(String(rule.value))
      } else {
        await editor.getByRole('textbox', { name: `规则组 1 文本值 ${index}` }).fill(String(rule.value))
      }
    }
    await editor.getByRole('button', { name: '应用规则', exact: true }).click()
    await expect(editor).toContainText(branchName)
  }

  /**
   * 把目标网关出线设为唯一默认分支并保存分支名称。
   * @param {string} flowId 顺序流稳定标识。
   * @param {string} branchName 默认分支名称。
   * @returns {Promise<void>} 网关默认引用和分支配置写入命令栈后结束。
   */
  async configureDefaultConditionBranch(flowId, branchName) {
    await this.selectSequenceFlow(flowId)
    const editor = this.page.locator('.condition-editor')
    await expect(editor).toBeVisible()
    await editor.getByRole('button', { name: '设为默认分支', exact: true }).click()
    await expect(editor.getByText('默认分支', { exact: true }).last()).toBeVisible()
    await editor.getByPlaceholder('例如：金额超过 5000 元').fill(branchName)
    await editor.getByRole('button', { name: '保存分支名称', exact: true }).click()
    await expect(editor.getByText('默认分支已设置', { exact: true })).toBeVisible()
  }

  /**
   * 从设计器 XML 预览读取当前 bpmn-js 作者资源。
   * @returns {Promise<string>} 当前模型的 UTF-8 BPMN XML 正文。
   */
  async readDesignerXml() {
    await this.page.getByRole('button', { name: '预览流程源码' }).click()
    await this.page.getByRole('menuitem', { name: 'XML 预览', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: 'XML 预览' })
    await expect(dialog).toBeVisible()
    const xml = await dialog.getByRole('textbox').inputValue()
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    return xml
  }

  /**
   * 从作者 XML 中查找两个稳定节点之间的唯一顺序流标识。
   * @param {string} sourceRef 顺序流上游元素标识。
   * @param {string} targetRef 顺序流下游元素标识。
   * @returns {Promise<string>} 唯一顺序流标识。
   */
  async findSequenceFlowId(sourceRef, targetRef) {
    const xml = await this.readDesignerXml()
    const document = new DOMParser().parseFromString(xml, 'application/xml')
    const flows = [...document.getElementsByTagNameNS('*', 'sequenceFlow')]
      .filter(flow => flow.getAttribute('sourceRef') === sourceRef && flow.getAttribute('targetRef') === targetRef)
    expect(flows, `${sourceRef} 到 ${targetRef} 必须只有一条顺序流`).toHaveLength(1)
    const flowId = flows[0].getAttribute('id') || ''
    expect(flowId, `${sourceRef} 到 ${targetRef} 的顺序流必须有稳定标识`).not.toBe('')
    return flowId
  }

  /**
   * 确保两个 BPMN 图形之间存在且仅存在一条顺序流。
   * @param {string} sourceRef 顺序流上游元素标识。
   * @param {string} targetRef 顺序流下游元素标识。
   * @returns {Promise<string>} 复用自动插入的顺序流，或通过真实鼠标连接创建后的稳定标识。
   */
  async ensureSequenceFlow(sourceRef, targetRef) {
    const xml = await this.readDesignerXml()
    const document = new DOMParser().parseFromString(xml, 'application/xml')
    const existingFlows = [...document.getElementsByTagNameNS('*', 'sequenceFlow')]
      .filter(flow => flow.getAttribute('sourceRef') === sourceRef && flow.getAttribute('targetRef') === targetRef)
    expect(existingFlows.length, `${sourceRef} 到 ${targetRef} 不得存在重复顺序流`).toBeLessThanOrEqual(1)
    if (existingFlows.length === 1) {
      const existingId = existingFlows[0].getAttribute('id') || ''
      expect(existingId, `${sourceRef} 到 ${targetRef} 的自动顺序流必须有稳定标识`).not.toBe('')
      return existingId
    }
    return this.connectShapes(sourceRef, targetRef)
  }

  /**
   * 配置任意用户任务的稳定标识、显示名称和候选角色规则。
   * @param {string} elementId 当前 BPMN 元素标识。
   * @param {string} roleName 正式角色显示名称。
   * @param {string} taskName 用户任务显示名称。
   * @param {string} [stableElementId] 需要持久化的稳定任务标识；省略时保留原标识。
   * @returns {Promise<string>} 最终持久化的任务元素标识。
   */
  async configureCandidateRoleForElement(elementId, roleName, taskName, stableElementId = elementId) {
    return this.configureTaskParticipantRuleForElement({
      elementId,
      stableElementId,
      taskName,
      ruleLabel: '候选角色 / 部门',
      targetFieldLabel: '候选角色或部门',
      targetName: roleName
    })
  }

  /**
   * 通过属性面板配置任意单实例用户任务的正式参与者规则。
   * @param {{elementId:string,stableElementId?:string,taskName:string,ruleLabel:string,targetFieldLabel?:string,targetName?:string}} options 任务标识、名称、规则及可选目录目标。
   * @returns {Promise<string>} 最终持久化的任务元素标识。
   */
  async configureTaskParticipantRuleForElement(options) {
    const {
      elementId,
      stableElementId = elementId,
      taskName,
      ruleLabel,
      targetFieldLabel = '',
      targetName = ''
    } = options
    const currentElementId = await this.properties.locator('.designer-properties-panel__context code')
      .textContent().catch(() => '')
    const userTaskSelected = await this.properties.getByText('用户任务', { exact: true })
      .isVisible().catch(() => false)
    if (currentElementId?.trim() !== elementId || !userTaskSelected) {
      // 用户任务中心区域承载名称内联编辑，固定点击底部空白区只触发画布选中。
      await this.page.locator(`.djs-shape[data-element-id="${elementId}"]`).click({ position: { x: 10, y: 70 } })
    }
    await expect(this.properties.getByText('用户任务', { exact: true })).toBeVisible()
    if (stableElementId !== elementId) {
      const idInput = this.properties.getByLabel('元素标识')
      await idInput.fill(stableElementId)
      await idInput.press('Tab')
      await expect(this.page.locator(`.djs-shape[data-element-id="${elementId}"]`)).toHaveCount(0)
      await expect(this.page.locator(`.djs-shape[data-element-id="${stableElementId}"]`)).toHaveCount(1)
    }
    await this.properties.getByLabel('元素名称').fill(taskName)
    await this.properties.getByLabel('元素名称').press('Tab')

    const ruleItem = this.properties.locator('.el-form-item').filter({ hasText: '办理人规则' }).first()
    await ruleItem.locator('.el-select').click()
    await this.page.getByRole('option', { name: ruleLabel, exact: true }).click()

    if (targetFieldLabel) {
      const targetLabels = this.properties.locator('.el-form-item__label').filter({ hasText: targetFieldLabel })
      await expect(targetLabels, `参与者目录字段 ${targetFieldLabel} 必须唯一`).toHaveCount(1)
      const targetSelect = targetLabels.first().locator('..').locator('.el-select')
      // 多选目录以 tag 回显，单选目录以 selected-item 文本回显；两种都是正式 Element Plus 选中态。
      const selectedTarget = targetSelect.locator('.el-tag, .el-select__selected-item')
        .filter({ hasText: targetName })
      if (!await selectedTarget.first().isVisible().catch(() => false)) {
        // 多选目录再次点击已选项会执行取消选择，重开模型时必须先核对现有业务状态。
        const targetInput = targetSelect.getByRole('combobox')
        await targetSelect.locator('.el-select__wrapper').click()
        await expect(targetInput, `参与者目录 ${targetFieldLabel} 必须真实展开`)
          .toHaveAttribute('aria-expanded', 'true')
        await targetInput.fill(targetName)
        const listboxId = await targetInput.getAttribute('aria-controls')
        expect(listboxId, `参与者目录 ${targetFieldLabel} 必须关联唯一选项列表`).toBeTruthy()
        const targetOption = this.page.locator(`[id="${listboxId}"]`).getByRole('option')
          .filter({ hasText: targetName }).first()
        await expect(targetOption, `参与者目录必须返回 ${targetName}`).toBeVisible()
        if (await targetOption.getAttribute('aria-selected') !== 'true') {
          await targetOption.click()
        }
        await this.page.keyboard.press('Escape')
      }
      await expect(selectedTarget.first(), `参与者目录必须保持选中 ${targetName}`).toBeVisible()
    }
    await expect(this.properties.locator('.participant-rule-editor__result')).toContainText('最终命中')
    return stableElementId
  }

  /**
   * 为单实例用户任务绑定正式节点表单，并把其中一个单值字段配置为直接办理人来源。
   * @param {{elementId:string,taskName:string,formName:string,formFieldLabel:string}} options 任务标识、任务名称、节点表单和用户字段可见标签。
   * @returns {Promise<void>} 节点表单与 FORM_USER 规则均经 bpmn-js 命令栈写入后结束。
   */
  async configureFormUserParticipantRule(options) {
    const { elementId, taskName, formName, formFieldLabel } = options
    await this.selectCanvasShape(elementId)
    const nodeFormLabels = this.properties.locator('.el-form-item__label').filter({ hasText: '节点表单' })
    await expect(nodeFormLabels, 'FORM_USER 任务必须提供唯一节点表单选择器').toHaveCount(1)
    const nodeFormSelect = nodeFormLabels.first().locator('..').locator('.el-select')
    await nodeFormSelect.locator('.el-select__wrapper').click()
    await this.page.getByRole('option', { name: formName, exact: true }).click()
    await expect(this.properties.locator('.designer-properties-panel__context code'),
      '绑定节点表单后属性面板必须仍属于目标任务').toHaveText(elementId)

    await this.configureTaskParticipantRuleForElement({
      elementId,
      taskName,
      ruleLabel: '表单用户字段'
    })
    const fieldLabels = this.properties.locator('.el-form-item__label').filter({ hasText: '用户字段' })
    await expect(fieldLabels, 'FORM_USER 必须提供唯一用户字段选择器').toHaveCount(1)
    const fieldInput = fieldLabels.first().locator('..').getByRole('combobox')
    await this.selectLinkedOption(fieldInput, formFieldLabel, 'FORM_USER 用户字段选择器')
    await expect(this.properties.locator('.participant-rule-editor__result'),
      'FORM_USER 规则摘要必须说明实时读取表单用户').toContainText('读取所选正式表单字段')
  }

  /**
   * 通过用户任务属性面板配置审批 SLA，并确保每次数值变更都由真实失焦事件提交。
   * @param {{elementId:string,calendarName:string,calendarKey:string,reminderMinutes:number,maxReminders:number,reminderRepeatMinutes:number,escalationMinutes:number,escalationUsername:string,escalationUserName:string}} options 目标节点、正式日历、提醒规则和升级办理人。
   * @returns {Promise<void>} 完整 SLA 作者配置进入 bpmn-js 命令栈并在属性面板稳定回显后结束。
   */
  async configureTaskSla(options) {
    const {
      elementId,
      calendarName,
      calendarKey,
      reminderMinutes,
      maxReminders,
      reminderRepeatMinutes,
      escalationMinutes,
      escalationUsername,
      escalationUserName
    } = options
    expect(Number.isInteger(reminderMinutes) && reminderMinutes > 0, '首次提醒分钟必须是正整数').toBe(true)
    expect(Number.isInteger(maxReminders) && maxReminders > 0, '最大提醒次数必须是正整数').toBe(true)
    expect(Number.isInteger(reminderRepeatMinutes) && reminderRepeatMinutes > 0, '重复提醒分钟必须是正整数').toBe(true)
    expect(Number.isInteger(escalationMinutes), '升级分钟必须是整数').toBe(true)
    expect(escalationMinutes, '升级时间必须晚于最后一次提醒')
      .toBeGreaterThan(reminderMinutes + reminderRepeatMinutes * (maxReminders - 1))

    await this.selectCanvasShape(elementId)
    const editor = this.properties.locator('.user-task-sla-editor')
    await expect(editor, '用户任务必须提供唯一审批 SLA 编辑器').toHaveCount(1)
    await expect(editor).toBeVisible()
    const slaSwitch = editor.getByRole('switch')
    await expect(slaSwitch, '审批 SLA 开关必须允许真实用户启用').toBeEnabled()
    if (!await slaSwitch.isChecked()) {
      await editor.locator('.el-switch').click()
    }
    await expect(slaSwitch).toBeChecked()

    const selectOption = async (fieldLabel, optionLabel, searchText = '') => {
      const item = editor.locator('.el-form-item').filter({ hasText: fieldLabel }).first()
      await expect(item, `审批 SLA ${fieldLabel} 字段必须可见`).toBeVisible()
      const select = item.locator('.el-select')
      await select.locator('.el-select__wrapper').click()
      const input = select.getByRole('combobox')
      if (searchText) await input.fill(searchText)
      const option = this.page.locator('.el-select-dropdown:visible').getByRole('option')
        .filter({ hasText: optionLabel }).first()
      await expect(option, `审批 SLA ${fieldLabel} 必须返回 ${optionLabel}`).toBeVisible()
      await option.click()
      await this.page.keyboard.press('Escape')
      await expect(item, `审批 SLA ${fieldLabel} 必须回显 ${optionLabel}`).toContainText(optionLabel)
    }
    const fillNumber = async (fieldLabel, value) => {
      const item = editor.locator('.el-form-item').filter({ hasText: fieldLabel }).first()
      const input = item.getByRole('spinbutton')
      await expect(input, `审批 SLA ${fieldLabel} 数值框必须可见`).toBeVisible()
      await input.fill(String(value))
      await input.press('Tab')
      await expect(input).toHaveValue(String(value))
    }

    await selectOption('业务日历', `${calendarName} · ${calendarKey}`)
    // 按合法中间状态提交，避免跨字段校验在用户尚未填完时拒绝后续真实输入。
    await fillNumber('首次提醒（分钟）', reminderMinutes)
    await fillNumber('最大提醒次数', maxReminders)
    if (maxReminders > 1) await fillNumber('重复提醒间隔（分钟）', reminderRepeatMinutes)
    await fillNumber('超时升级（分钟）', escalationMinutes)
    await selectOption('升级办理人', escalationUserName, escalationUsername)
  }

  /**
   * 通过审批人设置面板配置指定用户的受控会签或或签。
   * @param {{elementId:string,stableElementId?:string,taskName:string,mode:'ALL'|'ANY',users:Array<{username:string,displayName:string}>}} options 任务节点、稳定标识、显示名称、审批模式和正式办理用户目录项。
   * @returns {Promise<string>} 受控多实例来源、成员和完成策略全部写入后返回最终元素标识。
   */
  async configureControlledMultiInstanceForUsers(options) {
    return this.configureControlledMultiInstance({ ...options, memberSource: '指定用户', identities: options.users })
  }

  /**
   * 通过审批人设置面板配置受控会签或或签的人员来源及可选身份目录。
   * @param {{elementId:string,stableElementId?:string,taskName:string,mode:'ALL'|'ANY',memberSource:'办理时选择'|'发起时选择'|'指定用户'|'指定角色'|'指定部门',identities?:Array<{keyword:string,displayName:string}|{username:string,displayName:string}>}} options 任务节点、稳定标识、审批模式、人员来源和正式目录项。
   * @returns {Promise<string>} 受控多实例来源、成员和完成策略全部写入后返回最终元素标识。
   */
  async configureControlledMultiInstance(options) {
    const { elementId, stableElementId = elementId, taskName, mode, memberSource, identities = [] } = options
    expect(['ALL', 'ANY'], '受控多实例模式只允许 ALL 或 ANY').toContain(mode)
    const sourceContracts = {
      指定用户: { fieldLabel: '指定办理用户' },
      指定角色: { fieldLabel: '指定办理角色' },
      指定部门: { fieldLabel: '指定办理部门' }
    }
    expect(['办理时选择', '发起时选择', ...Object.keys(sourceContracts)], '受控多实例人员来源必须是产品支持的五类之一')
      .toContain(memberSource)
    if (sourceContracts[memberSource]) {
      expect(identities.length, `${memberSource}至少需要一个正式身份对象`).toBeGreaterThan(0)
    }
    const element = this.page.locator(`.djs-shape[data-element-id="${elementId}"]`)
    await expect(element, `画布元素 ${elementId} 必须存在`).toHaveCount(1)
    const currentElementId = await this.properties.locator('.designer-properties-panel__context code')
      .textContent().catch(() => '')
    const userTaskSelected = await this.properties.getByText('用户任务', { exact: true })
      .isVisible().catch(() => false)
    if (currentElementId?.trim() !== elementId || !userTaskSelected) {
      // 追加节点通常已经处于选中态；仅在选中态丢失时点击节点空白区，避免重复点击把属性面板清空。
      await element.click({ position: { x: 10, y: 70 } })
    }
    await expect(this.properties.getByText('用户任务', { exact: true })).toBeVisible()
    if (stableElementId !== elementId) {
      const idInput = this.properties.getByLabel('元素标识')
      await idInput.fill(stableElementId)
      await idInput.press('Tab')
      await expect(this.page.locator(`.djs-shape[data-element-id="${elementId}"]`)).toHaveCount(0)
      await expect(this.page.locator(`.djs-shape[data-element-id="${stableElementId}"]`)).toHaveCount(1)
    }
    await this.properties.getByLabel('元素名称').fill(taskName)
    await this.properties.getByLabel('元素名称').press('Tab')

    const approvalMethod = mode === 'ALL' ? '会签' : '或签'
    const methodOption = this.properties.locator('.user-task-approval__method-list .el-radio')
      .filter({ hasText: approvalMethod })
    await expect(methodOption, `${approvalMethod}审批方式必须唯一`).toHaveCount(1)
    await methodOption.click()
    await expect(this.properties.locator('.user-task-approval__heading .el-tag')).toHaveText(approvalMethod)

    const sourceOption = this.properties.locator('.user-task-approval__source-grid .el-radio')
      .filter({ hasText: memberSource })
    await expect(sourceOption, `${memberSource}来源必须唯一`).toHaveCount(1)
    await sourceOption.click()
    const contract = sourceContracts[memberSource]
    if (!contract) return stableElementId

    const identityItem = this.properties.locator('.el-form-item').filter({ hasText: contract.fieldLabel })
    await expect(identityItem, `${contract.fieldLabel}目录必须唯一`).toHaveCount(1)
    const identitySelect = identityItem.locator('.el-select')
    for (const identity of identities) {
      // 多选远程目录每次使用账号或名称关键字检索，再按显示名选择，避免同名对象误入作者模型。
      await identitySelect.locator('.el-select__wrapper').click()
      const input = identitySelect.getByRole('combobox')
      await input.fill(identity.keyword || identity.username)
      const option = this.page.locator('.el-select-dropdown:visible').getByRole('option')
        .filter({ hasText: identity.displayName }).first()
      await expect(option, `受控身份目录必须返回 ${identity.displayName}`).toBeVisible()
      await option.click()
    }
    await this.page.keyboard.press('Escape')
    for (const identity of identities) {
      await expect(identityItem, `已选身份必须回显 ${identity.displayName}`).toContainText(identity.displayName)
    }
    return stableElementId
  }

  /**
   * 读取画布中全部非标签图形的 BPMN 元素标识。
   * @returns {Promise<string[]>} 当前画布图形标识列表。
   */
  async canvasShapeIds() {
    return this.page.locator('.djs-shape[data-element-id]').evaluateAll(elements => (
      elements.map(element => element.getAttribute('data-element-id')).filter(Boolean)
    ))
  }

  /**
   * 通过工具栏执行服务端校验并保存当前 BPMN 模型。
   * @returns {Promise<void>} 校验通过且后端模型保存成功后结束。
   */
  async validateAndSave() {
    const validatePromise = this.page.waitForResponse(response => matchesEndpoint(response, '/workflow/model/validate', 'POST'))
    await this.page.getByRole('button', { name: '服务端校验' }).click()
    await expectAjaxSuccess(await validatePromise, '/workflow/model/validate')
    const validationDialog = this.page.getByRole('dialog', { name: '流程校验' })
    await expect(validationDialog.getByText('校验通过', { exact: true })).toBeVisible()
    await validationDialog.getByRole('button', { name: '关闭此对话框' }).click()

    const savePromise = this.page.waitForResponse(response => matchesEndpoint(response, '/workflow/model/save', 'POST'))
    await this.page.getByRole('button', { name: '保存', exact: true }).click()
    await expectAjaxSuccess(await savePromise, '/workflow/model/save')
    await expect(this.page.getByText('流程设计保存成功', { exact: true })).toBeVisible()
  }

  /**
   * 返回流程模型列表。
   * @returns {Promise<void>} 模型列表重新加载完成后结束。
   */
  async returnToModels() {
    await this.page.getByRole('button', { name: '返回模型列表' }).click()
    await expect(this.page).toHaveURL(/\/workflow\/model(?:\?|$)/u)
  }
}
