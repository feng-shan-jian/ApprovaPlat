package com.ruoyi.web.controller.workflow;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ruoyi.flowable.domain.dto.WorkflowDecisionTableRequest;
import com.ruoyi.flowable.service.model.WorkflowDecisionTableService;
import com.ruoyi.flowable.service.model.WorkflowDmnDecisionService;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class WfDecisionTableControllerTest
{
    private static final String REQUEST = """
        {"key":"precision","name":"精确数值","hitPolicy":"FIRST",
         "inputs":[{"key":"amount","label":"金额","type":"number"}],
         "outputs":[{"key":"large","label":"大整数","type":"number"},{"key":"decimal","label":"小数","type":"number"},
                    {"key":"flag","label":"布尔","type":"boolean"},{"key":"text","label":"文本","type":"string"}],
         "rules":[{"conditions":[{"operator":"EQ","value":"9999999999.12345678"}],
                   "results":["9007199254740993","9999999999.12345678",true,"9007199254740993"]}],
         "testValues":{"amount":"9999999999.12345678"}}
        """;

    @Test void decimalStringsSurviveHttpJsonInBothDirectionsWithoutChangingOtherColumns() throws Exception
    {
        var tables = mock(WorkflowDecisionTableService.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("large", new BigDecimal("9007199254740993"));
        row.put("decimal", new BigDecimal("9999999999.12345678"));
        row.put("flag", true);
        row.put("text", "9007199254740993");
        when(tables.evaluate(any())).thenAnswer(invocation -> {
            WorkflowDecisionTableRequest request = invocation.getArgument(0);
            assertThat(request.rules().get(0).conditions().get(0).value().textValue()).isEqualTo("9999999999.12345678");
            assertThat(request.rules().get(0).results().get(0).textValue()).isEqualTo("9007199254740993");
            assertThat(request.testValues().get("amount").textValue()).isEqualTo("9999999999.12345678");
            return List.of(row);
        });
        var mvc = MockMvcBuilders.standaloneSetup(new WfDmnController(mock(WorkflowDmnDecisionService.class), tables))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String body = mvc.perform(post("/workflow/dmn/table/evaluate").param("numberFormat", "decimal-string")
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andReturn().getResponse().getContentAsString();
        var result = JsonMapper.shared().readTree(body).path("data").get(0);
        assertThat(result.path("large").textValue()).isEqualTo("9007199254740993");
        assertThat(result.path("decimal").textValue()).isEqualTo("9999999999.12345678");
        assertThat(result.path("flag").booleanValue()).isTrue();
        assertThat(result.path("text").textValue()).isEqualTo("9007199254740993");
        assertThat(row.get("large")).isInstanceOf(BigDecimal.class);
        String legacy = mvc.perform(post("/workflow/dmn/table/evaluate").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andReturn().getResponse().getContentAsString();
        assertThat(legacy).contains("\"large\":9007199254740993", "\"decimal\":9999999999.12345678");
    }

    @Test void unsupportedResponseFormatIsRejectedBeforeRunningTheEngine() throws Exception
    {
        var tables = mock(WorkflowDecisionTableService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WfDmnController(mock(WorkflowDmnDecisionService.class), tables))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/workflow/dmn/table/evaluate").param("numberFormat", "float")
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(tables);
    }
}
