package com.ruoyi.flowable.service.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfForm;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.vo.WorkflowAuthorFieldCatalogView;
import com.ruoyi.flowable.domain.vo.WorkflowAuthorFieldCatalogView.Option;
import com.ruoyi.flowable.domain.vo.WorkflowAuthorFieldCatalogView.Scalar;
import com.ruoyi.flowable.mapper.WfFormMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;

/** 作者字段目录和保存、发布共用的模板来源解释。 */
@Service
public class WorkflowAuthorFieldCatalogService
{
    private static final String ACTIVE_DEL_FLAG = "0";
    private final WorkflowBpmnService bpmnService;
    private final WfFormMapper formMapper;
    private final WorkflowFormTemplateValidator formTemplateValidator;
    private final WorkflowStartVariableValidator startVariableValidator;
    private final WorkflowConditionDeploymentService conditionDeploymentService;
    private final WorkflowCallActivityFields callActivityFields;

    public WorkflowAuthorFieldCatalogService(WorkflowBpmnService bpmnService, WfFormMapper formMapper,
            WorkflowFormTemplateValidator formTemplateValidator, WorkflowConditionDeploymentService conditionDeploymentService)
    {
        this.bpmnService = bpmnService;
        this.formMapper = formMapper;
        this.formTemplateValidator = formTemplateValidator;
        this.startVariableValidator = new WorkflowStartVariableValidator(formTemplateValidator);
        this.conditionDeploymentService = conditionDeploymentService;
        this.callActivityFields = new WorkflowCallActivityFields(formTemplateValidator);
    }

    /**
     * 查询并校验有效表单模板。
     *
     * @param formId Long，表单模板主键
     * @return WfForm，未逻辑删除且内容完整的表单模板
     */
    WfForm requireActiveForm(Long formId)
    {
        if (formId == null || formId <= 0)
        {
            throw new ServiceException("表单主键不合法", HttpStatus.BAD_REQUEST);
        }
        WfForm form = TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                        ? formMapper.selectByIdForShare(formId) : formMapper.selectById(formId);
        if (form == null || !ACTIVE_DEL_FLAG.equals(form.getDelFlag()))
        {
            throw new ServiceException("流程表单不存在或已停用", HttpStatus.NOT_FOUND);
        }
        if (!hasText(form.getFormName()) || !hasText(form.getContent()))
        {
            throw new ServiceException("流程表单模板不完整", HttpStatus.CONFLICT);
        }
        return form;
    }

    /**
     * 批量加载 BPMN 节点引用的表单模板，并在部署前固定读取结果。
     *
     * @param references List&lt;WorkflowBpmnFormReference&gt;，BPMN 中按节点顺序解析出的表单引用
     * @return List&lt;FormSnapshotSource&gt;，用于部署后生成不可变快照的来源数据
     */
    List<FormSnapshotSource> resolveForms(List<WorkflowBpmnFormReference> references)
    {
        return resolveForms(references, new LinkedHashMap<>());
    }

    private List<FormSnapshotSource> resolveForms(List<WorkflowBpmnFormReference> references,
            Map<Long, FormTemplate> formsById)
    {
        List<FormSnapshotSource> sources = new ArrayList<>(references.size());
        // 多个表单共享锁按主键排序，投影输出仍沿用 BPMN 节点顺序。
        references.stream().filter(reference -> reference.sourceType() == WorkflowFormSourceType.TEMPLATE)
                .map(WorkflowBpmnFormReference::formId).distinct().sorted().forEach(id ->
                    formsById.computeIfAbsent(id, key -> {
                        WfForm stored = requireActiveForm(key);
                        return new FormTemplate(stored.getFormName(), formTemplateValidator.validateAuthorTemplate(stored.getContent()));
                    }));
        for (WorkflowBpmnFormReference reference : references)
        {
            if (reference.sourceType() == WorkflowFormSourceType.TEMPLATE)
            {
                FormTemplate form = formsById.get(reference.formId());
                var permissionRoot = WorkflowNodeFormPermissionSnapshot.apply(form.root(), reference);
                sources.add(new FormSnapshotSource(reference, form.name(), permissionRoot, List.of()));
            }
            else
            {
                String formName = hasText(reference.nodeName())
                        ? reference.nodeName() + "内嵌表单" : reference.nodeKey() + "内嵌表单";
                sources.add(new FormSnapshotSource(reference, formName, reference.embeddedContent(), List.of()));
            }
        }
        return List.copyOf(sources);
    }

