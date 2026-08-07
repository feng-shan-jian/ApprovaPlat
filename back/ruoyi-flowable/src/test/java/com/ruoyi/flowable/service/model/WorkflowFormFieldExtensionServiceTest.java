package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.extension.WorkflowFormFieldExtension;

/**
 * 自定义表单字段设计解析与部署冻结服务测试。
 */
class WorkflowFormFieldExtensionServiceTest
{
    /**
     * 验证部署阶段覆盖作者阶段版本元数据，并保留服务端固定渲染配置。
     * @return void，目录未加锁、版本未更新或正文损坏时测试失败
     * @throws Exception JSON 解析失败
     */
    @Test
    void freezesLatestVersionIntoEmbeddedFormSnapshot() throws Exception
    {
        WorkflowExtensionRegistryService registry = mock(WorkflowExtensionRegistryService.class);
        WorkflowExtensionOptionView deploymentVersion = option(3);
        when(registry.lockLatestForDeployment("approva.form.textarea"))
                .thenReturn(deploymentVersion);
        WorkflowFormFieldExtensionService service =
                new WorkflowFormFieldExtensionService(registry);
        String authorContent = """
                {"fields":[{"__config__":{"layout":"colFormItem","tag":"el-input",
                "workflowFormFieldExtensionKey":"approva.form.textarea",
                "workflowFormFieldExtensionVersion":1,
                "workflowFormFieldImplementation":"FORM_FIELD_TEXTAREA_V1",
                "workflowFormFieldChecksum":"old"},"__vModel__":"detail",
                "type":"textarea","rows":4}]}
                """;

        WorkflowFrozenFormContent frozenResult = service.freezeEmbeddedContentWithSnapshots(
                authorContent, "expense", "review");
        String frozen = frozenResult.content();

        JsonNode config = JsonMapper.shared().readTree(frozen)
                .path("fields").get(0).path("__config__");
        assertThat(config.path(WorkflowFormFieldExtension.VERSION_FIELD).intValue()).isEqualTo(3);
        assertThat(config.path(WorkflowFormFieldExtension.IMPLEMENTATION_FIELD).textValue())
                .isEqualTo(WorkflowFormFieldExtension.TEXTAREA_IMPLEMENTATION_KEY);
        assertThat(config.path(WorkflowFormFieldExtension.CHECKSUM_FIELD).textValue())
                .isEqualTo(deploymentVersion.checksum());
        assertThat(frozenResult.extensionSnapshots()).singleElement().satisfies(snapshot ->
        {
            assertThat(snapshot.getProcessKey()).isEqualTo("expense");
            assertThat(snapshot.getElementId()).isEqualTo("review#form#detail");
            assertThat(snapshot.getExtensionType()).isEqualTo("FORM_FIELD");
            assertThat(snapshot.getExtensionVersionId()).isEqualTo(deploymentVersion.versionId());
            assertThat(snapshot.getVersionNo()).isEqualTo(3);
            assertThat(snapshot.getImplementationKey())
                    .isEqualTo(WorkflowFormFieldExtension.TEXTAREA_IMPLEMENTATION_KEY);
            assertThat(snapshot.getVersionChecksum()).isEqualTo(deploymentVersion.checksum());
            assertThat(snapshot.getConfigJson()).isEqualTo("{}");
        });
    }

    /**
     * 验证 FORM_FIELD 使用位置拒绝其他扩展类型且不伪造字段实现。
     * @return void，类型错配未返回冲突时测试失败
     */
    @Test
    void rejectsExtensionTypeMismatch()
    {
        WorkflowExtensionRegistryService registry = mock(WorkflowExtensionRegistryService.class);
        WorkflowExtensionOptionView javaOption = new WorkflowExtensionOptionView(
                1L, "approva.set-variable", "设置变量", "JAVA", 2L, 1,
                "SET_VARIABLE", "{}", "0".repeat(64));
        when(registry.lockLatestForDeployment("approva.set-variable")).thenReturn(javaOption);
        WorkflowFormFieldExtensionService service =
                new WorkflowFormFieldExtensionService(registry);

        assertThatThrownBy(() -> service.lockForDeployment("approva.set-variable"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getMessage()).contains("其他扩展类型");
                });
    }

    /**
     * 构造指定版本的受控多行文本字段选项。
     * @param version int，不可变版本号
     * @return WorkflowExtensionOptionView，可用于部署冻结的正式选项
     */
    private WorkflowExtensionOptionView option(int version)
    {
        String schema = WorkflowFormFieldExtension.configSchema();
        String checksum = WorkflowExtensionChecksum.sha256(
                "approva.form.textarea", "FORM_FIELD", Integer.toString(version),
                WorkflowFormFieldExtension.TEXTAREA_IMPLEMENTATION_KEY, schema);
        return new WorkflowExtensionOptionView(18L, "approva.form.textarea", "多行文本",
                "FORM_FIELD", 20L + version, version,
                WorkflowFormFieldExtension.TEXTAREA_IMPLEMENTATION_KEY, schema, checksum);
    }
}
