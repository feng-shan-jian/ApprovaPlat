package com.ruoyi.flowable.service.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.WfDeployDmnSnapshot;
import com.ruoyi.flowable.domain.WfDeployCallActivitySnapshot;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;
import com.ruoyi.flowable.service.model.WorkflowAuthorFieldCatalogService.FormSnapshotSource;

/** 作者引用校验、单次编译与同事务的部署制品冻结。 */
@Service
public class WorkflowPublicationArtifactsService
{
    private static final String ACTIVE_DEL_FLAG = "0";
    private final WorkflowBpmnService bpmnService;
    private final WorkflowBpmnIdentityValidator bpmnIdentityValidator;
    private final WorkflowAuthorFieldCatalogService authorFieldCatalogService;
    private final WorkflowDeploymentArtifactRepository artifactRepository;
    private final WorkflowFormTemplateValidator formTemplateValidator;
    private final WorkflowExtensionDeploymentService extensionDeploymentService;
    private final WorkflowControlledLoopDeploymentService controlledLoopDeploymentService;
    private final WorkflowConditionDeploymentService conditionDeploymentService;
    private final WorkflowParticipantRuleDeploymentService participantRuleDeploymentService;
    private final WorkflowDmnDecisionService dmnDecisionService;
    private final WorkflowFormFieldExtensionService formFieldExtensionService;
    private final WorkflowCallActivityReferenceService callActivityReferenceService;
    private final WorkflowTaskSlaDeploymentService taskSlaDeploymentService;
    private final WorkflowRuntimeTaskListenerCompiler runtimeTaskListenerCompiler;
    private final WorkflowStartVariableValidator startVariableValidator;

    public WorkflowPublicationArtifactsService(WorkflowBpmnService bpmnService,
            WorkflowBpmnIdentityValidator bpmnIdentityValidator,
            WorkflowAuthorFieldCatalogService authorFieldCatalogService,
            WorkflowDeploymentArtifactRepository artifactRepository,
            WorkflowFormTemplateValidator formTemplateValidator,
            WorkflowExtensionDeploymentService extensionDeploymentService,
            WorkflowControlledLoopDeploymentService controlledLoopDeploymentService,
            WorkflowConditionDeploymentService conditionDeploymentService,
            WorkflowParticipantRuleDeploymentService participantRuleDeploymentService,
            WorkflowDmnDecisionService dmnDecisionService,
            WorkflowFormFieldExtensionService formFieldExtensionService,
            WorkflowCallActivityReferenceService callActivityReferenceService,
            WorkflowTaskSlaDeploymentService taskSlaDeploymentService,
            WorkflowRuntimeTaskListenerCompiler runtimeTaskListenerCompiler)
    {
        this.bpmnService = bpmnService;
        this.bpmnIdentityValidator = bpmnIdentityValidator;
        this.authorFieldCatalogService = authorFieldCatalogService;
        this.artifactRepository = artifactRepository;
        this.formTemplateValidator = formTemplateValidator;
        this.extensionDeploymentService = extensionDeploymentService;
        this.controlledLoopDeploymentService = controlledLoopDeploymentService;
        this.conditionDeploymentService = conditionDeploymentService;
        this.participantRuleDeploymentService = participantRuleDeploymentService;
        this.dmnDecisionService = dmnDecisionService;
        this.formFieldExtensionService = formFieldExtensionService;
        this.callActivityReferenceService = callActivityReferenceService;
        this.taskSlaDeploymentService = taskSlaDeploymentService;
        this.runtimeTaskListenerCompiler = runtimeTaskListenerCompiler;
        this.startVariableValidator = new WorkflowStartVariableValidator(formTemplateValidator);
    }

    /**
     * 保存时核验作者输入、正式引用和表单规则，不生成部署执行模型。
     *
     * @param document WorkflowBpmnDocument，已通过 XML、安全和 Flowable 结构校验的文档
     * @param identity WorkflowCurrentIdentity，当前操作者；内部入口允许为空
     */
    void validateAuthorReferences(WorkflowBpmnDocument document, WorkflowCurrentIdentity identity)
    {
        // 身份主数据可能在设计期间停用，每次保存、校验和部署都必须从正式表重新核验。
        bpmnIdentityValidator.validate(document);
        dmnDecisionService.validateReferences(document);
        List<FormSnapshotSource> sources = authorFieldCatalogService.resolveForms(document.formReferences());
        List<WorkflowControlledLoopFormSchema> schemas = buildControlledLoopFormSchemas(sources);
        // 保存、校验和部署共用冻结表单字段门禁，不能只在最终 deploy 才发现非法规则。
        conditionDeploymentService.validate(document, schemas);
        WorkflowAuthorFormFieldCatalog formFieldCatalog =
                buildAuthorFormFieldCatalog(sources);
        // 自动抄送和动态参与者必须消费同一次冻结的权限化表单目录，避免三个入口语义漂移。
        WorkflowAutoCopyRuleContract.validateFormUserFields(
                document.bpmnModel(), formFieldCatalog);
        participantRuleDeploymentService.validateAuthorRules(document, formFieldCatalog);
        taskSlaDeploymentService.validateAuthorRules(document.bpmnModel());
        if (identity == null) callActivityReferenceService.validateAuthorReferences(document, callActivityForms(sources));
        else callActivityReferenceService.validateAuthorReferences(document, identity, callActivityForms(sources));
    }

