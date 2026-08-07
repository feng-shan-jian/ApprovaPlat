package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfSqlDataSource;

/**
 * SQL 连接器受控数据源目录 Mapper。
 */
public interface WfSqlDataSourceMapper
{
    List<WfSqlDataSource> selectList();
    List<WfSqlDataSource> selectEnabledOptions();
    WfSqlDataSource selectByIdForUpdate(@Param("dataSourceId") Long dataSourceId);
    WfSqlDataSource selectEnabledByKeyForUpdate(@Param("dataSourceKey") String dataSourceKey);
    int insert(WfSqlDataSource dataSource);
    int updateRevision(@Param("item") WfSqlDataSource dataSource,
            @Param("expectedRevision") Integer expectedRevision);
    int updateStatus(@Param("dataSourceId") Long dataSourceId,
            @Param("status") String status, @Param("updateBy") String updateBy);
}
