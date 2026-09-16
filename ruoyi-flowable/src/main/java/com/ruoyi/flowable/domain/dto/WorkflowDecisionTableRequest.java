package com.ruoyi.flowable.domain.dto;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** 决策表作者协议；number 列接受十进制文本或 JSON number，由后端独立验证并精确编译。 */
public record WorkflowDecisionTableRequest(String key, String name, String category, String hitPolicy,
        List<Column> inputs, List<Column> outputs, List<Rule> rules,
        List<JsonNode> defaultResult, Map<String, JsonNode> testValues)
{
    public record Column(String key, String label, String type) { }
    public record Condition(String operator, JsonNode value) { }
    public record Rule(List<Condition> conditions, List<JsonNode> results) { }
}
