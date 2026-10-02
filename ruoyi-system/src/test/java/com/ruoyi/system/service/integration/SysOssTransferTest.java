package com.ruoyi.system.service.integration;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises the production transfer/ledger path with a controlled HTTP transport. */
class SysOssTransferTest
{
    private static final byte[] CONTENT = "synthetic upload bytes".getBytes(StandardCharsets.UTF_8);
    private JdbcTemplate jdbc;
    private SysOssService service;
    private HttpClient client;
    private final List<HttpRequest> requests = new ArrayList<>();
    private List<Path> initialTempFiles;
    private int putStatus;
    private boolean failLedger;

    @BeforeEach
    void setUp() throws Exception
    {
        var source = new DriverManagerDataSource("jdbc:h2:mem:oss_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table sys_oss_config (config_id bigint primary key, endpoint varchar(255), region varchar(64), bucket_name varchar(128), access_key varchar(128), secret_key varchar(256), domain varchar(255), prefix varchar(128), path_style char(1), access_policy varchar(16), status char(1))");
        jdbc.execute("create table sys_oss_object (object_id bigint auto_increment primary key, config_id bigint, object_key varchar(1024), original_name varchar(255), file_suffix varchar(32), content_type varchar(255), file_size bigint, sha256 varchar(64), access_policy varchar(16), public_url varchar(2048), status varchar(32), create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp, delete_time timestamp, last_error varchar(500))");
        jdbc.update("insert into sys_oss_config values (1,'https://storage.example.invalid','test-region','bucket','synthetic-access','synthetic-secret',null,'test','Y','PRIVATE','0')");
        client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            requests.add(request);
            assertSignature(request);
            if (request.method().equals("PUT"))
            {
                assertArrayEquals(CONTENT, body(request));
                if (failLedger)
                {
                    jdbc.update("update sys_oss_config set bucket_name='moved-bucket' where config_id=1");
                }
            }
            HttpResponse<InputStream> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(request.method().equals("PUT") ? putStatus : 200);
            when(response.body()).thenReturn(new ByteArrayInputStream(CONTENT));
            return response;
        });
        putStatus = 200;
        service = new SysOssService(jdbc, client, new TransactionTemplate(new DataSourceTransactionManager(source)));
        initialTempFiles = tempFiles();
    }

    @AfterEach
    void close() throws Exception
    {
        jdbc.execute("shutdown");
        assertEquals(initialTempFiles, tempFiles(), "upload must remove its staged file on success and failure");
    }

    @Test
    void signatureAndLedgerUseSamePayloadHashAcrossAllMethods() throws Exception
    {
        assertEquals(true, service.testConfig(1L).get("success"));
        var stored = service.upload(file(), "tester");
        assertEquals(hash(CONTENT), stored.sha256());
        assertEquals(stored.sha256(), jdbc.queryForObject("select sha256 from sys_oss_object where object_id=?",
                String.class, stored.objectId()));
        assertEquals(stored.sha256(), requests.get(1).headers().firstValue("x-amz-content-sha256").orElseThrow());
        try (InputStream content = service.open(stored.objectId()).content())
        {
            assertArrayEquals(CONTENT, content.readAllBytes());
        }
        service.delete(stored.objectId(), "tester");
        assertEquals("DELETED", jdbc.queryForObject("select status from sys_oss_object where object_id=?",
                String.class, stored.objectId()));
        assertEquals(List.of("HEAD", "PUT", "GET", "DELETE"), requests.stream().map(HttpRequest::method).toList());
    }

    @Test
    void remoteRejectionLeavesNeitherLedgerNorStagedFile()
    {
        putStatus = 403;
        assertThrows(ServiceException.class, () -> service.upload(file(), "tester"));
        assertEquals(0, jdbc.queryForObject("select count(*) from sys_oss_object", Integer.class));
        assertEquals(List.of("PUT"), requests.stream().map(HttpRequest::method).toList());
    }

    @Test
    void ledgerFailureCompensatesWithSignedEmptyDelete()
    {
        failLedger = true;
        assertThrows(ServiceException.class, () -> service.upload(file(), "tester"));
        assertEquals(0, jdbc.queryForObject("select count(*) from sys_oss_object", Integer.class));
        assertEquals(List.of("PUT", "DELETE"), requests.stream().map(HttpRequest::method).toList());
        assertEquals(requests.get(0).uri(), requests.get(1).uri());
    }

    @Test
    void actualSizeMismatchStopsBeforeTransport()
    {
        MockMultipartFile file = spy(file());
        when(file.getSize()).thenReturn((long) CONTENT.length + 1);
        assertThrows(ServiceException.class, () -> service.upload(file, "tester"));
        verifyNoInteractions(client);
    }

    private MockMultipartFile file()
    {
        return new MockMultipartFile("file", "document.txt", "text/plain", CONTENT);
    }

    private void assertSignature(HttpRequest request) throws Exception
    {
        String payload = request.headers().firstValue("x-amz-content-sha256").orElseThrow();
        assertEquals(hash(request.method().equals("PUT") ? CONTENT : new byte[0]), payload);
        String date = request.headers().firstValue("x-amz-date").orElseThrow();
        String signed = request.method().equals("PUT")
                ? "content-type;host;x-amz-content-sha256;x-amz-date" : "host;x-amz-content-sha256;x-amz-date";
        String headers = (request.method().equals("PUT") ? "content-type:text/plain\n" : "")
                + "host:" + request.uri().getAuthority() + "\nx-amz-content-sha256:" + payload + "\nx-amz-date:" + date + "\n";
        String canonical = request.method() + "\n" + request.uri().getRawPath() + "\n\n" + headers + "\n" + signed + "\n" + payload;
        String scope = date.substring(0, 8) + "/test-region/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + date + "\n" + scope + "\n" + hash(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac("AWS4synthetic-secret".getBytes(StandardCharsets.UTF_8), date.substring(0, 8));
        key = hmac(hmac(hmac(key, "test-region"), "s3"), "aws4_request");
        assertEquals("AWS4-HMAC-SHA256 Credential=synthetic-access/" + scope + ", SignedHeaders=" + signed
                + ", Signature=" + HexFormat.of().formatHex(hmac(key, toSign)),
                request.headers().firstValue("Authorization").orElseThrow());
    }

    private static String hash(byte[] bytes) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] hmac(byte[] key, String value) throws Exception
    {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] body(HttpRequest request) throws Exception
    {
        var result = new java.io.ByteArrayOutputStream();
        var completed = new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                result.writeBytes(bytes);
            }
            public void onError(Throwable error) { completed.completeExceptionally(error); }
            public void onComplete() { completed.complete(result.toByteArray()); }
        });
        return completed.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static List<Path> tempFiles() throws Exception
    {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir"))))
        {
            return files.filter(path -> path.getFileName().toString().startsWith("approvaplat-oss-"))
                    .sorted().toList();
        }
    }
}
