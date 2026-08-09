package com.ruoyi.flowable.domain.dto;

import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 设计器批量回显已保存身份对象的请求。
 *
 * @param type String，user、role 或 dept
 * @param capability String，可为空；approval 或 claim
 * @param values List&lt;String&gt;，作者 BPMN 中保存的受控目录值
 */
public record WorkflowIdentitySelectionRequest(
        @NotBlank(message = "工作流身份类型不能为空")
        @Pattern(regexp = "user|role|dept", message = "工作流身份类型必须为 user、role 或 dept")
        String type,
        @Pattern(regexp = "approval|claim|", message = "工作流身份目录能力必须为 approval 或 claim")
        String capability,
        @NotEmpty(message = "已选身份不能为空")
        @Size(max = 200, message = "单次最多回显200个已选身份")
        List<@NotBlank(message = "已选身份值不能为空")
                @Size(max = 32, message = "已选身份值过长") String> values)
{
}