    private Map<String, JsonNode> callActivityForms(List<FormSnapshotSource> sources)
    {
        return sources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                source -> source.reference().nodeKey(), FormSnapshotSource::root));
    }

    /**
     * 使用本次表单解释和独占执行模型按领域依赖顺序产生实际部署制品。
     * @param document WorkflowBpmnDocument，事务内取得的作者输入
     * @param identity WorkflowCurrentIdentity，本次命令的正式操作人
     * @return PreparedDeployment，截止于持久化之前的执行资源和快照
     */
    PreparedDeployment prepare(WorkflowBpmnDocument document, WorkflowCurrentIdentity identity)
    {
        bpmnIdentityValidator.validate(document);
        List<FormSnapshotSource> sources = authorFieldCatalogService.resolveForms(document.formReferences());
        // 只有部署/完整预检需要锁定字段扩展版本；普通保存直接使用作者解释。
        sources = sources.stream().map(source -> source.reference().sourceType() == WorkflowFormSourceType.EMBEDDED
                ? new FormSnapshotSource(source.reference(), source.formName(), source.root(),
                        formFieldExtensionService.freezeEmbeddedContentWithSnapshots(source.root(),
                                source.reference().processKey(), source.reference().nodeKey())) : source).toList();
        var schemas = buildControlledLoopFormSchemas(sources);
        var fields = buildAuthorFormFieldCatalog(sources);
        WorkflowAutoCopyRuleContract.validateFormUserFields(document.bpmnModel(), fields);
        var executableModel = bpmnService.executionModel(document);
        var extensions = extensionDeploymentService.prepare(document, executableModel, identity.userId());
        var conditions = conditionDeploymentService.prepare(document, executableModel, schemas, identity.userId());
        var loops = controlledLoopDeploymentService.prepare(document, executableModel, schemas, identity.userId());
        var participants = participantRuleDeploymentService.prepare(document, executableModel, fields, identity.userId());
        var dmn = dmnDecisionService.prepare(executableModel);
        var calls = callActivityReferenceService.prepare(executableModel, document, identity, callActivityForms(sources));
        var sla = taskSlaDeploymentService.prepare(executableModel, identity.userId());
        runtimeTaskListenerCompiler.compile(executableModel);
        byte[] executable = WorkflowBpmnXmlPreserver.write(executableModel, document.bpmnXml());
        return new PreparedDeployment(sources, extensions, conditions, loops, participants, dmn, calls, sla, executable);
    }

    record PreparedDeployment(List<FormSnapshotSource> forms,
            List<com.ruoyi.flowable.domain.WfDeployExtensionSnapshot> extensions,
            List<com.ruoyi.flowable.domain.WfDeployConditionRule> conditions,
            List<com.ruoyi.flowable.domain.WfDeployControlledLoop> loops,
            List<com.ruoyi.flowable.domain.WfDeployParticipantRule> participants,
            List<WorkflowDmnDecisionService.DecisionSource> dmn,
            List<com.ruoyi.flowable.domain.WfDeployCallActivitySnapshot> calls,
            List<com.ruoyi.flowable.domain.WfDeployTaskSla> sla, byte[] executableBpmn) { }

    /** 在调用方发布事务中冻结八类资源，仓库继续独占资源协议和持久化。 */
    WorkflowDeploymentArtifacts freezeArtifacts(String deploymentId, PreparedDeployment prepared,
            WorkflowCurrentIdentity identity)
    {
        List<FormSnapshotSource> snapshotSources = prepared.forms();
        var extensionDeployment = prepared.extensions();
        var conditionDeployment = prepared.conditions();
        var controlledLoopDeployment = prepared.loops();
        var participantDeployment = prepared.participants();
        var dmnDeployment = prepared.dmn();
        var callActivityDeployment = prepared.calls();
        var slaDeployment = prepared.sla();
        List<WfDeployForm> snapshots = buildSnapshots(deploymentId, snapshotSources, identity);
        // 表单字段与服务任务共享部署扩展台账，版本引用保护和删除门禁必须覆盖两类来源。
        List<WfDeployExtensionSnapshot> formFieldSnapshots = snapshotSources.stream()
                .flatMap(source -> source.extensionSnapshots().stream())
                .peek(snapshot -> snapshot.setCreateBy(identity.userId()))
                .toList();
        List<WfDeployExtensionSnapshot> extensionSnapshots = extensionDeploymentService
                .snapshotsForDeployment(deploymentId, extensionDeployment,
                        formFieldSnapshots);
        List<WfDeployDmnSnapshot> dmnSnapshots = dmnDecisionService.freezeSnapshots(
                deploymentId, dmnDeployment, identity.userId());
        List<WfDeployCallActivitySnapshot> callActivitySnapshots =
                callActivityReferenceService.snapshotsForDeployment(
                        deploymentId, callActivityDeployment, identity.userId());

        // 8 类不可变快照作为一个 Flowable 资源子部署原子提交，禁止出现部分表已写、部分表缺失。
        var artifacts = new WorkflowDeploymentArtifacts(
                snapshots, conditionDeployment, controlledLoopDeployment,
                participantDeployment, extensionSnapshots, dmnSnapshots,
                callActivitySnapshots, slaDeployment);
        artifactRepository.persist(deploymentId, artifacts);
        return artifacts;

    }

    /**
     * 使用部署主键和可信操作人生成完整表单快照集合。
     *
     * @param deploymentId String，刚创建的 Flowable 部署主键
     * @param sources List&lt;FormSnapshotSource&gt;，部署前读取的节点和表单模板
     * @param identity WorkflowCurrentIdentity，事务内重新核验的当前用户身份
     * @return List&lt;WfDeployForm&gt;，待批量持久化的不可变部署快照
     */
    private List<WfDeployForm> buildSnapshots(String deploymentId, List<FormSnapshotSource> sources,
            WorkflowCurrentIdentity identity)
    {
        Date now = new Date();
        List<WfDeployForm> snapshots = new ArrayList<>(sources.size());
        for (FormSnapshotSource source : sources)
        {
            WfDeployForm snapshot = new WfDeployForm();
            snapshot.setDeployId(deploymentId);
            snapshot.setSourceType(source.reference().sourceType().name());
            snapshot.setFormId(source.reference().formId());
            snapshot.setFormKey(source.reference().formKey());
            snapshot.setNodeKey(source.reference().nodeKey());
            snapshot.setNodeName(source.reference().nodeName());
            snapshot.setFormName(source.formName());
            snapshot.setContent(source.content());
            snapshot.setDelFlag(ACTIVE_DEL_FLAG);
            snapshot.setCreateBy(identity.userId());
            snapshot.setCreateTime(now);
            snapshots.add(snapshot);
        }
        return snapshots;
    }

    /**
     * 从本次部署已经冻结的节点表单来源提取受控循环判断字段白名单。
     *
     * @param sources List&lt;FormSnapshotSource&gt;，部署事务内固定读取的节点表单来源
     * @return List&lt;WorkflowControlledLoopFormSchema&gt;，按节点顺序返回的不可变字段白名单
     */
    private List<WorkflowControlledLoopFormSchema> buildControlledLoopFormSchemas(
            List<FormSnapshotSource> sources)
    {
        List<WorkflowControlledLoopFormSchema> schemas = new ArrayList<>(sources.size());
        for (FormSnapshotSource source : sources)
        {
            WorkflowBpmnFormReference reference = source.reference();
            schemas.add(new WorkflowControlledLoopFormSchema(reference.processKey(),
                    reference.nodeKey(), startVariableValidator
                            .describeControlledLoopFields(source.root())));
        }
        return List.copyOf(schemas);
    }

    /**
     * 从权限化表单快照构建自动抄送和参与者规则共用的正式用户主键字段目录。
     * 每个来源只解析并全量校验一次，用户主键签名和全部变量名只读复用同一根节点。
     * @param sources List&lt;FormSnapshotSource&gt;，部署事务内固定读取的表单来源
     * @return WorkflowAuthorFormFieldCatalog，按流程和节点隔离的不可变正式字段目录
     */
    private WorkflowAuthorFormFieldCatalog buildAuthorFormFieldCatalog(
            List<FormSnapshotSource> sources)
    {
        WorkflowAuthorFormFieldCatalog.Builder builder =
                WorkflowAuthorFormFieldCatalog.builder();
        for (FormSnapshotSource source : sources)
        {
            WorkflowBpmnFormReference reference = source.reference();
            JsonNode root = source.root();
            builder.add(reference.processKey(), reference.nodeKey(),
                    formTemplateValidator.extractUserIdSourceFieldSignatures(root),
                    formTemplateValidator.extractVariableNames(root));
        }
        return builder.build();
    }

}
