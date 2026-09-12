package com.ruoyi.flowable.service.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnValidationIssue;

/** 作者数据关联保留为建模信息；验证本地引用和作用域，不把关联解释为数据库读写。 */
final class WorkflowDataAssociationContract
{
    private static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final Set<String> DATA = Set.of("dataObject", "dataObjectReference", "dataStore", "dataStoreReference", "property", "dataInput", "dataOutput");

    static void validate(byte[] xml)
    {
        org.w3c.dom.Document document;
        try { document = WorkflowBpmnXmlPreserver.parse(xml); }
        catch (Exception exception) { throw new com.ruoyi.common.exception.ServiceException("BPMN 数据关联结构无法解析", 400); }
        var elements = document.getElementsByTagNameNS(BPMN, "*");
        Map<String, Element> ids = new LinkedHashMap<>();
        for (int i = 0; i < elements.getLength(); i++)
        {
            Element element = (Element) elements.item(i);
            if (element.hasAttribute("id")) ids.put(element.getAttribute("id"), element);
        }
        for (int i = 0; i < elements.getLength(); i++)
        {
            Element owner = (Element) elements.item(i);
            if (Set.of("dataInputAssociation", "dataOutputAssociation").contains(owner.getLocalName()))
                for (Node node = owner.getFirstChild(); node != null; node = node.getNextSibling())
                    if (node instanceof Element ref && BPMN.equals(ref.getNamespaceURI())
                            && Set.of("sourceRef", "targetRef").contains(ref.getLocalName()))
                        validateReference(owner, ref.getLocalName(), ref.getTextContent().trim(), ids, DATA);
            if ("dataObjectReference".equals(owner.getLocalName()) && owner.hasAttribute("dataObjectRef"))
                validateReference(owner, "dataObjectRef", owner.getAttribute("dataObjectRef"), ids, Set.of("dataObject"));
            if ("dataStoreReference".equals(owner.getLocalName()) && owner.hasAttribute("dataStoreRef"))
                validateReference(owner, "dataStoreRef", owner.getAttribute("dataStoreRef"), ids, Set.of("dataStore"));
        }
    }

    private static void validateReference(Element owner, String field, String reference, Map<String, Element> ids, Set<String> types)
    {
        Element target = ids.get(reference);
        Node scope = target == null ? null : target.getParentNode();
        if (scope instanceof Element parent && "ioSpecification".equals(parent.getLocalName())) scope = scope.getParentNode();
        boolean visible = false;
        for (Node node = owner; node != null; node = node.getParentNode()) if (node == scope) visible = true;
        if (target != null && types.contains(target.getLocalName()) && visible) return;
        Element process = owner;
        while (process != null && !"process".equals(process.getLocalName()))
            process = process.getParentNode() instanceof Element parent ? parent : null;
        Element located = owner.hasAttribute("id") ? owner : (Element) owner.getParentNode();
        throw new WorkflowBpmnValidationException(List.of(new WorkflowBpmnValidationIssue(
                "BPMN_DATA_REFERENCE_INVALID", "ERROR", located.getAttribute("id"),
                "数据关联引用不存在、类型不符或越过所属作用域：" + reference,
                process == null ? null : process.getAttribute("id"), field,
                "选择同一流程或外层作用域的数据元素；活动属性和输入输出只能由所属活动引用。"))).asServiceException();
    }
}
