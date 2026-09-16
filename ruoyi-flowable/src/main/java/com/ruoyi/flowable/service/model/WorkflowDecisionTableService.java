package com.ruoyi.flowable.service.model;

import static com.ruoyi.flowable.service.model.WorkflowDecisionTableDecimal.read;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.flowable.dmn.api.DmnDecisionService;
import org.flowable.dmn.api.DmnRepositoryService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowDecisionTableRequest;
import com.ruoyi.flowable.domain.dto.WorkflowDecisionTableRequest.Column;
import com.ruoyi.flowable.domain.dto.WorkflowDecisionTableRequest.Condition;
import com.ruoyi.flowable.domain.dto.WorkflowDecisionTableRequest.Rule;
import com.ruoyi.flowable.domain.dto.WorkflowDmnDeploymentRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;

/** 有界结构化决策表编译、真实引擎试算和正式版本发布。 */
@Service
public class WorkflowDecisionTableService
{
    private static final Map<String, String> OPERATORS = Map.of("EQ", "", "NE", "!= ",
            "LT", "< ", "LE", "<= ", "GT", "> ", "GE", ">= ");
    private final WorkflowDmnDecisionService decisions;
    private final DmnDecisionService evaluator;
    private final DmnRepositoryService repository;
    private final WorkflowEngineOperations operations;

    public WorkflowDecisionTableService(WorkflowDmnDecisionService decisions, DmnDecisionService evaluator,
            DmnRepositoryService repository, WorkflowEngineOperations operations)
    {
        this.decisions = decisions;
        this.evaluator = evaluator;
        this.repository = repository;
        this.operations = operations;
    }

    public String publish(WorkflowDecisionTableRequest table)
    {
        String xml = compile(table, table == null ? "" : table.key());
        return decisions.deploy(new WorkflowDmnDeploymentRequest(table.key() + ".dmn", table.category(), xml));
    }

    /** 试算使用唯一临时决策并在同一事务内删除，不产生正式版本或运行历史。 */
    public List<Map<String, Object>> evaluate(WorkflowDecisionTableRequest table)
    {
        String key = "trial_" + UUID.randomUUID().toString().replace("-", "");
        String xml = compile(table, key);
        if (table.testValues() == null || table.testValues().size() != table.inputs().size())
            throw invalid("请为每个输入列提供一个试算值");
        Map<String, Object> values = new LinkedHashMap<>();
        for (Column input : table.inputs())
        {
            JsonNode value = table.testValues().get(input.key());
            literal(input, value);
            values.put(input.key(), switch (input.type()) {
                case "number" -> read(value);
                case "boolean" -> value.booleanValue();
                default -> value.textValue();
            });
        }
        return operations.writeAsCurrentUser(identity -> {
            String deploymentId = decisions.deploy(new WorkflowDmnDeploymentRequest(key + ".dmn", "TRIAL", xml));
            try
            {
                var result = evaluator.createExecuteDecisionBuilder().decisionKey(key).variables(values)
                        .disableHistory().executeDecisionWithAuditTrail();
                if (Boolean.TRUE.equals(result.isFailed()) || result.getValidationMessage() != null)
                    throw invalid("决策试算失败，请检查命中策略、条件范围与输入类型");
                return result.getDecisionResult();
            }
            finally { repository.deleteDeployment(deploymentId); }
        });
    }