    /** 为尚未完成的作者图投影有效字段，不写模型、不校验无关业务配置。 */
    @Transactional(readOnly = true)
    public WorkflowAuthorFieldCatalogView catalog(String bpmnXml)
    {
        var author = bpmnService.readAuthorForms(bpmnXml.getBytes(StandardCharsets.UTF_8));
        var document = author.document();
        var issues = new ArrayList<>(author.issues());
        Map<Long, FormTemplate> forms = new LinkedHashMap<>();
        List<FormSnapshotSource> sources = new ArrayList<>();
        var userBuilder = WorkflowAuthorFormFieldCatalog.builder();
        List<WorkflowControlledLoopFormSchema> schemas = new ArrayList<>();
        List<WorkflowAuthorFieldCatalogView.Node> nodes = new ArrayList<>();
        Map<String, Map<String, String>> labelsByProcess = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, String>>> valuesByProcess = new LinkedHashMap<>();
        for (var reference : document.formReferences())
        {
            try
            {
                // 内嵌自定义类型已由作者解析使用 resolveForAuthor 固定元数据；目录读取不抢部署锁。
                var source = reference.sourceType() == WorkflowFormSourceType.EMBEDDED
                        ? new FormSnapshotSource(reference, reference.nodeName(), reference.embeddedContent(), List.of())
                        : resolveForms(List.of(reference), forms).get(0);
                JsonNode root = source.root();
                Map<String, String> labels = new LinkedHashMap<>();
                Map<String, Map<String, String>> values = new LinkedHashMap<>();
                formTemplateValidator.visitFields(root, (field, permission) -> {
                    String name = field.path("vModel").asText("");
                    if (!name.isEmpty())
                    {
                        labels.put(name, field.path("label").asText(name));
                        Map<String, String> optionLabels = new LinkedHashMap<>();
                        field.path("options").forEach(option -> {
                            String value = option.path("value").asText("");
                            optionLabels.put(value, option.path("label").asText(value));
                        });
                        values.put(name, optionLabels);
                    }
                });
                var userFields = formTemplateValidator.extractUserIdSourceFieldSignatures(root);
                var loopFields = startVariableValidator.describeControlledLoopFields(source.root());
                userBuilder.add(reference.processKey(), reference.nodeKey(), userFields,
                        formTemplateValidator.extractVariableNames(root));
                schemas.add(new WorkflowControlledLoopFormSchema(reference.processKey(), reference.nodeKey(), loopFields));
                nodes.add(new WorkflowAuthorFieldCatalogView.Node(reference.processKey(), reference.nodeKey(),
                        fieldOptions(userFields.keySet(), labels), scalarOptions(loopFields, labels, values)));
                labelsByProcess.computeIfAbsent(reference.processKey(), key -> new LinkedHashMap<>()).putAll(labels);
                valuesByProcess.computeIfAbsent(reference.processKey(), key -> new LinkedHashMap<>()).putAll(values);
                sources.add(source);
            }
            catch (ServiceException exception)
            {
                issues.add(new WorkflowAuthorFieldCatalogView.Issue(reference.processKey(), reference.nodeKey(),
                        exception.getMessage()));
            }
        }
        var users = userBuilder.build();
        List<WorkflowAuthorFieldCatalogView.Process> processes = new ArrayList<>();
        for (var process : document.bpmnModel().getProcesses())
        {
            if (!process.isExecutable() || issues.stream().anyMatch(issue -> process.getId().equals(issue.processKey())))
                continue;
            var processSources = sources.stream().filter(source -> source.reference().processKey().equals(process.getId())).toList();
            Map<String, String> labels = labelsByProcess.getOrDefault(process.getId(), Map.of());
            try
            {
                var processDocument = new WorkflowBpmnDocument(document.bpmnModel(), document.bpmnXml(),
                        processSources.stream().map(FormSnapshotSource::reference).toList());
                var contents = processSources.stream().collect(java.util.stream.Collectors.toMap(
                        source -> source.reference().nodeKey(), FormSnapshotSource::root));
                var conditions = conditionDeploymentService.buildProcessFieldIndex(schemas.stream()
                        .filter(schema -> schema.processKey().equals(process.getId())).toList());
                var calls = callActivityFields.describeParentFields(processDocument, contents);
                processes.add(new WorkflowAuthorFieldCatalogView.Process(process.getId(),
                        fieldOptions(labels.keySet().stream().filter(name -> users.containsProcessField(process.getId(), name)).toList(), labels),
                        scalarOptions(conditions.getOrDefault(process.getId(), Map.of()), labels,
                                valuesByProcess.getOrDefault(process.getId(), Map.of())),
                        calls.getOrDefault(process.getId(), List.of())));
            }
            catch (ServiceException exception)
            {
                issues.add(new WorkflowAuthorFieldCatalogView.Issue(process.getId(), "", exception.getMessage()));
            }
        }
        return new WorkflowAuthorFieldCatalogView(List.copyOf(nodes), List.copyOf(processes), List.copyOf(issues));
    }

