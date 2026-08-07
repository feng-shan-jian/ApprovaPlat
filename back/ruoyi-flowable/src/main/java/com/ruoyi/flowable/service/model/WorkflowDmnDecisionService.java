package com.ruoyi.flowable.service.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.flowable.bpmn.model.BusinessRuleTask;
import org.flowable.bpmn.model.Process;
import org.flowable.dmn.api.DmnDecision;
import org.flowable.dmn.api.DmnDeployment;
import org.flowable.dmn.api.DmnRepositoryService;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployDmnSnapshot;
import com.ruoyi.flowable.domain.dto.WorkflowDmnDeploymentRequest;
import com.ruoyi.flowable.domain.vo.WorkflowDmnDecisionView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.mapper.WfDeployDmnSnapshotMapper;
import com.ruoyi.flowable.service.model.WorkflowPreparedDmnDeployment.DecisionSource;

/**
 * Flowable DMN 决策目录、BusinessRuleTask 编译、版本冻结和删除保护服务。
 */
@Service
public class WorkflowDmnDecisionService
{
    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";
    private static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";
    private static final int MAX_DMN_BYTES = 2 * 1024 * 1024;

    private final WorkflowEngineOperations engineOperations;
    private final DmnRepositoryService dmnRepositoryService;
    private final WfDeployDmnSnapshotMapper snapshotMapper;

    /**
     * 创建 DMN 决策领域服务。
     * @param engineOperations WorkflowEngineOperations，统一事务和可信身份边界
     * @param dmnRepositoryService DmnRepositoryService，Flowable 官方 DMN 仓储服务
     * @param snapshotMapper WfDeployDmnSnapshotMapper，流程部署冻结快照数据访问层
     * @return void，构造后由 Spring 管理
     */
    public WorkflowDmnDecisionService(WorkflowEngineOperations engineOperations,
            DmnRepositoryService dmnRepositoryService,
            WfDeployDmnSnapshotMapper snapshotMapper)
    {
        this.engineOperations = engineOperations;
        this.dmnRepositoryService = dmnRepositoryService;
        this.snapshotMapper = snapshotMapper;
    }

    /**
     * 查询 DMN 决策版本目录。
     * @param latestOnly boolean，true 时每个 key 只返回最新版本
     * @return List&lt;WorkflowDmnDecisionView&gt;，官方 DMN 表的稳定视图
     */
    public List<WorkflowDmnDecisionView> list(boolean latestOnly)
    {
        return engineOperations.read(() ->
        {
            List<DmnDecision> sourceDecisions = dmnRepositoryService.createDecisionQuery()
                    .orderByDecisionKey().asc().orderByDecisionVersion().desc().list().stream()
                    .filter(this::isSourceDecision)
                    .toList();
            if (!latestOnly)
            {
                return sourceDecisions.stream().map(this::toView).toList();
            }
            Map<String, DmnDecision> latestByKey = new LinkedHashMap<>();
            for (DmnDecision decision : sourceDecisions)
            {
                // 查询已按 key 升序、版本降序排列，每个 key 首行就是最新可选来源版本。
                latestByKey.putIfAbsent(decision.getKey(), decision);
            }
            return latestByKey.values().stream().map(this::toView).toList();
        });
    }

