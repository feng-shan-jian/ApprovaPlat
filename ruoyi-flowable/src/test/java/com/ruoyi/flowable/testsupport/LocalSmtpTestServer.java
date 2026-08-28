package com.ruoyi.flowable.testsupport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/**
 * 本地真实 TCP SMTP 测试服务，统一支持成功投递和受控协议失败。
 */
public final class LocalSmtpTestServer implements AutoCloseable
{
    /** SMTP 测试服务的受控响应行为。 */
    public enum Behavior
    {
        ACCEPT,
        REJECT_AUTHENTICATION,
        REJECT_STARTTLS_NEGOTIATION,
        REJECT_MAIL_FROM,
        SUPPRESS_GREETING
    }

    private final Behavior behavior;
    private final String expectedUsername;
    private final String expectedCredential;
    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CountDownLatch connectionAccepted = new CountDownLatch(1);
    private final BlockingQueue<ReceivedMessage> messages = new LinkedBlockingQueue<>();
    /** 关闭时必须主动中断全部已接入 socket，不能只关闭监听 socket。 */
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final ExecutorService clientExecutor;
    private final Thread acceptThread;

    /**
     * 在系统回环地址随机端口启动 SMTP 测试服务。
     *
     * @param behavior Behavior，服务器响应模式
     * @param expectedUsername String，允许认证的账号
     * @param expectedCredential String，允许认证的授权码
     * @return void，构造完成即开始监听
     */
    public LocalSmtpTestServer(Behavior behavior, String expectedUsername,
            String expectedCredential)
    {
        if (behavior == null || expectedUsername == null || expectedCredential == null)
        {
            throw new IllegalArgumentException("本地 SMTP 测试服务参数不合法");
        }
        this.behavior = behavior;
        this.expectedUsername = expectedUsername;
        this.expectedCredential = expectedCredential;
        try
        {
            serverSocket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        }
        catch (IOException exception)
        {
            throw new IllegalStateException("无法创建本地 SMTP 测试服务", exception);
        }
        clientExecutor = Executors.newFixedThreadPool(2, runnable ->
        {
            Thread thread = new Thread(runnable,
                    "workflow-mail-smtp-client-" + serverSocket.getLocalPort());
            thread.setDaemon(true);
            return thread;
        });
        acceptThread = new Thread(this::acceptConnections,
                "workflow-mail-smtp-accept-" + serverSocket.getLocalPort());
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /**
     * 返回测试服务实际监听端口。
     *
     * @return int，操作系统分配的本地端口
     */
    public int port()
    {
        return serverSocket.getLocalPort();
    }

    /**
     * 等待一封已经完成 SMTP DATA 交付的邮件。
     *
     * @return ReceivedMessage，信封与原始 MIME 数据
     * @throws InterruptedException 当前测试线程被中断时抛出
     */
    public ReceivedMessage awaitMessage() throws InterruptedException
    {
        ReceivedMessage message = messages.poll(5, TimeUnit.SECONDS);
        if (message == null)
        {
            throw new AssertionError("本地 SMTP 测试服务在期限内未收到邮件");
        }
        return message;
    }

    /**
     * 等待生产发送器已经建立 TCP 连接。
     *
     * @return boolean，五秒内接入为 true
     */
    public boolean awaitConnection()
    {
        try
        {
            return connectionAccepted.await(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 接受客户端连接并交给独立工作线程，监听关闭属于正常退出。
     *
     * @return void，无返回值
     */
    private void acceptConnections()
    {
        while (running.get())
        {
            try
            {
                Socket client = serverSocket.accept();
                clients.add(client);
                connectionAccepted.countDown();
                clientExecutor.execute(() -> handleClient(client));
            }
            catch (IOException exception)
            {
                if (running.get())
                {
                    throw new IllegalStateException("本地 SMTP 测试服务接受连接失败",
                            exception);
                }
            }
        }
    }

    /**
     * 执行一条完整 SMTP 会话，认证或协议失败只影响当前连接。
     *
     * @param client Socket，已经接受的 SMTP 客户端连接
     * @return void，无返回值
     */
    private void handleClient(Socket client)
    {
        try (client;
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        client.getInputStream(), StandardCharsets.US_ASCII));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        client.getOutputStream(), StandardCharsets.US_ASCII)))
        {
            if (behavior == Behavior.SUPPRESS_GREETING)
            {
                // 故意保持 TCP 连接但不发送 220，验证生产 mail.smtp.timeout 读取超时。
                while (running.get() && reader.read() != -1)
                {
                    // SMTP 客户端在欢迎语前不会发送数据；关闭 socket 会解除该阻塞。
                }
                return;
            }
            writeResponse(writer, "220 localhost ApprovaPlat SMTP integration test");
            String mailFrom = null;
            String recipient = null;
            String line;
            while ((line = reader.readLine()) != null)
            {
                String command = line.toUpperCase(Locale.ROOT);
                if (command.startsWith("EHLO") || command.startsWith("HELO"))
                {
                    writeResponse(writer, "250-localhost");
                    if (behavior == Behavior.REJECT_STARTTLS_NEGOTIATION)
                    {
                        writeResponse(writer, "250-STARTTLS");
                    }
                    writeResponse(writer, "250-AUTH LOGIN PLAIN");
                    writeResponse(writer, "250 8BITMIME");
                }
                else if (command.equals("STARTTLS"))
                {
                    writeResponse(writer, "220 2.0.0 Ready to start TLS");
                    if (behavior == Behavior.REJECT_STARTTLS_NEGOTIATION)
                    {
                        // 明文响应使客户端在 TLS 握手阶段稳定触发 JSSE 协议异常。
                        writeResponse(writer, "550 TLS handshake rejected");
                        return;
                    }
                }
                else if (command.startsWith("AUTH LOGIN"))
                {
                    if (!authenticateLogin(line, reader, writer)) return;
                }
                else if (command.startsWith("AUTH PLAIN"))
                {
                    if (!authenticatePlain(line, reader, writer)) return;
                }
                else if (command.startsWith("MAIL FROM:"))
                {
                    mailFrom = line.substring("MAIL FROM:".length()).trim();
                    if (behavior == Behavior.REJECT_MAIL_FROM)
                    {
                        writeResponse(writer, "550 5.7.1 Sender address rejected");
                    }
                    else
                    {
                        writeResponse(writer, "250 2.1.0 Sender accepted");
                    }
                }
                else if (command.startsWith("RCPT TO:"))
                {
                    recipient = line.substring("RCPT TO:".length()).trim();
                    writeResponse(writer, "250 2.1.5 Recipient accepted");
                }
                else if (command.equals("DATA"))
                {
                    writeResponse(writer, "354 End data with <CR><LF>.<CR><LF>");
                    String rawMessage = readMessageData(reader);
                    messages.add(new ReceivedMessage(rawMessage, mailFrom, recipient));
                    writeResponse(writer, "250 2.0.0 Message accepted");
                }
                else if (command.equals("RSET") || command.equals("NOOP"))
                {
                    writeResponse(writer, "250 2.0.0 OK");
                }
                else if (command.equals("QUIT"))
                {
                    writeResponse(writer, "221 2.0.0 Bye");
                    return;
                }
                else
                {
                    writeResponse(writer, "502 5.5.2 Command not implemented");
                }
            }
        }
        catch (IOException ignored)
        {
            // 客户端超时或测试清理关闭连接属于预期生命周期，不污染测试输出。
        }
        finally
        {
            clients.remove(client);
        }
    }

