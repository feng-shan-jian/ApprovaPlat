package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.common.core.domain.entity.SysDept;

/**
 * 部门管理 数据层
 * 
 * @author ruoyi
 */
public interface SysDeptMapper
{
    /** 所有部门关系写入先锁定最小主键记录（软删除不会移除该记录）。 */
    Long lockDeptRelations();

    List<SysDept> selectDeptsForUpdate(@Param("query") SysDept query, @Param("ids") Long[] ids);

    /** 按目标集合取得完整详情，范围 SQL 由服务切面写入。 */
    List<SysDept> selectDeptsInScope(@Param("query") SysDept query, @Param("ids") Long[] ids);

    /**
     * 查询部门管理数据
     * 
     * @param dept 部门信息
     * @return 部门信息集合
     */
    public List<SysDept> selectDeptList(SysDept dept);

    /**
     * 根据角色ID查询部门树信息
     * 
     * @param roleId 角色ID
     * @param deptCheckStrictly 部门树选择项是否关联显示
     * @return 选中部门列表
     */
    public List<Long> selectDeptListByRoleId(@Param("roleId") Long roleId, @Param("deptCheckStrictly") boolean deptCheckStrictly);

    /**
     * 根据ID查询所有子部门
     * 
     * @param deptId 部门ID
     * @return 部门列表
     */
    public List<SysDept> selectChildrenDeptById(Long deptId);

    /**
     * 当前读取得一个直接子部门，删除事务不能使用外层旧快照判断引用。
     * 
     * @param deptId 部门ID
     * @return 子部门 ID，无引用时为空
     */
    public Long selectChildDeptIdForUpdate(Long deptId);

    /**
     * 当前读取得部门的一个用户引用。
     * 
     * @param deptId 部门ID
     * @return 用户 ID，无引用时为空
     */
    public Long selectDeptUserIdForUpdate(Long deptId);

    /**
     * 校验部门名称是否唯一
     * 
     * @param deptName 部门名称
     * @param parentId 父部门ID
     * @return 结果
     */
    public SysDept checkDeptNameUnique(@Param("deptName") String deptName, @Param("parentId") Long parentId);

    /**
     * 新增部门信息
     * 
     * @param dept 部门信息
     * @return 结果
     */
    public int insertDept(SysDept dept);

    /**
     * 修改部门信息
     * 
     * @param dept 部门信息
     * @return 结果
     */
    public int updateDept(SysDept dept);

    /**
     * 修改所在部门正常状态
     * 
     * @param deptIds 部门ID组
     */
    public void updateDeptStatusNormal(Long[] deptIds);

    /**
     * 修改子元素关系
     * 
     * @param depts 子元素
     * @return 结果
     */
    public int updateDeptChildren(@Param("depts") List<SysDept> depts);

    /**
     * 保存部门排序
     *
     * @param dept 部门信息
     */
    public void updateDeptSort(SysDept dept);

    /**
     * 删除部门管理信息
     * 
     * @param deptId 部门ID
     * @return 结果
     */
    public int deleteDeptById(Long deptId);
}
