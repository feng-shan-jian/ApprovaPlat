<template>
  <aside class="designer-properties-panel">
    <div class="designer-properties-panel__title">
      <div class="designer-properties-panel__heading">
        <span class="designer-properties-panel__eyebrow">属性检查器</span>
        <strong>{{ title }}</strong>
      </div>
      <div class="designer-properties-panel__actions">
        <el-tooltip content="展开全部分区" placement="bottom">
          <el-button text circle icon="ArrowDownBold" aria-label="展开全部属性分区" @click="expandAllSections" />
        </el-tooltip>
        <el-tooltip content="收起全部分区" placement="bottom">
          <el-button text circle icon="ArrowUpBold" aria-label="收起全部属性分区" @click="collapseAllSections" />
        </el-tooltip>
        <el-tooltip content="收起属性面板" placement="bottom">
          <el-button text circle icon="Close" aria-label="收起属性面板" @click="emit('close')" />
        </el-tooltip>
      </div>
    </div>

    <div v-if="selected" class="designer-properties-panel__context" :title="state.id">
      <span class="designer-properties-panel__context-dot" />
      <span>当前元素</span>
      <code>{{ state.id || '未命名元素' }}</code>
    </div>

    <el-scrollbar v-if="selected" class="designer-properties-panel__scroll">
      <el-form label-position="top" size="small" class="designer-properties-panel__form">
        <el-collapse v-model="activeSections">
          <el-collapse-item title="基础信息" name="base">
            <el-form-item label="元素名称">
              <el-input v-model="state.name" maxlength="255" @change="emit('common-change')" />
            </el-form-item>
            <el-form-item label="元素标识">
              <el-input v-model="state.id" maxlength="128" @change="emit('id-change')" />
            </el-form-item>
            <el-form-item v-if="flags.process" label="版本标签">
              <el-input v-model="state.versionTag" maxlength="64" @change="emit('process-change')" />
            </el-form-item>
            <el-form-item v-if="flags.process" label="可执行流程">
              <el-switch v-model="state.executable" @change="emit('process-change')" />
            </el-form-item>
            <el-form-item v-if="flags.participant" label="绑定流程定义 key" required>
              <el-input v-model="state.processRef" maxlength="255" placeholder="必须是已存在且可执行的流程定义" @change="emit('participant-change')" />
            </el-form-item>
            <el-form-item label="说明">
              <el-input v-model="state.documentation" type="textarea" :rows="3" maxlength="1000" @change="emit('documentation-change')" />
            </el-form-item>
          </el-collapse-item>

          <el-collapse-item v-if="hasBusinessSection" title="业务配置" name="business">
            <template v-if="flags.formSupported">
              <el-form-item label="表单来源" required>
                <el-segmented v-model="state.formSource" :options="formSourceOptions" @change="emit('form-source-change')" />
              </el-form-item>
              <el-form-item v-if="state.formSource === 'TEMPLATE'" :label="flags.startEvent ? '发起表单' : '节点表单'" :required="flags.startEvent">
                <el-select v-model="state.formKey" filterable clearable @change="emit('form-change')">
                  <el-option v-for="form in forms" :key="form.formId" :label="form.formName" :value="`key_${form.formId}`" />
                </el-select>
              </el-form-item>
              <EmbeddedFormFieldEditor
                v-else
                :fields="state.embeddedFields"
                :custom-field-options="formFieldOptions"
                :custom-field-loading="extensionLoading"
                @change="emit('embedded-form-change', $event)"
              />
              <el-form-item v-if="state.formSource === 'TEMPLATE' && state.formKey" label="节点字段权限">
                <FormFieldPermissionEditor
                  :fields="state.formPermissionFields"
                  :default-mode="state.formPermissionDefault"
                  @change="emit('form-permission-change', $event)"
                />
              </el-form-item>
            </template>

            <ParticipantRuleEditor
              v-if="flags.process"
              v-model="state.participantRule"
              mode="start"
              :identity-options="identityOptions"
              :loading="identityLoading"
              @identity-search="emit('identity-search', $event)"
              @identity-resolve="emit('identity-resolve', $event)"
              @change="emit('participant-rule-change', $event)"
            />

            <template v-if="isUserTaskPanel">
              <section v-if="['none', 'controlled'].includes(state.multiInstanceType)" class="user-task-approval" aria-labelledby="user-task-approval-heading">
                <div class="user-task-approval__heading">
                  <div>
                    <span class="user-task-approval__eyebrow">任务办理</span>
                    <h4 id="user-task-approval-heading">审批人设置</h4>
                  </div>
                  <el-tag size="small" effect="plain" :type="userTaskApprovalTag.type">
                    {{ userTaskApprovalTag.label }}
                  </el-tag>
                </div>

                <ParticipantRuleEditor
                  v-if="state.multiInstanceType === 'none'"
                  v-model="state.participantRule"
                  mode="task"
                  :identity-options="identityOptions"
                  :form-fields="participantFormFieldOptions"
                  :loading="identityLoading"
                  @identity-search="emit('identity-search', $event)"
                  @identity-resolve="emit('identity-resolve', $event)"
                  @change="emit('participant-rule-change', $event)"
                />

                <template v-else>
                  <el-form-item label="审批人来源" required>
                    <el-radio-group v-model="state.multiInstanceMemberSource" class="user-task-approval__source-grid" @change="handleMemberSourceChange">
                      <el-radio v-for="option in multiInstanceMemberSourceOptions" :key="option.value" :value="option.value">
                        {{ option.label }}
                      </el-radio>
                    </el-radio-group>
                  </el-form-item>
                  <el-form-item v-if="multiInstanceIdentityConfiguration" :label="multiInstanceIdentityConfiguration.label" required>
                    <el-select
                      v-model="state.configuredMultiInstanceIdentityIds"
                      multiple
                      filterable
                      remote
                      reserve-keyword
                      collapse-tags
                      collapse-tags-tooltip
                      :max-collapse-tags="3"
                      :remote-method="searchMultiInstanceIdentities"
                      :loading="identityLoading"
                      :placeholder="multiInstanceIdentityConfiguration.placeholder"
                      @change="emit('multi-instance-change')"
                    >
                      <el-option
                        v-for="option in multiInstanceIdentityConfiguration.options"
                        :key="option.value"
                        :label="option.label"
                        :value="String(option.value)"
                        :disabled="option.available === false"
                      />
                    </el-select>
                  </el-form-item>
                </template>

                <div class="user-task-approval__method">
                  <h4>审批方式</h4>
                  <el-radio-group :model-value="userTaskApprovalMethod" class="user-task-approval__method-list" @change="handleUserTaskApprovalMethodChange">
                    <el-radio value="single">
                      <span class="user-task-approval__method-label">普通审批</span>
                      <small>由一名审批人办理</small>
                    </el-radio>
                    <el-radio value="all">
                      <span class="user-task-approval__method-label">会签</span>
                      <small>需所有审批人完成</small>
                    </el-radio>
                    <el-radio value="any">
                      <span class="user-task-approval__method-label">或签</span>
                      <small>任一审批人完成即可</small>
                    </el-radio>
                  </el-radio-group>
                </div>
              </section>
              <template v-if="['sequential', 'parallel'].includes(state.multiInstanceType) && !isUserTaskPanel">
                <el-form-item label="集合表达式">
                  <el-input v-model="state.collection" maxlength="256" @change="emit('multi-instance-change')" />
                </el-form-item>
                <el-form-item label="元素变量">
                  <el-input v-model="state.elementVariable" maxlength="128" @change="emit('multi-instance-change')" />
                </el-form-item>
                <el-form-item label="完成条件">
                  <el-input v-model="state.completionCondition" type="textarea" :rows="2" maxlength="512" @change="emit('multi-instance-change')" />
                </el-form-item>
              </template>
              <template v-if="!['none', 'controlled'].includes(state.multiInstanceType)">
                <el-form-item label="办理方式">
                  <el-segmented v-model="state.assignmentType" :options="assignmentOptions" @change="emit('assignment-change')" />
                </el-form-item>
                <el-form-item v-if="state.assignmentType === 'assignee'" label="办理人">
                  <el-select
                    v-model="state.assignee"
                    filterable
                    clearable
                    remote
                    reserve-keyword
                    :remote-method="searchAssignees"
                    :loading="identityLoading"
                    @change="emit('assignment-change')"
                  >
                    <el-option v-for="user in identityOptions.assignees" :key="user.value" :label="user.label" :value="String(user.value)" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="state.assignmentType === 'users'" label="候选用户">
                  <el-select
                    v-model="state.candidateUsers"
                    multiple
                    filterable
                    remote
                    reserve-keyword
                    :remote-method="searchCandidateUsers"
                    :loading="identityLoading"
                    @change="emit('assignment-change')"
                  >
                    <el-option v-for="user in identityOptions.candidateUsers" :key="user.value" :label="user.label" :value="String(user.value)" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="state.assignmentType === 'groups'" label="候选角色或部门">
                  <el-select
                    v-model="state.candidateGroups"
                    multiple
                    filterable
                    remote
                    reserve-keyword
                    :remote-method="searchCandidateGroups"
                    :loading="identityLoading"
                    @change="emit('assignment-change')"
                  >
                    <el-option v-for="group in identityOptions.candidateGroups" :key="group.value" :label="group.label" :value="group.value" />
                  </el-select>
                </el-form-item>
              </template>
              <el-form-item label="到期时间">
                <el-input v-model="state.dueDate" maxlength="128" placeholder="ISO-8601 或受控表达式" @change="emit('user-task-change')" />
              </el-form-item>
              <el-form-item label="优先级">
                <el-input v-model="state.priority" maxlength="128" @change="emit('user-task-change')" />
              </el-form-item>
              <el-form-item label="任务分类">
                <el-input v-model="state.taskCategory" maxlength="128" @change="emit('user-task-change')" />
              </el-form-item>
              <el-form-item label="跳过条件">
                <el-input v-model="state.skipExpression" maxlength="512" @change="emit('user-task-change')" />
              </el-form-item>
              <el-form-item label="任务局部变量">
                <el-switch v-model="state.localScope" @change="emit('user-task-change')" />
              </el-form-item>
              <UserTaskSlaEditor
                v-model="state.sla"
                :calendars="slaCalendarOptions"
                :escalation-options="escalationEventOptions"
                :assignee-options="identityOptions.assignees"
                :loading="slaLoading || eventCodeLoading"
                :identity-loading="identityLoading"
                @identity-search="searchSlaAssignees"
                @change="emit('sla-change', $event)"
              />
            </template>

            <el-form-item v-if="flags.process || isUserTaskPanel" label="自动抄送">
              <AutoCopyRuleEditor
                v-model="state.autoCopyRules"
                :trigger-options="autoCopyTriggerOptions"
                :user-options="identityOptions.autoCopyUsers"
                :group-options="identityOptions.autoCopyGroups"
                :form-field-options="autoCopyFormFieldOptions"
                :identity-loading="identityLoading"
                @identity-search="emit('identity-search', $event)"
                @change="emit('auto-copy-change', $event)"
              />
            </el-form-item>

            <template v-if="taskPanelType === TASK_PANEL_TYPES.SERVICE">
              <el-alert type="info" show-icon :closable="false" :title="taskCapability.runtimeSemantics" />
              <ControlledTaskHandlerEditor
                :state="state"
                :options="extensionOptions"
                :connector-endpoints="connectorEndpoints"
                :sql-data-sources="sqlDataSources"
                :error-event-options="errorEventOptions"
                :escalation-event-options="escalationEventOptions"
                :loading="extensionLoading"
                @selection-change="emit('extension-selection-change', $event)"
                @config-update="emit('controlled-task-config-update', $event)"
                @change="emit('controlled-task-change')"
              />
            </template>

            <template v-else-if="taskPanelType === TASK_PANEL_TYPES.SEND">
              <el-alert type="info" show-icon :closable="false" :title="taskCapability.runtimeSemantics" />
              <el-alert
                type="warning"
                show-icon
                :closable="false"
                title="连接 MessageFlow 时必须使用事务 outbox 处理器；设计器不根据连线猜测能力，保存与部署由后端权威校验。"
              />
              <ControlledTaskHandlerEditor
                :state="state"
                :options="extensionOptions"
                :connector-endpoints="connectorEndpoints"
                :sql-data-sources="sqlDataSources"
                :error-event-options="errorEventOptions"
                :escalation-event-options="escalationEventOptions"
                :loading="extensionLoading"
                @selection-change="emit('extension-selection-change', $event)"
                @config-update="emit('controlled-task-config-update', $event)"
                @change="emit('controlled-task-change')"
              />
            </template>

            <template v-else-if="taskPanelType === TASK_PANEL_TYPES.RECEIVE">
              <el-alert type="info" show-icon :closable="false" :title="taskCapability.runtimeSemantics" />
              <dl class="task-runtime-contract">
                <div>
                  <dt>外部触发端点</dt>
                  <dd><code>POST /workflow/runtime-event/receive</code></dd>
                </div>
                <div>
                  <dt>activityId</dt>
                  <dd><code>{{ state.id || '请先设置元素标识' }}</code></dd>
                </div>
                <div>
                  <dt>关联条件</dt>
                  <dd><code>processInstanceId</code> 与 <code>businessKey</code> 必须且只能提供一个。</dd>
                </div>
                <div>
                  <dt>鉴权要求</dt>
                  <dd>请求头必须携带具备 RECEIVE 能力的 <code>X-Integration-Token</code>，变量还受该凭据白名单约束。</dd>
                </div>
              </dl>
            </template>

            <template v-else-if="taskPanelType === TASK_PANEL_TYPES.BUSINESS_RULE">
              <el-alert type="info" show-icon :closable="false" :title="taskCapability.runtimeSemantics" />
              <el-form-item label="DMN 决策版本" required>
                <el-select v-model="state.dmnDecisionId" filterable :loading="dmnLoading" @change="emit('dmn-change')">
                  <el-option
                    v-for="decision in dmnOptions"
                    :key="decision.decisionId"
                    :label="`${decision.decisionName || decision.decisionKey} · ${decision.decisionKey} · v${decision.version}`"
                    :value="decision.decisionId"
                  />
                </el-select>
              </el-form-item>
            </template>

            <el-alert
              v-else-if="taskPanelType === TASK_PANEL_TYPES.MANUAL_WARNING"
              type="warning"
              show-icon
              :closable="false"
              :title="taskCapability.runtimeSemantics"
              description="历史元素仍可导入、显示、编辑基础信息并原样保存；如需平台办理，请转换为 UserTask。"
            />

            <template v-if="flags.callActivity">
              <el-form-item label="已发布子流程" required>
                <el-select
                  v-model="state.callDefinitionId"
                  filterable
                  :loading="callActivityLoading"
                  placeholder="请选择有权引用的已发布流程"
                  @change="emit('call-activity-change')"
                >
                  <el-option
                    v-for="option in callActivityOptions"
                    :key="option.definitionId"
                    :value="option.definitionId"
                    :label="callActivityOptionLabel(option)"
                    :disabled="option.status !== 'ACTIVE'"
                  >
                    <div class="call-activity-option">
                      <span>{{ option.processName || option.processKey }}</span>
                      <code>{{ option.processKey }}</code>
                      <el-tag size="small" :type="option.status === 'ACTIVE' ? 'success' : 'info'">
                        v{{ option.version }} ·
                        {{ option.status === 'ACTIVE' ? '启用' : '停用' }}
                      </el-tag>
                    </div>
                  </el-option>
                </el-select>
              </el-form-item>
              <el-form-item label="版本绑定策略" required>
                <el-segmented v-model="state.callVersionPolicy" :options="callVersionPolicyOptions" @change="emit('call-activity-change')" />
              </el-form-item>
              <el-form-item label="业务键策略">
                <el-segmented v-model="state.callBusinessKeyPolicy" :options="callBusinessKeyPolicyOptions" @change="emit('call-activity-change')" />
              </el-form-item>
              <el-form-item label="继承父流程变量">
                <el-switch v-model="state.callInheritVariables" @change="emit('call-activity-change')" />
              </el-form-item>
              <el-form-item label="子流程实例名称">
                <el-input v-model="state.processInstanceName" maxlength="255" @change="emit('call-activity-change')" />
              </el-form-item>
              <el-form-item label="输入变量映射">
                <div class="call-activity-mappings">
                  <div v-for="(mapping, index) in state.callInMappings" :key="`in-${index}`" class="call-activity-mapping-row">
                    <el-select v-model="mapping.source" filterable placeholder="父流程字段" @change="handleCallMappingChange(mapping)">
                      <el-option v-for="field in callActivityParentReadableFields" :key="field.name" :label="variableFieldLabel(field)" :value="field.name" />
                    </el-select>
                    <span class="call-activity-mapping-row__arrow">→</span>
                    <el-select v-model="mapping.target" filterable placeholder="子流程字段" @change="handleCallMappingChange(mapping)">
                      <el-option v-for="field in selectedCallActivityInputFields" :key="field.name" :label="variableFieldLabel(field)" :value="field.name" />
                    </el-select>
                    <el-tooltip content="删除输入映射" placement="top">
                      <el-button text circle icon="Delete" aria-label="删除输入映射" @click="removeCallMapping('input', index)" />
                    </el-tooltip>
                  </div>
                  <el-button icon="Plus" :disabled="state.callInMappings.length >= 64" @click="addCallMapping('input')">添加输入映射</el-button>
                </div>
              </el-form-item>
              <el-form-item label="输出变量映射">
                <div class="call-activity-mappings">
                  <div v-for="(mapping, index) in state.callOutMappings" :key="`out-${index}`" class="call-activity-mapping-row">
                    <el-select v-model="mapping.source" filterable placeholder="子流程字段" @change="handleCallMappingChange(mapping)">
                      <el-option v-for="field in selectedCallActivityOutputFields" :key="field.name" :label="variableFieldLabel(field)" :value="field.name" />
                    </el-select>
                    <span class="call-activity-mapping-row__arrow">→</span>
                    <el-select v-model="mapping.target" filterable placeholder="父流程字段" @change="handleCallMappingChange(mapping)">
                      <el-option v-for="field in callActivityParentWritableFields" :key="field.name" :label="variableFieldLabel(field)" :value="field.name" />
                    </el-select>
                    <el-tooltip content="删除输出映射" placement="top">
                      <el-button text circle icon="Delete" aria-label="删除输出映射" @click="removeCallMapping('output', index)" />
                    </el-tooltip>
                  </div>
                  <el-button icon="Plus" :disabled="state.callOutMappings.length >= 64" @click="addCallMapping('output')">添加输出映射</el-button>
                </div>
              </el-form-item>
              <el-form-item label="输出变量作用域">
                <el-segmented v-model="state.callOutputScope" :options="callOutputScopeOptions" @change="emit('call-activity-change')" />
              </el-form-item>
              <el-form-item label="取消与终止传播">
                <el-input model-value="整棵父子流程原子传播" readonly />
              </el-form-item>
            </template>

            <template v-if="flags.sequenceFlow">
              <SequenceFlowRuleEditor
                v-if="flags.conditionGatewayFlow"
                :flow-id="state.id"
                :name="state.name"
                :config="state.conditionRule"
                :is-default="state.conditionDefault"
                :gateway-type="conditionContext.gatewayType"
                :gateway-branches="conditionContext.branches"
                :field-conflicts="conditionContext.fieldConflicts"
                :field-options="conditionFieldOptions"
                @apply="emit('condition-rule-change', $event)"
                @make-default="emit('condition-default-change')"
              />
              <el-alert v-else type="info" :closable="false" show-icon title="条件规则仅适用于排他或包容网关的多条出线" />
            </template>

            <template v-if="flags.event">
              <el-form-item label="事件定义">
                <el-input :model-value="eventDefinitionLabel" readonly />
              </el-form-item>
              <el-form-item v-if="flags.businessReferenceEvent" label="业务编码" required>
                <el-select v-model="state.eventReference" filterable :loading="eventCodeLoading" @change="emit('event-change')">
                  <el-option
                    v-for="option in businessEventOptions"
                    :key="option.eventCodeId"
                    :label="`${option.eventName} · ${option.eventCode}`"
                    :value="option.eventCode"
                  />
                </el-select>
              </el-form-item>
              <el-form-item v-else-if="flags.referenceEvent" label="事件引用">
                <el-input v-model="state.eventReference" maxlength="128" placeholder="消息或信号的稳定 key" @change="emit('event-change')" />
              </el-form-item>
              <template v-if="flags.timerEvent">
                <el-form-item label="时间类型">
                  <el-select v-model="state.timerDefinitionType" @change="emit('event-change')">
                    <el-option label="指定时间" value="timeDate" />
                    <el-option label="持续时间" value="timeDuration" />
                    <el-option label="周期" value="timeCycle" />
                  </el-select>
                </el-form-item>
                <el-form-item label="时间表达式">
                  <el-input v-model="state.timerDefinition" maxlength="512" @change="emit('event-change')" />
                </el-form-item>
              </template>
              <el-form-item v-if="flags.boundaryEvent" label="中断附着活动">
                <el-switch
                  v-model="state.cancelActivity"
                  :disabled="state.eventDefinitionType === 'bpmn:ErrorEventDefinition'"
                  @change="emit('event-change')"
                />
                <div v-if="state.eventDefinitionType === 'bpmn:ErrorEventDefinition'" class="form-tip">
                  BPMN Error 固定中断当前活动；需要保留主路径时请使用非中断升级边界。
                </div>
              </el-form-item>
            </template>
          </el-collapse-item>

          <el-collapse-item v-if="flags.activity" title="执行配置" name="execution">
            <el-form-item v-if="!isUserTaskPanel || state.multiInstanceType !== 'controlled'" label="循环方式">
              <el-select v-model="state.multiInstanceType" @change="handleLoopTypeChange">
                <el-option v-for="option in activityLoopOptions" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </el-form-item>
            <template v-if="state.multiInstanceType === 'standard'">
              <el-form-item label="最大循环次数">
                <el-input v-model="state.loopMaximum" maxlength="32" @change="emit('multi-instance-change')" />
              </el-form-item>
              <el-form-item label="循环条件">
                <el-input v-model="state.loopCondition" type="textarea" :rows="2" maxlength="512" @change="emit('multi-instance-change')" />
              </el-form-item>
              <el-form-item label="执行前检查条件">
                <el-switch v-model="state.testBefore" @change="emit('multi-instance-change')" />
              </el-form-item>
            </template>
            <template v-if="state.multiInstanceType === 'approvalLoop'">
              <el-alert
                type="info"
                show-icon
                :closable="false"
                title="任务首次正常进入；每次提交后按正式表单字段决定再次整改或退出。达到上限时拒绝继续整改，不会强制放行。配置完成后请点击应用。"
              />
              <el-form-item label="最大办理轮次" required>
                <el-input-number v-model="state.controlledLoopMaxIterations" :min="2" :max="50" controls-position="right" />
              </el-form-item>
              <el-form-item label="循环判断字段" required>
                <el-select
                  v-model="state.controlledLoopDecisionVariable"
                  filterable
                  placeholder="请选择当前节点表单字段"
                  @change="handleControlledLoopFieldChange"
                >
                  <el-option v-for="field in controlledLoopFieldOptions" :key="field.value" :label="field.label" :value="field.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="再次进入条件" required>
                <el-select
                  v-if="controlledLoopValueOptions.length"
                  v-model="state.controlledLoopRepeatValue"
                  filterable
                  :allow-create="!controlledLoopValueRestricted"
                  placeholder="字段等于此值时再次整改"
                >
                  <el-option v-for="item in controlledLoopValueOptions" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
                <el-input v-else v-model="state.controlledLoopRepeatValue" maxlength="128" placeholder="字段等于此值时再次整改" />
              </el-form-item>
              <el-form-item label="退出条件" required>
                <el-select
                  v-if="controlledLoopValueOptions.length"
                  v-model="state.controlledLoopExitValue"
                  filterable
                  :allow-create="!controlledLoopValueRestricted"
                  placeholder="字段等于此值时退出循环"
                >
                  <el-option v-for="item in controlledLoopValueOptions" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
                <el-input v-else v-model="state.controlledLoopExitValue" maxlength="128" placeholder="字段等于此值时退出循环" />
              </el-form-item>
              <el-alert
                v-if="!controlledLoopFieldOptions.length"
                type="warning"
                show-icon
                :closable="false"
                title="请先为当前用户任务配置包含判断字段的正式表单。"
              />
              <el-button type="primary" plain :disabled="!controlledLoopConfigurationReady" @click="emit('multi-instance-change')">
                应用整改循环配置
              </el-button>
            </template>
            <template v-if="['sequential', 'parallel'].includes(state.multiInstanceType) && !isUserTaskPanel">
              <el-form-item label="集合表达式">
                <el-input v-model="state.collection" maxlength="256" @change="emit('multi-instance-change')" />
              </el-form-item>
              <el-form-item label="元素变量">
                <el-input v-model="state.elementVariable" maxlength="128" @change="emit('multi-instance-change')" />
              </el-form-item>
              <el-form-item label="完成条件">
                <el-input v-model="state.completionCondition" type="textarea" :rows="2" maxlength="512" @change="emit('multi-instance-change')" />
              </el-form-item>
            </template>
            <el-form-item label="进入前异步">
              <el-switch v-model="state.asyncBefore" @change="emit('activity-change')" />
            </el-form-item>
            <el-form-item label="离开后异步">
              <el-switch v-model="state.asyncAfter" @change="emit('activity-change')" />
            </el-form-item>
            <el-form-item label="排他作业">
              <el-switch v-model="state.exclusive" @change="emit('activity-change')" />
            </el-form-item>
            <el-form-item label="补偿活动">
              <el-switch v-model="state.forCompensation" @change="emit('activity-change')" />
            </el-form-item>
          </el-collapse-item>

          <el-collapse-item v-if="flags.extensionPropertiesSupported" title="扩展属性" name="properties">
            <ExtensionPropertyEditor v-model="state.extensionProperties" @change="emit('extension-properties-change', $event)" />
          </el-collapse-item>

          <el-collapse-item v-if="flags.listenerSupported" title="业务监听器" name="listeners">
            <el-form-item label="执行监听器">
              <BusinessListenerEditor
                v-model="state.businessExecutionListeners"
                kind="EXECUTION"
                :options="listenerOptions"
                :loading="listenerLoading"
                @change="emit('business-execution-listener-change', $event)"
              />
            </el-form-item>
            <el-form-item v-if="isUserTaskPanel" label="任务监听器">
              <BusinessListenerEditor
                v-model="state.businessTaskListeners"
                kind="TASK"
                :options="listenerOptions"
                :loading="listenerLoading"
                @change="emit('business-task-listener-change', $event)"
              />
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>
    </el-scrollbar>
    <el-empty v-else description="未选择流程元素" :image-size="64" />
  </aside>
