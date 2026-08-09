package com.ruoyi.flowable.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.StringJoiner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

class WorkflowFormTemplateValidatorTest
{
    private WorkflowFormTemplateValidator validator;

    /**
     * 为每个测试创建无共享校验状态的验证器。
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        validator = new WorkflowFormTemplateValidator();
    }

    /**
     * 验证旧生成器全部基线组件标签均在服务端白名单内。
     * @param tag String，旧生成器组件标签
     * @return void，白名单遗漏时测试失败
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "el-input", "el-input-number", "el-select", "el-cascader",
            "el-radio-group", "el-checkbox-group", "el-switch", "el-slider",
            "el-time-picker", "el-date-picker", "el-rate", "el-color-picker",
            "el-upload", "tinymce", "el-table", "el-table-column", "el-button" })
    void acceptsAllAllowedComponentTags(String tag)
    {
        String layout = "el-table-column".equals(tag) ? "raw" : "colFormItem";
        String content = "{\"fields\":[{\"__config__\":{\"layout\":\""
                + layout + "\",\"tag\":\"" + tag + "\"}}]}";

        assertThatCode(() -> validator.validate(content)).doesNotThrowAnyException();
    }

    /**
     * 验证 rowFormItem 可无 tag，并递归校验其 children 中的正式组件。
     * @return void，行容器兼容契约错误时测试失败
     */
    @Test
    void acceptsNestedRowContainerWithoutTag()
    {
        String content = """
                {
                  "fields": [{
                    "__config__": {
                      "layout": "rowFormItem",
                      "children": [{
                        "__config__": {"layout": "colFormItem", "tag": "el-input"}
                      }]
                    }
                  }]
                }
                """;

        assertThatCode(() -> validator.validate(content)).doesNotThrowAnyException();
    }

