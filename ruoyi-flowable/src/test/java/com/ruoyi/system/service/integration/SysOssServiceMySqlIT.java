package com.ruoyi.system.service.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;
import com.ruoyi.system.domain.integration.OssConfigRequest;

/** 真实 MySQL 事务维护 OSS 定位，只有 HTTP 边界受控，不连接任何远端 S3。 */
class SysOssServiceMySqlIT
{
    private static final byte[] CONTENT = "oss-location-it".getBytes(StandardCharsets.UTF_8);
    private static MysqlDataSource dataSource;

    private final List<Long> configIds = new ArrayList<>();
    private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<URI, byte[]> remote = new ConcurrentHashMap<>();
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private SysOssService service;
    private CountDownLatch putWritten;
    private CountDownLatch releasePut;
    private boolean rejectDelete;

    @BeforeAll
    static void prepareIsolatedSchema() throws Exception
    {
        dataSource = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(dataSource, "OSS 定位 MySQL IT",
                "approvaplat_it", List.of("sys_user", "sys_oss_config", "sys_oss_object"));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception
    {
        jdbc = new JdbcTemplate(dataSource);
        assertEquals(0, jdbc.queryForObject("select count(*) from sys_oss_config where status='0'",
                Integer.class), "隔离库已有启用配置，不得停用其他测试或用户数据");
        var manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> respond(invocation.getArgument(0)));
        service = WorkflowMySqlITSupport.transactionalProxy(
                new SysOssService(jdbc, client, transactions), manager);
    }

    @AfterEach
    void cleanOnlyOwnedRows()
    {
        for (Long id : configIds)
        {
            jdbc.update("delete from sys_oss_object where config_id=?", id);
            jdbc.update("delete from sys_oss_config where config_id=?", id);
            assertEquals(0, jdbc.queryForObject("select count(*) from sys_oss_object where config_id=?",
                    Integer.class, id));
            assertEquals(0, jdbc.queryForObject("select count(*) from sys_oss_config where config_id=?",
                    Integer.class, id));
        }
    }

    @Test
    void referencedLocationCannotMoveButRotatedCredentialsReadAndDeleteOriginalObject() throws Exception
    {
        long id = createActiveConfig();
        var object = upload();
        URI original = requests.get(0).uri();
        for (OssConfigRequest change : List.of(
                request(id, "https://new-storage.invalid", "us-east-1", "bucket-a", "Y"),
                request(id, "https://storage.invalid", "eu-west-1", "bucket-a", "Y"),
                request(id, "https://storage.invalid", "us-east-1", "bucket-b", "Y"),
                request(id, "https://storage.invalid", "us-east-1", "bucket-a", "N")))
        {
            assertConflict(() -> service.updateConfig(change, "oss-it"));
        }
        rotateCredentialsAndDefaults(id);
        var opened = service.open(object.objectId());
        try (InputStream content = opened.content())
        {
            assertArrayEquals(CONTENT, content.readAllBytes());
        }
        service.delete(object.objectId(), "oss-it");
        assertEquals(List.of("PUT", "GET", "DELETE"), requests.stream().map(HttpRequest::method).toList());
        for (HttpRequest operation : requests.subList(1, requests.size()))
        {
            assertEquals(original, operation.uri());
            assertTrue(operation.headers().firstValue("Authorization").orElseThrow()
                    .contains("Credential=rotated-access/"));
        }
        assertTrue(remote.isEmpty());
        assertEquals("DELETED", status(object.objectId()));
        assertConflict(() -> service.updateConfig(request(id, "bucket-b"), "oss-it"));
        assertEquals("PRIVATE", jdbc.queryForObject("select access_policy from sys_oss_object where object_id=?",
                String.class, object.objectId()));
    }

