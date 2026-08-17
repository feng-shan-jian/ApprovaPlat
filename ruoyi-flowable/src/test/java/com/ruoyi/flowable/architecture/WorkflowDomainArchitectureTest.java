package com.ruoyi.flowable.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * 使用 ArchUnit 的编译后依赖图验证工作流通知和数据保留模块边界。
 */
class WorkflowDomainArchitectureTest
{
    /**
     * 验证 notification 包只保留架构方案定义的细粒度服务所有者。
     *
     * @return void，重新引入统一门面或未授权 Service 时 ArchUnit 抛出断言错误
     */
    @Test
    void notificationPackageContainsOnlyOwnedServices()
    {
        classes().that().resideInAPackage("..service.notification..")
                .and().haveSimpleNameEndingWith("Service")
                .should().haveSimpleName("WorkflowNotificationPolicyService")
                .orShould().haveSimpleName("WorkflowNotificationInboxService")
                .orShould().haveSimpleName("WorkflowNotificationOutboxService")
                .orShould().haveSimpleName("WorkflowNotificationAdminService")
                .orShould().haveSimpleName("WorkflowManualUrgeService")
                .check(importProductionClasses());
    }

    /**
     * 验证 SMTP 和 SMS 渠道只负责外部投递，不依赖 JDBC、事务 API 或 Mapper。
     *
     * @return void，渠道触碰数据访问或事务边界时 ArchUnit 抛出断言错误
     */
    @Test
    void externalNotificationChannelsDoNotAccessDatabaseOrTransactions()
    {
        noClasses().that().haveSimpleName("MailNotificationChannel")
                .or().haveSimpleName("SmsNotificationChannel")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.jdbc..",
                        "org.springframework.transaction..",
                        "..mapper..")
                .check(importProductionClasses());
    }

    /**
     * 验证投递协调器本身不声明事务，确保渠道网络调用位于领取和完成短事务之外。
     *
     * @return void，类或任一方法声明 @Transactional 时 ArchUnit 抛出断言错误
     */
    @Test
    void deliveryCoordinatorDoesNotOwnTransactions()
    {
        JavaClasses classes = importProductionClasses();
        noClasses().that().haveSimpleName("WorkflowNotificationDeliveryCoordinator")
                .should().beAnnotatedWith(Transactional.class)
                .check(classes);
        noMethods().that().areDeclaredInClassesThat()
                .haveSimpleName("WorkflowNotificationDeliveryCoordinator")
                .should().beAnnotatedWith(Transactional.class)
                .check(classes);
    }

    /**
     * 验证工作流领域模块和通知包不会反向依赖 ruoyi-admin Web 层。
     *
     * @return void，发现 Web 层反向依赖时 ArchUnit 抛出断言错误
     */
    @Test
    void workflowDomainDoesNotDependOnAdminWebLayer()
    {
        noClasses().that().resideInAnyPackage(
                        "com.ruoyi.flowable..",
                        "..service.notification..")
                .should().dependOnClassesThat().resideInAPackage("com.ruoyi.web..")
                .check(importProductionClasses());
    }

    /**
     * 验证 retention 协调器只编排领域清理器，不依赖任何 Controller。
     *
     * @return void，协调器依赖 Controller 时 ArchUnit 抛出断言错误
     */
    @Test
    void retentionCoordinatorDoesNotDependOnControllers()
    {
        noClasses().that().haveSimpleName("WorkflowDataRetentionCoordinator")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
                .check(importProductionClasses());
    }

    /**
     * 导入当前模块生产类的编译后字节码，排除测试夹具对架构边界的干扰。
     *
     * @return JavaClasses，com.ruoyi.flowable 生产类依赖图
     */
    private JavaClasses importProductionClasses()
    {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.ruoyi.flowable");
    }
}
