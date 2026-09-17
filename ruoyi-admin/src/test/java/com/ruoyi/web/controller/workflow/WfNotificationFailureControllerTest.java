package com.ruoyi.web.controller.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.service.notification.*;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实通知 Controller/配置服务到既有 MVC 安全错误边界；故障注入点为身份目录或 JDBC。 */
class WfNotificationFailureControllerTest
{
    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @ValueSource(strings = { "identity", "preference", "policy", "mail", "unauthenticated" })
    void systemFailuresDoNotMasqueradeAsLoginOrRevisionErrors(String mode) throws Exception
    {
        var jdbc = mock(JdbcTemplate.class);
        var mapper = mock(WorkflowIdentityMapper.class);
        when(mapper.selectActiveUserIdsByUserIds(List.of(42L))).thenReturn(List.of(42L));
        var identities = new WorkflowIdentityResolver(mapper, new WorkflowIdentityCodec());
        var failure = new DataAccessResourceFailureException("SELECT credential-sentinel FROM private-sql-table");
        if (mode.equals("identity")) when(mapper.selectActiveUserIdsByUserIds(anyList())).thenThrow(failure);
        if (mode.equals("preference")) when(jdbc.update(startsWith("insert into wf_notification_preference"), any(Object[].class))).thenThrow(failure);
        if (mode.equals("policy")) when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenThrow(failure);
        if (mode.equals("mail")) when(jdbc.query(contains("from sys_mail_config"), any(RowMapper.class))).thenThrow(failure);
        var mail = new WorkflowMailConfigService(jdbc, mock(WorkflowMailCredentialCipher.class), new WorkflowMailFailureClassifier(), identities);
        var catalog = mock(WorkflowNotificationCatalogService.class);
        var policies = new WorkflowNotificationPolicyService(jdbc, identities, catalog, mail);
        var controller = new WfNotificationController(null, policies, null, null, null, mail, catalog);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(
                new WorkflowNotificationExceptionHandler(), new GlobalExceptionHandler()).build();
        if (!mode.equals("unauthenticated"))
        {
            var user = new LoginUser(); user.setUserId(42L);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        }
        var request = switch (mode)
        {
            case "preference" -> put("/workflow/notification/preference").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"inboxEnabled\":true,\"emailEnabled\":true,\"smsEnabled\":false,\"expectedRevision\":0}");
            case "policy" -> put("/workflow/notification/policies").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"scopeType\":\"DEFAULT\",\"eventType\":\"COPY_CREATED\",\"recipientRules\":\"TASK_RECIPIENT\",\"channels\":\"INBOX\",\"titleTemplate\":\"Title\",\"contentTemplate\":\"Body\",\"maxAttempts\":3,\"status\":\"ENABLED\"}");
            case "mail" -> get("/workflow/notification/mail-config");
            default -> get("/workflow/notification/preference");
        };
        var result = mvc.perform(request).andExpect(jsonPath("$.code").value(mode.equals("unauthenticated") ? 401 : 500));
        if (mode.equals("mail")) result.andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.subCode").value("MAIL_CONFIG_PERSISTENCE_FAILED"));
        else if (mode.equals("unauthenticated")) result.andExpect(status().isUnauthorized());
        else result.andExpect(jsonPath("$.errorId").isNotEmpty());
        assertThat(result.andReturn().getResponse().getContentAsString()).doesNotContain("credential-sentinel", "private-sql-table", "SELECT");
    }
}