</template>

<script setup name="DesignerPropertiesPanel">
import EmbeddedFormFieldEditor from './EmbeddedFormFieldEditor.vue'
import FormFieldPermissionEditor from './FormFieldPermissionEditor.vue'
import BusinessListenerEditor from './BusinessListenerEditor.vue'
import ExtensionPropertyEditor from './ExtensionPropertyEditor.vue'
import ControlledTaskHandlerEditor from './ControlledTaskHandlerEditor.vue'
import UserTaskSlaEditor from './UserTaskSlaEditor.vue'
import ParticipantRuleEditor from './ParticipantRuleEditor.vue'
import SequenceFlowRuleEditor from './SequenceFlowRuleEditor.vue'
import AutoCopyRuleEditor from './AutoCopyRuleEditor.vue'
import { TASK_PANEL_TYPES } from './taskCapabilityMap.js'

const props = defineProps({
  selected: { type: Boolean, default: false },
  title: { type: String, default: '元素属性' },
  state: { type: Object, required: true },
  flags: { type: Object, required: true },
  taskCapability: { type: Object, default: null },
  forms: { type: Array, default: () => [] },
  identityOptions: {
    type: Object,
    default: () => ({
      assignees: [],
      candidateUsers: [],
      candidateGroups: [],
      candidateRoles: [],
      activeUsers: [],
      activeRoles: [],
      activeDepts: [],
      autoCopyUsers: [],
      autoCopyGroups: []
    })
  },
  identityLoading: { type: Boolean, default: false },
  assignmentOptions: { type: Array, default: () => [] },
  multiInstanceOptions: { type: Array, default: () => [] },
  multiInstanceApprovalOptions: { type: Array, default: () => [] },
  controlledLoopFieldOptions: { type: Array, default: () => [] },
  participantFormFieldOptions: { type: Array, default: () => [] },
  conditionFieldOptions: { type: Array, default: () => [] },
  conditionContext: {
    type: Object,
    default: () => ({ gatewayType: '', branches: [] })
  },
  autoCopyTriggerOptions: { type: Array, default: () => [] },
  autoCopyFormFieldOptions: { type: Array, default: () => [] },
  extensionOptions: { type: Array, default: () => [] },
  formFieldOptions: { type: Array, default: () => [] },
  connectorEndpoints: { type: Array, default: () => [] },
  sqlDataSources: { type: Array, default: () => [] },
  extensionLoading: { type: Boolean, default: false },
  dmnOptions: { type: Array, default: () => [] },
  dmnLoading: { type: Boolean, default: false },
  callActivityOptions: { type: Array, default: () => [] },
  callActivityLoading: { type: Boolean, default: false },
  callActivityParentFields: { type: Array, default: () => [] },
  listenerOptions: { type: Array, default: () => [] },
  listenerLoading: { type: Boolean, default: false },
  errorEventOptions: { type: Array, default: () => [] },
  escalationEventOptions: { type: Array, default: () => [] },
  eventCodeLoading: { type: Boolean, default: false },
  slaCalendarOptions: { type: Array, default: () => [] },
  slaLoading: { type: Boolean, default: false }
})

