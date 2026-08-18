package com.ruoyi.web.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 使用 ArchUnit 的编译后依赖图验证工作流 Web Controller 仅依赖应用服务。
 */
class WorkflowWebArchitectureTest
{
    /** Controller 禁止直连数据访问层的正式 ArchUnit 规则。 */
    private static final ArchRule CONTROLLER_DATA_ACCESS_RULE =
            noClasses().that().resideInAPackage("com.ruoyi.web.controller.workflow..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..mapper..",
                            "org.springframework.jdbc..");

    /**
     * 验证工作流 Controller 不直接依赖通知 Channel、Mapper 或 JDBC API。
     *
     * @return void，Controller 绕过应用服务边界时 ArchUnit 抛出断言错误
     */
    @Test
    void workflowControllersDoNotAccessNotificationChannelsOrDataLayer()
    {
        JavaClasses classes = importProductionClasses();
        CONTROLLER_DATA_ACCESS_RULE.check(classes);
        noClasses().that().resideInAPackage("com.ruoyi.web.controller.workflow..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("NotificationChannel")
                .check(classes);
    }

    /**
     * 导入完整应用生产类字节码，使 Web 层和工作流领域层依赖可以同时被分析。
     *
     * @return JavaClasses，com.ruoyi 生产类依赖图
     */
    private JavaClasses importProductionClasses()
    {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.ruoyi");
    }
}