    String compile(WorkflowDecisionTableRequest table, String decisionKey)
    {
        if (table == null) throw invalid("决策表不能为空");
        requireKey(table.key());
        if (table.name() == null || table.name().isBlank() || table.name().length() > 128)
            throw invalid("决策名称必须为 1 至 128 个字符");
        if (table.category() != null && table.category().length() > 255) throw invalid("分类超过长度限制");
        if (!Set.of("UNIQUE", "FIRST").contains(String.valueOf(table.hitPolicy())))
            throw invalid("当前决策表仅支持唯一命中或按顺序取首条命中");
        columns(table.inputs(), "输入");
        columns(table.outputs(), "输出");
        if (table.inputs().stream().anyMatch(input -> table.outputs().stream().anyMatch(output -> input.key().equals(output.key()))))
            throw invalid("输入列和输出列不能使用相同变量名");
        if (table.rules() == null || table.rules().isEmpty() || table.rules().size() > 100)
            throw invalid("决策表必须包含 1 至 100 条规则");
        if (table.defaultResult() != null && !"FIRST".equals(table.hitPolicy()))
            throw invalid("默认结果需使用按顺序取首条命中策略");
        try
        {
            StringWriter buffer = new StringWriter();
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(buffer);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("definitions");
            xml.writeDefaultNamespace(org.flowable.dmn.xml.constants.DmnXMLConstants.DMN_NAMESPACE);
            xml.writeAttribute("id", "definitions_" + decisionKey);
            xml.writeAttribute("name", table.name());
            xml.writeAttribute("namespace", "urn:approvaplat:decision-table");
            xml.writeAttribute("exporter", WorkflowDecisionTableExecutor.EXPORTER);
            xml.writeAttribute("exporterVersion", "2");
            xml.writeStartElement("decision");
            xml.writeAttribute("id", decisionKey);
            xml.writeAttribute("name", table.name());
            xml.writeStartElement("decisionTable");
            xml.writeAttribute("id", "table_" + decisionKey);
            xml.writeAttribute("hitPolicy", table.hitPolicy());
            for (Column column : table.inputs())
            {
                xml.writeStartElement("input");
                xml.writeAttribute("id", "input_" + column.key());
                xml.writeAttribute("label", column.label());
                xml.writeStartElement("inputExpression");
                xml.writeAttribute("id", "expression_" + column.key());
                xml.writeAttribute("typeRef", column.type());
                text(xml, column.key());
                xml.writeEndElement();
                xml.writeEndElement();
            }
            for (Column column : table.outputs())
            {
                xml.writeEmptyElement("output");
                xml.writeAttribute("id", "output_" + column.key());
                xml.writeAttribute("name", column.key());
                xml.writeAttribute("label", column.label());
                xml.writeAttribute("typeRef", column.type());
            }
            List<Rule> rules = new ArrayList<>(table.rules());
            if (table.defaultResult() != null) rules.add(new Rule(
                    table.inputs().stream().map(input -> new Condition("ANY", null)).toList(), table.defaultResult()));
            int index = 0;
            for (Rule rule : rules)
            {
                if (rule == null || rule.conditions() == null || rule.results() == null
                        || rule.conditions().size() != table.inputs().size() || rule.results().size() != table.outputs().size())
                    throw invalid("每条规则的条件及结果数量必须与列数一致");
                xml.writeStartElement("rule");
                xml.writeAttribute("id", "rule_" + ++index);
                for (int i = 0; i < table.inputs().size(); i++)
                {
                    Condition condition = rule.conditions().get(i);
                    if (condition == null) throw invalid("规则条件不能为空");
                    String expression;
                    if ("ANY".equals(condition.operator())) expression = "-";
                    else
                    {
                        Column column = table.inputs().get(i);
                        String operator = OPERATORS.get(condition.operator());
                        if (operator == null || (!"number".equals(column.type())
                                && !Set.of("EQ", "NE").contains(condition.operator()))) throw invalid("条件运算符与列类型不匹配");
                        expression = operator + literal(column, condition.value());
                    }
                    entry(xml, "inputEntry", "in_" + index + "_" + i, expression);
                }
                for (int i = 0; i < table.outputs().size(); i++)
                    entry(xml, "outputEntry", "out_" + index + "_" + i, literal(table.outputs().get(i), rule.results().get(i)));
                xml.writeEndElement();
            }
            xml.writeEndElement(); xml.writeEndElement(); xml.writeEndElement(); xml.writeEndDocument(); xml.close();
            if ("UNIQUE".equals(table.hitPolicy())) requireDisjointRules(table);
            return buffer.toString();
        }
        catch (XMLStreamException exception) { throw new ServiceException("决策表编译失败", HttpStatus.ERROR); }
    }

    private void columns(List<Column> columns, String label)
    {
        if (columns == null || columns.isEmpty() || columns.size() > 8) throw invalid(label + "列必须为 1 至 8 列");
        Set<String> keys = new HashSet<>();
        for (Column column : columns)
        {
            if (column == null) throw invalid("列不能为空");
            requireKey(column.key());
            if (!keys.add(column.key())) throw invalid("列变量名不能重复");
            if (column.label() == null || column.label().isBlank() || column.label().length() > 128) throw invalid("列名称不能为空或过长");
            if (!Set.of("string", "number", "boolean").contains(String.valueOf(column.type()))) throw invalid("列类型仅支持文本、数值、布尔值");
        }
    }

