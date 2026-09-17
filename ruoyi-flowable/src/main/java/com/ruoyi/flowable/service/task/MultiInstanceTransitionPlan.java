package com.ruoyi.flowable.service.task;

/** 取消回调匹配后直接交还原正式计划；两类计划不能表达动作与字段矛盾的组合。 */
public sealed interface MultiInstanceTransitionPlan
        permits MultiInstanceGroupReturnExecutionPlan, MultiInstanceGroupReopenPlan
{
    MultiInstanceRoundSnapshot round();
}