    /**
     * 验证字段提取只读取组件 __vModel__，保持嵌套顺序并对重复字段去重。
     * @return void，字段白名单提取不完整或包含配置字段时测试失败
     */
    @Test
    void extractsOrderedVariableNamesFromNestedComponents()
    {
        String content = """
                {
                  "fields": [
                    {
                      "__vModel__": "applicant",
                      "__config__": {"layout": "colFormItem", "tag": "el-input"}
                    },
                    {
                      "__config__": {
                        "layout": "rowFormItem",
                        "children": [
                          {
                            "__vModel__": "decision",
                            "__config__": {"layout": "colFormItem", "tag": "el-select"}
                          },
                          {
                            "__vModel__": "applicant",
                            "__config__": {"layout": "colFormItem", "tag": "el-input"}
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        assertThat(validator.extractVariableNames(content))
                .containsExactly("applicant", "decision");
    }

    /**
     * 验证节点部署快照中的隐藏字段仍属于 schema，但不会进入详情可读字段集合。
     * @return void，隐藏字段被详情层回显或旧模板兼容语义丢失时测试失败
     */
    @Test
    void extractsOnlyReadableVariablesWhileKeepingLegacyFieldsReadable()
    {
        String content = """
                {
                  "fields": [
                    {
                      "__vModel__": "legacyReadable",
                      "__config__": {"layout": "colFormItem", "tag": "el-input"}
                    },
                    {
                      "__vModel__": "hiddenField",
                      "__config__": {
                        "layout": "colFormItem",
                        "tag": "el-input",
                        "workflowHidden": true,
                        "workflowReadable": false,
                        "workflowWritable": false
                      },
                      "disabled": true
                    },
                    {
                      "__vModel__": "readonlyField",
                      "__config__": {
                        "layout": "colFormItem",
                        "tag": "el-input",
                        "workflowHidden": false,
                        "workflowReadable": true,
                        "workflowWritable": false
                      },
                      "disabled": true
                    }
                  ]
                }
                """;

        assertThat(validator.extractVariableNames(content))
                .containsExactly("legacyReadable", "hiddenField", "readonlyField");
        assertThat(validator.extractReadableVariableNames(content))
                .containsExactly("legacyReadable", "readonlyField");
    }

    /**
     * 验证用户主键来源目录只保留可见可读的单值字段，并对同名异构声明失败关闭。
     * @return void，隐藏、无读权限、集合、对象或同名复合字段进入目录时测试失败
     */
    @Test
    void extractsOnlyVisibleReadableSingleUserIdSources()
    {
        String content = """
                {
                  "fields": [
                    {"__vModel__":"textUser","__config__":{"layout":"colFormItem","tag":"el-input"}},
                    {"__vModel__":"numberUser","__config__":{"layout":"colFormItem","tag":"el-input-number"}},
                    {"__vModel__":"readonlyUser","__config__":{"layout":"colFormItem","tag":"el-select","workflowReadable":true,"workflowWritable":false}},
                    {"__vModel__":"hiddenUser","__config__":{"layout":"colFormItem","tag":"el-input","workflowHidden":true,"workflowReadable":false,"workflowWritable":false}},
                    {"__vModel__":"writeOnlyUser","__config__":{"layout":"colFormItem","tag":"el-input","workflowReadable":false,"workflowWritable":true}},
                    {"__vModel__":"multipleUsers","multiple":true,"__config__":{"layout":"colFormItem","tag":"el-select"}},
                    {"__vModel__":"objectUsers","__config__":{"layout":"colFormItem","tag":"el-table"}},
                    {"__vModel__":"conflictingUser","__config__":{"layout":"colFormItem","tag":"el-input"}},
                    {"__vModel__":"conflictingUser","multiple":true,"__config__":{"layout":"colFormItem","tag":"el-select"}},
                    {"__vModel__":"typeConflictingUser","__config__":{"layout":"colFormItem","tag":"el-input"}},
                    {"__vModel__":"typeConflictingUser","__config__":{"layout":"colFormItem","tag":"el-input-number"}}
                  ]
                }
                """;

        assertThat(validator.extractUserIdSourceVariableNames(content))
                .containsExactly("textUser", "numberUser", "readonlyUser");
    }

    /**
     * 验证未知组件标签和布局均被稳定拒绝。
     * @param config String，待放入 __config__ 的非法 JSON 片段
     * @return void，非法组件被放行时测试失败
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"layout\":\"colFormItem\",\"tag\":\"script\"}",
            "{\"layout\":\"freeLayout\",\"tag\":\"el-input\"}" })
    void rejectsUnknownTagOrLayout(String config)
    {
        assertBadRequest("{\"fields\":[{\"__config__\":" + config + "}]}");
    }

    /**
     * 验证任意层级的原型污染键、危险协议及控制空白混淆形式均被拒绝。
     * @param content String，包含危险内容的完整模板 JSON
     * @return void，危险内容被放行时测试失败
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"fields\":[],\"__proto__\":{\"polluted\":true}}",
            "{\"fields\":[],\"nested\":{\"constructor\":{}}}",
            "{\"fields\":[],\"url\":\"javascript:alert(1)\"}",
            "{\"fields\":[],\"url\":\"JaVa\\nScRiPt :alert(1)\"}",
            "{\"fields\":[],\"image\":\"data:image/svg+xml;base64,PHN2Zz4=\"}" })
    void rejectsPrototypePollutionAndDangerousProtocols(String content)
    {
        assertBadRequest(content);
    }

    /**
     * 验证重复 JSON 键和尾随第二根节点不会被 Jackson 静默接受。
     * @param content String，重复键或多根 JSON 文本
     * @return void，非单一严格 JSON 被放行时测试失败
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"fields\":[],\"fields\":[]}",
            "{\"fields\":[]} {\"fields\":[]}" })
    void rejectsDuplicateKeysAndTrailingRoot(String content)
    {
        assertBadRequest(content);
    }

    /**
     * 验证组件节点总数超过上限时拒绝模板。
     * @return void，超量组件被放行时测试失败
     */
    @Test
    void rejectsTooManyComponentNodes()
    {
        StringJoiner fields = new StringJoiner(",");
        for (int index = 0; index <= WorkflowFormTemplateValidator.MAX_COMPONENT_NODES; index++)
        {
            fields.add(validInputComponent());
        }

        assertBadRequest("{\"fields\":[" + fields + "]}");
    }

    /**
     * 验证 __config__.children 嵌套深度超过上限时拒绝模板。
     * @return void，过深组件树被放行时测试失败
     */
    @Test
    void rejectsComponentTreeBeyondMaximumDepth()
    {
        String component = validInputComponent();
        for (int depth = 0; depth < WorkflowFormTemplateValidator.MAX_COMPONENT_DEPTH; depth++)
        {
            component = "{\"__config__\":{\"layout\":\"rowFormItem\",\"children\":["
                    + component + "]}}";
        }

        assertBadRequest("{\"fields\":[" + component + "]}");
    }

    /**
     * 断言模板校验返回 400 且不在错误消息中回显完整模板。
     * @param content String，待校验非法模板
     * @return void，异常语义不稳定时测试失败
     */
    private void assertBadRequest(String content)
    {
        assertThatThrownBy(() -> validator.validate(content))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isNotBlank().doesNotContain(content);
                });
    }

    /**
     * 创建最小合法输入组件 JSON。
     * @return String，最小合法组件 JSON
     */
    private String validInputComponent()
    {
        return "{\"__config__\":{\"layout\":\"colFormItem\",\"tag\":\"el-input\"}}";
    }
}
