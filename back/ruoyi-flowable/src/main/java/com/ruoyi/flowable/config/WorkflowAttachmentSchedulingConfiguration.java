package com.ruoyi.flowable.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用工作流临时附件的轻量定时清理；状态竞争仍由数据库原子更新控制。
 */
@Configuration
@EnableScheduling
public class WorkflowAttachmentSchedulingConfiguration
{
}
