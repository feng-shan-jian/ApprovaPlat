package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 扩展 JSON 跨 MySQL 键重排的稳定规范化测试。
 */
class WorkflowExtensionJsonCanonicalizerTest
{
    /**
     * 验证对象键递归排序且数组业务顺序保持不变。
     * @return 无返回值；规范文本不稳定或数组被重排时测试失败
     */
    @Test
    void canonicalizesObjectKeysWithoutReorderingArrays()
    {
        String left = "{\"z\":1,\"nested\":{\"b\":2,\"a\":1},\"list\":[\"b\",\"a\"]}";
        String right = "{\"list\":[\"b\",\"a\"],\"nested\":{\"a\":1,\"b\":2},\"z\":1}";

        assertThat(WorkflowExtensionJsonCanonicalizer.canonicalize(left))
                .isEqualTo(WorkflowExtensionJsonCanonicalizer.canonicalize(right))
                .isEqualTo("{\"list\":[\"b\",\"a\"],\"nested\":{\"a\":1,\"b\":2},\"z\":1}");
    }

    /**
     * 验证内置 Schema 的规范摘要与正式 SQL 种子保持一致。
     * @return 无返回值；Schema、规范协议或迁移摘要漂移时测试失败
     */
    @Test
    void keepsBuiltInVersionChecksumStable()
    {
        WorkflowSetVariableJavaHandler handler = new WorkflowSetVariableJavaHandler();
        String schema = WorkflowExtensionJsonCanonicalizer.canonicalize(handler.configSchema());

        assertThat(WorkflowExtensionChecksum.sha256(
                "approva.set-variable", "JAVA", "1", "SET_VARIABLE", schema))
                .isEqualTo("42bca2710135b3faac369facee8c103683edf52b63f95c2ec2fb18f14fd3b3f0");
    }

    /**
     * 验证空白或非法持久化 JSON 以服务端错误拒绝，不能降级为原始字符串摘要。
     * @return 无返回值；非法 JSON 被接受时测试失败
     */
    @Test
    void rejectsInvalidJson()
    {
        assertThatThrownBy(() -> WorkflowExtensionJsonCanonicalizer.canonicalize("{"))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.ERROR));
    }
}