    /** 受控条件只含单值比较，可在发布前精确检查任意两行是否可能同时命中。 */
    private void requireDisjointRules(WorkflowDecisionTableRequest table)
    {
        for (int a = 0; a < table.rules().size(); a++)
            for (int b = a + 1; b < table.rules().size(); b++)
            {
                boolean overlaps = true;
                for (int column = 0; column < table.inputs().size(); column++)
                    if (!conditionsOverlap(table.inputs().get(column), table.rules().get(a).conditions().get(column),
                            table.rules().get(b).conditions().get(column))) { overlaps = false; break; }
                if (overlaps) throw invalid("唯一命中策略下第 " + (a + 1) + " 与第 " + (b + 1) + " 条规则的条件重叠");
            }
    }

    private boolean conditionsOverlap(Column column, Condition a, Condition b)
    {
        if ("boolean".equals(column.type()))
            return (matches(a, false) && matches(b, false)) || (matches(a, true) && matches(b, true));
        if ("string".equals(column.type()))
        {
            if ("EQ".equals(a.operator())) return matches(b, a.value().textValue());
            if ("EQ".equals(b.operator())) return matches(a, b.value().textValue());
            return true; // ANY/NE 的有限排除仍有共同文本值。
        }
        List<BigDecimal> bounds = new ArrayList<>();
        if (!"ANY".equals(a.operator())) bounds.add(read(a.value()));
        if (!"ANY".equals(b.operator())) bounds.add(read(b.value()));
        if (bounds.isEmpty()) return true;
        bounds.sort(BigDecimal::compareTo);
        List<BigDecimal> probes = new ArrayList<>(bounds);
        probes.add(bounds.get(0).subtract(BigDecimal.ONE));
        probes.add(bounds.get(bounds.size() - 1).add(BigDecimal.ONE));
        if (bounds.size() == 2) probes.add(bounds.get(0).add(bounds.get(1)).divide(BigDecimal.valueOf(2)));
        return probes.stream().anyMatch(value -> matches(a, value) && matches(b, value));
    }

    private boolean matches(Condition condition, Object value)
    {
        if ("ANY".equals(condition.operator())) return true;
        int comparison = value instanceof BigDecimal number ? number.compareTo(read(condition.value()))
                : value instanceof Boolean flag ? Boolean.compare(flag, condition.value().booleanValue())
                : ((String) value).compareTo(condition.value().textValue());
        return switch (condition.operator()) {
            case "EQ" -> comparison == 0;
            case "NE" -> comparison != 0;
            case "LT" -> comparison < 0;
            case "LE" -> comparison <= 0;
            case "GT" -> comparison > 0;
            case "GE" -> comparison >= 0;
            default -> false;
        };
    }

    private void requireKey(String key)
    {
        if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                || WorkflowStartVariableValidator.isReservedVariable(key)
                || Set.of("__proto__", "prototype", "constructor").contains(key)) throw invalid("决策或列变量标识不合法");
    }

    private String literal(Column column, JsonNode value)
    {
        if (value == null || value.isNull()) throw invalid(column.label() + "必须填写值");
        switch (column.type())
        {
            case "number" -> {
                return read(value).toString();
            }
            case "boolean" -> {
                if (!value.isBoolean()) throw invalid(column.label() + "必须为布尔值");
                return value.booleanValue() ? "true" : "false";
            }
            default -> {
                if (!value.isTextual() || value.textValue().length() > 512
                        || value.textValue().contains("${") || value.textValue().contains("#{"))
                    throw invalid(column.label() + "必须为 512 字以内的普通文本");
                return value.toString();
            }
        }
    }

    private void entry(XMLStreamWriter xml, String tag, String id, String value) throws XMLStreamException
    {
        xml.writeStartElement(tag); xml.writeAttribute("id", id); text(xml, value); xml.writeEndElement();
    }
    private void text(XMLStreamWriter xml, String text) throws XMLStreamException
    {
        xml.writeStartElement("text"); xml.writeCharacters(text); xml.writeEndElement();
    }
    private ServiceException invalid(String message) { return new ServiceException(message, HttpStatus.BAD_REQUEST); }
}
