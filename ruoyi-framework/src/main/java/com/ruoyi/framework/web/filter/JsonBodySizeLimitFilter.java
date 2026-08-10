package com.ruoyi.framework.web.filter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

/**
 * 在 Spring MVC 反序列化前限制 JSON 请求体的原始字节数。
 */
public final class JsonBodySizeLimitFilter implements Filter
{
    /** HTTP 413 状态码，Servlet API 未在所有兼容版本中提供同名常量。 */
    private static final int PAYLOAD_TOO_LARGE = 413;

    /** 单次底层读取块大小，限制读取次数且不额外分配大块临时数组。 */
    private static final int READ_BUFFER_BYTES = 8192;

    private final int maxBodyBytes;

    /**
     * 创建 JSON 请求体硬大小过滤器。
     *
     * @param maxBodyBytes int，允许进入后续过滤器和 MVC 反序列化的最大原始字节数
     * @return 无返回值，非法上限会在应用启动创建 Bean 时立即失败
     */
    public JsonBodySizeLimitFilter(int maxBodyBytes)
    {
        if (maxBodyBytes <= 0 || maxBodyBytes == Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("JSON 请求体大小上限必须为有效正整数");
        }
        this.maxBodyBytes = maxBodyBytes;
    }

    /**
     * 在任何 JSON 解析发生前校验 Content-Length，并对未知长度请求最多读取上限加一个字节。
     *
     * @param request ServletRequest，Servlet 容器提供的原始请求
     * @param response ServletResponse，Servlet 容器提供的原始响应
     * @param chain FilterChain，大小校验通过后继续执行的过滤器链
     * @return 无返回值，超限时直接返回 HTTP 413 且不会进入后续业务链
     * @throws IOException 读取请求或写入响应失败时抛出
     * @throws ServletException 后续过滤器或 Controller 执行失败时抛出
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException
    {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)
                || !hasJsonBody(httpRequest))
        {
            chain.doFilter(request, response);
            return;
        }

        long declaredLength = httpRequest.getContentLengthLong();
        if (declaredLength > maxBodyBytes)
        {
            reject(httpResponse);
            return;
        }

        byte[] body = readBoundedBody(httpRequest);
        if (body == null)
        {
            reject(httpResponse);
            return;
        }
        chain.doFilter(new CachedBodyRequest(httpRequest, body), response);
    }

    /**
     * 判断请求是否可能携带需要限制的 JSON 正文。
     *
     * @param request HttpServletRequest，待检查方法和 Content-Type 的请求
     * @return boolean，媒体类型为 application/json 或 application/*+json 时返回 true
     */
    private boolean hasJsonBody(HttpServletRequest request)
    {
        String contentType = request.getContentType();
        if (!StringUtils.hasText(contentType))
        {
            return false;
        }
        try
        {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            String subtype = mediaType.getSubtype();
            return MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)
                    || (subtype != null && subtype.toLowerCase(Locale.ROOT).endsWith("+json"));
        }
        catch (IllegalArgumentException exception)
        {
            // 非法媒体类型交由 Spring MVC 返回统一 415；此处不得误读 multipart 或普通流。
            return false;
        }
    }

    /**
     * 有界读取完整请求体，已知和未知 Content-Length 均无法越过同一硬上限。
     *
     * @param request HttpServletRequest，尚未进入 JSON 解析的原始请求
     * @return byte[]，完整且不超过上限的正文；读取到上限外第一个字节时返回 null
     * @throws IOException 底层网络流读取失败时抛出
     */
    private byte[] readBoundedBody(HttpServletRequest request) throws IOException
    {
        long declaredLength = request.getContentLengthLong();
        int initialCapacity = declaredLength > 0
                ? (int) Math.min(declaredLength, maxBodyBytes)
                : Math.min(READ_BUFFER_BYTES, maxBodyBytes);
        byte[] bounded = new byte[Math.max(initialCapacity, 1)];
        int total = 0;
        try (ServletInputStream input = request.getInputStream())
        {
            while (true)
            {
                if (total == bounded.length)
                {
                    // 即使同时出现不可信 Content-Length 与流式正文，也必须以真实 EOF 作为完整边界。
                    int overflowCandidate = input.read();
                    if (overflowCandidate < 0)
                    {
                        return bounded;
                    }
                    if (total == maxBodyBytes)
                    {
                        return null;
                    }
                    long requestedGrowth = Math.max((long) bounded.length + READ_BUFFER_BYTES,
                            (long) bounded.length * 2);
                    int grownLength = (int) Math.min(maxBodyBytes, requestedGrowth);
                    bounded = Arrays.copyOf(bounded, grownLength);
                    bounded[total++] = (byte) overflowCandidate;
                    continue;
                }
                int read = input.read(bounded, total,
                        Math.min(READ_BUFFER_BYTES, bounded.length - total));
                if (read < 0)
                {
                    return Arrays.copyOf(bounded, total);
                }
                if (read == 0)
                {
                    int singleByte = input.read();
                    if (singleByte < 0)
                    {
                        return Arrays.copyOf(bounded, total);
                    }
                    bounded[total++] = (byte) singleByte;
                }
                else
                {
                    total += read;
                }
            }
        }
    }

    /**
     * 返回稳定的若依 JSON 错误，并关闭当前连接以丢弃尚未读取的 chunked 剩余正文。
     *
     * @param response HttpServletResponse，尚未提交的 HTTP 响应
     * @return 无返回值，响应状态和正文固定为 HTTP 413
     * @throws IOException 响应输出失败时抛出
     */
    private void reject(HttpServletResponse response) throws IOException
    {
        byte[] payload = ("{\"msg\":\"工作流 JSON 请求体超过 " + maxBodyBytes
                + " 字节上限\",\"code\":" + PAYLOAD_TOO_LARGE + "}")
                .getBytes(StandardCharsets.UTF_8);
        response.reset();
        response.setStatus(PAYLOAD_TOO_LARGE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Connection", "close");
        response.setContentLength(payload.length);
        response.getOutputStream().write(payload);
        response.getOutputStream().flush();
    }

    /**
     * 为已通过门禁的原始字节提供可重复读取的同步 Servlet 请求。
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper
    {
        /** 已通过硬大小校验的完整 JSON 原始字节。 */
        private final byte[] body;

        /**
         * 创建缓存正文请求包装器。
         *
         * @param request HttpServletRequest，保留请求头、路径和认证信息的原始请求
         * @param body byte[]，已通过上限校验的完整 JSON 字节
         * @return 无返回值，后续过滤器每次读取都会获得独立输入流
         */
        private CachedBodyRequest(HttpServletRequest request, byte[] body)
        {
            super(request);
            // body 由外层有界读取方法独占创建且不会再暴露，直接转移所有权避免峰值内存翻倍。
            this.body = body;
        }

        /**
         * 返回缓存正文的真实字节长度。
         *
         * @return int，已验证 JSON 正文长度
         */
        @Override
        public int getContentLength()
        {
            return body.length;
        }

        /**
         * 返回缓存正文的真实长整型字节长度。
         *
         * @return long，已验证 JSON 正文长度
         */
        @Override
        public long getContentLengthLong()
        {
            return body.length;
        }

        /**
         * 按请求声明字符集创建缓存正文 Reader。
         *
         * @return BufferedReader，读取独立缓存输入流的字符 Reader
         * @throws IOException 创建输入流失败时抛出
         */
        @Override
        public BufferedReader getReader() throws IOException
        {
            Charset charset = StringUtils.hasText(getCharacterEncoding())
                    ? Charset.forName(getCharacterEncoding()) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        /**
         * 为每次调用返回从头读取的缓存正文输入流。
         *
         * @return ServletInputStream，独立且同步就绪的内存输入流
         */
        @Override
        public ServletInputStream getInputStream()
        {
            return new CachedBodyInputStream(body);
        }
    }

    /**
     * 基于受限缓存字节实现同步 ServletInputStream 状态语义。
     */
    private static final class CachedBodyInputStream extends ServletInputStream
    {
        /** 当前调用独占的内存输入流游标。 */
        private final ByteArrayInputStream input;

        /**
         * 创建独立缓存输入流。
         *
         * @param body byte[]，已通过上限校验的 JSON 正文
         * @return 无返回值，新输入流游标从正文开头开始
         */
        private CachedBodyInputStream(byte[] body)
        {
            this.input = new ByteArrayInputStream(body);
        }

        /**
         * 读取下一个正文原始字节。
         *
         * @return int，0 至 255 的字节值或流结束标记 -1
         */
        @Override
        public int read()
        {
            return input.read();
        }

        /**
         * 批量读取正文原始字节。
         *
         * @param bytes byte[]，接收正文数据的目标数组
         * @param offset int，目标数组写入起点
         * @param length int，本次最多读取字节数
         * @return int，真实读取字节数或流结束标记 -1
         */
        @Override
        public int read(byte[] bytes, int offset, int length)
        {
            return input.read(bytes, offset, length);
        }

        /**
         * 判断缓存正文是否已经全部读取。
         *
         * @return boolean，没有剩余字节时返回 true
         */
        @Override
        public boolean isFinished()
        {
            return input.available() == 0;
        }

        /**
         * 同步内存流始终可以立即读取。
         *
         * @return boolean，固定返回 true
         */
        @Override
        public boolean isReady()
        {
            return true;
        }

        /**
         * 通知异步监听器当前内存流已就绪或已完成。
         *
         * @param readListener ReadListener，Servlet 容器提供的非空读取监听器
         * @return 无返回值，回调异常会转交监听器的 onError
         */
        @Override
        public void setReadListener(ReadListener readListener)
        {
            if (readListener == null)
            {
                throw new IllegalArgumentException("读取监听器不能为空");
            }
            try
            {
                if (isFinished())
                {
                    readListener.onAllDataRead();
                }
                else
                {
                    readListener.onDataAvailable();
                }
            }
            catch (IOException exception)
            {
                readListener.onError(exception);
            }
        }
    }
}
