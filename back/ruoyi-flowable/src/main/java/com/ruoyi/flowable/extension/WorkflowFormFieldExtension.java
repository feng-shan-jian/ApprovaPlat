package com.ruoyi.flowable.extension;

import org.flowable.bpmn.model.FormProperty;
import tools.jackson.databind.node.ObjectNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView;

/**
 * 服务端安装的自定义表单字段实现，固定渲染协议并拒绝数据库提供任意组件。
 */
public final class WorkflowFormFieldExtension
{
    /** 多行文本字段的固定实现键。 */
    public static final String TEXTAREA_IMPLEMENTATION_KEY = "FORM_FIELD_TEXTAREA_V1";

    /** BPMN FormProperty 自定义类型前缀。 */
    public static final String TYPE_PREFIX = "custom:";

    /** 冻结在表单组件配置中的扩展稳定键。 */
    public static final String EXTENSION_KEY_FIELD = "workflowFormFieldExtensionKey";

    /** 冻结在表单组件配置中的扩展版本号。 */
    public static final String VERSION_FIELD = "workflowFormFieldExtensionVersion";

    /** 冻结在表单组件配置中的服务端实现键。 */
    public static final String IMPLEMENTATION_FIELD = "workflowFormFieldImplementation";

    /** 冻结在表单组件配置中的扩展版本校验和。 */
    public static final String CHECKSUM_FIELD = "workflowFormFieldChecksum";

    /**
     * 禁止实例化仅包含固定协议的工具类。
     * @return 无返回值
     */
    private WorkflowFormFieldExtension()
    {
    }

    /**
     * 返回多行文本字段固定配置 Schema。
     * @return String，规范 JSON Schema；不包含组件名、表达式或脚本入口
     */
    public static String configSchema()
    {
        return WorkflowExtensionJsonCanonicalizer.canonicalize("""
                {
                  "type":"object",
                  "additionalProperties":false,
                  "properties":{}
                }
                """);
    }

    /**
     * 校验目录版本确实指向服务端安装的字段实现。
     * @param option WorkflowExtensionOptionView，数据库读取并校验过的扩展版本
     * @return WorkflowExtensionOptionView，校验通过的原版本
     */
    public static WorkflowExtensionOptionView requireInstalled(WorkflowExtensionOptionView option)
    {
        if (option == null || !TEXTAREA_IMPLEMENTATION_KEY.equals(option.implementationKey()))
        {
            throw new ServiceException("自定义表单字段实现不受控", HttpStatus.CONFLICT);
        }
        return option;
    }

    /**
     * 把固定多行文本渲染配置和精确扩展版本写入部署表单组件。
     * @param property FormProperty，已通过变量、读写和表达式校验的 BPMN 字段
     * @param component ObjectNode，当前正式表单协议组件
     * @param config ObjectNode，组件 __config__ 配置
     * @param option WorkflowExtensionOptionView，已校验的精确扩展版本
     * @return void，直接补齐传入组件的安全渲染与版本元数据
     */
    public static void configure(FormProperty property, ObjectNode component, ObjectNode config,
            WorkflowExtensionOptionView option)
    {
        WorkflowExtensionOptionView installed = requireInstalled(option);
        // 渲染组件由服务端代码固定，数据库目录只能选择版本，不能注入任意前端组件。
        config.put("tag", "el-input");
        config.put(EXTENSION_KEY_FIELD, installed.extensionKey());
        config.put(VERSION_FIELD, installed.versionNo());
        config.put(IMPLEMENTATION_FIELD, installed.implementationKey());
        config.put(CHECKSUM_FIELD, installed.checksum());
        component.put("type", "textarea");
        component.put("rows", 4);
        component.put("maxlength", 4096);
        component.put("showWordLimit", true);
        component.put("clearable", true);
        component.put("disabled", !property.isWriteable());
    }
}
