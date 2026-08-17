import { expect } from '@playwright/test'
import { expectAjaxSuccess, matchesEndpoint } from '../../e2e/support/http.js'

export class WorkflowConfigurationPage {
  /**
   * 创建工作流配置页面对象。
   * @param {import('@playwright/test').Page} page 已完成设计者登录的浏览器页面。
   */
  constructor(page) {
    this.page = page
  }

  /**
   * 通过分类页面新增正式流程分类并核对列表回显。
   * @param {{name:string,code:string,remark:string}} category 分类名称、编码和备注。
   * @returns {Promise<void>} 保存成功且列表出现唯一分类后结束。
   */
  async createCategory(category) {
    await this.page.goto('/workflow/category')
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入分类编码', category.code)
    const existingRows = this.row(category.code)
    const existingCount = await existingRows.count()
    expect(existingCount, `分类编码 ${category.code} 不得存在重复数据`).toBeLessThanOrEqual(1)
    if (existingCount === 1) return

    await this.page.getByRole('button', { name: '新增', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '新增流程分类' })
    await dialog.getByLabel('分类名称').fill(category.name)
    await dialog.getByLabel('分类编码').fill(category.code)
    await dialog.getByLabel('备注').fill(category.remark)
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(this.page.getByText('流程分类保存成功', { exact: true })).toBeVisible()
    await this.filterTable('请输入分类编码', category.code)
    await expect(this.row(category.code)).toHaveCount(1)
  }

