package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DictUtils;
import com.ruoyi.system.mapper.SysDictDataMapper;
import com.ruoyi.system.mapper.SysDictTypeMapper;
import com.ruoyi.system.service.ISysDictDataService;

/**
 * 字典 业务层处理
 * 
 * @author ruoyi
 */
@Service
public class SysDictDataServiceImpl implements ISysDictDataService
{
    @Autowired
    private SysDictDataMapper dictDataMapper;

    @Autowired
    private SysDictTypeMapper dictTypeMapper;

    /**
     * 根据条件分页查询字典数据
     * 
     * @param dictData 字典数据信息
     * @return 字典数据集合信息
     */
    @Override
    public List<SysDictData> selectDictDataList(SysDictData dictData)
    {
        return dictDataMapper.selectDictDataList(dictData);
    }

    /**
     * 根据字典数据ID查询信息
     * 
     * @param dictCode 字典数据ID
     * @return 字典数据
     */
    @Override
    public SysDictData selectDictDataById(Long dictCode)
    {
        return dictDataMapper.selectDictDataById(dictCode);
    }

    /**
     * 批量删除字典数据信息
     * 
     * @param dictCodes 需要删除的字典数据ID
     */
    @Override
    public void deleteDictDataByIds(Long[] dictCodes)
    {
        for (Long dictCode : dictCodes)
        {
            SysDictData data = selectDictDataById(dictCode);
            dictDataMapper.deleteDictDataById(dictCode);
            List<SysDictData> dictDatas = dictDataMapper.selectDictDataByType(data.getDictType());
            DictUtils.setDictCache(data.getDictType(), dictDatas);
        }
    }

    /**
     * 新增保存字典数据信息
     * 
     * @param data 字典数据信息
     * @return 结果
     */
    @Override
    @Transactional
    public int insertDictData(SysDictData data)
    {
        if (dictTypeMapper.selectDictTypeForUpdate(data.getDictType()) == null)
        {
            throw new ServiceException("字典类型不存在或已删除");
        }
        int row = dictDataMapper.insertDictData(data);
        if (row > 0)
        {
            removeDictCacheAfterCommit(List.of(data.getDictType()));
        }
        return row;
    }

    /**
     * 修改保存字典数据信息
     * 
     * @param data 字典数据信息
     * @return 结果
     */
    @Override
    @Transactional
    public int updateDictData(SysDictData data)
    {
        if (dictTypeMapper.selectDictTypeForUpdate(data.getDictType()) == null)
        {
            throw new ServiceException("字典类型不存在或已删除");
        }
        SysDictData oldData = dictDataMapper.selectDictDataByIdForUpdate(data.getDictCode());
        int row = dictDataMapper.updateDictData(data);
        if (row > 0)
        {
            removeDictCacheAfterCommit(List.of(oldData.getDictType(), data.getDictType()));
        }
        return row;
    }

    private void removeDictCacheAfterCommit(List<String> dictTypes)
    {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
        {
            @Override
            public void afterCommit()
            {
                DictUtils.removeDictCache(dictTypes);
            }
        });
    }
}
