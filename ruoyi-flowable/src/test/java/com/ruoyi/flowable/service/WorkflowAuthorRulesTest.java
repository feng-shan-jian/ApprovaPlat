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

}
