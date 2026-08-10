package com.ruoyi.flowable.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCategory;
import com.ruoyi.flowable.mapper.WfCategoryMapper;
import com.ruoyi.flowable.service.WorkflowReferenceChecker;

@ExtendWith(MockitoExtension.class)
class WfCategoryServiceImplTest
{
    @Mock
    private WfCategoryMapper categoryMapper;

    @Mock
    private WorkflowReferenceChecker referenceChecker;

    private WfCategoryServiceImpl service;

    /**
     * 为每个测试创建独立服务实例。
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        service = new WfCategoryServiceImpl(categoryMapper, referenceChecker);
    }

    /**
     * 验证应用层唯一性预检查会拒绝重复分类编码且不执行写入。
     * @return void，断言失败时测试失败
     */
    @Test
    void rejectsDuplicateCodeBeforeInsert()
    {
        WfCategory request = category(null, "请假流程", "leave");
        when(categoryMapper.selectByCode("leave")).thenReturn(category(9L, "已有分类", "leave"));

        assertThatThrownBy(() -> service.insertCategory(request))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    org.assertj.core.api.Assertions.assertThat(exception.getCode())
                            .isEqualTo(HttpStatus.CONFLICT);
                    org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                            .isEqualTo(WfCategoryServiceImpl.DUPLICATE_CODE_MESSAGE);
                });
        verify(categoryMapper, never()).insert(any());
    }

    /**
     * 验证并发插入触发的数据库唯一键异常会稳定转换为 409。
     * @return void，断言失败时测试失败
     */
    @Test
    void translatesDatabaseDuplicateKeyToConflict()
    {
        WfCategory request = category(null, "请假流程", "leave");
        when(categoryMapper.insert(request)).thenThrow(new DuplicateKeyException("uk_wf_category_code"));

        assertThatThrownBy(() -> service.insertCategory(request))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    org.assertj.core.api.Assertions.assertThat(exception.getCode())
                            .isEqualTo(HttpStatus.CONFLICT);
                    org.assertj.core.api.Assertions.assertThat(exception.getCause())
                            .isInstanceOf(DuplicateKeyException.class);
                });
    }

    /**
     * 验证被未部署模型或流程定义引用的分类不能逻辑删除。
     * @return void，断言失败时测试失败
     */
    @Test
    void rejectsDeletionWhenCategoryIsReferenced()
    {
        WfCategory existing = category(7L, "请假流程", "leave");
        when(categoryMapper.countActiveByIds(anyCollection())).thenReturn(1);
        when(categoryMapper.selectById(7L)).thenReturn(existing);
        when(referenceChecker.hasCategoryReference("leave")).thenReturn(true);

        assertThatThrownBy(() -> service.deleteWithValidByIds(List.of(7L), "admin"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    org.assertj.core.api.Assertions.assertThat(exception.getCode())
                            .isEqualTo(HttpStatus.CONFLICT);
                    org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                            .isEqualTo(WfCategoryServiceImpl.REFERENCED_MESSAGE);
                });
        verify(categoryMapper, never()).logicalDelete(anyCollection(), any());
    }

    /**
     * 验证批量主键存在性不完整时返回 404 且不执行引用检查和删除。
     * @return void，断言失败时测试失败
     */
    @Test
    void rejectsDeletionWhenAnyCategoryIsMissing()
    {
        when(categoryMapper.countActiveByIds(anyCollection())).thenReturn(1);

        assertThatThrownBy(() -> service.deleteWithValidByIds(List.of(7L, 8L), "admin"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo(HttpStatus.NOT_FOUND));
        verify(referenceChecker, never()).hasCategoryReference(any());
        verify(categoryMapper, never()).logicalDelete(anyCollection(), any());
    }

    /**
     * 验证超大批量删除请求会在访问数据库和引擎前被拒绝。
     * @return void，断言失败时测试失败
     */
    @Test
    void rejectsOversizedDeleteBatchBeforeDataAccess()
    {
        List<Long> oversizedIds = Collections.nCopies(1001, 7L);

        assertThatThrownBy(() -> service.deleteWithValidByIds(oversizedIds, "admin"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    org.assertj.core.api.Assertions.assertThat(exception.getCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST);
                    org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                            .isEqualTo("单次最多删除1000个流程分类");
                });
        verify(categoryMapper, never()).countActiveByIds(anyCollection());
        verify(referenceChecker, never()).hasCategoryReference(any());
    }

    /**
     * 验证引用检查后发生并发状态变化时，以实际删除数量门禁返回 409。
     * @return void，断言失败时测试失败
     */
    @Test
    void detectsConcurrentLogicalDeleteCountMismatch()
    {
        when(categoryMapper.countActiveByIds(anyCollection())).thenReturn(2);
        when(categoryMapper.selectById(7L)).thenReturn(category(7L, "请假流程", "leave"));
        when(categoryMapper.selectById(8L)).thenReturn(category(8L, "报销流程", "expense"));
        when(categoryMapper.logicalDelete(anyCollection(), eq("admin"))).thenReturn(1);

        assertThatThrownBy(() -> service.deleteWithValidByIds(List.of(7L, 8L), "admin"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    org.assertj.core.api.Assertions.assertThat(exception.getCode())
                            .isEqualTo(HttpStatus.CONFLICT);
                    org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                            .isEqualTo(WfCategoryServiceImpl.CONCURRENT_CHANGE_MESSAGE);
                });
    }

    /**
     * 创建测试分类对象。
     * @param categoryId Long，分类主键，可为空
     * @param categoryName String，分类名称
     * @param code String，分类编码
     * @return WfCategory，填充基础字段的测试对象
     */
    private WfCategory category(Long categoryId, String categoryName, String code)
    {
        WfCategory category = new WfCategory();
        category.setCategoryId(categoryId);
        category.setCategoryName(categoryName);
        category.setCode(code);
        return category;
    }
}
