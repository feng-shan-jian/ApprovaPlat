package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfConnectorEndpoint;
import com.ruoyi.flowable.domain.vo.WorkflowConnectorEndpointView;

/**
 * HTTP 连接器端点白名单数据访问层。
 */
public interface WfConnectorEndpointMapper
{
    /**
     * 查询全部端点供管理页使用。
     * @return List&lt;WorkflowConnectorEndpointView&gt;，按名称和稳定键排序
     */
    List<WorkflowConnectorEndpointView> selectList();

    /**
     * 查询全部已启用端点供设计器选择。
     * @return List&lt;WorkflowConnectorEndpointView&gt;，不包含明文密钥
     */
    List<WorkflowConnectorEndpointView> selectEnabledOptions();

    /**
     * 按稳定键锁定已启用端点，部署冻结与修订不能并发穿透。
     * @param endpointKey String，稳定端点键
     * @return WfConnectorEndpoint，端点不存在或停用时返回 null
     */
    WfConnectorEndpoint selectEnabledByKeyForUpdate(@Param("endpointKey") String endpointKey);

    /**
     * 按主键锁定端点，串行化修订和启停。
     * @param endpointId Long，端点主键
     * @return WfConnectorEndpoint，不存在时返回 null
     */
    WfConnectorEndpoint selectByIdForUpdate(@Param("endpointId") Long endpointId);

    /**
     * 新增端点白名单。
     * @param endpoint WfConnectorEndpoint，已规范化端点
     * @return int，写入行数
     */
    int insert(@Param("endpoint") WfConnectorEndpoint endpoint);

    /**
     * 以当前修订号作为乐观锁发布下一修订。
     * @param endpoint WfConnectorEndpoint，新修订配置
     * @param expectedRevision Integer，数据库当前修订号
     * @return int，更新行数
     */
    int updateRevision(@Param("endpoint") WfConnectorEndpoint endpoint,
            @Param("expectedRevision") Integer expectedRevision);

    /**
     * 修改端点状态，不影响已冻结部署快照。
     * @param endpointId Long，端点主键
     * @param status String，目标状态
     * @param updateBy String，操作人主键
     * @return int，更新行数
     */
    int updateStatus(@Param("endpointId") Long endpointId, @Param("status") String status,
            @Param("updateBy") String updateBy);
}