    private List<Option> fieldOptions(java.util.Collection<String> fields, Map<String, String> labels)
    {
        return fields.stream().sorted().map(name -> new Option(name, fieldLabel(name, labels))).toList();
    }

    private String fieldLabel(String name, Map<String, String> labels)
    {
        String label = labels.getOrDefault(name, name);
        return name.equals(label) || !hasText(label) ? name : label + "（" + name + "）";
    }

    private List<Scalar> scalarOptions(Map<String, WorkflowControlledLoopFormField> fields,
            Map<String, String> labels, Map<String, Map<String, String>> optionLabels)
    {
        return fields.values().stream().sorted(java.util.Comparator.comparing(WorkflowControlledLoopFormField::name))
                .map(field -> new Scalar(field.name(), fieldLabel(field.name(), labels), field.kind().name(),
                        field.kind() == WorkflowControlledLoopFormField.Kind.BOOLEAN
                                ? List.of(new Option("true", "是"), new Option("false", "否"))
                                : (field.enumValues().isEmpty() ? optionLabels.getOrDefault(field.name(), Map.of()).keySet()
                                        : field.enumValues()).stream().sorted().map(value -> new Option(value,
                                        optionLabels.getOrDefault(field.name(), Map.of()).getOrDefault(value, value))).toList(),
                        field.kind() == WorkflowControlledLoopFormField.Kind.BOOLEAN || !field.enumValues().isEmpty()))
                .toList();
    }

    /** 当次不同模板的原始解释；多个节点从此各自派生权限。 */
    private record FormTemplate(String name, JsonNode root) { }

    /**
     * 部署快照的节点引用和表单模板固定读取结果。
     *
     * @param reference WorkflowBpmnFormReference，BPMN 节点表单引用
     * @param formName String，部署时固化的表单名称
     * @param root JsonNode，部署时固化的正式表单 JSON 根节点
     * @param extensionSnapshots List&lt;WfDeployExtensionSnapshot&gt;，内嵌自定义字段的精确版本快照
     */
    record FormSnapshotSource(WorkflowBpmnFormReference reference,
            String formName, JsonNode root,
            List<WfDeployExtensionSnapshot> extensionSnapshots)
    {
        String content() { return root.toString(); }
    }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
}