    @Test
    void unreferencedLocationCanChangeAndBlankSecretKeepsCredential()
    {
        long id = createActiveConfig();
        service.updateConfig(request(id, "https://new-storage.invalid", "eu-west-1", "bucket-b", "N"), "oss-it");
        service.updateConfig(new OssConfigRequest(id, "oss-it-" + id, "https://new-storage.invalid", "eu-west-1",
                "bucket-b", "next-access", "", null, "next", "N", "PRIVATE", "edited"), "oss-it");
        assertEquals("it-secret", jdbc.queryForObject("select secret_key from sys_oss_config where config_id=?",
                String.class, id));
        upload();
        assertEquals("bucket-b.new-storage.invalid", requests.get(0).uri().getHost());
        assertTrue(requests.get(0).uri().getPath().startsWith("/next/"));
        assertTrue(requests.get(0).headers().firstValue("Authorization").orElseThrow().contains("/eu-west-1/s3/"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void locationUpdateWinsDuringPutAndCompensationUsesOriginalBucket(boolean compensationFails) throws Exception
    {
        long id = createActiveConfig();
        rejectDelete = compensationFails;
        withPausedUpload(future -> {
            service.updateConfig(request(id, "bucket-b"), "oss-it");
            releasePut.countDown();
            Throwable failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> future.get(10, TimeUnit.SECONDS)).getCause();
            assertTrue(failure instanceof ServiceException);
            assertEquals(HttpStatus.CONFLICT, ((ServiceException) failure).getCode());
            assertEquals(compensationFails ? 1 : 0, failure.getSuppressed().length);
        });
        assertEquals(List.of("PUT", "DELETE"), requests.stream().map(HttpRequest::method).toList());
        assertEquals(requests.get(0).uri(), requests.get(1).uri());
        assertTrue(requests.get(1).uri().getPath().startsWith("/bucket-a/"));
        assertEquals(compensationFails ? 1 : 0, remote.size());
        assertEquals(0, objectCount(id));
        assertEquals("bucket-b", jdbc.queryForObject("select bucket_name from sys_oss_config where config_id=?",
                String.class, id));
    }

    @Test
    void credentialRotationDuringPutKeepsUploadSnapshotAndAllowsRegistration() throws Exception
    {
        long id = createActiveConfig();
        withPausedUpload(future -> {
            rotateCredentialsAndDefaults(id);
            releasePut.countDown();
            var object = future.get(10, TimeUnit.SECONDS);
            assertEquals("PRIVATE", object.accessPolicy());
            assertEquals(null, object.publicUrl());
            try (InputStream content = service.open(object.objectId()).content())
            {
                assertNotNull(content);
                assertArrayEquals(CONTENT, content.readAllBytes());
            }
        });
        assertEquals(1, objectCount(id));
        assertEquals(requests.get(0).uri(), requests.get(1).uri());
        assertTrue(requests.get(1).headers().firstValue("Authorization").orElseThrow()
                .contains("Credential=rotated-access/"));
    }

    @Test
    void committedUploadWinsEvenWhenUpdaterHasAnOlderRepeatableReadSnapshot() throws Exception
    {
        long id = createActiveConfig();
        transactions.executeWithoutResult(tx -> {
            assertEquals(0, objectCount(id));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try
            {
                executor.submit(this::upload).get(10, TimeUnit.SECONDS);
            }
            catch (Exception exception)
            {
                throw new AssertionError(exception);
            }
            finally
            {
                executor.shutdownNow();
            }
            assertEquals(0, objectCount(id), "旧 RR 快照必须仍看不到新入账，才能验证当前读守卫");
            assertConflict(() -> service.updateConfig(request(id, "bucket-b"), "oss-it"));
            tx.setRollbackOnly();
        });
        assertEquals(1, objectCount(id));
        assertEquals("bucket-a", jdbc.queryForObject("select bucket_name from sys_oss_config where config_id=?",
                String.class, id));
        assertEquals(1, remote.size());
    }

    @Test
    void deletionFailureAndMissingObjectStayAtOriginalLocation()
    {
        long id = createActiveConfig();
        var object = upload();
        rejectDelete = true;
        assertConflict(() -> service.delete(object.objectId(), "oss-it"));
        assertEquals("DELETE_FAILED", status(object.objectId()));
        assertConflict(() -> service.updateConfig(request(id, "bucket-b"), "oss-it"));
        rejectDelete = false;
        remote.clear();
        service.delete(object.objectId(), "oss-it");
        assertEquals("DELETED", status(object.objectId()));
        assertTrue(requests.stream().allMatch(operation -> operation.uri().equals(requests.get(0).uri())));
    }

    private long createActiveConfig()
    {
        OssConfigRequest initial = request(null, "bucket-a");
        long id = service.createConfig(initial, "oss-it");
        configIds.add(id);
        service.activate(id, "oss-it");
        return id;
    }

    private OssConfigRequest request(Long id, String bucket)
    {
        return request(id, "https://storage.invalid", "us-east-1", bucket, "Y");
    }

    private OssConfigRequest request(Long id, String endpoint, String region, String bucket, String style)
    {
        return new OssConfigRequest(id, "oss-it-" + (id == null ? UUID.randomUUID() : id), endpoint,
                region, bucket, "it-access", "it-secret", null, "original", style, "PRIVATE", null);
    }

    private void rotateCredentialsAndDefaults(long id)
    {
        service.updateConfig(new OssConfigRequest(id, "oss-it-" + id, "https://storage.invalid", "us-east-1",
                "bucket-a", "rotated-access", "rotated-secret", "https://cdn.invalid", "next", "Y", "PUBLIC",
                "credential rotation"), "oss-it");
        assertEquals("rotated-secret", jdbc.queryForObject("select secret_key from sys_oss_config where config_id=?",
                String.class, id));
    }

    private SysOssService.StoredOssObject upload()
    {
        return service.upload(new MockMultipartFile("file", "object.txt", "text/plain", CONTENT), "oss-it");
    }

    private int objectCount(long id)
    {
        return jdbc.queryForObject("select count(*) from sys_oss_object where config_id=?", Integer.class, id);
    }

    private String status(long id)
    {
        return jdbc.queryForObject("select status from sys_oss_object where object_id=?", String.class, id);
    }

    private void assertConflict(org.junit.jupiter.api.function.Executable operation)
    {
        assertEquals(HttpStatus.CONFLICT, assertThrows(ServiceException.class, operation).getCode());
    }

    private void withPausedUpload(UploadInterleaving interleaving) throws Exception
    {
        putWritten = new CountDownLatch(1);
        releasePut = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try
        {
            Future<SysOssService.StoredOssObject> future = executor.submit(this::upload);
            assertTrue(putWritten.await(10, TimeUnit.SECONDS), "上传未进入受控 PUT");
            interleaving.run(future);
        }
        finally
        {
            releasePut.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** 模拟桶内数据和 404，仅替换已构建并签名的 HttpClient 请求。 */
    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> respond(HttpRequest request) throws Exception
    {
        requests.add(request);
        int status = 200;
        byte[] body = new byte[0];
        switch (request.method())
        {
            case "PUT" -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                        "远端上传不能持有数据库事务或配置锁");
                remote.put(request.uri(), CONTENT);
                if (putWritten != null)
                {
                    putWritten.countDown();
                    assertTrue(releasePut.await(10, TimeUnit.SECONDS), "未释放受控 PUT");
                }
            }
            case "GET" -> {
                body = remote.get(request.uri());
                status = body == null ? 404 : 200;
            }
            case "DELETE" -> {
                status = rejectDelete ? 503 : remote.remove(request.uri()) == null ? 404 : 204;
            }
            default -> throw new AssertionError("意外 HTTP 操作: " + request.method());
        }
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(new ByteArrayInputStream(body == null ? new byte[0] : body));
        return response;
    }

    @FunctionalInterface
    private interface UploadInterleaving
    {
        void run(Future<SysOssService.StoredOssObject> upload) throws Exception;
    }
}
