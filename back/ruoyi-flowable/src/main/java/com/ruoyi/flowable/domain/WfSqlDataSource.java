package com.ruoyi.flowable.domain;

import java.time.LocalDateTime;

/**
 * SQL 连接器受控数据源目录实体。
 */
public class WfSqlDataSource
{
    private Long dataSourceId;
    private String dataSourceKey;
    private String dataSourceName;
    private String connectionType;
    private String jdbcUrlRef;
    private String usernameRef;
    private String passwordRef;
    private String allowedTables;
    private Integer connectTimeoutMs;
    private Integer queryTimeoutSeconds;
    private Integer revisionNo;
    private String status;
    private String checksum;
    private String createBy;
    private LocalDateTime createTime;
    private String updateBy;
    private LocalDateTime updateTime;

    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long dataSourceId) { this.dataSourceId = dataSourceId; }
    public String getDataSourceKey() { return dataSourceKey; }
    public void setDataSourceKey(String dataSourceKey) { this.dataSourceKey = dataSourceKey; }
    public String getDataSourceName() { return dataSourceName; }
    public void setDataSourceName(String dataSourceName) { this.dataSourceName = dataSourceName; }
    public String getConnectionType() { return connectionType; }
    public void setConnectionType(String connectionType) { this.connectionType = connectionType; }
    public String getJdbcUrlRef() { return jdbcUrlRef; }
    public void setJdbcUrlRef(String jdbcUrlRef) { this.jdbcUrlRef = jdbcUrlRef; }
    public String getUsernameRef() { return usernameRef; }
    public void setUsernameRef(String usernameRef) { this.usernameRef = usernameRef; }
    public String getPasswordRef() { return passwordRef; }
    public void setPasswordRef(String passwordRef) { this.passwordRef = passwordRef; }
    public String getAllowedTables() { return allowedTables; }
    public void setAllowedTables(String allowedTables) { this.allowedTables = allowedTables; }
    public Integer getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(Integer connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public Integer getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
    public void setQueryTimeoutSeconds(Integer queryTimeoutSeconds) { this.queryTimeoutSeconds = queryTimeoutSeconds; }
    public Integer getRevisionNo() { return revisionNo; }
    public void setRevisionNo(Integer revisionNo) { this.revisionNo = revisionNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