const emit = defineEmits([
  'common-change',
  'id-change',
  'process-change',
  'participant-change',
  'form-source-change',
  'form-change',
  'embedded-form-change',
  'form-permission-change',
  'assignment-change',
  'participant-rule-change',
  'user-task-change',
  'extension-selection-change',
  'controlled-task-config-update',
  'controlled-task-change',
  'condition-change',
  'condition-rule-change',
  'condition-default-change',
  'documentation-change',
  'multi-instance-change',
  'activity-change',
  'call-activity-change',
  'event-change',
  'dmn-change',
  'identity-search',
  'identity-resolve',
  'business-execution-listener-change',
  'business-task-listener-change',
  'extension-properties-change',
  'sla-change',
  'auto-copy-change',
  'close'
])

// 表单来源值与后端部署快照的 source_type 契约一致。
const formSourceOptions = Object.freeze([
  { label: '正式模板', value: 'TEMPLATE' },
  { label: '内嵌表单', value: 'EMBEDDED' }
])
// 会签和或签共用多实例语义，指定身份只引用正式目录，角色和部门由后端在节点进入时实时展开。
const multiInstanceMemberSourceOptions = Object.freeze([
  { label: '办理时选择', value: 'dynamic' },
  { label: '发起时选择', value: 'start' },
  { label: '指定用户', value: 'user' },
  { label: '指定角色', value: 'role' },
  { label: '指定部门', value: 'dept' }
])
// activeSections 只记录当前元素真实存在的分区，避免固定展开状态与用户点击动作相互反转。
const activeSections = ref(['base', 'business'])

