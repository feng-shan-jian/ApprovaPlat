package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WorkflowCurrentVariableMetadataRow;
import com.ruoyi.flowable.domain.WorkflowHistoricSubmissionRow;
import com.ruoyi.flowable.domain.WorkflowHistoricVariableBodyRow;

/**
 * Flowable 8 历史变量安全读取专用数据访问层。
 */
public interface WorkflowHistoricVariableMapper
{
    /**
     * 从 ACT_HI_DETAIL 查询固定内部提交快照的完整元数据和正文统计。
     *
     * @param processInstanceId String，已经完成对象授权的流程实例主键
     * @param variableName String，服务端固定内部快照变量名
     * @param rowLimit int，数据库层最大返回行数，用上限加一识别截断
     * @return List&lt;WorkflowHistoricSubmissionRow&gt;，包含 BYTEARRAY_ID_、正文存在性和物理字节统计但不含正文的完整固定名行
     */
    List<WorkflowHistoricSubmissionRow> selectSubmissionMetadata(
            @Param("processInstanceId") String processInstanceId,
            @Param("variableName") String variableName,
            @Param("rowLimit") int rowLimit);

    /**
     * 在全部快照元数据通过校验后，按已授权实例和已验证主键批量读取正文。
     *
     * @param processInstanceId String，已经完成对象授权的流程实例主键
     * @param variableName String，服务端固定内部快照变量名
     * @param rowIds List&lt;String&gt;，已通过元数据、类型、关联和容量门禁的历史详情主键
     * @return List&lt;WorkflowHistoricVariableBodyRow&gt;，每个主键唯一对应的行内文本或 Blob 正文
     */
    List<WorkflowHistoricVariableBodyRow> selectSubmissionBodies(
            @Param("processInstanceId") String processInstanceId,
            @Param("variableName") String variableName,
            @Param("rowIds") List<String> rowIds);

    /**
     * 按已授权实例、任务作用域和部署表单字段白名单查询活动表单变量存储元数据。
     *
     * @param processInstanceId String，已经完成对象授权的流程实例主键
     * @param taskId String，真实活动任务主键
     * @param taskLocal boolean，true 查询任务局部变量，false 查询流程根变量
     * @param variableNames List&lt;String&gt;，部署表单 schema 声明且非内部的字段白名单
     * @param rowLimit int，白名单变量数加一，用于识别重复或越界结果
     * @return List&lt;WorkflowCurrentVariableMetadataRow&gt;，包含 BYTEARRAY_ID_ 和物理字节统计但不含正文的当前历史变量元数据
     */
    List<WorkflowCurrentVariableMetadataRow> selectCurrentVariableMetadata(
            @Param("processInstanceId") String processInstanceId,
            @Param("taskId") String taskId,
            @Param("taskLocal") boolean taskLocal,
            @Param("variableNames") List<String> variableNames,
            @Param("rowLimit") int rowLimit);

    /**
     * 在活动变量元数据通过校验后，按完整授权边界和白名单批量读取受控正文。
     *
     * @param processInstanceId String，已经完成对象授权的流程实例主键
     * @param taskId String，真实活动任务主键
     * @param taskLocal boolean，true 查询任务局部变量，false 查询流程根变量
     * @param variableNames List&lt;String&gt;，部署表单 schema 声明且非内部的字段白名单
     * @param rowIds List&lt;String&gt;，已通过元数据和容量门禁的变量主键
     * @return List&lt;WorkflowHistoricVariableBodyRow&gt;，每个变量主键唯一对应的行内或 Blob 正文
     */
    List<WorkflowHistoricVariableBodyRow> selectCurrentVariableBodies(
            @Param("processInstanceId") String processInstanceId,
            @Param("taskId") String taskId,
            @Param("taskLocal") boolean taskLocal,
            @Param("variableNames") List<String> variableNames,
            @Param("rowIds") List<String> rowIds);
}
