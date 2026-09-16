package com.ruoyi.flowable.service.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.constant.HttpStatus;

/** 保留转换器不写回的标准引用、根定义和表单权限标志，不恢复作者执行配置。 */
final class WorkflowBpmnXmlPreserver
{
    private static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final Set<String> PRESERVED = Set.of("dataObjectReference", "dataStoreReference",
            "dataStore", "property", "error", "escalation", "group", "textAnnotation", "association");
    private WorkflowBpmnXmlPreserver() { }

    static byte[] write(org.flowable.bpmn.model.BpmnModel model, String authorXml)
    {
        byte[] converted = new org.flowable.bpmn.converter.BpmnXMLConverter().convertToXML(model);
        return restore(converted, authorXml.getBytes(java.nio.charset.StandardCharsets.UTF_8), model);
    }

    private static byte[] restore(byte[] converted, byte[] source, org.flowable.bpmn.model.BpmnModel model)
    {
        try
        {
            Document original = parse(source), target = parse(converted);
            boolean changed = restoreCollaborationIdentity(original, target);
            Map<String, Element> targets = index(target);
            var nodes = original.getElementsByTagNameNS(BPMN, "*");
            for (int i = 0; i < nodes.getLength(); i++)
            {
                Element node = (Element) nodes.item(i);
                if (!PRESERVED.contains(node.getLocalName()) || !node.hasAttribute("id")
                        || targets.containsKey(node.getAttribute("id"))) continue;
                Element parent = (Element) node.getParentNode();
                Element targetParent = parent == original.getDocumentElement() ? target.getDocumentElement()
                        : targets.get(parent.getAttribute("id"));
                if (targetParent == null || (!parent.getLocalName().equals(targetParent.getLocalName())
                        && !("serviceTask".equals(targetParent.getLocalName())
                                && Set.of("sendTask", "businessRuleTask", "intermediateThrowEvent", "endEvent").contains(parent.getLocalName()))))
                    throw new IllegalStateException("BPMN preserved element scope missing: " + parent.getLocalName() + "/" + parent.getAttribute("id"));
                Element copy = (Element) target.importNode(node, true);
                // definitions 的 rootElement 必须位于 BPMNDiagram 前，继续满足官方 XSD 顺序。
                if (targetParent == target.getDocumentElement()) targetParent.insertBefore(copy, targetParent.getFirstChild());
                else if (Set.of("dataObjectReference", "dataStoreReference").contains(node.getLocalName()))
                {
                    // 数据引用属于 flowElement，不能追加在 converter 输出的 artifact 之后。
                    org.w3c.dom.Node before = targetParent.getFirstChild();
                    while (before != null && (!(before instanceof Element element)
                            || !Set.of("textAnnotation", "association", "group", "resourceRole", "correlationSubscription", "supports")
                                .contains(element.getLocalName()))) before = before.getNextSibling();
                    targetParent.insertBefore(copy, before);
                }
                else if ("property".equals(node.getLocalName()))
                {
                    // Activity 的 property 必须位于 dataAssociation、loopCharacteristics 等子项前。
                    org.w3c.dom.Node before = targetParent.getFirstChild();
                    while (before != null && (!(before instanceof Element element)
                            || Set.of("documentation", "extensionElements", "incoming", "outgoing", "ioSpecification", "property")
                                .contains(element.getLocalName()))) before = before.getNextSibling();
                    targetParent.insertBefore(copy, before);
                }
                else targetParent.appendChild(copy);
                targets.put(copy.getAttribute("id"), copy);
                changed = true;
            }
            // Flowable 8 不输出 escalation 根定义；包含 SLA 本次生成的新引用。
            for (var escalation : model.getEscalations())
            {
                if (targets.containsKey(escalation.getId())) continue;
                Element root = target.createElementNS(BPMN, "escalation");
                root.setAttribute("id", escalation.getId());
                if (escalation.getName() != null) root.setAttribute("name", escalation.getName());
                root.setAttribute("escalationCode", escalation.getEscalationCode());
                target.getDocumentElement().insertBefore(root, target.getDocumentElement().getFirstChild());
                changed = true;
            }
            // Flowable FormProperty 不写回平台的系统输出权限；按节点和字段双重身份保留。
            var formProperties = original.getElementsByTagNameNS("http://flowable.org/bpmn", "formProperty");
            for (int i = 0; i < formProperties.getLength(); i++)
            {
                Element property = (Element) formProperties.item(i);
                if (!property.hasAttribute(WorkflowSystemOutputContract.ATTRIBUTE)) continue;
                Element owner = (Element) property.getParentNode().getParentNode();
                Element targetOwner = targets.get(owner.getAttribute("id"));
                if (targetOwner == null) throw new IllegalStateException("Form owner lost during compilation");
                var targetProperties = targetOwner.getElementsByTagNameNS("http://flowable.org/bpmn", "formProperty");
                boolean found = false;
                for (int j = 0; j < targetProperties.getLength(); j++)
                {
                    Element field = (Element) targetProperties.item(j);
                    if (!property.getAttribute("id").equals(field.getAttribute("id"))) continue;
                    field.setAttribute(WorkflowSystemOutputContract.ATTRIBUTE,
                            property.getAttribute(WorkflowSystemOutputContract.ATTRIBUTE));
                    found = changed = true;
                    break;
                }
                if (!found) throw new IllegalStateException("Form field lost during compilation");
            }
            if (!changed) return converted;
            return serialize(target);
        }
        catch (Exception exception)
        {
            ServiceException failure = new ServiceException("BPMN 标准引用保留失败", HttpStatus.ERROR)
                    .setSubCode("BPMN_REFERENCE_PRESERVATION_FAILED");
            failure.initCause(exception);
            throw failure;
        }
    }