  /**
   * 通过可视化表单生成器添加一个必填文本字段并保存正式表单。
   * @param {{name:string,remark:string,amountRequired?:boolean}} form 表单名称、备注和金额字段是否必填。
   * @returns {Promise<void>} 表单正文真实落库并在列表回显后结束。
   */
  async createTextForm(form) {
    await this.page.goto('/workflow/form')
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    const existingRows = this.row(form.name)
    const existingCount = await existingRows.count()
    if (existingCount === 1) return
    if (existingCount > 1) {
      await this.removeFilteredTestRows(existingRows, form.name, '流程表单删除成功')
    }

    await this.page.getByRole('button', { name: '新增', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form-design/u)
    await this.page.locator('.components-item').filter({ hasText: '单行文本' }).first().click()
    await expect(this.page.locator('.drawing-board .drawing-item')).toHaveCount(1)
    await this.page.getByRole('button', { name: '保存', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '新增流程表单' })
    await dialog.getByLabel('表单名称').fill(form.name)
    await dialog.getByLabel('备注').fill(form.remark)
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(this.page.getByText('流程表单保存成功', { exact: true })).toBeVisible()
    // 保存后通过页面正式返回入口触发路由和列表初始化，避免绕过真实用户操作。
    await this.page.getByRole('button', { name: '返回表单列表', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form(?:\?.*)?$/u)
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    await expect(this.row(form.name)).toHaveCount(1)
  }

  /**
   * 通过可视化表单生成器创建包含必填文本和私有附件的正式流程表单。
   * @param {{name:string,remark:string,textFieldName?:string,textLabel?:string,textPlaceholder?:string,attachmentFieldName?:string,attachmentLabel?:string}} form 表单名称、字段稳定标识和用户可见文案。
   * @returns {Promise<void>} 两个字段完成 UI 配置、正式保存并在列表唯一回显后结束。
   */
  async createTextAttachmentForm(form) {
    const textFieldName = form.textFieldName || 'requestTitle'
    const textLabel = form.textLabel || '申请主题'
    const textPlaceholder = form.textPlaceholder || '请输入申请主题'
    const attachmentFieldName = form.attachmentFieldName || 'proofFiles'
    const attachmentLabel = form.attachmentLabel || '证明附件'

    await this.page.goto('/workflow/form')
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    const existingRows = this.row(form.name)
    const existingCount = await existingRows.count()
    if (existingCount === 1) return
    if (existingCount > 1) {
      await this.removeFilteredTestRows(existingRows, form.name, '流程表单删除成功')
    }

    await this.page.getByRole('button', { name: '新增', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form-design/u)
    await this.page.locator('.components-item').filter({ hasText: '单行文本' }).first().click()
    await expect(this.page.locator('.drawing-board .drawing-item')).toHaveCount(1)
    await this.configureActiveFormField({
      fieldName: textFieldName,
      label: textLabel,
      placeholder: textPlaceholder
    })

    await this.page.locator('.components-item').filter({ hasText: '上传' }).first().click()
    await expect(this.page.locator('.drawing-board .drawing-item')).toHaveCount(2)
    await this.configureActiveFormField({ fieldName: attachmentFieldName, label: attachmentLabel })

    await this.page.getByRole('button', { name: '保存', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '新增流程表单' })
    await dialog.getByLabel('表单名称').fill(form.name)
    await dialog.getByLabel('备注').fill(form.remark)
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(this.page.getByText('流程表单保存成功', { exact: true })).toBeVisible()
    await this.page.getByRole('button', { name: '返回表单列表', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form(?:\?.*)?$/u)
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    await expect(this.row(form.name)).toHaveCount(1)
  }

  /**
   * 通过可视化表单生成器创建条件路由所需的文本和数值字段。
   * @param {{name:string,remark:string}} form 表单名称和备注。
   * @returns {Promise<void>} 两个正式字段经 UI 配置、保存并在表单列表唯一回显后结束。
   */
  async createConditionRoutingForm(form) {
    // amountRequired 控制用户能否合法留空，用于验证缺失条件变量时的安全失败和事务回滚。
    const amountRequired = form.amountRequired !== false
    await this.page.goto('/workflow/form')
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    const existingRows = this.row(form.name)
    const existingCount = await existingRows.count()
    if (existingCount === 1) return
    if (existingCount > 1) {
      await this.removeFilteredTestRows(existingRows, form.name, '流程表单删除成功')
    }

    await this.page.getByRole('button', { name: '新增', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form-design/u)
    await this.addAndConfigureFormField('单行文本', {
      fieldName: 'requestTitle', label: '申请主题', placeholder: '请输入申请主题', required: true
    }, 1)
    await this.addAndConfigureFormField('计数器', {
      fieldName: 'amount', label: '申请金额', required: amountRequired
    }, 2)

    await this.page.getByRole('button', { name: '保存', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '新增流程表单' })
    await dialog.getByLabel('表单名称').fill(form.name)
    await dialog.getByLabel('备注').fill(form.remark)
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(this.page.getByText('流程表单保存成功', { exact: true })).toBeVisible()
    await this.page.getByRole('button', { name: '返回表单列表', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form(?:\?.*)?$/u)
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    await expect(this.row(form.name)).toHaveCount(1)
  }

  /**
   * 通过可视化表单生成器创建文本、金额、日期、枚举和备注组成的正式数据校验表单。
   * @param {{name:string,remark:string}} form 表单名称和备注。
   * @returns {Promise<void>} 五类字段均经 UI 配置并在正式表单列表唯一回显后结束。
   */
  async createValidationForm(form) {
    await this.page.goto('/workflow/form')
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    const existingRows = this.row(form.name)
    const existingCount = await existingRows.count()
    if (existingCount === 1) return
    if (existingCount > 1) {
      await this.removeFilteredTestRows(existingRows, form.name, '流程表单删除成功')
    }

    await this.page.getByRole('button', { name: '新增', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form-design/u)
    await this.addAndConfigureFormField('单行文本', {
      fieldName: 'requestTitle', label: '申请主题', placeholder: '请输入申请主题', required: true
    }, 1)
    await this.addAndConfigureFormField('计数器', {
      fieldName: 'amount', label: '申请金额', required: true
    }, 2)
    await this.addAndConfigureFormField('日期选择', {
      fieldName: 'requestDate', label: '申请日期', placeholder: '请选择申请日期', required: true
    }, 3)
    await this.addAndConfigureFormField('下拉选择', {
      fieldName: 'requestType', label: '申请类型', placeholder: '请选择申请类型', required: true,
      options: [
        { label: '日常申请', value: 'DAILY' },
        { label: '紧急申请', value: 'URGENT' }
      ]
    }, 4)
    await this.addAndConfigureFormField('多行文本', {
      fieldName: 'description', label: '申请说明', placeholder: '请输入申请说明', required: false
    }, 5)

    await this.page.getByRole('button', { name: '保存', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '新增流程表单' })
    await dialog.getByLabel('表单名称').fill(form.name)
    await dialog.getByLabel('备注').fill(form.remark)
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(this.page.getByText('流程表单保存成功', { exact: true })).toBeVisible()
    await this.page.getByRole('button', { name: '返回表单列表', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form(?:\?.*)?$/u)
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    await expect(this.row(form.name)).toHaveCount(1)
  }

  /**
   * 通过可视化表单生成器创建申请主题和单值审批人下拉字段。
   * @param {{name:string,remark:string,userId:string,userLabel:string}} form 表单名称、备注、正式用户主键和用户可见标签。
   * @returns {Promise<void>} 两个字段经真实 UI 配置、保存并在正式表单列表唯一回显后结束。
   */
  async createFormUserAssignmentForm(form) {
    expect(String(form.userId), '表单用户字段选项必须使用正式正整数用户主键').toMatch(/^[1-9][0-9]*$/u)
    await this.page.goto('/workflow/form')
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    const existingRows = this.row(form.name)
    const existingCount = await existingRows.count()
    if (existingCount === 1) return
    if (existingCount > 1) {
      await this.removeFilteredTestRows(existingRows, form.name, '流程表单删除成功')
    }

    await this.page.getByRole('button', { name: '新增', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form-design/u)
    await this.addAndConfigureFormField('单行文本', {
      fieldName: 'requestTitle', label: '申请主题', placeholder: '请输入申请主题', required: true
    }, 1)
    await this.addAndConfigureFormField('下拉选择', {
      fieldName: 'approverId', label: '审批人', placeholder: '请选择审批人', required: true,
      options: [{ label: form.userLabel, value: String(form.userId) }]
    }, 2)

    await this.page.getByRole('button', { name: '保存', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '新增流程表单' })
    await dialog.getByLabel('表单名称').fill(form.name)
    await dialog.getByLabel('备注').fill(form.remark)
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(this.page.getByText('流程表单保存成功', { exact: true })).toBeVisible()
    await this.page.getByRole('button', { name: '返回表单列表', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form(?:\?.*)?$/u)
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    await expect(this.row(form.name)).toHaveCount(1)
  }

  /**
   * 通过可视化表单生成器创建带最大长度约束的单行文本表单。
   * @param {{name:string,remark:string,maxlength:number}} form 表单名称、备注和用户输入的最大字符数。
   * @returns {Promise<void>} 最大长度经真实属性面板配置、保存并在列表唯一回显后结束。
   */
  async createMaxlengthForm(form) {
    await this.page.goto('/workflow/form')
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    const existingRows = this.row(form.name)
    const existingCount = await existingRows.count()
    if (existingCount === 1) return
    if (existingCount > 1) {
      await this.removeFilteredTestRows(existingRows, form.name, '流程表单删除成功')
    }

    await this.page.getByRole('button', { name: '新增', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form-design/u)
    await this.addAndConfigureFormField('单行文本', {
      fieldName: 'requestTitle', label: '申请主题', placeholder: '请输入申请主题', required: true,
      maxlength: form.maxlength
    }, 1)
    await this.page.getByRole('button', { name: '保存', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '新增流程表单' })
    await dialog.getByLabel('表单名称').fill(form.name)
    await dialog.getByLabel('备注').fill(form.remark)
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(this.page.getByText('流程表单保存成功', { exact: true })).toBeVisible()
    await this.page.getByRole('button', { name: '返回表单列表', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form(?:\?.*)?$/u)
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    await expect(this.row(form.name)).toHaveCount(1)
  }

  /**
   * 通过可视化表单生成器创建一组正式单行文本字段，供节点字段权限场景复用。
   * @param {{name:string,remark:string,fields:Array<{fieldName:string,label:string,placeholder:string,required?:boolean}>}} form 表单名称、备注和有序字段目录。
   * @returns {Promise<void>} 全部字段经真实 UI 配置、保存并在正式表单列表唯一回显后结束。
   */
  async createPermissionFieldsForm(form) {
    expect(form.fields.length, '字段权限表单至少需要一个正式字段').toBeGreaterThan(0)
    await this.page.goto('/workflow/form')
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    const existingRows = this.row(form.name)
    const existingCount = await existingRows.count()
    if (existingCount === 1) return
    if (existingCount > 1) {
      await this.removeFilteredTestRows(existingRows, form.name, '流程表单删除成功')
    }

    await this.page.getByRole('button', { name: '新增', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form-design/u)
    for (let fieldIndex = 0; fieldIndex < form.fields.length; fieldIndex += 1) {
      // 字段顺序是部署快照和权限编辑器的共同目录，必须按测试规格稳定创建。
      await this.addAndConfigureFormField('单行文本', form.fields[fieldIndex], fieldIndex + 1)
    }
    await this.page.getByRole('button', { name: '保存', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '新增流程表单' })
    await dialog.getByLabel('表单名称').fill(form.name)
    await dialog.getByLabel('备注').fill(form.remark)
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(this.page.getByText('流程表单保存成功', { exact: true })).toBeVisible()
    await this.page.getByRole('button', { name: '返回表单列表', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form(?:\?.*)?$/u)
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    await expect(this.row(form.name)).toHaveCount(1)
  }

  /**
   * 通过可视化表单生成器创建受控整改循环使用的正式业务表单。
   * @param {{name:string,remark:string}} form 表单名称和备注。
   * @returns {Promise<void>} 申请主题、审批结论和整改说明经真实 UI 配置并保存后结束。
   */
  async createControlledLoopForm(form) {
    await this.page.goto('/workflow/form')
    await expect(this.page.locator('.app-container .el-table')).toBeVisible()
    await this.filterTable('请输入表单名称', form.name)
    const existingRows = this.row(form.name)
    const existingCount = await existingRows.count()
    if (existingCount === 1) return
    if (existingCount > 1) {
      await this.removeFilteredTestRows(existingRows, form.name, '流程表单删除成功')
    }

    await this.page.getByRole('button', { name: '新增', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form-design/u)
    await this.addAndConfigureFormField('单行文本', {
      fieldName: 'requestTitle', label: '申请主题', placeholder: '请输入申请主题', required: true
    }, 1)
    await this.addAndConfigureFormField('下拉选择', {
      fieldName: 'reviewResult', label: '审批结论', placeholder: '请选择审批结论', required: false,
      options: [
        { label: '继续整改', value: 'RECTIFY' },
        { label: '整改通过', value: 'PASS' }
      ]
    }, 2)
    await this.addAndConfigureFormField('多行文本', {
      fieldName: 'rectifyNote', label: '整改说明', placeholder: '请输入本轮整改说明', required: false
    }, 3)

    await this.page.getByRole('button', { name: '保存', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '新增流程表单' })
    await dialog.getByLabel('表单名称').fill(form.name)
    await dialog.getByLabel('备注').fill(form.remark)
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(this.page.getByText('流程表单保存成功', { exact: true })).toBeVisible()
    await this.page.getByRole('button', { name: '返回表单列表', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/form(?:\?.*)?$/u)
    await this.filterTable('请输入表单名称', form.name)
    await expect(this.row(form.name)).toHaveCount(1)
  }

  /**
   * 从左侧组件目录添加一个字段，并在右侧属性面板写入业务约束。
   * @param {string} componentLabel 左侧组件名称。
   * @param {{fieldName:string,label:string,placeholder?:string,required?:boolean,maxlength?:number,min?:number,max?:number,step?:number,precision?:number,options?:Array<{label:string,value:string|number}>}} field 字段变量、文案和约束。
   * @param {number} expectedCount 添加后画布字段总数。
   * @returns {Promise<void>} 字段属性和静态选项完成 UI 回填后结束。
   */
  async addAndConfigureFormField(componentLabel, field, expectedCount) {
    await this.page.locator('.components-item').filter({ hasText: componentLabel }).first().click()
    await expect(this.page.locator('.drawing-board .drawing-item')).toHaveCount(expectedCount)
    await this.configureActiveFormField(field)
  }

  /**
   * 通过模型列表新增绑定正式分类和表单的流程模型。
   * @param {{name:string,key:string,categoryName:string,formName:string,description:string}} model 模型元数据。
   * @returns {Promise<void>} 模型保存并在列表唯一回显后结束。
   */
  async createModel(model) {
    await this.page.goto('/workflow/model')
    await this.page.getByRole('button', { name: '新增', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '新增流程模型' })
    await dialog.getByLabel('模型名称').fill(model.name)
    await dialog.getByLabel('模型标识').fill(model.key)
    await this.selectFormItem(dialog, '流程分类', model.categoryName)
    await this.selectFormItem(dialog, '流程表单', model.formName)
    await dialog.getByLabel('模型描述').fill(model.description)
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(this.page.getByText('流程模型保存成功', { exact: true })).toBeVisible()
    await this.filterTable('请输入模型标识', model.key)
    await expect(this.row(model.key)).toHaveCount(1)
  }

  /**
   * 从模型列表进入指定模型的真实 BPMN 设计器。
   * @param {string} modelKey 模型稳定标识。
   * @returns {Promise<void>} 设计器画布和属性检查器加载完成后结束。
   */
  async openDesigner(modelKey) {
    await this.page.goto('/workflow/model')
    await this.filterTable('请输入模型标识', modelKey)
    const row = this.row(modelKey)
    await expect(row).toHaveCount(1)
    await row.locator('button').nth(0).click()
    await expect(this.page.locator('.process-designer__canvas')).toBeVisible()
    await expect(this.page.locator('.designer-properties-panel')).toBeVisible()
  }

  /**
   * 从模型列表部署已经通过设计器保存的模型。
   * @param {string} modelKey 模型稳定标识。
   * @returns {Promise<void>} 确认部署且列表状态变为已部署后结束。
   */
  async deployModel(modelKey) {
    await this.page.goto('/workflow/model')
    await this.filterTable('请输入模型标识', modelKey)
    const row = this.row(modelKey)
    await row.locator('button').nth(3).click()
    const deployPromise = this.page.waitForResponse(response => matchesEndpoint(response, '/workflow/model/deploy', 'POST'))
    await this.page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expectAjaxSuccess(await deployPromise, '/workflow/model/deploy')
    await expect(this.page.getByText('流程模型部署成功', { exact: true })).toBeVisible()
    await expect(this.row(modelKey).getByText('已部署', { exact: true })).toBeVisible()
  }

  /**
   * 通过连接端点管理页创建正式 HTTP 白名单端点。
   * @param {{name:string,key:string,baseUrl:string,pathPrefix:string,connectTimeoutMs:number,requestTimeoutMs:number,authType?:string,secretRef?:string,apiKeyHeader?:string}} endpoint 端点名称、稳定键、地址边界、认证和超时。
   * @returns {Promise<void>} 端点保存成功并在列表回显启用状态后结束。
   */
  async createHttpEndpoint(endpoint) {
    await this.page.goto('/workflow/extensions/connector')
    await expect(this.page.getByRole('heading', { name: '连接端点', exact: true })).toBeVisible()
    await this.refreshHttpEndpointList(endpoint.key)
    const existingRows = this.row(endpoint.key)
    const existingCount = await existingRows.count()
    expect(existingCount, `连接端点稳定键 ${endpoint.key} 不得存在重复修订行`).toBeLessThanOrEqual(1)
    if (existingCount === 1) return

    await this.page.getByRole('button', { name: '新增端点', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '新增连接端点' })
    await dialog.getByLabel('端点名称').fill(endpoint.name)
    await dialog.getByLabel('稳定键').fill(endpoint.key)
    await dialog.getByLabel('基础 URL').fill(endpoint.baseUrl)
    await dialog.getByLabel('路径前缀').fill(endpoint.pathPrefix)
    const privateScope = dialog.locator('.el-segmented__item').filter({ hasText: '内网' })
    await expect(privateScope, '本机故障端点必须提供内网范围选项').toHaveCount(1)
    await privateScope.click()

    if (endpoint.authType && endpoint.authType !== 'NONE') {
      // 外部认证必须通过页面正式控件配置，测试不得直接改库绕过端点校验和修订快照。
      const authSelect = dialog.locator('.el-form-item').filter({ hasText: '认证类型' }).locator('.el-select')
      await authSelect.click()
      const authLabel = endpoint.authType === 'API_KEY' ? 'API Key' : 'Bearer Token'
      await this.page.locator('.el-select-dropdown:visible').getByRole('option', {
        name: authLabel, exact: true
      }).click()
      await dialog.getByLabel('密钥引用').fill(endpoint.secretRef || '')
      if (endpoint.authType === 'API_KEY') {
        await dialog.getByLabel('认证请求头').fill(endpoint.apiKeyHeader || '')
      }
    }

    const connectTimeout = dialog.locator('.el-form-item').filter({ hasText: '连接超时' }).getByRole('spinbutton')
    await connectTimeout.fill(String(endpoint.connectTimeoutMs))
    await connectTimeout.press('Tab')
    const requestTimeout = dialog.locator('.el-form-item').filter({ hasText: '请求超时' }).getByRole('spinbutton')
    await requestTimeout.fill(String(endpoint.requestTimeoutMs))
    await requestTimeout.press('Tab')

    await dialog.getByRole('button', { name: '保存端点', exact: true }).click()
    await expect(this.page.getByText('连接端点创建成功', { exact: true })).toBeVisible()
    await this.refreshHttpEndpointList(endpoint.key)
    const row = this.row(endpoint.key)
    await expect(row, `连接端点 ${endpoint.key} 必须唯一回显`).toHaveCount(1)
    await expect(row).toContainText(endpoint.name)
    await expect(row).toContainText('R1')
    await expect(row).toContainText('已启用')
  }

  /**
   * 通过连接端点列表停用当前测试创建的端点，避免继续进入后续作者目录。
   * @param {string} endpointKey `E2E_UI_` 前缀的正式端点稳定键。
   * @returns {Promise<void>} 端点不存在或已经停用时直接结束，否则确认停用并核对回显。
  */
  async disableHttpEndpoint(endpointKey) {
    expect(endpointKey.toUpperCase().startsWith('E2E_UI_'), '自动停用只能作用于 UI 测试端点').toBe(true)
    await this.page.goto('/workflow/extensions/connector')
    await expect(this.page.getByRole('heading', { name: '连接端点', exact: true })).toBeVisible()
    await this.refreshHttpEndpointList(endpointKey)
    const row = this.row(endpointKey)
    const rowCount = await row.count()
    expect(rowCount, `连接端点 ${endpointKey} 不得存在重复当前修订`).toBeLessThanOrEqual(1)
    if (rowCount === 0 || await row.getByText('已停用', { exact: true }).count() === 1) return

    await row.getByRole('button', { name: '停用端点', exact: true }).click()
    await this.page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expect(this.page.getByText('端点已停用', { exact: true })).toBeVisible()
    await expect(row.getByText('已停用', { exact: true })).toBeVisible()
  }

  /**
   * 刷新 HTTP 端点正式清单并等待后端响应，避免表格旧数据导致清理误判。
   * @param {string} endpointKey 需要在客户端清单中筛选的端点稳定键。
   * @returns {Promise<void>} `/workflow/connector/list` 成功返回且表格完成本次刷新后结束。
   */
  async refreshHttpEndpointList(endpointKey) {
    const keyword = this.page.getByPlaceholder('名称或稳定键')
    await keyword.fill(endpointKey)
    const responsePromise = this.page.waitForResponse(response => (
      matchesEndpoint(response, '/workflow/connector/list', 'GET')
    ))
    await this.page.getByRole('button', { name: '刷新', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/connector/list')
    await expect(this.page.locator('.el-table__body-wrapper')).toBeVisible()
  }

  /**
   * 使用页面搜索框过滤当前列表。
   * @param {string} placeholder 搜索框占位文本。
   * @param {string} value 搜索值。
   * @returns {Promise<void>} 搜索请求完成且表格停止加载后结束。
   */
  async filterTable(placeholder, value) {
    const input = this.page.getByPlaceholder(placeholder)
    await input.fill(value)
    const queryForm = input.locator('xpath=ancestor::form[1]')
    const endpoint = `${new URL(this.page.url()).pathname}/list`
    const responsePromise = this.page.waitForResponse(response => {
      // Vite 开发代理会给后端地址增加 /dev-api 前缀，按业务端点后缀匹配才能兼容真实开发入口。
      return response.request().method() === 'GET' && new URL(response.url()).pathname.endsWith(endpoint)
    })
    await queryForm.getByRole('button', { name: '搜索', exact: true }).click()
    const response = await responsePromise
    expect(response.ok(), `${endpoint} 查询必须成功`).toBe(true)
    await expect(this.page.locator('.app-container .el-loading-mask')).toHaveCount(0)
  }

  /**
   * 通过列表勾选和正式删除入口清理同一运行编号遗留的重复测试资产。
   * @param {import('@playwright/test').Locator} rows 已按唯一测试名称过滤出的重复行。
   * @param {string} assetName `E2E_UI_` 前缀的测试资产名称。
   * @param {string} successMessage 删除成功的页面提示文案。
   * @returns {Promise<void>} 所有重复测试行经后端引用校验删除后结束。
   */
  async removeFilteredTestRows(rows, assetName, successMessage) {
    expect(assetName.startsWith('E2E_UI_'), '自动清理只能作用于 UI 测试资产').toBe(true)
    const rowCount = await rows.count()
    for (let rowIndex = 0; rowIndex < rowCount; rowIndex += 1) {
      // Element Plus 隐藏原生 input，必须点击可见的复选框容器才能模拟真实用户勾选。
      await rows.nth(rowIndex).locator('.el-checkbox').click()
    }
    await this.page.getByRole('button', { name: '删除', exact: true }).click()
    await this.page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expect(this.page.getByText(successMessage, { exact: true })).toBeVisible()
    await expect(this.row(assetName)).toHaveCount(0)
  }

  /**
   * 返回包含指定唯一文本的表格行。
   * @param {string} value 行内稳定名称或编码。
   * @returns {import('@playwright/test').Locator} 表格行定位器。
   */
  row(value) {
    return this.page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: value })
  }

  /**
   * 在指定表单项中选择 Element Plus 下拉选项。
   * @param {import('@playwright/test').Locator} container 对话框或属性面板容器。
   * @param {string} label 表单项标签。
   * @param {string} option 目标选项文本。
   * @returns {Promise<void>} 选项写入并关闭下拉后结束。
   */
  async selectFormItem(container, label, option) {
    // 只按表单项标签定位，避免单选按钮或选项正文包含同名文本时误选其他表单项。
    const matchingLabels = container.locator('.el-form-item__label').filter({ hasText: label })
    await expect(matchingLabels, `表单项标签 ${label} 必须唯一`).toHaveCount(1)
    const item = matchingLabels.first().locator('..')
    await item.locator('.el-select').click()
    await this.page.getByRole('option', { name: option, exact: true }).click()
  }

  /**
   * 配置表单设计器当前选中字段的变量名、标题和可选占位提示。
   * @param {{fieldName:string,label:string,placeholder?:string,required?:boolean,maxlength?:number,min?:number,max?:number,step?:number,precision?:number,options?:Array<{label:string,value:string|number}>}} field 当前字段的正式变量名、显示属性和业务约束。
   * @returns {Promise<void>} 右侧属性面板完成回填后结束。
   */
  async configureActiveFormField(field) {
    const panel = this.page.locator('.right-board')
    await expect(panel).toBeVisible()
    const fieldNameInput = panel.getByPlaceholder('请输入字段名（v-model）')
    const labelInput = panel.getByPlaceholder('请输入标题')
    await expect(fieldNameInput).toBeVisible()
    await fieldNameInput.fill(field.fieldName)
    await labelInput.fill(field.label)
    if (field.placeholder !== undefined) {
      const placeholderInput = panel.getByPlaceholder('请输入占位提示')
      await expect(placeholderInput).toBeVisible()
      await placeholderInput.fill(field.placeholder)
    }
    if (field.required !== undefined) {
      const requiredItem = panel.locator('.el-form-item').filter({ hasText: '是否必填' })
      await expect(requiredItem).toHaveCount(1)
      const requiredSwitch = requiredItem.getByRole('switch')
      const checked = await requiredSwitch.getAttribute('aria-checked')
      if ((checked === 'true') !== field.required) {
        // Element Plus 隐藏原生 checkbox，真实用户点击的是可见开关包装层。
        await requiredItem.locator('.el-switch').click()
      }
    }
    if (field.maxlength !== undefined) {
      await panel.getByPlaceholder('请输入字符长度').fill(String(field.maxlength))
    }
    for (const [label, value] of [
      ['最小值', field.min], ['最大值', field.max], ['步长', field.step], ['精度', field.precision]
    ]) {
      if (value === undefined) continue
      const item = panel.locator('.el-form-item').filter({ hasText: label })
      await expect(item, `字段属性 ${label} 必须唯一`).toHaveCount(1)
      const input = item.locator('input').first()
      await input.fill(String(value))
      await input.press('Enter')
    }
    if (field.options) {
      const optionLabels = panel.getByPlaceholder('选项名')
      const optionValues = panel.getByPlaceholder('选项值')
      while (await optionLabels.count() < field.options.length) {
        await panel.getByRole('button', { name: '添加选项', exact: true }).click()
      }
      for (let index = 0; index < field.options.length; index += 1) {
        await optionLabels.nth(index).fill(field.options[index].label)
        await optionValues.nth(index).fill(String(field.options[index].value))
      }
    }
  }
}