    /**
     * 部署一份受大小和 XML 安全边界保护的 DMN 资源。
     * @param request WorkflowDmnDeploymentRequest，资源名、分类和 DMN XML
     * @return String，Flowable 官方 DMN 部署主键
     */
    public String deploy(WorkflowDmnDeploymentRequest request)
    {
        if (request == null)
        {
            throw new ServiceException("DMN 部署请求不能为空", HttpStatus.BAD_REQUEST);
        }
        String resourceName = requireText(request.resourceName(), "DMN 资源名不能为空");
        if (!resourceName.toLowerCase(java.util.Locale.ROOT).endsWith(".dmn"))
        {
            throw new ServiceException("DMN 资源名必须以 .dmn 结尾", HttpStatus.BAD_REQUEST);
        }
        byte[] bytes = requireDmnBytes(request.dmnXml());
        String category = request.category() == null ? "" : request.category().trim();
        return engineOperations.writeAsCurrentUser(identity ->
        {
            DmnDeployment deployment = dmnRepositoryService.createDeployment()
                    .name(resourceName).category(category).addDmnBytes(resourceName, bytes).deploy();
            if (deployment == null || deployment.getId() == null
                    || dmnRepositoryService.createDecisionQuery()
                            .deploymentId(deployment.getId()).count() == 0)
            {
                throw new ServiceException("DMN 部署未产生可执行决策", HttpStatus.CONFLICT);
            }
            return deployment.getId();
        });
    }

    /**
     * 删除尚未被流程部署快照引用的 DMN 来源部署。
     * @param deploymentId String，Flowable DMN 部署主键
     * @return void，删除成功后官方决策和资源同时移除
     */
    public void delete(String deploymentId)
    {
        String id = requireText(deploymentId, "DMN 部署主键不能为空");
        engineOperations.writeAsCurrentUser(identity ->
        {
            if (dmnRepositoryService.createDeploymentQuery().deploymentId(id).singleResult() == null)
            {
                throw new ServiceException("DMN 部署不存在", HttpStatus.NOT_FOUND);
            }
            DmnDeployment deployment = dmnRepositoryService.createDeploymentQuery()
                    .deploymentId(id).singleResult();
            if (!isSourceDeployment(deployment))
            {
                throw new ServiceException("流程冻结 DMN 部署不允许独立删除", HttpStatus.CONFLICT);
            }
            if (snapshotMapper.countBySourceDeploymentId(id) > 0)
            {
                throw new ServiceException("DMN 部署已被流程版本冻结引用", HttpStatus.CONFLICT);
            }
            dmnRepositoryService.deleteDeployment(id);
            return null;
        });
    }

    /**
     * 保存和部署时确认 BusinessRuleTask 只引用一个已部署的精确 DMN 版本。
     * @param document WorkflowBpmnDocument，已通过 BPMN 安全解析的作者文档
     * @return void，引用缺失、任意类或不受控规则配置时抛出业务异常
     */
    public void validateReferences(WorkflowBpmnDocument document)
    {
        for (Process process : document.bpmnModel().getProcesses())
        {
            for (BusinessRuleTask task : process.findFlowElementsOfType(BusinessRuleTask.class, true))
            {
                requireSelectedDecision(task);
            }
        }
    }

