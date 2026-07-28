package com.ruoyi.common.utils.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.web.util.UriUtils;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.uuid.IdUtils;

/**
 * 文件处理工具类
 * 
 * @author ruoyi
 */
public class FileUtils
{
    public static String FILENAME_PATTERN = "[a-zA-Z0-9_\\-\\|\\.\\u4e00-\\u9fa5]+";

    /** 防止通过多层百分号编码绕过 profile 私有目录检查的最大解码次数。 */
    private static final int MAX_PROTECTED_PATH_DECODE_PASSES = 3;

    /** 工作流附件正文只能经过对象授权接口读取，禁止进入任何 profile 通用资源链。 */
    private static final String WORKFLOW_ATTACHMENT_DIRECTORY = "workflow-attachments";

    /**
     * 输出指定文件的byte数组
     * 
     * @param filePath 文件路径
     * @param os 输出流
     * @return
     */
    public static void writeBytes(String filePath, OutputStream os) throws IOException
    {
        FileInputStream fis = null;
        try
        {
            File file = new File(filePath);
            if (!file.exists())
            {
                throw new FileNotFoundException(filePath);
            }
            fis = new FileInputStream(file);
            byte[] b = new byte[1024];
            int length;
            while ((length = fis.read(b)) > 0)
            {
                os.write(b, 0, length);
            }
        }
        catch (IOException e)
        {
            throw e;
        }
        finally
        {
            IOUtils.close(os);
            IOUtils.close(fis);
        }
    }

    /**
     * 写数据到文件中
     *
     * @param data 数据
     * @return 目标文件
     * @throws IOException IO异常
     */
    public static String writeImportBytes(byte[] data) throws IOException
    {
        return writeBytes(data, RuoYiConfig.getImportPath());
    }

    /**
     * 写数据到文件中
     *
     * @param data 数据
     * @param uploadDir 目标文件
     * @return 目标文件
     * @throws IOException IO异常
     */
    public static String writeBytes(byte[] data, String uploadDir) throws IOException
    {
        FileOutputStream fos = null;
        String pathName = "";
        try
        {
            String extension = getFileExtendName(data);
            pathName = DateUtils.datePath() + "/" + IdUtils.fastUUID() + "." + extension;
            File file = FileUploadUtils.getAbsoluteFile(uploadDir, pathName);
            fos = new FileOutputStream(file);
            fos.write(data);
        }
        finally
        {
            IOUtils.close(fos);
        }
        return FileUploadUtils.getPathFileName(uploadDir, pathName);
    }

    /**
     * 移除路径中的请求前缀片段
     * 
     * @param filePath 文件路径
     * @return 移除后的文件路径
     */
    public static String stripPrefix(String filePath)
    {
        return StringUtils.substringAfter(filePath, Constants.RESOURCE_PREFIX);
    }

    /**
     * 删除文件
     * 
     * @param filePath 文件
     * @return
     */
    public static boolean deleteFile(String filePath)
    {
        boolean flag = false;
        File file = new File(filePath);
        // 路径为文件且不为空则进行删除
        if (file.isFile() && file.exists())
        {
            flag = file.delete();
        }
        return flag;
    }

    /**
     * 文件名称验证
     * 
     * @param filename 文件名称
     * @return true 正常 false 非法
     */
    public static boolean isValidFilename(String filename)
    {
        return filename.matches(FILENAME_PATTERN);
    }

    /**
     * 检查文件是否允许通过若依通用下载入口读取。
     *
     * @param resource String，客户端提交的文件名或 profile 资源路径
     * @return boolean，扩展名允许且不属于私有目录时返回 true
     */
    public static boolean checkAllowDownload(String resource)
    {
        // 通用入口没有工作流对象上下文，必须在扩展名判断前统一拒绝私有附件目录。
        if (isProtectedProfilePath(resource))
        {
            return false;
        }

        // 禁止目录上跳级别
        if (StringUtils.contains(resource, ".."))
        {
            return false;
        }

        // 检查允许下载的文件规则
        if (ArrayUtils.contains(MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION, FileTypeUtils.getFileType(resource)))
        {
            return true;
        }

        // 不在允许下载的文件规则
        return false;
    }

