package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.annotation.DataScope;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.TreeSelect;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.system.mapper.SysDeptMapper;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.service.ISysDeptService;

/**
 * 部门管理 服务实现
 * 
 * @author ruoyi
 */
@Service
public class SysDeptServiceImpl implements ISysDeptService
{
    @Autowired
    private SysDeptMapper deptMapper;

    @Autowired
    private SysRoleMapper roleMapper;

    /**
     * 查询部门管理数据
     * 
     * @param dept 部门信息
     * @return 部门信息集合
     */
    @Override
    @DataScope(deptAlias = "d")
    public List<SysDept> selectDeptList(SysDept dept)
    {
        return deptMapper.selectDeptList(dept);
    }

    /**
     * 查询部门树结构信息
     * 
     * @param dept 部门信息
     * @return 部门树信息集合
     */
    @Override
    public List<TreeSelect> selectDeptTreeList(SysDept dept)
    {
        List<SysDept> depts = SpringUtils.getAopProxy(this).selectDeptList(dept);
        return buildDeptTreeSelect(depts);
    }

    /**
     * 构建前端所需要树结构
     * 
     * @param depts 部门列表
     * @return 树结构列表
     */
    @Override
    public List<SysDept> buildDeptTree(List<SysDept> depts)
    {
        List<SysDept> returnList = new ArrayList<SysDept>();
        List<Long> tempList = depts.stream().map(SysDept::getDeptId).collect(Collectors.toList());
        for (SysDept dept : depts)
        {
            // 如果是顶级节点, 遍历该父节点的所有子节点
            if (!tempList.contains(dept.getParentId()))
            {
                recursionFn(depts, dept);
                returnList.add(dept);
            }
        }
        if (returnList.isEmpty())
        {
            returnList = depts;
        }
        return returnList;
    }

    /**
     * 构建前端所需要下拉树结构
     * 
     * @param depts 部门列表
     * @return 下拉树结构列表
     */
    @Override
    public List<TreeSelect> buildDeptTreeSelect(List<SysDept> depts)
    {
        List<SysDept> deptTrees = buildDeptTree(depts);
        return deptTrees.stream().map(TreeSelect::new).collect(Collectors.toList());
    }

    /**
     * 根据角色ID查询部门树信息
     * 
     * @param roleId 角色ID
     * @return 选中部门列表
     */
    @Override
    public List<Long> selectDeptListByRoleId(Long roleId)
    {
        SysRole role = roleMapper.selectRoleById(roleId);
        return deptMapper.selectDeptListByRoleId(roleId, role.isDeptCheckStrictly());
    }

    /**
     * 根据部门ID查询信息
     * 
     * @param deptId 部门ID
     * @return 部门信息
     */
    @Override
    public SysDept selectDeptById(Long deptId)
    {
        List<SysDept> targets = SpringUtils.getAopProxy(this).selectDeptsInScope(new SysDept(), new Long[] { deptId });
        if (targets.isEmpty())
        {
            throw new ServiceException("没有权限访问部门数据！");
        }
        return targets.get(0);
    }

