package com.ruoyi.flowable.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCategory;
import com.ruoyi.flowable.domain.WfForm;
import com.ruoyi.flowable.mapper.WfCategoryMapper;
import com.ruoyi.flowable.mapper.WfFormMapper;
import com.ruoyi.flowable.service.impl.WfCategoryServiceImpl;
import com.ruoyi.flowable.service.impl.WfFormServiceImpl;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

class WorkflowAuthorRulesTest
{
    private final WorkflowFormTemplateValidator validator = new WorkflowFormTemplateValidator();

    @Test
    void categoryCodeCannotChangeInServiceOrMapperWhileOtherFieldsRemainEditable()
    {
        var ds = WorkflowH2SchemaMapperSupport.createDataSource("category_author_rules", true, 5000);
        try
        {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("create table wf_category (category_id bigint auto_increment primary key, "
                    + "category_name varchar(64), code varchar(64) unique, create_by varchar(64), "
                    + "create_time timestamp, update_by varchar(64), update_time timestamp, remark varchar(500), del_flag char(1))");
            var mapper = WorkflowH2SchemaMapperSupport.createSpringMapper(ds, "category", WfCategoryMapper.class,
                    "mapper/flowable/WfCategoryMapper.xml");
            var references = mock(WorkflowReferenceChecker.class);
            var service = new WfCategoryServiceImpl(mapper, references);
            var category = new WfCategory();
            category.setCategoryName("Original");
            category.setCode("original_code");
            assertEquals(1, service.insertCategory(category));
            category.setCode("new_code");
            assertEquals(409, assertThrows(ServiceException.class, () -> service.updateCategory(category)).getCode());
            assertEquals("original_code", mapper.selectById(category.getCategoryId()).getCode());
            mapper.update(category);
            assertEquals("original_code", mapper.selectById(category.getCategoryId()).getCode());
            category.setCode("original_code");
            category.setCategoryName("Renamed");
            category.setRemark("remark");
            assertEquals(1, service.updateCategory(category));
            assertEquals("Renamed", mapper.selectById(category.getCategoryId()).getCategoryName());
            when(references.hasCategoryReference("original_code")).thenReturn(true);
            assertEquals(409, assertThrows(ServiceException.class,
                    () -> service.deleteWithValidByIds(List.of(category.getCategoryId()), "test")).getCode());
            category.setCategoryId(99999L);
            assertEquals(404, assertThrows(ServiceException.class, () -> service.updateCategory(category)).getCode());
            assertEquals(409, assertThrows(ServiceException.class, () -> service.insertCategory(category)).getCode());
        }
        finally { WorkflowH2SchemaMapperSupport.shutdown(ds); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"initiator\"", "\"processStatus\"", "\"_wfMiMembers_review\"", "\"constructor\"",
            "\" hasSpace\"", "\"trailing \"", "\"a-b\"", "42", "null", "\"\""})
    void rejectsInvalidAuthorVariablesBeforePersistence(String variable)
    {
        var mapper = mock(WfFormMapper.class);
        when(mapper.insert(any())).thenReturn(1);
        var service = new WfFormServiceImpl(mapper, mock(WorkflowReferenceChecker.class), validator);
        var form = form("{\"fields\":[" + field(variable) + "]}");
        assertEquals(400, assertThrows(ServiceException.class, () -> service.insertForm(form)).getCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    void rejectsNestedDuplicatesAndReportsStoredProblemsWithoutRewritingHistory()
    {
        String content = "{\"fields\":[" + field("\"amount\"") + ","
                + "{\"layout\":\"rowFormItem\",\"workflowHidden\":false,\"workflowReadable\":true,"
                + "\"workflowWritable\":true,\"children\":[" + field("\"amount\"") + "]}]}";
        var mapper = mock(WfFormMapper.class);
        when(mapper.insert(any())).thenReturn(1);
        var service = new WfFormServiceImpl(mapper, mock(WorkflowReferenceChecker.class), validator);
        var form = form(content);
        assertEquals(400, assertThrows(ServiceException.class, () -> service.insertForm(form)).getCode());
        when(mapper.selectById(7L)).thenReturn(form);
        assertFalse(service.queryById(7L).getProtocolValid());
        assertEquals(content, form.getContent());
        // 已冻结正文继续通过原结构校验与解码，不重新施加作者命名规则。
        assertDoesNotThrow(() -> validator.validate(content));
        assertDoesNotThrow(() -> WorkflowFormTemplateValidator.decode(content));
    }

    @Test
    void acceptsIndependentTemplatesAndMaximumLengthButRejectsMissingOrLongNames()
    {
        var mapper = mock(WfFormMapper.class);
        when(mapper.insert(any())).thenReturn(1);
        var service = new WfFormServiceImpl(mapper, mock(WorkflowReferenceChecker.class), validator);
        String content = "{\"fields\":[" + field("\"" + "a".repeat(128) + "\"") + "]}";
        assertEquals(1, service.insertForm(form(content)));
        assertEquals(1, service.insertForm(form(content)));
        String longContent = "{\"fields\":[" + field("\"" + "a".repeat(129) + "\"") + "]}";
        assertEquals(400, assertThrows(ServiceException.class, () -> service.insertForm(form(longContent))).getCode());
        String missing = "{\"fields\":[" + field("null").replace("\"vModel\":null,", "") + "]}";
        assertEquals(400, assertThrows(ServiceException.class, () -> service.insertForm(form(missing))).getCode());
        String button = "{\"fields\":[{\"tag\":\"el-button\",\"layout\":\"raw\","
                + "\"workflowHidden\":false,\"workflowReadable\":true,\"workflowWritable\":true}]}";
        assertEquals(1, service.insertForm(form(button)));
    }

    private static WfForm form(String content)
    {
        var form = new WfForm();
        form.setFormName("Author rules");
        form.setContent(content);
        return form;
    }

    private static String field(String name)
    {
        return "{\"tag\":\"el-input\",\"layout\":\"colFormItem\",\"vModel\":" + name
                + ",\"workflowHidden\":false,\"workflowReadable\":true,\"workflowWritable\":true}";
    }
}
