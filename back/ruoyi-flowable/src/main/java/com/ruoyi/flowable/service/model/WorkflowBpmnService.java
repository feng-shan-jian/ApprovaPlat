package com.ruoyi.flowable.service.model;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.Event;
import org.flowable.bpmn.model.EventDefinition;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.FormProperty;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.ScriptTask;
import org.flowable.bpmn.model.SendTask;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.validation.ValidationError;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnValidationIssue;
import com.ruoyi.flowable.extension.WorkflowExtensionBpmnContract;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceModelContract;

/**
 * BPMN XML 的有界 UTF-8 解码、安全 XML 解析和工作流业务规则校验组件。
 */
@Component
public class WorkflowBpmnService
{
    /** 单个 BPMN XML 允许的最大 UTF-8 字节数。 */
    static final int MAX_BPMN_BYTES = 2 * 1024 * 1024;

    /** 表单键必须严格使用 key_正Long。 */
    private static final Pattern FORM_KEY_PATTERN = Pattern.compile("key_([1-9][0-9]*)");

    /** Flowable BPMN 扩展命名空间，其他引擎私有扩展只允许作者往返。 */
    private static final String FLOWABLE_NAMESPACE = "http://flowable.org/bpmn";

    /** 通用扩展属性名只允许稳定 ASCII 标识，保留 approva.* 给平台内部契约。 */
    private static final Pattern EXTENSION_PROPERTY_NAME_PATTERN =
            Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

    /** 单个 properties 容器最多保存的普通元数据条目数。 */
    private static final int MAX_EXTENSION_PROPERTIES = 32;

    /** 单个普通扩展属性值最大字符数。 */
    private static final int MAX_EXTENSION_PROPERTY_VALUE_LENGTH = 1024;

    /** 任务监听器只允许引用生产兼容入口，不接受任意 Spring Bean。 */
    private static final String USER_TASK_LISTENER_EXPRESSION = "${userTaskListener}";

    /** 生产兼容入口允许处理的任务生命周期事件。 */
    private static final Set<String> USER_TASK_LISTENER_EVENTS =
            Set.of("create", "assignment", "complete");

    /** 普通表达式仅允许变量、属性、索引、字面量和运算符，禁止方法调用。 */
    private static final Pattern SAFE_EXPRESSION_PATTERN = Pattern.compile(
            "[$#]\\{[A-Za-z0-9_.$#{}\\[\\]'\"\\s=!<>+\\-*/%?:,&|;]+}");

    /**
     * SLA 编译阶段唯一允许生成的服务任务表达式；参数均来自已校验并冻结的部署快照。
     * 该白名单拒绝任意 Bean、方法链和用户输入拼接，避免作者借编译阶段绕过受控扩展边界。
     */
    private static final Pattern SLA_TIMER_DELEGATE_EXPRESSION = Pattern.compile(
            "\\$\\{workflowSlaTimerDelegate\\.executeTimer\\(execution,'[A-Za-z0-9_.:-]+',"
                    + "'(REMINDER|ESCALATE)',[0-9]{1,3},(null|'[1-9][0-9]{0,18}')\\)\\}");

    /** 原始 XML 中显式声明的非中断 Error 边界；模型转换会丢失该作者意图，必须先行拦截。 */
    private static final Pattern NON_INTERRUPTING_ERROR_BOUNDARY_PATTERN = Pattern.compile(
            "(?is)<boundaryEvent\\b[^>]*cancelActivity\\s*=\\s*['\"]false['\"][^>]*>"
                    + ".*?<errorEventDefinition\\b");

    /** 即使表达式语法受限也不允许出现的敏感对象和反射入口。 */
    private static final List<String> DANGEROUS_EXPRESSION_TOKENS = List.of(
            "java.", "javax.", "jakarta.", "runtime", "processbuilder", "system.",
            "getclass", "classloader", "forname", "applicationcontext", "beanfactory",
            "scriptengine", "jndi", "reflection", ".class", "exec", "new ");

    /** BPMN 资源所处阶段，用于区分作者字段与部署编译结果的互斥契约。 */
    private enum ValidationContext
    {
        AUTHOR,
        COMPILED_DEPLOYMENT
    }

    private final RepositoryService repositoryService;

    /** 正式自定义表单字段目录解析器；纯解析单测可不启用。 */
    private final WorkflowFormFieldExtensionService formFieldExtensionService;

    /** 错误与升级正式编码目录；纯解析单测可不启用。 */
    private final WorkflowBpmnEventCodeService bpmnEventCodeService;

    /**
     * 创建 BPMN 安全校验组件。
     *
     * @param repositoryService RepositoryService，执行 Flowable 官方流程模型校验的公共 API
     * @return 无返回值，构造后由 Spring 管理该组件
     */
    public WorkflowBpmnService(RepositoryService repositoryService)
    {
        this(repositoryService, null, null);
    }

    /**
     * 创建接入正式自定义表单字段目录的 BPMN 安全校验组件。
     * @param repositoryService RepositoryService，Flowable 官方模型校验公共 API
     * @param formFieldExtensionService WorkflowFormFieldExtensionService，自定义字段目录解析器
     * @return 无返回值，构造后由 Spring 管理
     */
    @Autowired
    public WorkflowBpmnService(RepositoryService repositoryService,
            WorkflowFormFieldExtensionService formFieldExtensionService)
    {
        this(repositoryService, formFieldExtensionService, null);
    }

    /**
     * 创建接入正式表单字段和 BPMN 事件编码目录的安全校验组件。
     * @param repositoryService RepositoryService，Flowable 官方模型校验公共 API
     * @param formFieldExtensionService WorkflowFormFieldExtensionService，自定义字段目录解析器
     * @param bpmnEventCodeService WorkflowBpmnEventCodeService，错误与升级正式编码目录
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowBpmnService(RepositoryService repositoryService,
            WorkflowFormFieldExtensionService formFieldExtensionService,
            WorkflowBpmnEventCodeService bpmnEventCodeService)
    {
        this.repositoryService = repositoryService;
        this.formFieldExtensionService = formFieldExtensionService;
        this.bpmnEventCodeService = bpmnEventCodeService;
    }

    /**
     * 有界解码并安全解析 BPMN，校验节点表单、脚本、实现类、表达式和 Flowable 规则。
     *
     * @param bpmnBytes byte[]，客户端或模型仓储提供的 BPMN UTF-8 原始字节
     * @return WorkflowBpmnDocument，通过全部校验的 BPMN 文档和表单引用
     */
    public WorkflowBpmnDocument validate(byte[] bpmnBytes)
    {
        WorkflowBpmnDocument document = validateDocument(
                bpmnBytes, true, ValidationContext.AUTHOR);
        validateDeployable(document);
        return document;
    }

    /**
     * 校验作者 XML 是否可以安全保存和回显，不要求所有元素均可由 Flowable 8 执行。
     *
     * @param bpmnBytes byte[]，客户端提交的 BPMN UTF-8 原始字节
     * @return WorkflowBpmnDocument，通过安全、表单和业务约束的作者文档
     */
    public WorkflowBpmnDocument validateForSave(byte[] bpmnBytes)
    {
        return validateDocument(bpmnBytes, true, ValidationContext.AUTHOR);
    }

    /**
     * 校验尚在设计阶段的 BPMN 草稿；草稿可以暂未配置开始表单，其余安全和结构规则保持不变。
     *
     * @param bpmnBytes byte[]，模型创建时生成或编辑器读取的 BPMN UTF-8 原始字节
     * @return WorkflowBpmnDocument，通过草稿安全校验的 BPMN 文档和已有表单引用
     */
    public WorkflowBpmnDocument validateDraft(byte[] bpmnBytes)
    {
        return validateDocument(bpmnBytes, false, ValidationContext.AUTHOR);
    }

    /**
     * 校验部署编译器生成的可执行 BPMN；固定调度器必须保留，作者扩展字段必须已经剥离。
     *
     * @param bpmnBytes byte[]，Flowable 部署仓储中读取的已编译 BPMN UTF-8 字节
     * @return WorkflowBpmnDocument，通过安全、编译契约、兼容性和 Flowable 官方校验的部署文档
     */
    public WorkflowBpmnDocument validateCompiledDeployment(byte[] bpmnBytes)
    {
        WorkflowBpmnDocument document = validateDocument(
                bpmnBytes, true, ValidationContext.COMPILED_DEPLOYMENT);
        validateDeployable(document);
        return document;
    }

