package com.ruoyi.framework.web.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.ruoyi.framework.config.FilterConfig;

/**
 * JsonBodySizeLimitFilter 的 HTTP 原始请求体边界测试。
 */
class JsonBodySizeLimitFilterTest
{
    /** 测试专用字节上限，便于覆盖边界而不分配生产上限大小的正文。 */
    private static final int TEST_LIMIT = 32;

    /**
     * 验证主要工作流 JSON 写入口在声明长度超限时都不会进入后续 MVC 链。
     *
     * @param requestUri String，工作流 Controller 的真实入口路径
     * @return 无返回值，断言 HTTP 413、稳定 JSON 和零后续调用
     * @throws Exception 执行 Servlet 过滤器失败时抛出
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/workflow/process/start/definition-1",
            "/workflow/task/complete",
            "/workflow/model/save",
            "/workflow/form"
    })
    void rejectsDeclaredOversizedWorkflowJsonBeforeController(String requestUri) throws Exception
    {
        JsonBodySizeLimitFilter filter = new JsonBodySizeLimitFilter(TEST_LIMIT);
        MockHttpServletRequest request = jsonRequest("POST", requestUri, TEST_LIMIT + 1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (filteredRequest, filteredResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(MediaType.parseMediaType(response.getContentType())
                .isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("32 字节上限");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Connection")).isEqualTo("close");
    }

    /**
     * 验证没有 Content-Length 的 chunked 等价请求仍只读取上限加一个字节并返回 413。
     *
     * @return 无返回值，断言未知长度不能绕过预反序列化硬门禁
     * @throws Exception 执行 Servlet 过滤器失败时抛出
     */
    @Test
    void rejectsUnknownLengthJsonWhenStreamExceedsLimit() throws Exception
    {
        JsonBodySizeLimitFilter filter = new JsonBodySizeLimitFilter(TEST_LIMIT);
        UnknownLengthRequest request = new UnknownLengthRequest();
        request.setMethod("POST");
        request.setRequestURI("/workflow/task/complete");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(repeatedBytes(TEST_LIMIT + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (filteredRequest, filteredResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
    }

    /**
     * 验证不可信的偏小 Content-Length 不能让真实流式正文绕过字节门禁。
     *
     * @return 无返回值，断言过滤器继续读取到真实 EOF 或首个超限字节
     * @throws Exception 执行 Servlet 过滤器失败时抛出
     */
    @Test
    void rejectsBodyThatExceedsLimitDespiteSmallerDeclaredLength() throws Exception
    {
        JsonBodySizeLimitFilter filter = new JsonBodySizeLimitFilter(TEST_LIMIT);
        FixedLengthRequest request = new FixedLengthRequest(1);
        request.setMethod("POST");
        request.setRequestURI("/workflow/process/start/definition-1");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(repeatedBytes(TEST_LIMIT + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (filteredRequest, filteredResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
    }

    /**
     * 验证只读方法携带 JSON 时仍受门禁保护，不能进入后续全量缓存过滤器。
     *
     * @return 无返回值，断言 GET 超限正文同样返回 HTTP 413
     * @throws Exception 执行 Servlet 过滤器失败时抛出
     */
    @Test
    void rejectsOversizedJsonBodyOnReadMethod()
            throws Exception
    {
        JsonBodySizeLimitFilter filter = new JsonBodySizeLimitFilter(TEST_LIMIT);
        MockHttpServletRequest request = jsonRequest("GET", "/workflow/process/detail",
                TEST_LIMIT + 1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (filteredRequest, filteredResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
    }

    /**
     * 验证恰好命中上限的 JSON 原始字节保持不变，并可供后续过滤器重复读取。
     *
     * @return 无返回值，断言边界请求通过且不会因缓存过程改变正文
     * @throws Exception 执行 Servlet 过滤器或读取正文失败时抛出
     */
    @Test
    void preservesExactLimitJsonForDownstreamDeserialization() throws Exception
    {
        JsonBodySizeLimitFilter filter = new JsonBodySizeLimitFilter(TEST_LIMIT);
        byte[] expected = repeatedBytes(TEST_LIMIT);
        MockHttpServletRequest request = jsonRequest("PUT", "/workflow/model", TEST_LIMIT);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<byte[]> firstRead = new AtomicReference<>();
        AtomicReference<byte[]> secondRead = new AtomicReference<>();

        filter.doFilter(request, response, (filteredRequest, filteredResponse) ->
        {
            firstRead.set(filteredRequest.getInputStream().readAllBytes());
            secondRead.set(filteredRequest.getInputStream().readAllBytes());
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(firstRead.get()).containsExactly(expected);
        assertThat(secondRead.get()).containsExactly(expected);
    }

    /**
     * 验证 multipart 附件即使大于 JSON 上限也保持原请求，不被 JSON 门禁读取或拒绝。
     *
     * @return 无返回值，断言附件上传继续交由 Spring multipart 独立大小配置处理
     * @throws Exception 执行 Servlet 过滤器失败时抛出
     */
    @Test
    void leavesMultipartAttachmentRequestsUntouched() throws Exception
    {
        JsonBodySizeLimitFilter filter = new JsonBodySizeLimitFilter(TEST_LIMIT);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/workflow/attachment");
        request.setContentType(MediaType.MULTIPART_FORM_DATA_VALUE + ";boundary=codex-boundary");
        request.setContent(repeatedBytes(TEST_LIMIT + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> downstreamRequest = new AtomicReference<>();

        filter.doFilter(request, response,
                (filteredRequest, filteredResponse) -> downstreamRequest.set(filteredRequest));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(downstreamRequest.get()).isSameAs(request);
    }

    /**
     * 验证 Servlet 注册只覆盖工作流路径且优先于现有 repeatableFilter 的完整正文缓存。
     *
     * @return 无返回值，断言 URL 范围、过滤器类型和最高优先级
     */
    @Test
    void registersLimitForAllWorkflowEndpointsBeforeRepeatableFilter()
    {
        FilterRegistrationBean<JsonBodySizeLimitFilter> registration =
                new FilterConfig().workflowJsonBodySizeLimitFilterRegistration();

        assertThat(registration.getUrlPatterns()).containsExactly("/workflow/*");
        assertThat(registration.getFilter()).isInstanceOf(JsonBodySizeLimitFilter.class);
        assertThat(registration.getOrder()).isEqualTo(FilterRegistrationBean.HIGHEST_PRECEDENCE);
    }

    /**
     * 创建带完整原始正文和 application/json 媒体类型的模拟 HTTP 请求。
     *
     * @param method String，HTTP 方法
     * @param requestUri String，请求路径
     * @param bodyBytes int，正文原始字节数
     * @return MockHttpServletRequest，Content-Length 与正文一致的请求
     */
    private MockHttpServletRequest jsonRequest(String method, String requestUri, int bodyBytes)
    {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(requestUri);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(repeatedBytes(bodyBytes));
        return request;
    }

    /**
     * 创建固定内容的测试字节数组。
     *
     * @param length int，需要创建的数组长度
     * @return byte[]，每个字节均为 ASCII 空格的独立数组
     */
    private byte[] repeatedBytes(int length)
    {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) ' ');
        return bytes;
    }

    /**
     * 模拟 Transfer-Encoding: chunked 在 Servlet API 中不提供 Content-Length 的请求。
     */
    private static final class UnknownLengthRequest extends MockHttpServletRequest
    {
        /**
         * 返回未知的整型正文长度。
         *
         * @return int，固定为 -1
         */
        @Override
        public int getContentLength()
        {
            return -1;
        }

        /**
         * 返回未知的长整型正文长度。
         *
         * @return long，固定为 -1
         */
        @Override
        public long getContentLengthLong()
        {
            return -1L;
        }
    }

    /**
     * 模拟请求头声明长度与容器输入流可读长度不一致的防御性场景。
     */
    private static final class FixedLengthRequest extends MockHttpServletRequest
    {
        /** 请求 API 对外报告的固定长度。 */
        private final int declaredLength;

        /**
         * 创建固定声明长度请求。
         *
         * @param declaredLength int，Servlet API 对外报告的正文长度
         * @return 无返回值，真实正文仍由 setContent 单独设置
         */
        private FixedLengthRequest(int declaredLength)
        {
            this.declaredLength = declaredLength;
        }

        /**
         * 返回测试指定的整型声明长度。
         *
         * @return int，固定 Content-Length
         */
        @Override
        public int getContentLength()
        {
            return declaredLength;
        }

        /**
         * 返回测试指定的长整型声明长度。
         *
         * @return long，固定 Content-Length
         */
        @Override
        public long getContentLengthLong()
        {
            return declaredLength;
        }
    }
}