    /**
     * 把 BusinessRuleTask 编译为 Flowable 原生 DMN ServiceTask，并固定所有来源资源。
     * @param compiledBpmn byte[]，扩展注册表已编译的 BPMN 资源
     * @return WorkflowPreparedDmnDeployment，最终 BPMN 与精确决策来源
     */
    public WorkflowPreparedDmnDeployment prepare(byte[] compiledBpmn)
    {
        try
        {
            Document document = parseSecure(compiledBpmn);
            NodeList tasks = document.getElementsByTagNameNS(BPMN_NS, "businessRuleTask");
            List<Element> taskElements = new ArrayList<>(tasks.getLength());
            for (int index = 0; index < tasks.getLength(); index++)
            {
                taskElements.add((Element) tasks.item(index));
            }
            List<DecisionSource> sources = new ArrayList<>();
            for (Element task : taskElements)
            {
                sources.add(compileTask(document, task));
            }
            return new WorkflowPreparedDmnDeployment(writeXml(document), sources);
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (Exception exception)
        {
            ServiceException failure = new ServiceException("DMN 业务规则任务编译失败", HttpStatus.BAD_REQUEST);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 创建绑定流程 deploymentId 的 DMN 子部署并持久化不可变快照。
     * @param processDeploymentId String，新流程部署主键
     * @param prepared WorkflowPreparedDmnDeployment，部署前固定的决策来源
     * @param actorUserId String，可信部署操作人正式用户主键
     * @return void，任一子部署或快照不完整时外层事务整体回滚
     */
    public void persist(String processDeploymentId, WorkflowPreparedDmnDeployment prepared,
            String actorUserId)
    {
        Map<String, DmnDeployment> frozenByResource = new LinkedHashMap<>();
        List<WfDeployDmnSnapshot> snapshots = new ArrayList<>();
        for (DecisionSource source : prepared.sources())
        {
            DmnDeployment frozen = frozenByResource.computeIfAbsent(source.resourceGroupKey(), key ->
                    dmnRepositoryService.createDeployment()
                            .name("流程部署 " + processDeploymentId + " 的 DMN 冻结资源")
                            .parentDeploymentId(processDeploymentId)
                            .addDmnBytes(source.resourceName(), source.resourceBytes())
                            .deploy());
            if (frozen == null || frozen.getId() == null)
            {
                throw new ServiceException("DMN 冻结部署结果不完整", HttpStatus.CONFLICT);
            }
            DmnDecision frozenDecision = dmnRepositoryService.createDecisionQuery()
                    .deploymentId(frozen.getId()).decisionKey(source.decisionKey()).singleResult();
            if (frozenDecision == null)
            {
                throw new ServiceException("DMN 冻结部署缺少目标决策", HttpStatus.CONFLICT);
            }
            snapshots.add(buildSnapshot(processDeploymentId, source, frozen,
                    frozenDecision, actorUserId));
        }
        int inserted = snapshots.isEmpty() ? 0 : snapshotMapper.insertBatch(snapshots);
        if (inserted != snapshots.size())
        {
            throw new ServiceException("DMN 部署快照保存不完整", HttpStatus.CONFLICT);
        }
    }

    /**
     * 删除流程部署拥有的全部 DMN 子部署。
     * @param snapshots List&lt;WfDeployDmnSnapshot&gt;，删除前读取的流程 DMN 快照
     * @return void，每个子部署只删除一次
     */
    public void deleteFrozenDeployments(List<WfDeployDmnSnapshot> snapshots)
    {
        Set<String> deploymentIds = new LinkedHashSet<>();
        for (WfDeployDmnSnapshot snapshot : snapshots)
        {
            deploymentIds.add(snapshot.getFrozenDeploymentId());
        }
        for (String deploymentId : deploymentIds)
        {
            // Process Engine Configurator 可能已随主部署删除子部署，因此这里必须保持幂等。
            if (hasText(deploymentId) && dmnRepositoryService.createDeploymentQuery()
                    .deploymentId(deploymentId).count() > 0)
            {
                dmnRepositoryService.deleteDeployment(deploymentId);
            }
        }
    }

    /**
     * 从官方决策表和部署表组装管理视图。
     * @param decision DmnDecision，官方精确版本
     * @return WorkflowDmnDecisionView，不暴露资源正文的目录视图
     */
    private WorkflowDmnDecisionView toView(DmnDecision decision)
    {
        DmnDeployment deployment = dmnRepositoryService.createDeploymentQuery()
                .deploymentId(decision.getDeploymentId()).singleResult();
        return new WorkflowDmnDecisionView(decision.getId(), decision.getKey(), decision.getName(),
                decision.getVersion(), decision.getCategory(), decision.getDecisionType(),
                decision.getDeploymentId(), decision.getResourceName(),
                deployment == null ? null : deployment.getDeploymentTime());
    }

    /**
     * 校验模型对象上的受控 DMN 精确引用。
     * @param task BusinessRuleTask，作者任务
     * @return DmnDecision，存在的精确版本
     */
    private DmnDecision requireSelectedDecision(BusinessRuleTask task)
    {
        if (task.getClassName() != null && !task.getClassName().isBlank()
                || task.getInputVariables() != null && !task.getInputVariables().isEmpty()
                || task.isExclude())
        {
            throw new ServiceException("业务规则任务不允许任意类或规则引擎配置", HttpStatus.BAD_REQUEST);
        }
        List<String> selected = task.getRuleNames();
        if (selected == null || selected.size() != 1 || selected.get(0) == null
                || selected.get(0).isBlank())
        {
            throw new ServiceException("业务规则任务必须选择一个精确 DMN 决策版本", HttpStatus.BAD_REQUEST);
        }
        DmnDecision decision = dmnRepositoryService.getDecision(selected.get(0).trim());
        if (decision == null)
        {
            throw new ServiceException("业务规则任务引用的 DMN 决策不存在", HttpStatus.CONFLICT);
        }
        requireSourceDecision(decision);
        return decision;
    }

    /**
     * 编译单个 DOM BusinessRuleTask 并读取其精确 DMN 来源。
     * @param document Document，待修改的 BPMN DOM
     * @param task Element，业务规则任务元素
     * @return DecisionSource，任务与精确决策资源的绑定
     * @throws IOException DMN 资源读取失败
     */
    private DecisionSource compileTask(Document document, Element task) throws IOException
    {
        String decisionId = task.getAttributeNS(FLOWABLE_NS, "rules").trim();
        if (decisionId.isEmpty() || decisionId.contains(",")
                || task.hasAttributeNS(FLOWABLE_NS, "class")
                || task.hasAttributeNS(FLOWABLE_NS, "ruleVariablesInput")
                || task.hasAttributeNS(FLOWABLE_NS, "exclude"))
        {
            throw new ServiceException("业务规则任务只允许一个精确 DMN 决策引用", HttpStatus.BAD_REQUEST);
        }
        DmnDecision decision = dmnRepositoryService.getDecision(decisionId);
        if (decision == null)
        {
            throw new ServiceException("业务规则任务引用的 DMN 决策不存在", HttpStatus.CONFLICT);
        }
        requireSourceDecision(decision);
        byte[] resource;
        try (InputStream stream = dmnRepositoryService.getDmnResource(decisionId))
        {
            resource = readBounded(stream);
        }
        String processKey = findProcessKey(task);
        String elementId = requireText(task.getAttribute("id"), "业务规则任务标识不能为空");
        rejectAuthorFields(task);
        Element compiled = (Element) document.renameNode(task, BPMN_NS, "serviceTask");
        compiled.removeAttributeNS(FLOWABLE_NS, "rules");
        compiled.removeAttributeNS(FLOWABLE_NS, "class");
        compiled.removeAttributeNS(FLOWABLE_NS, "ruleVariablesInput");
        compiled.removeAttributeNS(FLOWABLE_NS, "exclude");
        compiled.setAttributeNS(FLOWABLE_NS, "flowable:type", "dmn");
        ensureFlowableNamespace(document);
        Element extensionElements = directChild(compiled, "extensionElements");
        if (extensionElements == null)
        {
            extensionElements = document.createElementNS(BPMN_NS, "extensionElements");
            compiled.insertBefore(extensionElements, compiled.getFirstChild());
        }
        appendField(document, extensionElements, "decisionTableReferenceKey", decision.getKey());
        appendField(document, extensionElements, "sameDeployment", "true");
        return new DecisionSource(processKey, elementId, decision.getId(), decision.getKey(),
                decision.getVersion(), decision.getDeploymentId(), decision.getResourceName(),
                WorkflowExtensionChecksum.sha256(new String(resource, StandardCharsets.UTF_8)), resource);
    }

    /**
     * 拒绝作者在 BusinessRuleTask 中预置 Flowable Field 覆盖编译器字段。
     * @param task Element，作者业务规则任务
     * @return void，存在任意 Flowable Field 时拒绝
     */
    private void rejectAuthorFields(Element task)
    {
        NodeList fields = task.getElementsByTagNameNS(FLOWABLE_NS, "field");
        if (fields.getLength() > 0)
        {
            throw new ServiceException("业务规则任务不允许作者字段注入", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 向编译后的 DMN ServiceTask 写入固定字符串字段。
     * @param document Document，BPMN DOM
     * @param extensionElements Element，扩展元素容器
     * @param name String，Flowable DmnActivityBehavior 字段名
     * @param value String，固定字符串值
     * @return void，字段追加到容器尾部
     */
    private void appendField(Document document, Element extensionElements,
            String name, String value)
    {
        Element field = document.createElementNS(FLOWABLE_NS, "flowable:field");
        field.setAttribute("name", name);
        field.setAttribute("stringValue", value);
        extensionElements.appendChild(field);
    }

    /**
     * 组装最终不可变 DMN 快照并计算覆盖全部关键字段的摘要。
     * @param processDeploymentId String，流程部署主键
     * @param source DecisionSource，部署前固定来源
     * @param frozen DmnDeployment，流程拥有的 DMN 子部署
     * @param frozenDecision DmnDecision，子部署中的目标决策
     * @param actorUserId String，可信部署操作人
     * @return WfDeployDmnSnapshot，可直接批量持久化的完整快照
     */
    private WfDeployDmnSnapshot buildSnapshot(String processDeploymentId, DecisionSource source,
            DmnDeployment frozen, DmnDecision frozenDecision, String actorUserId)
    {
        WfDeployDmnSnapshot snapshot = new WfDeployDmnSnapshot();
        snapshot.setDeployId(processDeploymentId);
        snapshot.setProcessKey(source.processKey());
        snapshot.setElementId(source.elementId());
        snapshot.setSourceDecisionId(source.sourceDecisionId());
        snapshot.setDecisionKey(source.decisionKey());
        snapshot.setDecisionVersion(source.decisionVersion());
        snapshot.setSourceDeploymentId(source.sourceDeploymentId());
        snapshot.setResourceName(source.resourceName());
        snapshot.setResourceChecksum(source.resourceChecksum());
        snapshot.setFrozenDeploymentId(frozen.getId());
        snapshot.setFrozenDecisionId(frozenDecision.getId());
        snapshot.setCreateBy(actorUserId);
        snapshot.setSnapshotChecksum(WorkflowExtensionChecksum.sha256(
                snapshot.getDeployId(), snapshot.getProcessKey(), snapshot.getElementId(),
                snapshot.getSourceDecisionId(), snapshot.getDecisionKey(),
                String.valueOf(snapshot.getDecisionVersion()), snapshot.getSourceDeploymentId(),
                snapshot.getResourceName(), snapshot.getResourceChecksum(),
                snapshot.getFrozenDeploymentId(), snapshot.getFrozenDecisionId()));
        return snapshot;
    }

    /**
     * 判断决策是否来自独立管理部署，而不是流程部署拥有的内部冻结副本。
     * @param decision DmnDecision，待判断的官方决策版本
     * @return boolean，来源部署没有 parentDeploymentId 时返回 true
     */
    private boolean isSourceDecision(DmnDecision decision)
    {
        DmnDeployment deployment = dmnRepositoryService.createDeploymentQuery()
                .deploymentId(decision.getDeploymentId()).singleResult();
        return deployment != null && isSourceDeployment(deployment);
    }

    /**
     * 判断 DMN 部署是否是官方根来源部署。
     * @param deployment DmnDeployment，待判断的官方部署
     * @return boolean，parentDeploymentId 为空或等于自身时返回 true
     */
    private boolean isSourceDeployment(DmnDeployment deployment)
    {
        return !hasText(deployment.getParentDeploymentId())
                || deployment.getId().equals(deployment.getParentDeploymentId());
    }

    /**
     * 拒绝设计器引用只供既有流程运行的内部冻结决策。
     * @param decision DmnDecision，设计阶段提交的精确决策版本
     * @return void，内部冻结版本或损坏部署关系会抛出业务异常
     */
    private void requireSourceDecision(DmnDecision decision)
    {
        if (!isSourceDecision(decision))
        {
            throw new ServiceException("业务规则任务不能引用流程内部冻结的 DMN 决策",
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * 使用禁用外部实体的 DOM 工厂解析 BPMN。
     * @param bytes byte[]，扩展编译器输出的 BPMN
     * @return Document，命名空间感知 DOM
     * @throws Exception XML 配置或解析失败
     */
    private Document parseSecure(byte[] bytes) throws Exception
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    /**
     * 将编译 DOM 序列化为 UTF-8 BPMN。
     * @param document Document，已转换任务的 DOM
     * @return byte[]，不带缩进漂移的可执行 BPMN
     * @throws Exception Transformer 初始化或输出失败
     */
    private byte[] writeXml(Document document) throws Exception
    {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    /**
     * 查找任务所属可执行流程 key。
     * @param task Element，BPMN 任务元素
     * @return String，最近 process 祖先的 id
     */
    private String findProcessKey(Element task)
    {
        Node current = task.getParentNode();
        while (current instanceof Element element)
        {
            if (BPMN_NS.equals(element.getNamespaceURI()) && "process".equals(element.getLocalName()))
            {
                return requireText(element.getAttribute("id"), "DMN 任务所属流程标识不能为空");
            }
            current = current.getParentNode();
        }
        throw new ServiceException("DMN 任务不属于任何流程", HttpStatus.BAD_REQUEST);
    }

    /**
     * 查找指定名称的直接 BPMN 子元素。
     * @param parent Element，父元素
     * @param localName String，BPMN 局部名称
     * @return Element，找不到时返回 null
     */
    private Element directChild(Element parent, String localName)
    {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling())
        {
            if (child instanceof Element element && BPMN_NS.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName()))
            {
                return element;
            }
        }
        return null;
    }

    /**
     * 确保编译文档声明 Flowable 命名空间。
     * @param document Document，BPMN 文档
     * @return void，缺失时在根元素补充声明
     */
    private void ensureFlowableNamespace(Document document)
    {
        Element root = document.getDocumentElement();
        if (!root.hasAttributeNS(XMLNS_NS, "flowable"))
        {
            root.setAttributeNS(XMLNS_NS, "xmlns:flowable", FLOWABLE_NS);
        }
    }

    /**
     * 有界读取官方 DMN 资源流。
     * @param stream InputStream，官方仓储返回的 XML 流
     * @return byte[]，最大 2 MiB 的资源
     * @throws IOException 资源读取失败
     */
    private byte[] readBounded(InputStream stream) throws IOException
    {
        if (stream == null)
        {
            throw new ServiceException("DMN 决策资源不存在", HttpStatus.CONFLICT);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1)
        {
            total += read;
            if (total > MAX_DMN_BYTES)
            {
                throw new ServiceException("DMN XML 超过大小限制", HttpStatus.BAD_REQUEST);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /**
     * 校验外部 DMN XML 大小并阻断 DTD 和外部实体声明。
     * @param xml String，用户提交的 DMN XML
     * @return byte[]，严格 UTF-8 字节
     */
    private byte[] requireDmnBytes(String xml)
    {
        String value = requireText(xml, "DMN XML 不能为空");
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY"))
        {
            throw new ServiceException("DMN XML 禁止 DTD 或实体声明", HttpStatus.BAD_REQUEST);
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_DMN_BYTES)
        {
            throw new ServiceException("DMN XML 超过大小限制", HttpStatus.BAD_REQUEST);
        }
        return bytes;
    }

    /**
     * 校验文本非空并去除首尾空白。
     * @param value String，待校验文本
     * @param message String，稳定错误消息
     * @return String，规范化非空文本
     */
    private String requireText(String value, String message)
    {
        if (value == null || value.isBlank())
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    /**
     * 判断文本是否包含非空白内容。
     * @param value String，待判断文本
     * @return boolean，非空且非空白时返回 true
     */
    private boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }
}
