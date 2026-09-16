package com.ruoyi.system.service.impl;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import com.ruoyi.system.exception.ConfigKeyConflictException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.SysConfig;
import com.ruoyi.system.mapper.SysConfigMapper;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 参数配置 服务层实现
 * 
 * @author ruoyi
 */
@Service
public class SysConfigServiceImpl implements ISysConfigService
{
    @Autowired
    private SysConfigMapper configMapper;

    @Autowired
    private RedisCache redisCache;

    /**
     * 项目启动时，初始化参数到缓存
     */
    @PostConstruct
    public void init()
    {
        loadingConfigCache();
    }

    /**
     * 查询参数配置信息
     * 
     * @param configId 参数配置ID
     * @return 参数配置信息
     */
    @Override
    public SysConfig selectConfigById(Long configId)
    {
        SysConfig config = new SysConfig();
        config.setConfigId(configId);
        return configMapper.selectConfig(config);
    }

    /**
     * 根据键名查询参数配置信息
     * 
     * @param configKey 参数key
     * @return 参数键值
     */
    @Override
    public String selectConfigByKey(String configKey)
    {
        String configValue = Convert.toStr(redisCache.getCacheObject(getCacheKey(configKey)));
        if (StringUtils.isNotEmpty(configValue))
        {
            return configValue;
        }
        SysConfig config = new SysConfig();
        config.setConfigKey(configKey);
        SysConfig retConfig = configMapper.selectConfig(config);
        if (StringUtils.isNotNull(retConfig))
        {
            redisCache.setCacheObject(getCacheKey(configKey), retConfig.getConfigValue());
            return retConfig.getConfigValue();
        }
        return StringUtils.EMPTY;
    }

    /**
     * 获取验证码开关
     * 
     * @return true开启，false关闭
     */
    @Override
    public boolean selectCaptchaEnabled()
    {
        String captchaEnabled = selectConfigByKey("sys.account.captchaEnabled");
        if (StringUtils.isEmpty(captchaEnabled))
        {
            return true;
        }
        return Convert.toBool(captchaEnabled);
    }

    /**
     * 查询参数配置列表
     * 
     * @param config 参数配置信息
     * @return 参数配置集合
     */
    @Override
    public List<SysConfig> selectConfigList(SysConfig config)
    {
        return configMapper.selectConfigList(config);
    }

    /**
     * 新增参数配置
     * 
     * @param config 参数配置信息
     * @return 结果
     */
    @Override
    @Transactional
    public int insertConfig(SysConfig config)
    {
        int row;
        try
        {
            row = configMapper.insertConfig(config);
        }
        catch (DuplicateKeyException error)
        {
            throw ConfigKeyConflictException.translate(error);
        }
        if (row > 0)
        {
            cacheConfigAfterCommit(null, config.getConfigKey(), config.getConfigValue());
        }
        return row;
    }

    /** 修改参数；读取原键只用于提交后失效缓存，不作为唯一性证明。 */
    @Override
    @Transactional
    public int updateConfig(SysConfig config)
    {
        SysConfig previous = configMapper.selectConfigById(config.getConfigId());
        int row;
        try
        {
            row = configMapper.updateConfig(config);
        }
        catch (DuplicateKeyException error)
        {
            throw ConfigKeyConflictException.translate(error);
        }
        if (row > 0)
        {
            cacheConfigAfterCommit(previous.getConfigKey(), config.getConfigKey(), config.getConfigValue());
        }
        return row;
    }

    private void cacheConfigAfterCommit(String previousKey, String key, String value)
    {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
        {
            @Override
            public void afterCommit()
            {
                if (previousKey != null && !StringUtils.equals(previousKey, key))
                {
                    redisCache.deleteObject(getCacheKey(previousKey));
                }
                redisCache.setCacheObject(getCacheKey(key), value);
            }
        });
    }

    /**
     * 批量删除参数信息
     * 
     * @param configIds 需要删除的参数ID
     */
    @Override
    @Transactional
    public void deleteConfigByIds(Long[] configIds)
    {
        if (configIds.length == 0)
        {
            return;
        }
        List<SysConfig> configs = configMapper.selectConfigsByIdsForUpdate(configIds);
        Map<Long, SysConfig> configsById = configs.stream()
                .collect(Collectors.toMap(SysConfig::getConfigId, Function.identity()));
        // 按请求顺序报告首个不可删项，检查完成前不产生写入或缓存副作用。
        for (Long configId : configIds)
        {
            SysConfig config = configsById.get(configId);
            if (config == null)
            {
                throw new ServiceException("参数配置不存在或已删除");
            }
            if (StringUtils.equals(UserConstants.YES, config.getConfigType()))
            {
                throw new ServiceException(String.format("内置参数【%1$s】不能删除 ", config.getConfigKey()));
            }
        }
        configMapper.deleteConfigByIds(configIds);
        List<String> cacheKeys = configs.stream().map(config -> getCacheKey(config.getConfigKey())).toList();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
        {
            @Override
            public void afterCommit()
            {
                // 此时数据库已经提交；缓存异常原样上抛，由既有刷新入口恢复。
                redisCache.deleteObject(cacheKeys);
            }
        });
    }

    /**
     * 加载参数缓存数据
     */
    @Override
    public void loadingConfigCache()
    {
        List<SysConfig> configsList = configMapper.selectConfigList(new SysConfig());
        for (SysConfig config : configsList)
        {
            redisCache.setCacheObject(getCacheKey(config.getConfigKey()), config.getConfigValue());
        }
    }

    /**
     * 清空参数缓存数据
     */
    @Override
    public void clearConfigCache()
    {
        Collection<String> keys = redisCache.keys(CacheConstants.SYS_CONFIG_KEY + "*");
        redisCache.deleteObject(keys);
    }

    /**
     * 重置参数缓存数据
     */
    @Override
    public void resetConfigCache()
    {
        clearConfigCache();
        loadingConfigCache();
    }

    /**
     * 设置cache key
     * 
     * @param configKey 参数键
     * @return 缓存键key
     */
    private String getCacheKey(String configKey)
    {
        return CacheConstants.SYS_CONFIG_KEY + configKey;
    }
}
