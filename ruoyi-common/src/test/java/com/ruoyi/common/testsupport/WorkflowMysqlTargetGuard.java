package com.ruoyi.common.testsupport;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Fails closed before any MySQL fixture or engine can write to the shared development database. */
public final class WorkflowMysqlTargetGuard
{
    private WorkflowMysqlTargetGuard() { }

    public static void verify(String url)
    {
        try
        {
            Path repository = Path.of("").toAbsolutePath();
            while (repository != null && !Files.isRegularFile(repository.resolve("bin/verification-common.ps1")))
            {
                repository = repository.getParent();
            }
            require(repository != null, "Run the fixture from this repository");
            Map<String, String> target = validate(url, System.getenv(), repository, Instant.now());
            String username = System.getenv("WORKFLOW_MYSQL_TEST_USERNAME");
            String password = System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD");
            require(username != null && !username.isBlank() && password != null && !password.isBlank(),
                    "MySQL fixture credentials are required in the current process");
            try (Connection connection = DriverManager.getConnection(url, username, password))
            {
                verifyIdentity(connection, target);
            }
        }
        catch (IllegalStateException exception) { throw exception; }
        catch (Exception exception)
        {
            // Driver errors may contain connection details. Do not expose credentials or URL text.
            throw new IllegalStateException("MySQL target verification failed; no fixture writes are authorized");
        }
    }

    static Map<String, String> validate(String url, Map<String, String> environment, Path repository, Instant now)
            throws Exception
    {
        String statePath = environment.get("WORKFLOW_VERIFICATION_STATE_PATH");
        require(statePath != null && !statePath.isBlank(), "Register the development target first");
        Path stateFile = Path.of(statePath).toRealPath();
        Map<String, String> state = readTarget(Files.readString(stateFile));
        String runId = state.get("runId");
        require(runId != null && runId.matches("[a-f0-9]{24}"), "Invalid run identifier");
        Path root = repository.toRealPath();
        Path expected = root.resolve("output/verification-environments").resolve(runId).resolve("state.json");
        require(stateFile.equals(expected.toRealPath()) && root.equals(Path.of(state.get("repository")).toRealPath()),
                "Registration belongs to another repository");
        require(Files.readString(stateFile.resolveSibling("owner")).trim().equals(runId), "Run owner mismatch");
        require("ApprovaPlat".equals(state.get("project")) && "shared-development".equals(state.get("mode"))
                && "active".equals(state.get("status")), "Inactive or unknown development registration");
        Instant expires = Instant.parse(state.get("expiresAt"));
        require(expires.isAfter(now) && !expires.isAfter(now.plusSeconds(8 * 3600)), "Expired or invalid run registration");
        require(("approvaplat:" + runId).equals(environment.get("WORKFLOW_DEVELOPMENT_TEST_OPT_IN")),
                "Explicit current-run development test opt-in is required");
        require("127.0.0.1".equals(state.get("host")) && "13306".equals(state.get("port"))
                && "approvaplat".equals(state.get("database")), "Unapproved development target");
        require(state.get("serverUuid") != null && state.get("serverUuid").matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"),
                "Invalid registered server UUID");
        require(url != null && url.startsWith("jdbc:mysql://"), "A single MySQL JDBC target is required");
        URI uri = URI.create(url.substring(5));
        require("mysql".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == 13306
                && "/approvaplat".equals(uri.getRawPath()) && uri.getRawUserInfo() == null && uri.getRawFragment() == null,
                "Connection target differs from the registered development database");
        Set<String> allowed = Set.of("useUnicode", "characterEncoding", "useSSL", "allowPublicKeyRetrieval", "serverTimezone");
        if (uri.getRawQuery() != null)
        {
            Set<String> seen = new java.util.HashSet<>();
            for (String parameter : uri.getRawQuery().split("&", -1))
            {
                String[] pair = parameter.split("=", 2);
                require(pair.length == 2 && allowed.contains(pair[0]) && seen.add(pair[0]), "Unapproved JDBC connection option");
            }
        }
        return state;
    }

    // The owned tool writes one flat, two-space-indented line per target field. Accept only that
    // narrow format, with forward-slash paths; no general JSON parser or runtime dependency is needed.
    static Map<String, String> readTarget(String json)
    {
        Map<String, String> values = new HashMap<>();
        Set<String> fields = Set.of("project", "mode", "status", "repository", "runId", "host", "port", "database", "serverUuid", "expiresAt");
        var matcher = Pattern.compile("(?m)^  \"([A-Za-z]+)\": \"([^\"\\\\\\r\\n]*)\",?\\r?$").matcher(json);
        while (matcher.find())
        {
            if (fields.contains(matcher.group(1)))
            {
                require(values.putIfAbsent(matcher.group(1), matcher.group(2)) == null, "Duplicate registration field");
            }
        }
        require(values.keySet().equals(fields), "Incomplete or unsupported target registration");
        return values;
    }

    static void verifyIdentity(Connection connection, Map<String, String> state) throws Exception
    {
        try (var statement = connection.createStatement())
        {
            statement.setQueryTimeout(5);
            try (var result = statement.executeQuery("SELECT DATABASE(), @@server_uuid"))
            {
                require(result.next() && state.get("database").equals(result.getString(1))
                        && state.get("serverUuid").equalsIgnoreCase(result.getString(2)) && !result.next(),
                        "Connected database/server UUID does not match registration");
            }
        }
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) throw new IllegalStateException(message);
    }
}
