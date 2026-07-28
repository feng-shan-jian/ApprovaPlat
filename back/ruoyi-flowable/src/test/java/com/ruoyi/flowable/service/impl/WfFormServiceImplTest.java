package com.ruoyi.flowable.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfForm;
import com.ruoyi.flowable.mapper.WfFormMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.WorkflowReferenceChecker;

@ExtendWith(MockitoExtension.class)
class WfFormServiceImplTest
{
    @Mock
    private WfFormMapper formMapper;

    @Mock
    private WorkflowReferenceChecker referenceChecker;

    private WfFormServiceImpl service;

    /**
     * 为每个测试创建独立服务实例。
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        service = new WfFormServiceImpl(formMapper, referenceChecker,
                new WorkflowFormTemplateValidator());
    }

    /**
     * 验证含 fields 数组的旧生成器根对象可通过校验并持久化。
     * @param content String，合法表单 JSON
     * @return void，断言失败时测试失败
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"fields\":[]}",
            "{\"fields\":[{\"__config__\":{\"layout\":\"colFormItem\",\"tag\":\"el-input\"}}]}" })
    void acceptsGeneratorRootObject(String content)
    {
        WfForm form = form(null, content);
        when(formMapper.insert(form)).thenReturn(1);

        assertThat(service.insertForm(form)).isEqualTo(1);
    }

    /**
     * 验证非法 JSON、标量、数组根、缺失 fields 和尾随第二根节点均被拒绝。
     * @param content String，非法或不允许的表单内容
     * @return void，断言失败时测试失败
     */
    @ParameterizedTest
    @ValueSource(strings = { "not-json", "null", "1", "true", "\"text\"", "[]", "{}", "{} []" })
    void rejectsInvalidOrScalarJson(String content)
    {
        WfForm form = form(null, content);

        assertThatThrownBy(() -> service.insertForm(form))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isNotBlank();
                });
        verify(formMapper, never()).insert(any());
    }

    /**
     * 验证按 UTF-8 字节计数超过 1 MiB 的合法对象也会在解析前被拒绝。
     * @return void，断言失败时测试失败
     */
    @Test
    void rejectsContentLargerThanOneMiB()
    {
        String content = "{\"fields\":[],\"value\":\""
                + "中".repeat(WorkflowFormTemplateValidator.MAX_CONTENT_BYTES) + "\"}";

        assertThatThrownBy(() -> service.insertForm(form(null, content)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("表单内容不能超过1 MiB");
                });
        verify(formMapper, never()).insert(any());
    }

    /**
     * 验证快照、模型或流程定义存在任一引用时拒绝逻辑删除。
     * @return void，断言失败时测试失败
     */
    @Test
    void rejectsDeletionWhenAnyReferenceExists()
    {
        when(formMapper.countActiveByIds(anyCollection())).thenReturn(1);
        when(referenceChecker.hasFormReference(anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.deleteWithValidByIds(List.of(7L), "admin"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).isEqualTo(WfFormServiceImpl.REFERENCED_MESSAGE);
                });
        verify(formMapper, never()).logicalDelete(anyCollection(), any());
    }

    /**
     * 验证待删除主键缺失时返回 404 且不会开始引用检查。
     * @return void，断言失败时测试失败
     */
    @Test
    void rejectsDeletionWhenAnyFormIsMissing()
    {
        when(formMapper.countActiveByIds(anyCollection())).thenReturn(1);

        assertThatThrownBy(() -> service.deleteWithValidByIds(List.of(7L, 8L), "admin"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(referenceChecker, never()).hasFormReference(anyCollection());
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
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("单次最多删除1000个流程表单");
                });
        verify(formMapper, never()).countActiveByIds(anyCollection());
        verify(referenceChecker, never()).hasFormReference(anyCollection());
    }

    /**
     * 验证引用检查后实际逻辑删除数量不一致时返回并发冲突。
     * @return void，断言失败时测试失败
     */
    @Test
    void detectsConcurrentLogicalDeleteCountMismatch()
    {
        when(formMapper.countActiveByIds(anyCollection())).thenReturn(2);
        when(formMapper.logicalDelete(anyCollection(), any())).thenReturn(1);

        assertThatThrownBy(() -> service.deleteWithValidByIds(List.of(7L, 8L), "admin"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).isEqualTo(WfFormServiceImpl.CONCURRENT_CHANGE_MESSAGE);
                });
    }

    /**
     * 创建测试表单对象。
     * @param formId Long，表单主键，可为空
     * @param content String，表单 JSON 内容
     * @return WfForm，填充基础字段的测试对象
     */
    private WfForm form(Long formId, String content)
    {
        WfForm form = new WfForm();
        form.setFormId(formId);
        form.setFormName("请假申请表");
        form.setContent(content);
        return form;
    }
}