// 作者只选择业务策略，父组件负责写入 Flowable key/id 和布尔属性，不开放表达式。
const callVersionPolicyOptions = Object.freeze([
  { label: '发布时最新版', value: 'LATEST_ACTIVE' },
  { label: '固定所选版本', value: 'FIXED' }
])
const callBusinessKeyPolicyOptions = Object.freeze([
  { label: '继承父流程', value: 'INHERIT' },
  { label: '不设置', value: 'NONE' }
])
const callOutputScopeOptions = Object.freeze([
  { label: '父流程变量', value: 'PARENT' },
  { label: '调用节点局部变量', value: 'LOCAL' }
])

// 任务业务分区由唯一能力表决定；非任务元素只列出模板中确实有业务控件的明确能力。
const taskPanelType = computed(() => props.taskCapability?.panelType || '')
const isUserTaskPanel = computed(() => taskPanelType.value === TASK_PANEL_TYPES.USER)
const hasTaskBusinessSection = computed(() => Boolean(taskPanelType.value))
const hasNonTaskBusinessSection = computed(() => (
  props.flags.process
  || (props.flags.formSupported && !isUserTaskPanel.value)
  || props.flags.sequenceFlow
  || props.flags.callActivity
  || props.flags.event
))
const hasBusinessSection = computed(() => (
  hasTaskBusinessSection.value || hasNonTaskBusinessSection.value
))
const availableSections = computed(() => [
  'base',
  ...(hasBusinessSection.value ? ['business'] : []),
  ...(props.flags.activity ? ['execution'] : []),
  ...(props.flags.extensionPropertiesSupported ? ['properties'] : []),
  ...(props.flags.listenerSupported ? ['listeners'] : [])
])
const selectedCallActivityOption = computed(() => props.callActivityOptions.find(option => option.definitionId === props.state.callDefinitionId))
const selectedCallActivityInputFields = computed(() => (selectedCallActivityOption.value?.inputFields || []).filter(field => field.writable))
const selectedCallActivityOutputFields = computed(() => (selectedCallActivityOption.value?.outputFields || []).filter(field => field.readable))
const callActivityParentReadableFields = computed(() => props.callActivityParentFields.filter(field => field.readable))
const callActivityParentWritableFields = computed(() => props.callActivityParentFields.filter(field => field.writable))
/**
 * 生成当前指定身份来源对应的正式目录池和安全回显选项。
 * @returns {{label:string,pool:string,placeholder:string,options:object[]}|null} 当前来源的选择器配置；动态来源返回 null。
 */
