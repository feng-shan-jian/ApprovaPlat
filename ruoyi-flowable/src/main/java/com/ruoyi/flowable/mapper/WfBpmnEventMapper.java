package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfBpmnEventCode;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnEventAuditView;
import com.ruoyi.flowable.mapper.param.WfBpmnEventAuditWriteParam;

/**
 * BPMN 错误、升级目录及运行审计数据访问层。
 */
public interface WfBpmnEventMapper
{
    /** @return List&lt;WfBpmnEventCode&gt;，全部编码目录。 */
    List<WfBpmnEventCode> selectCodeList();

    /**
     * 查询设计器可选的已启用目录。
     * @param eventType String，ERROR 或 ESCALATION
     * @return List&lt;WfBpmnEventCode&gt;，按编码排序的真实目录
     */
    List<WfBpmnEventCode> selectEnabledCodes(@Param("eventType") String eventType);

    /**
     * 按类型和编码查询目录。
     * @param eventType String，事件类型
     * @param eventCode String，稳定编码
     * @return WfBpmnEventCode，目录记录；不存在时为空
     */
    WfBpmnEventCode selectCode(@Param("eventType") String eventType,
            @Param("eventCode") String eventCode);

    /** @param eventCodeId Long，主键；@return WfBpmnEventCode，锁定记录。 */
    WfBpmnEventCode selectCodeForUpdate(@Param("eventCodeId") Long eventCodeId);

    /** @param code WfBpmnEventCode，新增目录；@return int，写入行数。 */
    int insertCode(WfBpmnEventCode code);

    /** @param code WfBpmnEventCode，名称、通知与说明修改；@return int，更新行数。 */
    int updateCode(WfBpmnEventCode code);

    /**
     * 修改目录状态。
     * @param eventCodeId Long，目录主键
     * @param status String，ENABLED 或 DISABLED
     * @param updateBy String，操作人用户主键
     * @return int，更新行数
     */
    int updateCodeStatus(@Param("eventCodeId") Long eventCodeId,
            @Param("status") String status, @Param("updateBy") String updateBy);

    /**
     * 首次写入幂等运行审计。
     * @param idempotencyKey String，稳定幂等摘要
     * @param deploymentId String，部署主键
     * @param processInstanceId String，实例主键
     * @param processDefinitionId String，定义主键
     * @param executionId String，执行主键
     * @param sourceElementId String，产生节点
     * @param sourceType String，业务来源类型
     * @param eventType String，事件类型
     * @param eventCode String，事件编码
     * @param eventName String，冻结名称
     * @param matchStatus String，CAPTURED 或 UNMATCHED
     * @param boundaryEventId String，匹配边界标识
     * @param interrupting Boolean，中断语义
     * @param messageSummary String，脱敏摘要
     * @param initiatorUserId String，发起人用户主键
     * @return int，首次写入 1，重复触发 0
     */
    int insertAudit(WfBpmnEventAuditWriteParam param);

    /** @param query BpmnEventAudit，运维筛选条件；@return long，符合条件的审计总数。 */
    long countAuditList(@Param("query") WorkflowOperationsQuery.BpmnEventAudit query);

    /**
     * 分页查询 BPMN 事件运行审计。
     * @param query BpmnEventAudit，运维筛选条件
     * @param offset int，数据库起始偏移
     * @param pageSize int，本页最大记录数
     * @return List&lt;WorkflowBpmnEventAuditView&gt;，按时间和审计主键倒序的当前页
     */
    List<WorkflowBpmnEventAuditView> selectAuditList(
            @Param("query") WorkflowOperationsQuery.BpmnEventAudit query,
            @Param("offset") int offset, @Param("pageSize") int pageSize);

}