    /**
     * 校验部门名称是否唯一
     * 
     * @param dept 部门信息
     * @return 结果
     */
    @Override
    public boolean checkDeptNameUnique(SysDept dept)
    {
        Long deptId = StringUtils.isNull(dept.getDeptId()) ? -1L : dept.getDeptId();
        SysDept info = deptMapper.checkDeptNameUnique(dept.getDeptName(), dept.getParentId());
        if (StringUtils.isNotNull(info) && info.getDeptId().longValue() != deptId.longValue())
        {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    /** 直接装载目标集合；详情字段和范围条件在同一 SQL 中取得。 */
    @Override
    @DataScope(deptAlias = "d")
    public List<SysDept> selectDeptsInScope(SysDept query, Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return List.of();
        }
        return deptMapper.selectDeptsInScope(query, ids);
    }

    /** 当前读装载本次关系写入的目标，必须在关系锁之后调用。 */
    @DataScope(deptAlias = "d")
    public List<SysDept> selectDeptsForUpdate(SysDept query, Long[] ids)
    {
        List<SysDept> targets = deptMapper.selectDeptsForUpdate(query, ids);
        if (targets.size() != Arrays.stream(ids).distinct().count())
        {
            throw new ServiceException("没有权限访问部门数据！");
        }
        return targets;
    }

    @Override
    @Transactional
    public int insertDept(SysDept dept)
    {
        deptMapper.lockDeptRelations();
        SysDept parent = SpringUtils.getAopProxy(this).selectDeptsForUpdate(new SysDept(),
                new Long[] { dept.getParentId() }).get(0);
        if (!UserConstants.DEPT_NORMAL.equals(parent.getStatus()))
        {
            throw new ServiceException("部门停用，不允许新增");
        }
        dept.setAncestors(parent.getAncestors() + "," + parent.getDeptId());
        return deptMapper.insertDept(dept);
    }

    @Override
    @Transactional
    public int updateDept(SysDept dept)
    {
        // 部门新增、移动、删除按相同顺序先取得关系锁，避免交叉移动和漏掉新后代。
        deptMapper.lockDeptRelations();
        SysDept oldDept = SpringUtils.getAopProxy(this).selectDeptsForUpdate(new SysDept(),
                new Long[] { dept.getDeptId() }).get(0);
        Long parentId = dept.getParentId();
        String ancestors = oldDept.getAncestors();
        if (parentId != null && parentId != 0L && !parentId.equals(oldDept.getParentId()))
        {
            if (parentId.equals(oldDept.getDeptId()))
            {
                throw new ServiceException("修改部门'" + dept.getDeptName() + "'失败，上级部门不能是自己");
            }
            SysDept parent = SpringUtils.getAopProxy(this).selectDeptsForUpdate(new SysDept(),
                    new Long[] { parentId }).get(0);
            if (Arrays.asList(parent.getAncestors().split(",")).contains(oldDept.getDeptId().toString()))
            {
                throw new ServiceException("上级部门不能是自己的子部门");
            }
            ancestors = parent.getAncestors() + "," + parent.getDeptId();
        }
        // parentId=0 沿用顶级部门编辑语义，不接受外部提交的 ancestors。
        dept.setAncestors(ancestors);
        boolean moving = !ancestors.equals(oldDept.getAncestors());
        List<SysDept> children = moving || UserConstants.DEPT_DISABLE.equals(dept.getStatus())
                ? deptMapper.selectChildrenDeptById(oldDept.getDeptId()) : List.of();
        if (UserConstants.DEPT_DISABLE.equals(dept.getStatus()) && children.stream()
                .anyMatch(d -> "0".equals(d.getDelFlag()) && UserConstants.DEPT_NORMAL.equals(d.getStatus())))
        {
            throw new ServiceException("该部门包含未停用的子部门！");
        }
        if (moving && !children.isEmpty())
        {
            for (SysDept child : children)
            {
                child.setAncestors(ancestors + child.getAncestors().substring(oldDept.getAncestors().length()));
            }
            deptMapper.updateDeptChildren(children);
        }
        int rows = deptMapper.updateDept(dept);
        if (UserConstants.DEPT_NORMAL.equals(dept.getStatus()) && !"0".equals(ancestors))
        {
            deptMapper.updateDeptStatusNormal(Convert.toLongArray(ancestors));
        }
        return rows;
    }

    /**
     * 保存部门排序
     *
     * @param deptIds 部门ID数组
     * @param orderNums 排序数组
     */
    @Override
    @Transactional
    public void updateDeptSort(String[] deptIds, String[] orderNums)
    {
        Long[] ids = Arrays.stream(deptIds).map(Convert::toLong).toArray(Long[]::new);
        List<SysDept> targets = SpringUtils.getAopProxy(this).selectDeptsInScope(new SysDept(), ids);
        if (targets.size() != Arrays.stream(ids).distinct().count())
        {
            throw new ServiceException("没有权限访问部门数据！");
        }
        Map<Long, SysDept> byId = targets.stream().collect(Collectors.toMap(SysDept::getDeptId, d -> d));
        for (int i = 0; i < ids.length; i++)
        {
            SysDept dept = byId.get(ids[i]);
            dept.setOrderNum(Convert.toInt(orderNums[i]));
            deptMapper.updateDeptSort(dept);
        }
    }

    /**
     * 删除部门管理信息
     * 
     * @param deptId 部门ID
     * @return 结果
     */
    @Override
    @Transactional
    public int deleteDeptById(Long deptId)
    {
        deptMapper.lockDeptRelations();
        SysDept target = SpringUtils.getAopProxy(this).selectDeptsForUpdate(new SysDept(), new Long[] { deptId }).get(0);
        if (deptMapper.selectChildDeptIdForUpdate(target.getDeptId()) != null)
        {
            throw new ServiceException("存在下级部门,不允许删除", HttpStatus.WARN);
        }
        if (deptMapper.selectDeptUserIdForUpdate(target.getDeptId()) != null)
        {
            throw new ServiceException("部门存在用户,不允许删除", HttpStatus.WARN);
        }
        return deptMapper.deleteDeptById(target.getDeptId());
    }

    /**
     * 递归列表
     */
    private void recursionFn(List<SysDept> list, SysDept t)
    {
        // 得到子节点列表
        List<SysDept> childList = getChildList(list, t);
        t.setChildren(childList);
        for (SysDept tChild : childList)
        {
            if (hasChild(list, tChild))
            {
                recursionFn(list, tChild);
            }
        }
    }

    /**
     * 得到子节点列表
     */
    private List<SysDept> getChildList(List<SysDept> list, SysDept t)
    {
        List<SysDept> tlist = new ArrayList<SysDept>();
        Iterator<SysDept> it = list.iterator();
        while (it.hasNext())
        {
            SysDept n = (SysDept) it.next();
            if (StringUtils.isNotNull(n.getParentId()) && n.getParentId().longValue() == t.getDeptId().longValue())
            {
                tlist.add(n);
            }
        }
        return tlist;
    }

    /**
     * 判断是否有子节点
     */
    private boolean hasChild(List<SysDept> list, SysDept t)
    {
        return getChildList(list, t).size() > 0;
    }
}