const multiInstanceIdentityConfiguration = computed(() => {
  const definitions = {
    user: { label: '指定办理用户', pool: 'assignees', placeholder: '请选择会签或或签办理用户' },
    role: { label: '指定办理角色', pool: 'activeRoles', placeholder: '请选择会签或或签办理角色' },
    dept: { label: '指定办理部门', pool: 'activeDepts', placeholder: '请选择会签或或签办理部门' }
  }
  const definition = definitions[props.state.multiInstanceMemberSource]
  if (!definition) return null
  const options = [...(props.identityOptions[definition.pool] || [])]
  const loadedValues = new Set(options.map(option => String(option.value)))
  for (const value of props.state.configuredMultiInstanceIdentityIds || []) {
    if (!loadedValues.has(String(value))) {
      options.push({
        value: String(value),
        label: '正在核验已选对象',
        available: false
      })
    }
  }
  return { ...definition, options }
})
// 审批方式是设计者操作的业务状态；底层仍由受控多实例类型和完成条件共同表达。
const userTaskApprovalMethod = computed(() => (props.state.multiInstanceType === 'controlled' ? props.state.multiInstanceApprovalMode : 'single'))
// 标题标签帮助设计者在长属性面板中快速确认当前审批语义。
const userTaskApprovalTag = computed(
  () =>
    ({
      single: { label: '普通审批', type: 'info' },
      all: { label: '会签', type: 'success' },
      any: { label: '或签', type: 'warning' }
    }[userTaskApprovalMethod.value])
)
const businessEventOptions = computed(() =>
  props.state.eventDefinitionType === 'bpmn:ErrorEventDefinition' ? props.errorEventOptions : props.escalationEventOptions
)
// UserTask 的会签/或签入口已并入审批人设置；执行配置只保留普通循环和整改循环。
const activityLoopOptions = computed(() =>
  props.multiInstanceOptions.filter(option => {
    if (isUserTaskPanel.value) return !['sequential', 'parallel', 'controlled'].includes(option.value)
    return !['controlled', 'approvalLoop'].includes(option.value)
  })
)
// 条件值选项跟随当前正式表单字段；自由文本字段仍允许输入受限标量值。
const controlledLoopValueOptions = computed(
  () => props.controlledLoopFieldOptions.find(option => option.value === props.state.controlledLoopDecisionVariable)?.values || []
)
// 静态枚举字段只能选择正式表单给出的值；自由文本和普通标量字段仍由后端执行类型与长度校验。
const controlledLoopValueRestricted = computed(
  () => props.controlledLoopFieldOptions.find(option => option.value === props.state.controlledLoopDecisionVariable)?.valueRestricted === true
)
// 应用按钮只在五项受控属性均完整时开放，避免半成品配置进入 BPMN 命令栈或被保存。
const controlledLoopConfigurationReady = computed(() => {
  const maxIterations = Number(props.state.controlledLoopMaxIterations)
  const decisionVariable = String(props.state.controlledLoopDecisionVariable || '').trim()
  const repeatValue = String(props.state.controlledLoopRepeatValue || '').trim()
  const exitValue = String(props.state.controlledLoopExitValue || '').trim()
  return (
    Number.isInteger(maxIterations) &&
    maxIterations >= 2 &&
    maxIterations <= 50 &&
    Boolean(decisionVariable) &&
    Boolean(repeatValue) &&
    Boolean(exitValue) &&
    repeatValue !== exitValue
  )
})
const eventDefinitionLabel = computed(
  () =>
    ({
      'bpmn:MessageEventDefinition': '消息',
      'bpmn:SignalEventDefinition': '信号',
      'bpmn:TimerEventDefinition': '定时器',
      'bpmn:ErrorEventDefinition': '错误',
      'bpmn:EscalationEventDefinition': '升级',
      'bpmn:CompensateEventDefinition': '补偿'
    }[props.state.eventDefinitionType] || '无')
)

