package com.ruoyi.common.testsupport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

class WorkflowMysqlTargetGuardTest
{
    @TempDir Path repository;
    private final String run = "0123456789abcdef01234567";
    private final String uuid = "00000000-1111-2222-3333-444444444444";
    private final Instant now = Instant.parse("2026-10-12T12:00:00Z");
    private Path stateFile;
    private Map<String, String> environment;
    private String state;

    @BeforeEach void prepare() throws Exception
    {
        stateFile = repository.resolve("output/verification-environments/" + run + "/state.json");
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile.resolveSibling("owner"), run);
        state = "{\n  \"project\": \"ApprovaPlat\",\n  \"mode\": \"shared-development\",\n  \"status\": \"active\",\n"
                + "  \"repository\": \"" + repository.toRealPath().toString().replace('\\', '/') + "\",\n"
                + "  \"runId\": \"" + run + "\",\n  \"host\": \"127.0.0.1\",\n  \"port\": \"13306\",\n"
                + "  \"database\": \"approvaplat\",\n  \"serverUuid\": \"" + uuid + "\",\n"
                + "  \"expiresAt\": \"2026-10-12T13:00:00Z\"\n}\n";
        Files.writeString(stateFile, state);
        environment = new HashMap<>(Map.of("WORKFLOW_VERIFICATION_STATE_PATH", stateFile.toString(),
                "WORKFLOW_DEVELOPMENT_TEST_OPT_IN", "approvaplat:" + run));
    }

    @Test void acceptsRegisteredExactTarget() throws Exception
    {
        assertThat(validate("jdbc:mysql://127.0.0.1:13306/approvaplat?serverTimezone=Asia%2FShanghai"))
                .containsEntry("serverUuid", uuid);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:mysql://127.0.0.1:3306/approvaplat", "jdbc:mysql://localhost:13306/approvaplat",
            "jdbc:mysql://127.0.0.1:13306/approvaplat_it", "jdbc:mysql://127.0.0.1:13306/%61pprovaplat",
            "jdbc:mysql://user@127.0.0.1:13306/approvaplat", "jdbc:mysql://127.0.0.1:13306/approvaplat#other",
            "jdbc:mysql://127.0.0.1:13306/approvaplat?sessionVariables=sql_mode='ANSI'",
            "jdbc:mysql:loadbalance://127.0.0.1:13306/approvaplat",
            "jdbc:mysql://127.0.0.1:13306/approvaplat?useSSL=false&useSSL=true"})
    void rejectsOtherTargetsAndConnectionOverrides(String url)
    {
        assertThatThrownBy(() -> validate(url)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"", "true", "approvaplat:another-run"})
    void rejectsMissingOrOtherRunOptIn(String optIn)
    {
        environment.put("WORKFLOW_DEVELOPMENT_TEST_OPT_IN", optIn);
        assertThatThrownBy(() -> validate("jdbc:mysql://127.0.0.1:13306/approvaplat"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("opt-in");
    }

    @ParameterizedTest @ValueSource(strings = {"2026-10-12T11:59:59Z", "2026-10-13T13:00:00Z"})
    void rejectsStaleOrUnboundedRegistration(String expiry) throws Exception
    {
        Files.writeString(stateFile, state.replace("2026-10-12T13:00:00Z", expiry));
        assertThatThrownBy(() -> validate("jdbc:mysql://127.0.0.1:13306/approvaplat"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("registration");
    }

    @Test void rejectsMissingRegistrationAndWrongRepository() throws Exception
    {
        environment.remove("WORKFLOW_VERIFICATION_STATE_PATH");
        assertThatThrownBy(() -> validate("jdbc:mysql://127.0.0.1:13306/approvaplat")).isInstanceOf(IllegalStateException.class);
        environment.put("WORKFLOW_VERIFICATION_STATE_PATH", stateFile.toString());
        Files.writeString(stateFile, state.replace("\"ApprovaPlat\"", "\"OtherProject\""));
        assertThatThrownBy(() -> validate("jdbc:mysql://127.0.0.1:13306/approvaplat")).isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsDuplicateFieldsAndWrongOwner() throws Exception
    {
        assertThatThrownBy(() -> WorkflowMysqlTargetGuard.readTarget(state.replace("{\n", "{\n  \"mode\": \"shared-development\",\n")))
                .isInstanceOf(IllegalStateException.class);
        Files.writeString(stateFile.resolveSibling("owner"), "another-run");
        assertThatThrownBy(() -> validate("jdbc:mysql://127.0.0.1:13306/approvaplat")).hasMessageContaining("owner");
    }

    @Test void checksLiveIdentityUsingOnlySelect() throws Exception
    {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT DATABASE(), @@server_uuid")).thenReturn(result);
        when(result.next()).thenReturn(true, false, true, false);
        when(result.getString(1)).thenReturn("approvaplat");
        when(result.getString(2)).thenReturn(uuid, "another-server");
        WorkflowMysqlTargetGuard.verifyIdentity(connection, Map.of("database", "approvaplat", "serverUuid", uuid));
        assertThatThrownBy(() -> WorkflowMysqlTargetGuard.verifyIdentity(connection, Map.of("database", "approvaplat", "serverUuid", uuid)))
                .hasMessageContaining("UUID");
        verify(statement, times(2)).executeQuery("SELECT DATABASE(), @@server_uuid");
        verify(statement, never()).executeUpdate(anyString());
    }

    private Map<String, String> validate(String url) throws Exception
    {
        return WorkflowMysqlTargetGuard.validate(url, environment, repository, now);
    }
}