    /**
     * 执行 AUTH LOGIN 账号和授权码交互。
     *
     * @param firstLine String，AUTH LOGIN 首行，可带初始账号响应
     * @param reader BufferedReader，SMTP 客户端输入
     * @param writer BufferedWriter，SMTP 服务响应
     * @return boolean，认证成功且会话可继续时为 true
     * @throws IOException 网络读写失败时抛出
     */
    private boolean authenticateLogin(String firstLine, BufferedReader reader,
            BufferedWriter writer) throws IOException
    {
        String[] parts = firstLine.trim().split("\\s+", 3);
        String usernameToken;
        if (parts.length == 3)
        {
            usernameToken = parts[2];
        }
        else
        {
            writeResponse(writer, "334 VXNlcm5hbWU6");
            usernameToken = reader.readLine();
        }
        writeResponse(writer, "334 UGFzc3dvcmQ6");
        String credentialToken = reader.readLine();
        return finishAuthentication(decodeBase64(usernameToken),
                decodeBase64(credentialToken), writer);
    }

    /**
     * 执行 AUTH PLAIN 账号和授权码校验。
     *
     * @param firstLine String，AUTH PLAIN 首行，可带初始响应
     * @param reader BufferedReader，SMTP 客户端输入
     * @param writer BufferedWriter，SMTP 服务响应
     * @return boolean，认证成功且会话可继续时为 true
     * @throws IOException 网络读写失败时抛出
     */
    private boolean authenticatePlain(String firstLine, BufferedReader reader,
            BufferedWriter writer) throws IOException
    {
        String[] parts = firstLine.trim().split("\\s+", 3);
        String token;
        if (parts.length == 3)
        {
            token = parts[2];
        }
        else
        {
            writeResponse(writer, "334 ");
            token = reader.readLine();
        }
        String plain = decodeBase64(token);
        String[] credentials = plain.split("\\u0000", -1);
        String username = credentials.length >= 2
                ? credentials[credentials.length - 2] : "";
        String credential = credentials.length >= 1
                ? credentials[credentials.length - 1] : "";
        return finishAuthentication(username, credential, writer);
    }