/**
 * 在切换 BPMN 元素时恢复高频属性分区，并移除当前元素不支持的旧分区。
 * @param {[string, string[]]} current 当前元素标识和可用分区名称。
 * @param {[string, string[]]|undefined} previous 上一个元素标识和可用分区名称。
 * @returns {void} 新活动默认同时展开执行配置，其他元素展开基础与业务配置；同一元素只清理失效分区。
 */
function syncActiveSections(current, previous) {
  const [elementId, sections] = current
  const [previousElementId] = previous || []
  if (elementId !== previousElementId) {
    // 活动的循环、会签和整改能力属于核心业务入口，切换元素后必须直接可见，避免配置看似存在但无法发现。
    activeSections.value = sections.filter(name => ['base', 'business', 'execution'].includes(name))
    return
  }
  activeSections.value = activeSections.value.filter(name => sections.includes(name))
}

/**
 * 展开当前元素支持的全部属性分区。
 * @returns {void} 仅展开模板中实际存在的分区。
 */
function expandAllSections() {
  activeSections.value = [...availableSections.value]
}

/**
 * 收起全部属性分区，为画布和长表单提供更快的浏览切换。
 * @returns {void} 保留元素上下文标题，不隐藏属性面板。
 */
function collapseAllSections() {
  activeSections.value = []
}

/**
 * 请求父组件查询直接办理人目录。
 * @param {string} keyword 用户输入的检索词。
 * @returns {void} 无返回值。
 */
function searchAssignees(keyword) {
  emit('identity-search', { target: 'assignees', keyword })
}