    /**
     * 执行统一的 BPMN 解码、解析和安全扫描；部署层另行执行执行兼容性与官方校验。
     *
     * @param bpmnBytes byte[]，待校验的 BPMN UTF-8 原始字节
     * @param requireStartForm boolean，true 表示保存或部署场景必须配置开始表单
     * @param validationContext ValidationContext，作者资源或部署编译资源的字段契约
     * @return WorkflowBpmnDocument，通过指定校验强度的 BPMN 文档和表单引用
     */
    private WorkflowBpmnDocument validateDocument(byte[] bpmnBytes, boolean requireStartForm,
            ValidationContext validationContext)
    {
        if (bpmnBytes == null || bpmnBytes.length == 0)
        {
            throw invalidBpmn("BPMN XML 不能为空", null);
        }
        if (bpmnBytes.length > MAX_BPMN_BYTES)
        {
            throw invalidBpmn("BPMN XML 超过大小限制", null);
        }

        String bpmnXml = decodeUtf8(bpmnBytes);
        String normalizedXml = bpmnXml.toLowerCase(Locale.ROOT);
        if (normalizedXml.contains("<!doctype") || normalizedXml.contains("<!entity"))
        {
            // 即使底层 StAX 实现忽略相关属性，也不允许 DTD 或实体声明进入转换器。
            throw invalidBpmn("BPMN XML 不允许 DTD 或实体声明", null);
        }
        try
        {
            validateRawExtensionProperties(bpmnXml);
            validateRawBusinessBoundarySemantics(bpmnXml);
            org.flowable.bpmn.model.BpmnModel bpmnModel = parseSecurely(bpmnXml);
            List<WorkflowBpmnFormReference> references = validateModel(
                    bpmnModel, requireStartForm, validationContext);
            validateRawExpressions(bpmnXml,
                    countControlledMultiInstanceCollections(bpmnModel), validationContext);
            return new WorkflowBpmnDocument(bpmnModel, bpmnXml, references);
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (RuntimeException exception)
        {
            // 解析器或转换器的原始消息可能包含 XML 正文和内部类名，对外只返回稳定提示。
            throw invalidBpmn("BPMN XML 解析失败", exception);
        }
    }

    /**
     * 返回 Flowable 8 无法执行、但允许在作者 XML 中稳定往返的元素诊断。
     *
     * @param document WorkflowBpmnDocument，已经通过保存安全校验的作者文档
     * @return List&lt;WorkflowBpmnValidationIssue&gt;，按流程顺序返回的不可部署警告
     */
    public List<WorkflowBpmnValidationIssue> deploymentCompatibilityIssues(
            WorkflowBpmnDocument document)
    {
        if (document == null || document.bpmnModel() == null)
        {
            throw invalidBpmn("BPMN 文档不能为空", null);
        }
        List<WorkflowBpmnValidationIssue> issues = new ArrayList<>();
        XMLStreamReader reader = null;
        try
        {
            reader = createSecureXmlInputFactory().createXMLStreamReader(
                    new java.io.StringReader(document.bpmnXml()));
            ArrayDeque<String> ownerElementIds = new ArrayDeque<>();
            while (reader.hasNext())
            {
                if (reader.isStartElement())
                {
                    String localName = reader.getLocalName();
                    String parentElementId = ownerElementIds.isEmpty()
                            ? "" : ownerElementIds.peek();
                    String currentElementId = trimToEmpty(
                            reader.getAttributeValue(null, "id"));
                    if ("complexGateway".equals(localName))
                    {
                        issues.add(new WorkflowBpmnValidationIssue(
                                "BPMN_ELEMENT_NOT_EXECUTABLE", "WARNING",
                                reader.getAttributeValue(null, "id"),
                                "ComplexGateway 可编辑和导出，但 Flowable 8 不支持部署执行"));
                    }
                    else if ("standardLoopCharacteristics".equals(localName))
                    {
                        issues.add(new WorkflowBpmnValidationIssue(
                                "BPMN_ELEMENT_NOT_EXECUTABLE", "WARNING",
                                parentElementId.isEmpty() ? null : parentElementId,
                                "标准循环可编辑和导出，但 Flowable 8 不提供可执行模型，禁止部署"));
                    }
                    ownerElementIds.push(currentElementId.isEmpty()
                            ? parentElementId : currentElementId);
                }
                else if (reader.isEndElement() && !ownerElementIds.isEmpty())
                {
                    ownerElementIds.pop();
                }
                reader.next();
            }
            issues.addAll(scanForeignPrivateExtensions(document.bpmnXml()));
        }
        catch (XMLStreamException exception)
        {
            throw invalidBpmn("BPMN XML 兼容性扫描失败", exception);
        }
        finally
        {
            closeXmlReader(reader);
        }
        return List.copyOf(issues);
    }

    /**
     * 校验 flowable:properties 仅包含有界、唯一、非保留的普通名值元数据。
     * @param bpmnXml String，已经通过严格 UTF-8 解码的作者 XML
     * @return void，结构、数量、名称或值越界时抛出稳定 400
     */
    private void validateRawExtensionProperties(String bpmnXml)
    {
        XMLStreamReader reader = null;
        try
        {
            reader = createSecureXmlInputFactory().createXMLStreamReader(
                    new java.io.StringReader(bpmnXml));
            int depth = 0;
            int propertiesDepth = -1;
            int propertyCount = 0;
            Set<String> propertyNames = new HashSet<>();
            while (reader.hasNext())
            {
                if (reader.isStartElement())
                {
                    depth++;
                    if (FLOWABLE_NAMESPACE.equals(reader.getNamespaceURI())
                            && "properties".equals(reader.getLocalName()))
                    {
                        if (propertiesDepth >= 0)
                        {
                            throw invalidBpmn("通用扩展属性容器不允许嵌套", null);
                        }
                        propertiesDepth = depth;
                        propertyCount = 0;
                        propertyNames.clear();
                    }
                    else if (propertiesDepth >= 0)
                    {
                        if (depth != propertiesDepth + 1
                                || !FLOWABLE_NAMESPACE.equals(reader.getNamespaceURI())
                                || !"property".equals(reader.getLocalName()))
                        {
                            throw invalidBpmn("通用扩展属性只允许 property 名值项", null);
                        }
                        validateRawExtensionProperty(reader, ++propertyCount, propertyNames);
                    }
                }
                else if (reader.isEndElement())
                {
                    if (depth == propertiesDepth)
                    {
                        propertiesDepth = -1;
                        propertyNames.clear();
                    }
                    depth--;
                }
                reader.next();
            }
        }
        catch (XMLStreamException exception)
        {
            throw invalidBpmn("BPMN XML 扩展属性扫描失败", exception);
        }
        finally
        {
            closeXmlReader(reader);
        }
    }

    /**
     * 校验单条 flowable:property 的名称、值、重复和数量边界。
     * @param reader XMLStreamReader，当前定位在 property 开始标签
     * @param propertyCount int，当前容器内从 1 开始的属性数量
     * @param propertyNames Set&lt;String&gt;，当前容器已出现的属性名
     * @return void，任一约束不满足时抛出稳定 400
     */
    private void validateRawExtensionProperty(XMLStreamReader reader, int propertyCount,
            Set<String> propertyNames)
    {
        if (propertyCount > MAX_EXTENSION_PROPERTIES)
        {
            throw invalidBpmn("单个元素最多允许 32 个通用扩展属性", null);
        }
        String name = trimToEmpty(reader.getAttributeValue(null, "name"));
        String value = reader.getAttributeValue(null, "value");
        boolean allowedPlatformProperty =
                WorkflowControlledLoopBpmnContract.isReservedProperty(name)
                || WorkflowTaskSlaDeploymentService.AUTHOR_PROPERTY_NAMES.contains(name);
        if (!EXTENSION_PROPERTY_NAME_PATTERN.matcher(name).matches()
                || (name.startsWith("approva.") && !allowedPlatformProperty))
        {
            throw invalidBpmn("通用扩展属性名不合法或使用了平台保留前缀", null);
        }
        if (!propertyNames.add(name))
        {
            throw invalidBpmn("同一元素的通用扩展属性名不能重复", null);
        }
        if (value == null || value.length() > MAX_EXTENSION_PROPERTY_VALUE_LENGTH)
        {
            throw invalidBpmn("通用扩展属性值缺失或超过长度限制", null);
        }
    }

    /**
     * 扫描 extensionElements 中非 BPMN、非 Flowable 命名空间的私有扩展。
     * @param bpmnXml String，已经通过保存门禁的作者 XML
     * @return List&lt;WorkflowBpmnValidationIssue&gt;，部署阶段不兼容诊断
     */
    private List<WorkflowBpmnValidationIssue> scanForeignPrivateExtensions(String bpmnXml)
    {
        List<WorkflowBpmnValidationIssue> issues = new ArrayList<>();
        XMLStreamReader reader = null;
        try
        {
            reader = createSecureXmlInputFactory().createXMLStreamReader(
                    new java.io.StringReader(bpmnXml));
            int depth = 0;
            int extensionDepth = -1;
            while (reader.hasNext())
            {
                if (reader.isStartElement())
                {
                    depth++;
                    if ("extensionElements".equals(reader.getLocalName()))
                    {
                        extensionDepth = depth;
                    }
                    else if (extensionDepth >= 0 && depth == extensionDepth + 1)
                    {
                        String namespace = trimToEmpty(reader.getNamespaceURI());
                        if (!FLOWABLE_NAMESPACE.equals(namespace)
                                && !"http://www.omg.org/spec/BPMN/20100524/MODEL".equals(namespace))
                        {
                            issues.add(new WorkflowBpmnValidationIssue(
                                    "BPMN_PRIVATE_EXTENSION_NOT_COMPATIBLE", "WARNING", null,
                                    "检测到其他引擎私有扩展 " + reader.getName()
                                            + "，可导入和导出但禁止部署"));
                        }
                    }
                }
                else if (reader.isEndElement())
                {
                    if (depth == extensionDepth)
                    {
                        extensionDepth = -1;
                    }
                    depth--;
                }
                reader.next();
            }
        }
        catch (XMLStreamException exception)
        {
            throw invalidBpmn("BPMN XML 私有扩展扫描失败", exception);
        }
        finally
        {
            closeXmlReader(reader);
        }
        return issues;
    }

    /**
     * 对已通过保存校验的作者文档执行部署兼容性和 Flowable 官方规则门禁。
     *
     * @param document WorkflowBpmnDocument，待部署的安全作者文档
     * @return void，通过时无返回；发现不可执行元素或官方错误时抛出 400
     */
    public void validateDeployable(WorkflowBpmnDocument document)
    {
        List<WorkflowBpmnValidationIssue> issues = deploymentCompatibilityIssues(document);
        if (!issues.isEmpty())
        {
            WorkflowBpmnValidationIssue issue = issues.get(0);
            throw new ServiceException(issue.message(), HttpStatus.BAD_REQUEST)
                    .setSubCode(issue.code());
        }
        validateWithFlowable(document.bpmnModel());
    }

    /**
     * 使用 REPORT 策略严格解码 UTF-8，拒绝替换非法字节。
     *
     * @param bytes byte[]，BPMN 原始字节
     * @return String，严格 UTF-8 文本
     */
    private String decodeUtf8(byte[] bytes)
    {
        try
        {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        }
        catch (CharacterCodingException exception)
        {
            throw invalidBpmn("BPMN XML 必须使用有效 UTF-8 编码", exception);
        }
    }

    /**
     * 使用禁用 DTD、外部实体和实体替换的 StAX Reader 解析 BPMN。
     *
     * @param bpmnXml String，已经严格 UTF-8 解码的 BPMN XML
     * @return org.flowable.bpmn.model.BpmnModel，Flowable 公共 BPMN 模型
     */
    private org.flowable.bpmn.model.BpmnModel parseSecurely(String bpmnXml)
    {
        XMLStreamReader reader = null;
        try
        {
            XMLInputFactory factory = createSecureXmlInputFactory();
            reader = factory.createXMLStreamReader(new java.io.StringReader(bpmnXml));
            return new BpmnXMLConverter().convertToBpmnModel(reader);
        }
        catch (XMLStreamException | IllegalArgumentException exception)
        {
            throw invalidBpmn("BPMN XML 解析失败", exception);
        }
        finally
        {
            closeXmlReader(reader);
        }
    }

    /**
     * 创建统一禁用 DTD、外部实体和实体替换的 StAX 工厂。
     *
     * @return XMLInputFactory，可用于 Flowable 转换和作者 XML 兼容性扫描的安全工厂
     */
    private XMLInputFactory createSecureXmlInputFactory()
    {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) ->
        {
            throw new XMLStreamException("external resources disabled");
        });
        return factory;
    }

    /**
     * 关闭 StAX Reader，关闭失败不得覆盖已经形成的业务结果。
     *
     * @param reader XMLStreamReader，允许为空的 XML Reader
     * @return void，关闭失败时静默结束
     */
    private void closeXmlReader(XMLStreamReader reader)
    {
        if (reader == null)
        {
            return;
        }
        try
        {
            reader.close();
        }
        catch (XMLStreamException ignored)
        {
            // Reader 关闭失败不覆盖已经产生的业务校验结果。
        }
    }

    /**
     * 校验可执行流程、开始节点、表单引用及所有递归流程元素的安全约束。
     *
     * @param bpmnModel org.flowable.bpmn.model.BpmnModel，安全 XML Reader 解析后的模型
     * @param requireStartForm boolean，是否要求每个流程的开始节点已经配置表单
     * @param validationContext ValidationContext，作者资源或部署编译资源的字段契约
     * @return List&lt;WorkflowBpmnFormReference&gt;，按流程和节点顺序提取的表单引用
     */
    private List<WorkflowBpmnFormReference> validateModel(
            org.flowable.bpmn.model.BpmnModel bpmnModel, boolean requireStartForm,
            ValidationContext validationContext)
    {
        List<Process> processes = bpmnModel.getProcesses();
        if (processes == null || processes.isEmpty()
                || processes.stream().noneMatch(Process::isExecutable))
        {
            throw invalidBpmn("BPMN 至少需要一个可执行流程", null);
        }

        List<WorkflowBpmnFormReference> references = new ArrayList<>();
        Set<String> uniqueReferences = new HashSet<>();
        for (Process process : processes)
        {
            // 主流程唯一开始节点只统计顶层元素；事件子流程和嵌入子流程拥有各自合法的开始事件。
            List<StartEvent> startEvents = process.getFlowElements().stream()
                    .filter(StartEvent.class::isInstance)
                    .map(StartEvent.class::cast)
                    .toList();
            if (startEvents.size() != 1)
            {
                throw invalidBpmn("每个流程必须且只能包含一个开始节点", null);
            }
            StartEvent startEvent = startEvents.get(0);
            boolean startHasTemplate = hasText(startEvent.getFormKey());
            boolean startHasEmbedded = hasFormProperties(startEvent.getFormProperties());
            if (!startHasTemplate && !startHasEmbedded)
            {
                if (requireStartForm)
                {
                    throw invalidBpmn("开始节点必须配置流程表单", null);
                }
            }
            else
            {
                addNodeFormReference(process.getId(), startEvent.getFormKey(), startEvent.getFormProperties(),
                        startEvent.getId(), startEvent.getName(), references, uniqueReferences);
            }

            for (UserTask userTask : process.findFlowElementsOfType(UserTask.class, true))
            {
                if (hasText(userTask.getFormKey())
                        || hasFormProperties(userTask.getFormProperties()))
                {
                    addNodeFormReference(process.getId(), userTask.getFormKey(), userTask.getFormProperties(),
                            userTask.getId(), userTask.getName(), references, uniqueReferences);
                }
                validateTaskListeners(userTask.getTaskListeners(), validationContext);
            }
            if (!process.findFlowElementsOfType(ScriptTask.class, true).isEmpty())
            {
                throw invalidBpmn("流程不允许使用脚本任务", null);
            }

            validateBpmnBusinessEvents(bpmnModel, process);

            validateListeners(process.getExecutionListeners(), validationContext);
            for (FlowElement element : process.findFlowElementsOfType(FlowElement.class, true))
            {
                validateListeners(element.getExecutionListeners(), validationContext);
                validateFlowElement(process, element, validationContext);
            }
        }
        if (validationContext == ValidationContext.AUTHOR)
        {
            // 作者资源必须把 MessageFlow 绑定到可靠 outbox；编译资源的 SendTask 已转换为固定调度器。
            WorkflowCollaborationValidator.validate(bpmnModel);
        }
        return List.copyOf(references);
    }

    /**
     * 校验错误与升级引用必须来自正式目录，并收紧边界附着、匹配和中断语义。
     * @param bpmnModel BpmnModel，包含根 Error/Escalation 定义的完整模型
     * @param process Process，当前可执行流程
     * @return void，目录、附着或匹配不合法时拒绝保存和部署
     */
    private void validateBpmnBusinessEvents(org.flowable.bpmn.model.BpmnModel bpmnModel,
            Process process)
    {
        Set<String> attachedMatches = new HashSet<>();
        for (Event event : process.findFlowElementsOfType(Event.class, true))
        {
            List<EventDefinition> definitions = event.getEventDefinitions();
            if (definitions == null || definitions.isEmpty())
            {
                continue;
            }
            int businessEventDefinitions = 0;
            for (EventDefinition definition : definitions)
            {
                WorkflowBpmnEventModelSupport.ResolvedEvent resolved =
                        WorkflowBpmnEventModelSupport.resolve(bpmnModel, definition);
                if (resolved == null)
                {
                    continue;
                }
                businessEventDefinitions++;
                if (!hasText(resolved.eventCode()))
                {
                    throw invalidBpmn("BPMN 错误或升级必须引用正式业务编码", null);
                }
                if (bpmnEventCodeService != null)
                {
                    // 保存和部署均核验当前启用目录，停用编码只能由历史部署继续运行。
                    bpmnEventCodeService.requireEnabled(resolved.eventType(), resolved.eventCode());
                }
                if (event instanceof BoundaryEvent boundaryEvent)
                {
                    validateBusinessBoundaryEvent(boundaryEvent, resolved, attachedMatches);
                }
            }
            if (businessEventDefinitions > 1)
            {
                throw invalidBpmn("同一 BPMN 事件只能配置一个错误或升级定义", null);
            }
        }
    }

    /**
     * 在 Flowable 转换前拒绝作者 XML 明确写出的非中断 Error 边界。
     * @param bpmnXml String，已完成 UTF-8 解码的作者 BPMN XML
     * @return void，发现 Error 边界 cancelActivity=false 时抛出稳定 400
     */
    private void validateRawBusinessBoundarySemantics(String bpmnXml)
    {
        if (NON_INTERRUPTING_ERROR_BOUNDARY_PATTERN.matcher(bpmnXml).find())
        {
            throw invalidBpmn("错误边界必须使用中断语义", null);
        }
    }

    /**
     * 校验单个错误或升级边界只能精确附着一个活动并通过唯一出边进入处理路径。
     * @param boundaryEvent BoundaryEvent，待校验边界事件
     * @param resolved ResolvedEvent，已解析事件类型和编码
     * @param attachedMatches Set&lt;String&gt;，同一活动已登记的类型与编码
     * @return void，重复匹配、非法附着或 Error 非中断时拒绝模型
     */
    private void validateBusinessBoundaryEvent(BoundaryEvent boundaryEvent,
            WorkflowBpmnEventModelSupport.ResolvedEvent resolved,
            Set<String> attachedMatches)
    {
        String attachedTo = hasText(boundaryEvent.getAttachedToRefId())
                ? boundaryEvent.getAttachedToRefId().trim()
                : boundaryEvent.getAttachedToRef() == null
                    ? "" : trimToEmpty(boundaryEvent.getAttachedToRef().getId());
        if (!hasText(boundaryEvent.getId()) || !hasText(attachedTo)
                || boundaryEvent.getAttachedToRef() == null)
        {
            throw invalidBpmn("错误或升级边界必须附着到一个真实活动", null);
        }
        if (boundaryEvent.getIncomingFlows() != null && !boundaryEvent.getIncomingFlows().isEmpty())
        {
            throw invalidBpmn("错误或升级边界不允许存在入边", null);
        }
        if (boundaryEvent.getOutgoingFlows() == null || boundaryEvent.getOutgoingFlows().size() != 1)
        {
            throw invalidBpmn("错误或升级边界必须通过唯一出边进入处理路径", null);
        }
        String cancelActivityAttribute = boundaryEvent.getAttributeValue("", "cancelActivity");
        if ("ERROR".equals(resolved.eventType())
                && "false".equalsIgnoreCase(cancelActivityAttribute))
        {
            // BPMN Error 只能中断当前活动；非中断业务提醒必须使用 Escalation。
            throw invalidBpmn("错误边界必须使用中断语义", null);
        }
        if ("ERROR".equals(resolved.eventType()))
        {
            // Flowable 8 解析 Error 边界时会把 cancelActivity 归一为 false，但引擎语义仍然是中断；
            // 这里显式恢复领域语义，供部署校验和运行审计使用，避免把内部默认值误判为非中断。
            boundaryEvent.setCancelActivity(true);
        }
        String matchKey = attachedTo + '\u0000' + resolved.eventType()
                + '\u0000' + resolved.eventCode();
        if (!attachedMatches.add(matchKey))
        {
            throw invalidBpmn("同一活动不允许配置重复的错误或升级捕获编码", null);
        }
    }

    /**
     * 校验单个递归流程元素中的实现和表达式。
     *
     * @param process Process，元素所属的可执行流程，用于核验动态多实例初始化拓扑
     * @param element FlowElement，待校验流程元素
     * @param validationContext ValidationContext，作者资源或部署编译资源的字段契约
     * @return 无返回值
     */
    private void validateFlowElement(Process process, FlowElement element,
            ValidationContext validationContext)
    {
        if (WorkflowControlledLoopBpmnContract.hasReservedProperties(element)
                && !(element instanceof UserTask))
        {
            throw invalidBpmn("受控循环只能配置在用户任务上", null);
        }
        if (element instanceof ServiceTask serviceTask)
        {
            validateServiceTask(serviceTask, validationContext);
        }
        if (element instanceof SendTask sendTask)
        {
            validateSendTask(sendTask, validationContext);
        }
        if (element instanceof SequenceFlow sequenceFlow)
        {
            validateExpression(sequenceFlow.getConditionExpression());
            validateExpression(sequenceFlow.getSkipExpression());
        }
        if (element instanceof UserTask userTask)
        {
            validateExpression(userTask.getAssignee());
            validateExpression(userTask.getOwner());
            validateExpression(userTask.getPriority());
            validateExpression(userTask.getDueDate());
            validateExpression(userTask.getCategory());
            validateExpression(userTask.getSkipExpression());
            validateExpressions(userTask.getCandidateUsers());
            validateExpressions(userTask.getCandidateGroups());
            validateControlledLoop(process, userTask, validationContext);
        }
        if (element instanceof CallActivity callActivity)
        {
            validateExpression(callActivity.getCalledElement());
            validateExpression(callActivity.getBusinessKey());
            validateExpression(callActivity.getProcessInstanceName());
        }
        if (element instanceof Activity activity)
        {
            MultiInstanceLoopCharacteristics loop = activity.getLoopCharacteristics();
            if (loop != null)
            {
                validateMultiInstance(process, activity, loop);
            }
        }
    }

    /**
     * 校验用户任务受控循环作者属性的完整性，并禁止编译执行资源继续携带可编辑配置。
     *
     * @param process Process，用户任务所属可执行流程
     * @param task UserTask，待校验用户任务
     * @param validationContext ValidationContext，作者资源或编译执行资源阶段
     * @return void，无返回值；配置残缺、位置越权或编译资源残留作者字段时抛出 400
     */
    private void validateControlledLoop(Process process, UserTask task,
            ValidationContext validationContext)
    {
        if (!WorkflowControlledLoopBpmnContract.hasReservedProperties(task))
        {
            return;
        }
        if (validationContext == ValidationContext.COMPILED_DEPLOYMENT)
        {
            throw invalidBpmn("已编译执行资源不允许保留受控循环作者配置", null);
        }
        try
        {
            WorkflowControlledLoopBpmnContract.readAuthorConfig(process.getId(), task);
        }
        catch (IllegalArgumentException exception)
        {
            throw invalidBpmn(exception.getMessage(), exception);
        }
    }

    /**
     * 校验作者 SendTask 只能携带受控扩展字段，禁止 WebService、任意实现类型和操作引用。
     * @param task SendTask，待校验发送任务
     * @param validationContext ValidationContext，作者资源或部署编译资源字段契约
     * @return void，发现不受控实现或部署副本仍含 SendTask 时抛出 HTTP 400
     */
    private void validateSendTask(SendTask task, ValidationContext validationContext)
    {
        if (validationContext == ValidationContext.COMPILED_DEPLOYMENT)
        {
            throw invalidBpmn("已编译执行资源不允许保留发送任务", null);
        }
        if (hasText(task.getType()) || hasText(task.getImplementationType())
                || hasText(task.getOperationRef()))
        {
            // 标准 WebService SendTask 和引擎私有实现均不可绕过扩展注册表直接执行。
            throw invalidBpmn("发送任务必须从受控扩展注册表选择", null);
        }
        validateExtensionFields(task.getFieldExtensions());
    }

    /**
     * 校验多实例集合来源；固定 handler 形态必须同时满足并行 UserTask、固定办理人和
     * ALL/ANY 完成条件，避免只替换一个表达式就绕过动态调整的快照与并发契约。
     *
     * @param process Process，多实例活动所属的可执行流程
     * @param activity Activity，持有多实例循环配置的流程活动
     * @param loop MultiInstanceLoopCharacteristics，待校验的多实例配置
     * @return 无返回值
     */
    private void validateMultiInstance(Process process, Activity activity,
            MultiInstanceLoopCharacteristics loop)
    {
        boolean usesControlledHandler =
                WorkflowMultiInstanceModelContract.usesControlledHandler(loop);

        if (!usesControlledHandler)
        {
            // 静态集合继续保持兼容，但仍走普通表达式白名单并拒绝可实例化的 collectionHandler。
            validateExpression(loop.getInputDataItem());
            validateExpression(loop.getCollectionString());
            validateExpression(loop.getLoopCardinality());
            validateExpression(loop.getCompletionCondition());
            if (loop.getHandler() != null)
            {
                throw invalidBpmn("多实例集合处理器未列入安全白名单", null);
            }
            return;
        }

        try
        {
            // 保存门禁与运行时调整服务复用同一结构契约，不能各自维护近似白名单。
            WorkflowMultiInstanceModelContract.requireMode(activity);
        }
        catch (IllegalArgumentException exception)
        {
            throw invalidBpmn("动态多实例配置不符合受控会签或或签契约", null);
        }
        if (WorkflowMultiInstanceModelContract.usesDynamicHandler(loop))
        {
            // 仅动态来源需要前驱任务在完成事务中写入 nextUserIds；固定成员已固化在 BPMN，
            // 允许开始节点、网关或任意合法同步路径直接进入，不能错误复用动态初始化拓扑门禁。
            validateControlledMultiInstanceTopology(process, (UserTask) activity);
        }
    }

    /**
     * 核验受控动态多实例只能由一个同步普通用户任务通过唯一无条件顺序流初始化。
     *
     * <p>运行时只有前序普通用户任务的 {@code nextUserIds} 链路会在完成命令前写入
     * {@code wfMiUsers_<activityId>}。因此开始节点、网关、服务任务、多实例任务、异步任务、
     * 可跳过任务或分支流都不能作为初始化来源，否则模型虽能部署却会在真实进入节点时失败。</p>
     *
     * @param process Process，动态多实例所属的主流程
     * @param dynamicTask UserTask，已经通过固定 handler 结构契约的动态多实例任务
     * @return 无返回值；初始化来源或可重入路径不安全时抛出 HTTP 400
     */
    private void validateControlledMultiInstanceTopology(Process process, UserTask dynamicTask)
    {
        List<SequenceFlow> incomingFlows = dynamicTask.getIncomingFlows();
        if (incomingFlows == null || incomingFlows.size() != 1)
        {
            throw invalidBpmn("动态多实例初始化拓扑不合法：必须存在唯一普通用户任务前驱", null);
        }

        SequenceFlow initializerFlow = incomingFlows.get(0);
        FlowElement source = resolveSequenceEndpoint(process, initializerFlow, true);
        FlowElement target = resolveSequenceEndpoint(process, initializerFlow, false);
        if (!(source instanceof UserTask initializerTask)
                || target != dynamicTask
                || initializerTask.getParentContainer() != process
                || initializerTask.getLoopCharacteristics() != null
                || initializerTask.isForCompensation()
                || hasText(initializerTask.getSkipExpression())
                || hasAsyncContinuation(initializerTask)
                || (initializerTask.getBoundaryEvents() != null
                    && !initializerTask.getBoundaryEvents().isEmpty()))
        {
            throw invalidBpmn("动态多实例初始化拓扑不合法：前驱必须是同步普通用户任务", null);
        }

        // initializerIncomingFlows 表示进入“选择 nextUserIds”任务的全部令牌入口；唯一入边阻断并行分支创建多个来源任务。
        List<SequenceFlow> initializerIncomingFlows = initializerTask.getIncomingFlows();
        if (initializerIncomingFlows == null || initializerIncomingFlows.size() != 1)
        {
            throw invalidBpmn("动态多实例初始化拓扑不合法：前驱必须且只能存在一条入边", null);
        }

        List<SequenceFlow> initializerOutgoingFlows = initializerTask.getOutgoingFlows();
        if (initializerOutgoingFlows == null || initializerOutgoingFlows.size() != 1
                || !sameSequenceFlow(initializerFlow, initializerOutgoingFlows.get(0))
                || hasText(initializerFlow.getConditionExpression())
                || hasText(initializerFlow.getSkipExpression()))
        {
            throw invalidBpmn("动态多实例初始化拓扑不合法：前驱只能通过唯一无条件顺序流直连", null);
        }

        assertDynamicTaskCannotReenter(process, dynamicTask);
    }

    /**
     * 判断普通用户任务是否声明会延迟离开、并发离开或改变排他语义的异步配置。
     *
     * @param task UserTask，动态多实例初始化前驱任务
     * @return boolean，任一异步或非排他标识存在时返回 true
     */
    private boolean hasAsyncContinuation(UserTask task)
    {
        return task.isAsynchronous()
                || task.isAsynchronousLeave()
                || task.isNotExclusive()
                || task.isAsynchronousLeaveNotExclusive();
    }

    /**
     * 从动态多实例的所有后继路径做有界图遍历，拒绝任何能够再次到达同一活动 ID 的回路。
     *
     * <p>成员快照和 revision 当前按活动 ID 存在流程实例作用域，同一节点重复进入会把上一轮
     * 正式状态错误复用到下一轮。遍历同时把可达活动的边界事件作为潜在分支，避免计时器或
     * 错误边界路径绕过普通顺序流检查。</p>
     *
     * @param process Process，动态多实例所属的主流程
     * @param dynamicTask UserTask，禁止重复进入的动态多实例任务
     * @return 无返回值；任一可执行路径可回到该任务时抛出 HTTP 400
     */
    private void assertDynamicTaskCannotReenter(Process process, UserTask dynamicTask)
    {
        ArrayDeque<FlowNode> pending = new ArrayDeque<>();
        enqueueOutgoingTargets(process, dynamicTask, pending);
        Set<FlowNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!pending.isEmpty())
        {
            FlowNode current = pending.removeFirst();
            if (current == dynamicTask || dynamicTask.getId().equals(current.getId()))
            {
                throw invalidBpmn("动态多实例节点不允许通过流程回路重复进入", null);
            }
            if (!visited.add(current))
            {
                continue;
            }

            enqueueOutgoingTargets(process, current, pending);
            if (current instanceof Activity activity && activity.getBoundaryEvents() != null)
            {
                for (BoundaryEvent boundaryEvent : activity.getBoundaryEvents())
                {
                    if (boundaryEvent == null)
                    {
                        throw invalidBpmn("动态多实例后继执行路径结构不合法", null);
                    }
                    pending.addLast(boundaryEvent);
                }
            }
        }
    }

    /**
     * 解析一个可达流程节点的全部顺序流目标并加入待遍历队列。
     *
     * @param process Process，顺序流端点必须所属的主流程
     * @param source FlowNode，当前可达流程节点
     * @param pending ArrayDeque&lt;FlowNode&gt;，后续待检查节点队列
     * @return 无返回值；损坏或跨作用域端点会阻止模型保存
     */
    private void enqueueOutgoingTargets(Process process, FlowNode source,
            ArrayDeque<FlowNode> pending)
    {
        List<SequenceFlow> outgoingFlows = source.getOutgoingFlows();
        if (outgoingFlows == null)
        {
            return;
        }
        for (SequenceFlow outgoingFlow : outgoingFlows)
        {
            FlowElement target = resolveSequenceEndpoint(process, outgoingFlow, false);
            if (!(target instanceof FlowNode targetNode))
            {
                throw invalidBpmn("动态多实例后继执行路径结构不合法", null);
            }
            pending.addLast(targetNode);
        }
    }

    /**
     * 从 Flowable 模型对象和引用字段解析顺序流端点，并核验端点仍属于同一主流程。
     *
     * @param process Process，端点必须所属的主流程
     * @param sequenceFlow SequenceFlow，待解析的顺序流
     * @param source boolean，true 解析来源端点，false 解析目标端点
     * @return FlowElement，引用完整且属于主流程的真实端点
     */
    private FlowElement resolveSequenceEndpoint(Process process, SequenceFlow sequenceFlow,
            boolean source)
    {
        if (sequenceFlow == null)
        {
            throw invalidBpmn("动态多实例执行路径结构不合法", null);
        }
        FlowElement endpoint = source ? sequenceFlow.getSourceFlowElement()
                : sequenceFlow.getTargetFlowElement();
        String endpointRef = source ? sequenceFlow.getSourceRef() : sequenceFlow.getTargetRef();
        if (endpoint == null && hasText(endpointRef))
        {
            endpoint = process.getFlowElement(endpointRef, true);
        }
        if (endpoint == null || endpoint.getParentContainer() != process
                || !hasText(endpoint.getId())
                || (hasText(endpointRef) && !endpointRef.equals(endpoint.getId())))
        {
            throw invalidBpmn("动态多实例执行路径端点不合法", null);
        }
        return endpoint;
    }

    /**
     * 比较解析器可能复用或重新构造的两个顺序流是否代表同一部署边。
     *
     * @param left SequenceFlow，动态多实例唯一入边
     * @param right SequenceFlow，前驱用户任务唯一出边
     * @return boolean，对象相同或非空 ID 完全一致时返回 true
     */
    private boolean sameSequenceFlow(SequenceFlow left, SequenceFlow right)
    {
        return left == right || (left != null && right != null && hasText(left.getId())
                && left.getId().equals(right.getId()));
    }

    /**
     * 校验服务任务只能使用固定扩展调度器，并按资源阶段执行互斥字段契约。
     *
     * @param task ServiceTask，待校验服务任务
     * @param validationContext ValidationContext，作者资源要求受控字段，编译资源要求字段已剥离
     * @return 无返回值
     */
    private void validateServiceTask(ServiceTask task, ValidationContext validationContext)
    {
        if (hasText(task.getType()))
        {
            throw invalidBpmn("服务任务类型未列入安全白名单", null);
        }
        if (validationContext == ValidationContext.COMPILED_DEPLOYMENT)
        {
            // 部署快照已经固化精确参数，执行 XML 不得再次携带可篡改的作者字段。
            if (task.getFieldExtensions() != null && !task.getFieldExtensions().isEmpty())
            {
                throw invalidBpmn("已编译服务任务不允许保留作者扩展字段", null);
            }
            String implementation = trimToEmpty(task.getImplementation());
            boolean fixedExtensionDelegate = ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION
                    .equals(task.getImplementationType())
                    && WorkflowExtensionBpmnContract.DELEGATE_EXPRESSION.equals(implementation);
            boolean fixedSlaDelegate = ImplementationType.IMPLEMENTATION_TYPE_EXPRESSION
                    .equals(task.getImplementationType())
                    && SLA_TIMER_DELEGATE_EXPRESSION.matcher(implementation).matches();
            // 编译资源只允许平台扩展固定入口或 SLA 编译器生成的固定定时入口；作者任意服务任务已在前置阶段拒绝。
            if (!fixedExtensionDelegate && !fixedSlaDelegate)
            {
                throw invalidBpmn("服务任务必须从受控扩展注册表选择", null);
            }
            validateExpression(task.getSkipExpression());
            return;
        }
        if (!ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION.equals(
                task.getImplementationType())
                || !WorkflowExtensionBpmnContract.DELEGATE_EXPRESSION.equals(
                        trimToEmpty(task.getImplementation())))
        {
            // 作者 BPMN 不再接受类名或任意 Bean；真实实现只能由部署快照解析服务端注册表。
            throw invalidBpmn("服务任务必须从受控扩展注册表选择", null);
        }
        validateExpression(task.getSkipExpression());
        List<FieldExtension> fields = task.getFieldExtensions();
        validateExtensionFields(fields);
    }

    /**
     * 校验 ServiceTask 作者配置只包含唯一扩展键和可选配置 JSON 字符串。
     * @param fields List&lt;FieldExtension&gt;，服务任务作者字段集合
     * @return 无返回值；字段注入、表达式、重复或缺失扩展键时拒绝
     */
    private void validateExtensionFields(List<FieldExtension> fields)
    {
        boolean extensionKeySeen = false;
        boolean extensionConfigSeen = false;
        if (fields != null)
        {
            for (FieldExtension field : fields)
            {
                if (hasText(field.getExpression()))
                {
                    throw invalidBpmn("服务任务扩展配置不允许表达式", null);
                }
                if (WorkflowExtensionBpmnContract.EXTENSION_KEY_FIELD.equals(field.getFieldName()))
                {
                    if (extensionKeySeen || !hasText(field.getStringValue()))
                    {
                        throw invalidBpmn("服务任务扩展标识缺失或重复", null);
                    }
                    extensionKeySeen = true;
                }
                else if (WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD.equals(field.getFieldName()))
                {
                    if (extensionConfigSeen || !hasText(field.getStringValue()))
                    {
                        throw invalidBpmn("服务任务扩展配置缺失或重复", null);
                    }
                    extensionConfigSeen = true;
                }
                else
                {
                    throw invalidBpmn("服务任务包含未注册的字段注入", null);
                }
            }
        }
        if (!extensionKeySeen)
        {
            throw invalidBpmn("服务任务必须选择受控扩展", null);
        }
    }

    /**
     * 校验执行监听器和任务监听器的实现及字段表达式。
     *
     * @param listeners List&lt;FlowableListener&gt;，监听器集合，允许为空
     * @return 无返回值
     */
    private void validateListeners(List<FlowableListener> listeners,
            ValidationContext validationContext)
    {
        if (listeners == null)
        {
            return;
        }
        Set<String> businessEvents = new HashSet<>();
        for (FlowableListener listener : listeners)
        {
            if (!businessEvents.add(trimToEmpty(listener.getEvent())))
            {
                throw invalidBpmn("同一元素的执行业务监听器事件不能重复", null);
            }
            validateBusinessListener(listener, validationContext, "执行");
        }
    }

    /**
     * 校验用户任务监听器只能使用固定兼容 Bean 和批准事件，并拒绝字段注入、脚本、
     * 事务回调及自定义属性解析器，保证模型无法借监听器执行任意代码。
     *
     * @param listeners List&lt;FlowableListener&gt;，用户任务监听器集合，必须完整包含三个固定事件
     * @return 无返回值；监听器缺失、重复或实现漂移时拒绝模型
     */
    private void validateTaskListeners(List<FlowableListener> listeners,
            ValidationContext validationContext)
    {
        if (listeners == null)
        {
            throw invalidBpmn("用户任务必须配置固定身份审计任务监听器", null);
        }
        Set<String> seenEvents = new HashSet<>();
        Set<String> businessEvents = new HashSet<>();
        for (FlowableListener listener : listeners)
        {
            String event = trimToEmpty(listener.getEvent());
            String implementation = trimToEmpty(listener.getImplementation());
            if (!USER_TASK_LISTENER_EXPRESSION.equals(implementation))
            {
                if (!businessEvents.add(event))
                {
                    throw invalidBpmn("同一用户任务的业务监听器事件不能重复", null);
                }
                validateBusinessListener(listener, validationContext, "任务");
                continue;
            }
            boolean hasFields = listener.getFieldExtensions() != null
                    && !listener.getFieldExtensions().isEmpty();
            boolean hasCustomResolver = hasText(listener.getCustomPropertiesResolverImplementationType())
                    || hasText(listener.getCustomPropertiesResolverImplementation());
            if (!USER_TASK_LISTENER_EVENTS.contains(event)
                    || !seenEvents.add(event)
                    || !ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION.equals(
                            listener.getImplementationType())
                    || !USER_TASK_LISTENER_EXPRESSION.equals(implementation)
                    || hasFields
                    || listener.getScriptInfo() != null
                    || hasCustomResolver
                    || hasText(listener.getOnTransaction())
                    || listener.getInstance() != null)
            {
                throw invalidBpmn("任务监听器未列入安全白名单", null);
            }
        }
        if (!seenEvents.equals(USER_TASK_LISTENER_EVENTS))
        {
            throw invalidBpmn("用户任务必须配置固定身份审计任务监听器", null);
        }
    }

    /**
     * 校验单个受控业务监听器，区分作者字段形态和编译后固定 Bean 形态。
     * @param listener FlowableListener，待校验的执行或任务监听器
     * @param validationContext ValidationContext，作者或编译执行资源阶段
     * @param listenerLabel String，异常提示中的监听器种类
     * @return 无返回值，发现任意实现、脚本、字段或生命周期回调越权时抛出
     */
    private void validateBusinessListener(FlowableListener listener,
            ValidationContext validationContext, String listenerLabel)
    {
        boolean fixedDelegate = ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION.equals(
                listener.getImplementationType())
                && WorkflowExtensionBpmnContract.BUSINESS_LISTENER_DELEGATE_EXPRESSION.equals(
                        trimToEmpty(listener.getImplementation()));
        if (!fixedDelegate)
        {
            throw invalidBpmn(listenerLabel + "监听器必须从受控业务扩展注册表选择", null);
        }
        if (listener.getScriptInfo() != null
                || hasText(listener.getCustomPropertiesResolverImplementation())
                || hasText(listener.getOnTransaction())
                || listener.getInstance() != null)
        {
            throw invalidBpmn(listenerLabel + "监听器不允许脚本、事务回调或实例注入", null);
        }
        if (validationContext == ValidationContext.COMPILED_DEPLOYMENT)
        {
            if (listener.getFieldExtensions() != null && !listener.getFieldExtensions().isEmpty())
            {
                throw invalidBpmn("已编译业务监听器不允许保留作者扩展字段", null);
            }
            return;
        }
        validateListenerExtensionFields(listener.getFieldExtensions(), listenerLabel);
    }

    /**
     * 校验业务监听器只能携带唯一稳定扩展键和可选 JSON 配置字段。
     * @param fields List&lt;FieldExtension&gt;，业务监听器字段集合
     * @param listenerLabel String，异常提示中的监听器种类
     * @return 无返回值，字段表达式、重复字段和未知字段均被拒绝
     */
    private void validateListenerExtensionFields(List<FieldExtension> fields, String listenerLabel)
    {
        boolean keySeen = false;
        boolean configSeen = false;
        if (fields != null)
        {
            for (FieldExtension field : fields)
            {
                if (hasText(field.getExpression()))
                {
                    throw invalidBpmn(listenerLabel + "监听器字段不允许表达式", null);
                }
                if (WorkflowExtensionBpmnContract.EXTENSION_KEY_FIELD.equals(field.getFieldName()))
                {
                    if (keySeen || !hasText(field.getStringValue()))
                    {
                        throw invalidBpmn(listenerLabel + "监听器扩展标识缺失或重复", null);
                    }
                    keySeen = true;
                }
                else if (WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD.equals(field.getFieldName()))
                {
                    if (configSeen || !hasText(field.getStringValue()))
                    {
                        throw invalidBpmn(listenerLabel + "监听器扩展配置缺失或重复", null);
                    }
                    configSeen = true;
                }
                else
                {
                    throw invalidBpmn(listenerLabel + "监听器包含未注册字段", null);
                }
            }
        }
        if (!keySeen)
        {
            throw invalidBpmn(listenerLabel + "监听器必须选择受控业务扩展", null);
        }
    }

    /**
     * 校验实现对象中的字段表达式。
     *
     * @param fields List&lt;FieldExtension&gt;，服务任务或监听器字段集合，允许为空
     * @return 无返回值
     */
    private void validateFieldExtensions(List<FieldExtension> fields)
    {
        if (fields == null)
        {
            return;
        }
        for (FieldExtension field : fields)
        {
            validateExpression(field.getExpression());
        }
    }

    /**
     * 校验字符串集合中可能存在的表达式。
     *
     * @param values List&lt;String&gt;，用户或候选组表达式集合，允许为空
     * @return 无返回值
     */
    private void validateExpressions(List<String> values)
    {
        if (values == null)
        {
            return;
        }
        for (String value : values)
        {
            validateExpression(value);
        }
    }

    /**
     * 校验单个可选表达式，不含表达式标记的普通常量直接放行。
     *
     * @param value String，可能包含 ${...} 或 #{...} 的配置值
     * @return 无返回值
     */
    private void validateExpression(String value)
    {
        if (!hasText(value) || (!value.contains("${") && !value.contains("#{")))
        {
            return;
        }
        if (!SAFE_EXPRESSION_PATTERN.matcher(value.trim()).matches())
        {
            throw invalidBpmn("流程表达式包含不允许的语法", null);
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (DANGEROUS_EXPRESSION_TOKENS.stream().anyMatch(normalized::contains))
        {
            throw invalidBpmn("流程表达式包含危险访问", null);
        }
    }

    /**
     * 从原始 XML 扫描全部表达式，防止转换器未映射的扩展字段绕过模型级校验。
     *
     * @param bpmnXml String，严格 UTF-8 解码后的原始 BPMN XML
     * @param controlledCollectionCount int，模型级完整校验通过的受控集合表达式数量
     * @param validationContext ValidationContext，作者资源或编译执行资源阶段
     * @return 无返回值
     */
    private void validateRawExpressions(String bpmnXml, int controlledCollectionCount,
            ValidationContext validationContext)
    {
        int remainingControlledCollections = controlledCollectionCount;
        int cursor = 0;
        while (cursor < bpmnXml.length() - 1)
        {
            int dollar = bpmnXml.indexOf("${", cursor);
            int hash = bpmnXml.indexOf("#{", cursor);
            int start;
            if (dollar < 0)
            {
                start = hash;
            }
            else if (hash < 0)
            {
                start = dollar;
            }
            else
            {
                start = Math.min(dollar, hash);
            }
            if (start < 0)
            {
                break;
            }
            int end = bpmnXml.indexOf('}', start + 2);
            if (end < 0 || end - start > 4096)
            {
                throw invalidBpmn("流程表达式格式不合法", null);
            }
            String expression = bpmnXml.substring(start, end + 1);
            if (expression.indexOf('{', 2) >= 0)
            {
                throw invalidBpmn("流程表达式不允许嵌套", null);
            }
            if (isControlledMultiInstanceCollectionExpression(expression))
            {
                if (remainingControlledCollections == 0)
                {
                    throw invalidBpmn("受控多实例处理器只能用于受控集合字段", null);
                }
                remainingControlledCollections--;
            }
            else if (!(validationContext == ValidationContext.COMPILED_DEPLOYMENT
                    && (SLA_TIMER_DELEGATE_EXPRESSION.matcher(expression).matches()
                    || WorkflowExtensionBpmnContract.DELEGATE_EXPRESSION.equals(expression))))
            {
                // 编译资源仅放行平台固定扩展入口与 SLA 固定定时入口；作者 XML 和其他表达式继续走通用安全语法。
                validateExpression(expression);
            }
            cursor = end + 1;
        }
        if (remainingControlledCollections != 0)
        {
            throw invalidBpmn("受控多实例集合表达式与模型不一致", null);
        }
    }

    /**
     * 统计已经通过完整结构契约的动态多实例集合，供原始 XML 表达式执行一一对应校验。
     *
     * @param bpmnModel org.flowable.bpmn.model.BpmnModel，已完成模型级业务校验的流程模型
     * @return int，受控 handler 合法集合位置的数量
     */
    private int countControlledMultiInstanceCollections(
            org.flowable.bpmn.model.BpmnModel bpmnModel)
    {
        int count = 0;
        for (org.flowable.bpmn.model.Process process : bpmnModel.getProcesses())
        {
            for (org.flowable.bpmn.model.Activity activity :
                    process.findFlowElementsOfType(
                            org.flowable.bpmn.model.Activity.class, true))
            {
                MultiInstanceLoopCharacteristics loop = activity.getLoopCharacteristics();
                if (loop != null && WorkflowMultiInstanceModelContract.usesControlledHandler(loop))
                {
                    // validateModel 已对相同 activity 调用 requireMode；此处只统计，不重复放宽契约。
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 判断原始 XML 表达式是否为模型层已经受控识别的多实例集合表达式。
     *
     * @param expression String，原始 XML 扫描得到的完整 EL 表达式。
     * @return boolean，办理时、发起时或严格格式固定集合表达式时返回 true。
     */
    private boolean isControlledMultiInstanceCollectionExpression(String expression)
    {
        // BPMN 序列化会把固定成员表达式参数中的单引号转义为 &#39; 或 &apos;；模型层读取时已还原，
        // 原始 XML 扫描必须使用同一文本才能完成一一对应，不能把合法设计器输出误判为任意 EL。
        String normalizedExpression = expression.replace("&#39;", "'")
                .replace("&apos;", "'");
        if (WorkflowMultiInstanceModelContract.COLLECTION_EXPRESSION.equals(normalizedExpression)
                || WorkflowMultiInstanceModelContract.START_COLLECTION_EXPRESSION.equals(
                        normalizedExpression))
        {
            return true;
        }
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setInputDataItem(normalizedExpression);
        return WorkflowMultiInstanceModelContract.usesFixedHandler(loop);
    }

    /**
     * 解析并添加严格格式的节点表单引用，同时拒绝重复快照主键。
     *
     * @param processKey String，表单节点所属可执行流程标识
     * @param formKey String，BPMN 表单键
     * @param nodeKey String，BPMN 节点主键
     * @param nodeName String，BPMN 节点名称，允许为空
     * @param references List&lt;WorkflowBpmnFormReference&gt;，待写入引用集合
     * @param uniqueReferences Set&lt;String&gt;，部署快照业务唯一键集合
     * @return 无返回值
     */
    private void addFormReference(String processKey, String formKey, String nodeKey, String nodeName,
            List<WorkflowBpmnFormReference> references, Set<String> uniqueReferences)
    {
        Matcher matcher = FORM_KEY_PATTERN.matcher(formKey.trim());
        if (!matcher.matches())
        {
            throw invalidBpmn("流程表单键必须使用 key_正整数格式", null);
        }
        if (!hasText(nodeKey))
        {
            throw invalidBpmn("表单节点主键不能为空", null);
        }
        long formId;
        try
        {
            formId = Long.parseLong(matcher.group(1));
        }
        catch (NumberFormatException exception)
        {
            throw invalidBpmn("流程表单主键超出有效范围", exception);
        }
        String normalizedProcessKey = requireElementId(processKey, "可执行流程标识不能为空");
        String uniqueKey = normalizedProcessKey + '\u0000' + formKey.trim()
                + '\u0000' + nodeKey.trim();
        if (!uniqueReferences.add(uniqueKey))
        {
            throw invalidBpmn("流程包含重复的节点表单引用", null);
        }
        references.add(new WorkflowBpmnFormReference(WorkflowFormSourceType.TEMPLATE,
                formId, formKey.trim(), nodeKey.trim(), nodeName == null ? "" : nodeName,
                null, normalizedProcessKey));
    }

    /**
     * 按节点配置选择正式模板或 BPMN 内嵌表单，并拒绝双重来源歧义。
     *
     * @param processKey String，表单节点所属可执行流程标识
     * @param formKey String，正式 wf_form 引用键；未配置时为空
     * @param properties List&lt;FormProperty&gt;，Flowable FormData 字段；未配置时为空
     * @param nodeKey String，BPMN 节点主键
     * @param nodeName String，BPMN 节点名称
     * @param references List&lt;WorkflowBpmnFormReference&gt;，待写入的已校验引用
     * @param uniqueReferences Set&lt;String&gt;，部署快照业务唯一键集合
     * @return 无返回值，配置歧义或内容非法时抛出稳定 400
     */
    private void addNodeFormReference(String processKey, String formKey, List<FormProperty> properties,
            String nodeKey, String nodeName, List<WorkflowBpmnFormReference> references,
            Set<String> uniqueReferences)
    {
        boolean hasTemplate = hasText(formKey);
        boolean hasEmbedded = hasFormProperties(properties);
        if (hasTemplate && hasEmbedded)
        {
            throw invalidBpmn("同一节点不能同时配置正式表单和 BPMN 内嵌表单", null);
        }
        if (hasTemplate)
        {
            addFormReference(processKey, formKey, nodeKey, nodeName, references, uniqueReferences);
            return;
        }
        if (!hasText(nodeKey))
        {
            throw invalidBpmn("表单节点主键不能为空", null);
        }
        String normalizedNodeKey = nodeKey.trim();
        String formKeyForSnapshot = WorkflowFormSourceType.EMBEDDED_FORM_KEY;
        String normalizedProcessKey = requireElementId(processKey, "可执行流程标识不能为空");
        String uniqueKey = normalizedProcessKey + '\u0000' + formKeyForSnapshot
                + '\u0000' + normalizedNodeKey;
        if (!uniqueReferences.add(uniqueKey))
        {
            throw invalidBpmn("流程包含重复的节点表单引用", null);
        }
        String embeddedContent = formFieldExtensionService == null
                ? WorkflowEmbeddedFormConverter.convert(properties)
                : WorkflowEmbeddedFormConverter.convert(properties,
                        formFieldExtensionService::resolveForAuthor);
        references.add(new WorkflowBpmnFormReference(WorkflowFormSourceType.EMBEDDED,
                null, formKeyForSnapshot, normalizedNodeKey,
                nodeName == null ? "" : nodeName, embeddedContent, normalizedProcessKey));
    }

    /**
     * 判断 Flowable 节点是否声明了内嵌 FormData 字段。
     *
     * @param properties List&lt;FormProperty&gt;，节点解析出的表单字段
     * @return boolean，至少包含一个字段时返回 true
     */
    private boolean hasFormProperties(List<FormProperty> properties)
    {
        return properties != null && !properties.isEmpty();
    }

    /**
     * 调用 Flowable 官方验证器并拒绝全部非 warning 错误。
     *
     * @param bpmnModel org.flowable.bpmn.model.BpmnModel，已通过模块安全规则的模型
     * @return 无返回值
     */
    private void validateWithFlowable(org.flowable.bpmn.model.BpmnModel bpmnModel)
    {
        List<ValidationError> errors = repositoryService.validateProcess(bpmnModel);
        if (errors != null && errors.stream().anyMatch(error -> !error.isWarning()))
        {
            throw invalidBpmn("BPMN 流程规则校验失败", null);
        }
    }

    /**
     * 构造不泄露解析器、引擎或 XML 正文的稳定 BPMN 参数异常。
     *
     * @param message String，对外稳定业务提示
     * @param cause Throwable，内部解析或转换异常，允许为空
     * @return ServiceException，HTTP 400 BPMN 参数异常
     */
    private ServiceException invalidBpmn(String message, Throwable cause)
    {
        ServiceException exception = new ServiceException(message, HttpStatus.BAD_REQUEST);
        if (cause != null)
        {
            exception.initCause(cause);
        }
        return exception;
    }

    /**
     * 判断文本是否包含非空白字符。
     *
     * @param value String，待判断文本
     * @return boolean，true 表示文本非空白
     */
    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    /**
     * 规范化流程或节点标识，避免跨流程快照唯一键出现空值歧义。
     * @param value String，待校验的 BPMN 标识
     * @param message String，标识为空时返回的业务提示
     * @return String，去除首尾空白后的非空标识
     */
    private String requireElementId(String value, String message)
    {
        if (!hasText(value))
        {
            throw invalidBpmn(message, null);
        }
        return value.trim();
    }

    /**
     * 将可空文本规范化为去除首尾空白的字符串，供固定 BPMN 契约执行精确比较。
     *
     * @param value String，待规范化文本，允许为空
     * @return String，非空的去空白文本；空值返回空字符串
     */
    private static String trimToEmpty(String value)
    {
        return value == null ? "" : value.trim();
    }
}