    /**
     * 按服务行为和预期凭据完成认证响应。
     *
     * @param username String，客户端解码账号
     * @param credential String，客户端解码授权码
     * @param writer BufferedWriter，SMTP 服务响应
     * @return boolean，认证成功为 true
     * @throws IOException 网络写失败时抛出
     */
    private boolean finishAuthentication(String username, String credential,
            BufferedWriter writer) throws IOException
    {
        if (behavior == Behavior.REJECT_AUTHENTICATION
                || !expectedUsername.equals(username)
                || !expectedCredential.equals(credential))
        {
            writeResponse(writer, "535 5.7.8 Authentication credentials invalid");
            return false;
        }
        writeResponse(writer, "235 2.7.0 Authentication successful");
        return true;
    }

    /**
     * 读取 SMTP DATA 段直至单独句点，并还原点转义后的 MIME 行。
     *
     * @param reader BufferedReader，SMTP 客户端输入
     * @return String，不含 SMTP 终止句点的原始 MIME 数据
     * @throws IOException 网络读取失败时抛出
     */
    private String readMessageData(BufferedReader reader) throws IOException
    {
        StringBuilder rawMessage = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null && !".".equals(line))
        {
            if (line.startsWith("..")) line = line.substring(1);
            rawMessage.append(line).append("\r\n");
        }
        return rawMessage.toString();
    }

    /**
     * 解码 SMTP AUTH 的 Base64 字段，非法输入按空字符串处理并导致认证拒绝。
     *
     * @param value String，可空 Base64 字段
     * @return String，UTF-8 解码内容或空字符串
     */
    private String decodeBase64(String value)
    {
        if (value == null) return "";
        try
        {
            return new String(Base64.getDecoder().decode(value),
                    StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException exception)
        {
            return "";
        }
    }

    /**
     * 写入一行 CRLF 结尾的 SMTP 响应并立即刷新。
     *
     * @param writer BufferedWriter，SMTP 客户端输出
     * @param response String，不含行尾的 SMTP 响应
     * @return void，无返回值
     * @throws IOException 网络写失败时抛出
     */
    private void writeResponse(BufferedWriter writer, String response)
            throws IOException
    {
        writer.write(response);
        writer.write("\r\n");
        writer.flush();
    }

    /**
     * 关闭监听与全部在途连接，等待后台线程退出，避免污染后续测试。
     *
     * @return void，无返回值
     */
    @Override
    public void close()
    {
        if (!running.compareAndSet(true, false)) return;
        try
        {
            serverSocket.close();
        }
        catch (IOException ignored)
        {
            // 重复关闭或测试失败后的关闭异常不改变测试业务结论。
        }
        for (Socket client : clients)
        {
            try
            {
                client.close();
            }
            catch (IOException ignored)
            {
                // 继续关闭其余连接，确保清理完整。
            }
        }
        clientExecutor.shutdownNow();
        try
        {
            acceptThread.join(2_000);
            clientExecutor.awaitTermination(2, TimeUnit.SECONDS);
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 本地 SMTP 测试服务冻结的信封与原始 MIME 数据。
     *
     * @param rawMessage String，不含 DATA 终止句点的原始 MIME 内容
     * @param mailFrom String，MAIL FROM 信封参数
     * @param recipient String，RCPT TO 信封参数
     */
    public record ReceivedMessage(String rawMessage, String mailFrom, String recipient)
    {
        /**
         * 使用 Jakarta Mail 解析接收器捕获的 MIME 内容。
         *
         * @return MimeMessage，可按主题、收件人和自定义头断言的消息
         * @throws Exception MIME 内容损坏时抛出
         */
        public MimeMessage parse() throws Exception
        {
            return new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(rawMessage.getBytes(
                            StandardCharsets.US_ASCII)));
        }
    }
}