/**
 * 请求父组件查询候选认领用户目录。
 * @param {string} keyword 用户输入的检索词。
 * @returns {void} 无返回值。
 */
function searchCandidateUsers(keyword) {
  emit('identity-search', { target: 'candidateUsers', keyword })
}

/**
 * 请求父组件查询候选角色或部门目录。
 * @param {string} keyword 用户输入的检索词。
 * @returns {void} 无返回值。
 */
function searchCandidateGroups(keyword) {
  emit('identity-search', { target: 'candidateGroups', keyword })
}

/**
 * 切换循环判断字段时清空旧字段条件，避免不同字段的枚举值被静默复用。
 * @returns {void} 清理条件后保留面板草稿，等待设计者显式应用完整配置。
 */
function handleControlledLoopFieldChange() {
  props.state.controlledLoopRepeatValue = ''
  props.state.controlledLoopExitValue = ''
}

/**
 * 切换循环类型；受控整改循环先保留面板草稿，其他类型沿用即时写入命令栈。
 * @param {string} value 当前选中的循环类型。
 * @returns {void} 受控整改循环等待显式应用，其他类型立即通知父组件。
 */
function handleLoopTypeChange(value) {
  if (value !== 'approvalLoop') emit('multi-instance-change')
}

/**
 * 将普通审批、会签和或签的业务选择转换为现有受控 BPMN 多实例状态。
 * @param {'single'|'all'|'any'} value 设计者选择的审批方式。
 * @returns {void} 原子更新循环类型和完成策略后交由父组件写入命令栈。
 */
function handleUserTaskApprovalMethodChange(value) {
  if (!['single', 'all', 'any'].includes(value)) return
  if (value === 'single') {
    props.state.multiInstanceType = 'none'
    emit('multi-instance-change')
    return
  }
  // 会签与或签共享同一人员来源，只通过受控完成条件区分全员完成和任一完成。
  props.state.multiInstanceType = 'controlled'
  props.state.multiInstanceApprovalMode = value
  if (!multiInstanceMemberSourceOptions.some(option => option.value === props.state.multiInstanceMemberSource)) {
    props.state.multiInstanceMemberSource = 'dynamic'
  }
  emit('multi-instance-change')
}

/**
 * 切换会签或或签人员来源并清空上一来源的身份主键。
 * @param {'dynamic'|'start'|'user'|'role'|'dept'} value 当前人员来源。
 * @returns {void} 办理时或发起时来源立即写入模型；指定身份等待目录选择完成后写入。
 */
function handleMemberSourceChange(value) {
  // 不同来源的目录编码不能复用；例如 ROLE101 不能在切换部门后被静默解释为部门主键。
  props.state.configuredMultiInstanceIdentityIds = []
  if (['dynamic', 'start'].includes(value)) emit('multi-instance-change')
}

/**
 * 按当前指定身份来源请求真实用户、角色或部门目录。
 * @param {string} keyword 设计者输入的名称关键字。
 * @returns {void} 未选择指定身份来源时不发起请求。
 */
function searchMultiInstanceIdentities(keyword) {
  const configuration = multiInstanceIdentityConfiguration.value
  if (!configuration) return
  emit('identity-search', {
    target: configuration.pool,
    keyword: String(keyword || '').trim()
  })
}

/**
 * 请求父级批量核验远程分页外的已保存多实例身份。
 * @param {[string|undefined,string[]]} current 当前目录池及尚未加载的选择值。
 * @returns {void} 没有缺失值或相同请求仍在等待时不重复调用。
 */
function resolveMissingMultiInstanceIdentities(current) {
  const [pool, missingValues] = current
  if (!pool || !missingValues.length) return
  const requestKey = `${pool}:${missingValues.join(',')}`
  if (requestKey === pendingMultiInstanceResolutionKey) return
  pendingMultiInstanceResolutionKey = requestKey
  emit('identity-resolve', { target: pool, values: missingValues })
}

let pendingMultiInstanceResolutionKey = ''

/**
 * 请求父组件查询 SLA 超时升级办理人目录。
 * @param {string} keyword 用户输入的检索词。
 * @returns {void} 复用直接办理人的审批能力与权限边界。
 */
function searchSlaAssignees(keyword) {
  emit('identity-search', { target: 'assignees', keyword })
}
/**
 * 生成流程目录下拉的稳定业务标签。
 * @param {object} option 服务端权限过滤后的流程定义目录项。
 * @returns {string} 名称、key、版本和状态组成的可检索标签。
 */
function callActivityOptionLabel(option) {
  return `${option.processName || option.processKey} · ${option.processKey} · v${option.version} · ${option.status === 'ACTIVE' ? '启用' : '停用'}`
}

/**
 * 生成变量映射下拉标签，字段类型来自服务端或当前父模型正式表单。
 * @param {object} field 变量字段目录项。
 * @returns {string} 字段名称、变量名和类型。
 */
function variableFieldLabel(field) {
  return `${field.label || field.name}（${field.name}）· ${field.type}`
}

/**
 * 为输入或输出映射追加一行空草稿。
 * @param {'input'|'output'} direction 映射方向。
 * @returns {void} 映射达到后端 64 项上限时不再追加。
 */
function addCallMapping(direction) {
  const mappings = direction === 'input' ? props.state.callInMappings : props.state.callOutMappings
  if (mappings.length >= 64) return
  mappings.push({ source: '', target: '' })
}

/**
 * 仅在一行映射的来源和目标都完成选择后写入 BPMN 命令栈。
 * @param {{source:string,target:string}} mapping 当前编辑映射。
 * @returns {void} 半成品保留在面板状态中，不生成不可保存 XML。
 */
function handleCallMappingChange(mapping) {
  if (mapping?.source && mapping?.target) emit('call-activity-change')
}

/**
 * 删除指定输入或输出映射并立即交给父组件写入命令栈。
 * @param {'input'|'output'} direction 映射方向。
 * @param {number} index 待删除映射下标。
 * @returns {void} 下标非法时不修改状态。
 */
function removeCallMapping(direction, index) {
  const mappings = direction === 'input' ? props.state.callInMappings : props.state.callOutMappings
  if (!Number.isInteger(index) || index < 0 || index >= mappings.length) return
  mappings.splice(index, 1)
  emit('call-activity-change')
}

watch([() => props.state.id, availableSections], syncActiveSections, {
  immediate: true
})
watch(
  () => {
    const configuration = multiInstanceIdentityConfiguration.value
    if (!configuration) return [undefined, []]
    const loaded = new Set((props.identityOptions[configuration.pool] || []).map(option => String(option.value)))
    const missing = [...new Set((props.state.configuredMultiInstanceIdentityIds || []).map(String))].filter(value => !loaded.has(value))
    if (!missing.length) pendingMultiInstanceResolutionKey = ''
    return [configuration.pool, missing]
  },
  resolveMissingMultiInstanceIdentities,
  { immediate: true, deep: true }
)
</script>

