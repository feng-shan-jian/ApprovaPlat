package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

/**
 * BPMN 错误与升级 Mapper 的幂等、权限和敏感数据静态契约测试。
 */
class WorkflowBpmnEventMapperXmlTest
{
    /**
     * 验证 XML 可解析并包含目录、独立审计、统一通知来源和对象级已读条件。
     * @return void，SQL 映射缺少关键边界时失败
     * @throws Exception Mapper 文件读取或解析失败时测试失败
     */
    @Test
    void definesCatalogAuditNotificationAndOwnershipQueries() throws Exception
    {
        Path mapper = find("ruoyi-flowable/src/main/resources/mapper/flowable/WfBpmnEventMapper.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.newDocumentBuilder().parse(mapper.toFile());
        String xml = Files.readString(mapper, StandardCharsets.UTF_8).toLowerCase();

        assertThat(xml).contains(
                "insert ignore into wf_bpmn_event_audit",
                "where idempotency_key = #{idempotencykey}",
                "from wf_notification_inbox n",
                "join wf_notification_outbox o",
                "o.source_type = 'bpmn_event'",
                "where n.recipient_user_id = cast(#{userid} as unsigned)",
                "where n.notification_id = #{notificationid}",
                "and n.recipient_user_id = cast(#{userid} as unsigned)")
                .doesNotContain("wf_bpmn_event_notification")
                .doesNotContain("exception_stack", "request_body", "response_body");
    }

    /** @param relative String，后端相对路径；@return Path，定位到的正式文件。 */
    private Path find(String relative)
    {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null)
        {
            Path direct = current.resolve(relative);
            if (Files.isRegularFile(direct)) return direct;
            Path underBack = current.resolve("back").resolve(relative);
            if (Files.isRegularFile(underBack)) return underBack;
            current = current.getParent();
        }
        throw new AssertionError("未找到文件: " + relative);
    }
}
