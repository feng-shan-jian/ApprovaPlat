package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfBpmnExtension;
import com.ruoyi.flowable.domain.WfBpmnExtensionVersion;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionManagementView;

/**
 * BPMN 受控扩展目录与不可变版本数据访问层。
 */
public interface WfBpmnExtensionMapper
{
    /**
     * 查询全部扩展目录及可选最新版，管理页必须包含停用和尚无版本的目录。
     * @return List&lt;WorkflowExtensionManagementView&gt;，按名称和稳定键排序的目录
     */
    List<WorkflowExtensionManagementView> selectManagementList();

    /**
     * 查询已启用且至少存在一个版本的扩展最新版。
     * @param extensionType String，扩展类型编码
     * @return List&lt;WorkflowExtensionOptionView&gt;，按扩展名称和键排序的设计器选项
     */
    List<WorkflowExtensionOptionView> selectLatestEnabledOptions(
            @Param("extensionType") String extensionType);

    /**
     * 按稳定键查询扩展目录。
     * @param extensionKey String，扩展稳定键
     * @return WfBpmnExtension，不存在时返回 null
     */
    WfBpmnExtension selectByKey(@Param("extensionKey") String extensionKey);

    /**
     * 按稳定键锁定扩展目录，部署与启停不能越过彼此的当前状态。
     * @param extensionKey String，扩展稳定键
     * @return WfBpmnExtension，锁定后的目录；不存在时返回 null
     */
    WfBpmnExtension selectByKeyForUpdate(@Param("extensionKey") String extensionKey);

    /**
     * 按主键锁定扩展目录，串行化版本号分配和启停变更。
     * @param extensionId Long，扩展目录主键
     * @return WfBpmnExtension，锁定后的目录；不存在时返回 null
     */
    WfBpmnExtension selectByIdForUpdate(@Param("extensionId") Long extensionId);

    /**
     * 按稳定键读取已启用扩展最新版；调用方必须先锁定目录行。
     * @param extensionKey String，扩展稳定键
     * @return WorkflowExtensionOptionView，锁定后的最新版；不存在或停用时返回 null
     */
    WorkflowExtensionOptionView selectLatestEnabledByKey(
            @Param("extensionKey") String extensionKey);

    /**
     * 查询扩展当前最大版本号。
     * @param extensionId Long，扩展目录主键
     * @return Integer，尚无版本时返回 0
     */
    Integer selectMaxVersionNo(@Param("extensionId") Long extensionId);

    /**
     * 新增扩展目录。
     * @param extension WfBpmnExtension，已校验目录数据
     * @return int，实际写入行数
     */
    int insertExtension(@Param("extension") WfBpmnExtension extension);

    /**
     * 新增不可变扩展版本。
     * @param version WfBpmnExtensionVersion，包含连续版本号和服务端校验和的版本
     * @return int，实际写入行数
     */
    int insertVersion(@Param("version") WfBpmnExtensionVersion version);

    /**
     * 修改目录启停状态；历史版本和部署快照均不变。
     * @param extensionId Long，扩展目录主键
     * @param status String，ENABLED 或 DISABLED
     * @param updateBy String，正式操作人用户主键
     * @return int，实际更新行数
     */
    int updateStatus(@Param("extensionId") Long extensionId,
            @Param("status") String status, @Param("updateBy") String updateBy);

    /**
     * 统计目录下全部版本被部署快照引用的数量。
     * @param extensionId Long，扩展目录主键
     * @return int，部署快照引用数量
     */
    int countDeploymentSnapshots(@Param("extensionId") Long extensionId);

    /**
     * 删除未被部署快照引用的目录版本；调用方必须先锁定并完成引用校验。
     * @param extensionId Long，扩展目录主键
     * @return int，删除的版本数量
     */
    int deleteVersions(@Param("extensionId") Long extensionId);

    /**
     * 删除已停用且无部署引用的扩展目录。
     * @param extensionId Long，扩展目录主键
     * @return int，删除的目录数量
     */
    int deleteExtension(@Param("extensionId") Long extensionId);
}
