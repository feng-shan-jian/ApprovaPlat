package com.ruoyi.flowable.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfIntegrationCredential;

/**
 * 集成账号、哈希 Token 和原子限流 Mapper。
 */
public interface WfIntegrationCredentialMapper
{
    /** @return List，脱敏管理清单。 */
    List<WfIntegrationCredential> selectList();

    /** @param credentialId Long，凭据主键；@return WfIntegrationCredential，锁定行或 null。 */
    WfIntegrationCredential selectByIdForUpdate(@Param("credentialId") Long credentialId);

    /** @param tokenPrefix String，Token 前 12 位；@return WfIntegrationCredential，锁定行或 null。 */
    WfIntegrationCredential selectByPrefixForUpdate(@Param("tokenPrefix") String tokenPrefix);

    /** @param credential WfIntegrationCredential，完整新增实体；@return int，影响行数。 */
    int insert(WfIntegrationCredential credential);

    /**
     * 原子保存新 Token 摘要并递增修订号，旧摘要立即失效。
     * @param credential WfIntegrationCredential，新 Token 和审计信息
     * @param expectedRevision Integer，行锁读取的旧修订号
     * @return int，影响行数
     */
    int rotate(@Param("item") WfIntegrationCredential credential,
            @Param("expectedRevision") Integer expectedRevision);

    /**
     * 吊销仍处于有效状态的集成账号。
     * @param credentialId Long，凭据主键
     * @param updateBy String，当前管理用户主键
     * @return int，影响行数
     */
    int revoke(@Param("credentialId") Long credentialId,
            @Param("updateBy") String updateBy);

    /**
     * 在已锁定行上提交新的限流窗口状态。
     * @param credentialId Long，凭据主键
     * @param windowStart Date，窗口起点
     * @param windowCount int，消费后的次数
     * @param lastUsedAt Date，本次认证时间
     * @return int，影响行数
     */
    int updateRateWindow(@Param("credentialId") Long credentialId,
            @Param("windowStart") Date windowStart, @Param("windowCount") int windowCount,
            @Param("lastUsedAt") Date lastUsedAt);
}
