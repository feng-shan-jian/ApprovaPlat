package com.ruoyi.flowable.service.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnValidationIssue;
import com.ruoyi.flowable.domain.WorkflowVariableNames;

/** 在 Flowable 转换器之前校验原始数据对象，避免未知类型被转换器静默丢弃。 */
final class WorkflowDataObjectContract
{
    private static final Set<String> TYPES = Set.of("xsd:string", "xsd:int", "xsd:long", "xsd:double", "xsd:boolean");

    private WorkflowDataObjectContract() { }

    static void validate(XMLStreamReader reader) throws XMLStreamException
    {
        ArrayDeque<String> scopes = new ArrayDeque<>();
        Map<String, Set<String>> names = new HashMap<>();
        Map<String, String> references = new HashMap<>();
        Map<String, String> processIds = new HashMap<>();
        List<WorkflowBpmnValidationIssue> issues = new ArrayList<>();
        String objectId = null;
        String type = null;
        int count = 0;
        while (reader.hasNext())
        {
            if (reader.isStartElement())
            {
                String tag = reader.getLocalName();
                boolean bpmn = "http://www.omg.org/spec/BPMN/20100524/MODEL".equals(reader.getNamespaceURI());
                if (bpmn && Set.of("process", "subProcess", "transaction").contains(tag))
                {
                    scopes.push(String.valueOf(reader.getAttributeValue(null, "id")));
                }
                else if (bpmn && "dataObjectReference".equals(tag))
                {
                    references.putIfAbsent(reader.getAttributeValue(null, "dataObjectRef"), reader.getAttributeValue(null, "id"));
                }
                else if (bpmn && "dataObject".equals(tag))
                {
                    objectId = reader.getAttributeValue(null, "id");
                    if (!scopes.isEmpty()) processIds.put(objectId, scopes.getLast());
                    String name = reader.getAttributeValue(null, "name");
                    type = reader.getAttributeValue(null, "itemSubjectRef");
                    if (type == null || type.isBlank()) type = "xsd:string";
                    if (++count > 500) fail(issues, objectId, "LIMIT", "数据对象数量不能超过 500");
                    if (scopes.isEmpty()) fail(issues, objectId, "SCOPE", "数据对象必须属于流程或子流程");
                    if (name == null || name.isBlank()) fail(issues, objectId, "NAME", "数据对象缺少数据变量名称");
                    else if (!WorkflowVariableNames.isBusinessVariable(name))
                    {
                        fail(issues, objectId, "NAME", "数据变量名不合法或属于系统保留变量");
                    }
                    else if (!names.computeIfAbsent(String.valueOf(scopes.peek()), key -> new HashSet<>()).add(name))
                    {
                        fail(issues, objectId, "DUPLICATE", "同一作用域的数据变量名不能重复");
                    }
                    if (!TYPES.contains(type)) fail(issues, objectId, "TYPE", "数据对象仅支持文本、整数、长整数、小数和布尔值");
                    if ("true".equals(reader.getAttributeValue(null, "isCollection")))
                    {
                        fail(issues, objectId, "TYPE", "数据对象暂不支持集合类型");
                    }
                }
                else if (objectId != null && "value".equals(tag)
                        && "http://flowable.org/bpmn".equals(reader.getNamespaceURI()))
                {
                    String value = reader.getElementText();
                    try
                    {
                        if (value.length() > 4096) throw new IllegalArgumentException();
                        if (!value.isEmpty()) switch (type)
                        {
                            case "xsd:int" -> Integer.parseInt(value);
                            case "xsd:long" -> Long.parseLong(value);
                            case "xsd:double" -> {
                                if (!Double.isFinite(Double.parseDouble(value))) throw new IllegalArgumentException();
                            }
                            case "xsd:boolean" -> {
                                if (!Set.of("true", "false").contains(value)) throw new IllegalArgumentException();
                            }
                            default -> { }
                        }
                    }
                    catch (IllegalArgumentException exception)
                    {
                        fail(issues, objectId, "VALUE", "数据对象默认值与类型不匹配或超过长度限制");
                    }
                }
            }
            else if (reader.isEndElement()
                    && "http://www.omg.org/spec/BPMN/20100524/MODEL".equals(reader.getNamespaceURI()))
            {
                String tag = reader.getLocalName();
                if ("dataObject".equals(tag)) objectId = null;
                if (Set.of("process", "subProcess", "transaction").contains(tag)) scopes.pop();
            }
            reader.next();
        }
        if (!issues.isEmpty()) throw new WorkflowBpmnValidationException(issues.stream()
                .map(issue -> new WorkflowBpmnValidationIssue(issue.code(), issue.severity(),
                        references.getOrDefault(issue.elementId(), issue.elementId()), issue.message(),
                        processIds.get(issue.elementId()))).toList()).asServiceException();
    }

    private static void fail(List<WorkflowBpmnValidationIssue> issues, String id, String code, String message)
    {
        if (issues.size() < 50) issues.add(new WorkflowBpmnValidationIssue("BPMN_DATA_OBJECT_" + code, "ERROR", id, message));
    }
}