    private static byte[] serialize(Document document) throws Exception
    {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        factory.newTransformer().transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    /** Flowable 会生成新的 collaboration ID；按已保留的 participant 身份恢复原容器。 */
    private static boolean restoreCollaborationIdentity(Document original, Document target)
    {
        var originals = original.getElementsByTagNameNS(BPMN, "collaboration");
        var converted = target.getElementsByTagNameNS(BPMN, "collaboration");
        boolean changed = false;
        for (int i = 0; i < originals.getLength(); i++)
        {
            Element source = (Element) originals.item(i);
            Element matched = null;
            var participants = source.getElementsByTagNameNS(BPMN, "participant");
            for (int j = 0; j < converted.getLength(); j++)
            {
                Element candidate = (Element) converted.item(j);
                Map<String, Element> children = new HashMap<>();
                var candidateParticipants = candidate.getElementsByTagNameNS(BPMN, "participant");
                for (int p = 0; p < candidateParticipants.getLength(); p++)
                {
                    Element participant = (Element) candidateParticipants.item(p);
                    children.put(participant.getAttribute("id"), participant);
                }
                boolean same = participants.getLength() == children.size();
                for (int p = 0; same && p < participants.getLength(); p++)
                    same = children.containsKey(((Element) participants.item(p)).getAttribute("id"));
                if (same)
                {
                    if (matched != null) throw new IllegalStateException("Ambiguous collaboration identity");
                    matched = candidate;
                }
            }
            if (matched == null) throw new IllegalStateException("Collaboration participants lost during compilation");
            String oldId = matched.getAttribute("id"), id = source.getAttribute("id");
            if (!id.equals(oldId))
            {
                matched.setAttribute("id", id);
                var planes = target.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNPlane");
                for (int p = 0; p < planes.getLength(); p++)
                {
                    Element plane = (Element) planes.item(p);
                    if (!oldId.isEmpty() && oldId.equals(plane.getAttribute("bpmnElement"))) plane.setAttribute("bpmnElement", id);
                }
                changed = true;
            }
        }
        return changed;
    }

    static Document parse(byte[] bytes) throws Exception
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static Map<String, Element> index(Document document)
    {
        Map<String, Element> result = new HashMap<>();
        var nodes = document.getElementsByTagNameNS(BPMN, "*");
        for (int i = 0; i < nodes.getLength(); i++)
        {
            Element element = (Element) nodes.item(i);
            if (element.hasAttribute("id")) result.put(element.getAttribute("id"), element);
        }
        return result;
    }
}