<style scoped>
.designer-properties-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: linear-gradient(180deg, color-mix(in srgb, var(--el-bg-color) 92%, var(--el-color-primary) 8%), var(--el-bg-color) 118px);
}

.designer-properties-panel__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: none;
  min-height: 68px;
  gap: 10px;
  padding: 11px 10px 10px 16px;
  font-size: 14px;
  font-weight: 600;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.designer-properties-panel__heading {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.designer-properties-panel__eyebrow {
  color: var(--el-color-primary);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0;
  line-height: 1.4;
}

.designer-properties-panel__heading strong {
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 650;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.designer-properties-panel__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 1px;
}

.designer-properties-panel__actions :deep(.el-button) {
  width: 28px;
  height: 28px;
  margin: 0;
  color: var(--el-text-color-secondary);
}

.designer-properties-panel__actions :deep(.el-button:hover),
.designer-properties-panel__actions :deep(.el-button:focus-visible) {
  color: var(--el-color-primary);
  background: var(--el-fill-color-light);
}

.designer-properties-panel__context {
  display: grid;
  grid-template-columns: auto auto minmax(0, 1fr);
  align-items: center;
  flex: none;
  gap: 7px;
  min-height: 34px;
  padding: 7px 16px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  background: color-mix(in srgb, var(--el-fill-color-light) 72%, transparent);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.designer-properties-panel__context-dot {
  width: 7px;
  height: 7px;
  background: var(--el-color-success);
  border: 2px solid color-mix(in srgb, var(--el-color-success) 22%, transparent);
  border-radius: 50%;
  box-sizing: content-box;
}

.designer-properties-panel__context code {
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-regular);
  font-family: 'Cascadia Code', Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.designer-properties-panel__scroll {
  flex: 1;
  min-height: 0;
}

.designer-properties-panel__form {
  padding: 10px 12px 18px;
}

.designer-properties-panel__form :deep(.el-collapse) {
  display: grid;
  gap: 8px;
  border-top: 0;
  border-bottom: 0;
}

.designer-properties-panel__form :deep(.el-collapse-item) {
  overflow: hidden;
  background: color-mix(in srgb, var(--el-bg-color) 94%, var(--el-fill-color-light));
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 7px;
  box-shadow: 0 1px 2px rgb(15 23 42 / 4%);
}

.designer-properties-panel__form :deep(.el-collapse-item__header) {
  height: 40px;
  padding: 0 12px;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 600;
  background: transparent;
  border-bottom: 0;
}

.designer-properties-panel__form :deep(.el-collapse-item__header.is-active) {
  color: var(--el-color-primary);
  background: color-mix(in srgb, var(--el-color-primary) 6%, transparent);
}

.designer-properties-panel__form :deep(.el-collapse-item__wrap) {
  background: transparent;
  border-bottom: 0;
}

.designer-properties-panel__form :deep(.el-collapse-item__content) {
  padding: 12px 12px 14px;
}

.designer-properties-panel__form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.designer-properties-panel__form :deep(.el-form-item:last-child) {
  margin-bottom: 0;
}

.designer-properties-panel__form :deep(.el-form-item__label) {
  height: auto;
  margin-bottom: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
}

.designer-properties-panel__form :deep(.el-select),
.designer-properties-panel__form :deep(.el-segmented),
.designer-properties-panel__form :deep(.el-input-number) {
  width: 100%;
}

.task-runtime-contract {
  display: grid;
  gap: 10px;
  padding: 12px;
  margin: 12px 0 0;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.task-runtime-contract div {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 8px;
}

.task-runtime-contract dt {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
}

.task-runtime-contract dd {
  min-width: 0;
  margin: 0;
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.user-task-approval {
  width: 100%;
}

.user-task-approval__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.user-task-approval__heading h4,
.user-task-approval__method h4 {
  margin: 2px 0 0;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 700;
  line-height: 1.5;
}

.user-task-approval__eyebrow {
  color: var(--el-text-color-placeholder);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0;
}

.user-task-approval :deep(.participant-rule-editor) {
  padding: 0;
  margin-bottom: 0;
  background: transparent;
  border: 0;
  border-radius: 0;
}

.user-task-approval__source-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  width: 100%;
  gap: 8px 12px;
}

.user-task-approval__source-grid :deep(.el-radio) {
  min-width: 0;
  height: 30px;
  margin-right: 0;
}

.user-task-approval__source-grid :deep(.el-radio__label) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-task-approval__method {
  padding-top: 14px;
  margin-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.user-task-approval__method h4 {
  margin-bottom: 8px;
}

.user-task-approval__method-list {
  display: grid;
  width: 100%;
}

.user-task-approval__method-list :deep(.el-radio) {
  align-items: flex-start;
  width: 100%;
  min-height: 48px;
  padding: 9px 0;
  margin-right: 0;
  white-space: normal;
  border-bottom: 1px solid var(--el-border-color-extra-light);
}

.user-task-approval__method-list :deep(.el-radio:last-child) {
  border-bottom: 0;
}

.user-task-approval__method-list :deep(.el-radio__input) {
  margin-top: 3px;
}

.user-task-approval__method-list :deep(.el-radio__label) {
  display: grid;
  min-width: 0;
  gap: 2px;
  padding-left: 9px;
  color: var(--el-text-color-regular);
  line-height: 1.35;
  white-space: normal;
}

.user-task-approval__method-list :deep(.el-radio.is-checked .user-task-approval__method-label) {
  color: var(--el-color-primary);
}

.user-task-approval__method-label {
  color: var(--el-text-color-primary);
  font-weight: 650;
}

.user-task-approval__method-list small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.designer-properties-panel :deep(.el-empty) {
  flex: 1;
}
.designer-properties-panel :deep(.el-scrollbar__bar.is-vertical) {
  right: 3px;
}

.call-activity-option {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) minmax(96px, auto) auto;
  align-items: center;
  gap: 10px;
}

.call-activity-option code {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  text-overflow: ellipsis;
}

.call-activity-mappings {
  display: grid;
  width: 100%;
  gap: 8px;
}

.call-activity-mapping-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 18px minmax(0, 1fr) 28px;
  align-items: center;
  gap: 4px;
}

.call-activity-mapping-row__arrow {
  color: var(--el-text-color-placeholder);
  text-align: center;
}

.call-activity-mapping-row :deep(.el-button) {
  width: 28px;
  height: 28px;
  margin: 0;
}
</style>