    /**
     * 判断路径是否命中只能经过领域对象授权访问的 profile 私有目录。
     *
     * @param resourcePath String，原始、已解码或多层编码的 profile 相对/完整路径
     * @return boolean，私有目录、空路径、非法编码或超出解码上限时返回 true
     */
    public static boolean isProtectedProfilePath(String resourcePath)
    {
        if (resourcePath == null || resourcePath.trim().isEmpty())
        {
            return true;
        }

        String decoded = resourcePath;
        for (int pass = 0; pass < MAX_PROTECTED_PATH_DECODE_PASSES; pass++)
        {
            if (containsProtectedProfileSegment(decoded))
            {
                return true;
            }
            try
            {
                String next = UriUtils.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded))
                {
                    return false;
                }
                decoded = next;
            }
            catch (IllegalArgumentException invalidEncoding)
            {
                // 非法编码不能回退给文件系统解释，否则不同解码层可能形成策略分歧。
                return true;
            }
        }

        if (containsProtectedProfileSegment(decoded))
        {
            return true;
        }
        try
        {
            // 达到上限后仍可继续解码时按私有路径处理，避免任意深度编码绕过。
            return !UriUtils.decode(decoded, StandardCharsets.UTF_8).equals(decoded);
        }
        catch (IllegalArgumentException invalidEncoding)
        {
            return true;
        }
    }

    /**
     * 将公开 profile 候选文件解析为真实路径，并拒绝链接或 junction 指向私有附件根。
     *
     * @param profileRoot Path，若依 profile 配置根目录
     * @param publicRoot Path，当前入口允许访问的公开目录根
     * @param candidate Path，由受控根和请求相对路径组成的候选文件
     * @return Path，位于公开真实根内且不属于工作流私有根的普通文件真实路径
     * @throws IOException 根目录不存在、路径越界、私有别名或文件类型无法安全确认
     */
    public static Path resolvePublicProfileFile(Path profileRoot, Path publicRoot,
            Path candidate) throws IOException
    {
        if (profileRoot == null || publicRoot == null || candidate == null)
        {
            throw new IOException("profile公开文件路径不能为空");
        }
        Path normalizedProfile = profileRoot.toAbsolutePath().normalize();
        Path normalizedPublic = publicRoot.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedPublic.startsWith(normalizedProfile)
                || !normalizedCandidate.startsWith(normalizedPublic))
        {
            throw new IOException("profile公开文件路径越界");
        }

        Path realProfile = normalizedProfile.toRealPath();
        Path realPublic = normalizedPublic.toRealPath();
        if (!realPublic.startsWith(realProfile))
        {
            throw new IOException("profile公开目录真实路径越界");
        }

        Path protectedLexicalRoot = normalizedProfile
                .resolve(WORKFLOW_ATTACHMENT_DIRECTORY).normalize();
        Path protectedRealRoot = Files.exists(protectedLexicalRoot, LinkOption.NOFOLLOW_LINKS)
                ? protectedLexicalRoot.toRealPath() : realProfile
                        .resolve(WORKFLOW_ATTACHMENT_DIRECTORY).normalize();
        if (isWithin(realPublic, protectedRealRoot)
                || isWithin(realPublic, protectedLexicalRoot))
        {
            throw new IOException("profile公开目录不能指向工作流私有目录");
        }

        Path realCandidate = normalizedCandidate.toRealPath();
        if (!Files.isRegularFile(realCandidate, LinkOption.NOFOLLOW_LINKS)
                || !isWithin(realCandidate, realPublic)
                || !isWithin(realCandidate, realProfile)
                || isWithin(realCandidate, protectedRealRoot)
                || isWithin(realCandidate, protectedLexicalRoot))
        {
            throw new IOException("profile文件真实路径不允许公开访问");
        }
        return realCandidate;
    }

    /**
     * 按路径目录边界判断候选是否等于根或位于根的后代。
     *
     * @param candidate Path，已规范化或真实化的候选路径
     * @param root Path，已规范化或真实化的目录根
     * @return boolean，候选位于根边界内时返回 true
     */
    private static boolean isWithin(Path candidate, Path root)
    {
        return candidate.equals(root) || candidate.startsWith(root);
    }

    /**
     * 统一路径分隔符后按完整目录段识别工作流附件私有目录。
     *
     * @param path String，某一解码层的待检查路径
     * @return boolean，包含不区分大小写的私有目录段时返回 true
     */
    private static boolean containsProtectedProfileSegment(String path)
    {
        String normalized = path.replace('\\', '/');
        for (String segment : normalized.split("/", -1))
        {
            if (WORKFLOW_ATTACHMENT_DIRECTORY.equalsIgnoreCase(segment))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 下载文件名重新编码
     * 
     * @param request 请求对象
     * @param fileName 文件名
     * @return 编码后的文件名
     */
    public static String setFileDownloadHeader(HttpServletRequest request, String fileName) throws UnsupportedEncodingException
    {
        final String agent = request.getHeader("USER-AGENT");
        String filename = fileName;
        if (agent.contains("MSIE"))
        {
            // IE浏览器
            filename = URLEncoder.encode(filename, "utf-8");
            filename = filename.replace("+", " ");
        }
        else if (agent.contains("Firefox"))
        {
            // 火狐浏览器
            filename = new String(fileName.getBytes(), "ISO8859-1");
        }
        else if (agent.contains("Chrome"))
        {
            // google浏览器
            filename = URLEncoder.encode(filename, "utf-8");
        }
        else
        {
            // 其它浏览器
            filename = URLEncoder.encode(filename, "utf-8");
        }
        return filename;
    }

    /**
     * 下载文件名重新编码
     *
     * @param response 响应对象
     * @param realFileName 真实文件名
     */
    public static void setAttachmentResponseHeader(HttpServletResponse response, String realFileName) throws UnsupportedEncodingException
    {
        String percentEncodedFileName = percentEncode(realFileName);

        StringBuilder contentDispositionValue = new StringBuilder();
        contentDispositionValue.append("attachment; filename=")
                .append(percentEncodedFileName)
                .append(";")
                .append("filename*=")
                .append("utf-8''")
                .append(percentEncodedFileName);

        response.addHeader("Access-Control-Expose-Headers", "Content-Disposition,download-filename");
        response.setHeader("Content-disposition", contentDispositionValue.toString());
        response.setHeader("download-filename", percentEncodedFileName);
    }

    /**
     * 百分号编码工具方法
     *
     * @param s 需要百分号编码的字符串
     * @return 百分号编码后的字符串
     */
    public static String percentEncode(String s) throws UnsupportedEncodingException
    {
        String encode = URLEncoder.encode(s, StandardCharsets.UTF_8.toString());
        return encode.replaceAll("\\+", "%20");
    }

    /**
     * 获取图像后缀
     * 
     * @param photoByte 图像数据
     * @return 后缀名
     */
    public static String getFileExtendName(byte[] photoByte)
    {
        String strFileExtendName = "jpg";
        if ((photoByte[0] == 71) && (photoByte[1] == 73) && (photoByte[2] == 70) && (photoByte[3] == 56)
                && ((photoByte[4] == 55) || (photoByte[4] == 57)) && (photoByte[5] == 97))
        {
            strFileExtendName = "gif";
        }
        else if ((photoByte[6] == 74) && (photoByte[7] == 70) && (photoByte[8] == 73) && (photoByte[9] == 70))
        {
            strFileExtendName = "jpg";
        }
        else if ((photoByte[0] == 66) && (photoByte[1] == 77))
        {
            strFileExtendName = "bmp";
        }
        else if ((photoByte[1] == 80) && (photoByte[2] == 78) && (photoByte[3] == 71))
        {
            strFileExtendName = "png";
        }
        return strFileExtendName;
    }

    /**
     * 获取文件名称 /profile/upload/2022/04/16/ruoyi.png -- ruoyi.png
     * 
     * @param fileName 路径名称
     * @return 没有文件路径的名称
     */
    public static String getName(String fileName)
    {
        if (fileName == null)
        {
            return null;
        }
        int lastUnixPos = fileName.lastIndexOf('/');
        int lastWindowsPos = fileName.lastIndexOf('\\');
        int index = Math.max(lastUnixPos, lastWindowsPos);
        return fileName.substring(index + 1);
    }

    /**
     * 获取不带后缀文件名称 /profile/upload/2022/04/16/ruoyi.png -- ruoyi
     * 
     * @param fileName 路径名称
     * @return 没有文件路径和后缀的名称
     */
    public static String getNameNotSuffix(String fileName)
    {
        if (fileName == null)
        {
            return null;
        }
        String baseName = FilenameUtils.getBaseName(fileName);
        return baseName;
    }
}
