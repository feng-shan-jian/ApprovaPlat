package com.ruoyi.web.controller.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.framework.config.ServerConfig;

/**
 * 通用请求处理
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/common")
public class CommonController
{
    private static final Logger log = LoggerFactory.getLogger(CommonController.class);

    @Autowired
    private ServerConfig serverConfig;

    private static final String FILE_DELIMITER = ",";

    /**
     * 从固定 download 目录处理通用文件下载，并拒绝任何 profile 私有目录语义。
     *
     * @param fileName String，客户端提交的下载目录内文件名
     * @param delete Boolean，下载完成后是否删除临时导出文件
     * @param response HttpServletResponse，文件或拒绝状态响应
     * @param request HttpServletRequest，用于生成兼容下载响应头的当前请求
     * @return void，文件内容直接写入响应
     */
    @GetMapping("/download")
    public void fileDownload(String fileName, Boolean delete, HttpServletResponse response, HttpServletRequest request)
    {
        try
        {
            if (!FileUtils.checkAllowDownload(fileName))
            {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            String realFileName = System.currentTimeMillis()
                    + fileName.substring(fileName.indexOf("_") + 1);
            Path profileRoot = Path.of(RuoYiConfig.getProfile());
            Path publicDownloadRoot = Path.of(RuoYiConfig.getDownloadPath());
            Path safeRealFile = FileUtils.resolvePublicProfileFile(profileRoot,
                    publicDownloadRoot, publicDownloadRoot.resolve(fileName));

            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            FileUtils.setAttachmentResponseHeader(response, realFileName);
            FileUtils.writeBytes(safeRealFile.toString(), response.getOutputStream());
            if (Boolean.TRUE.equals(delete))
            {
                Files.deleteIfExists(safeRealFile);
            }
        }
        catch (Exception e)
        {
            if (!response.isCommitted())
            {
                response.reset();
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
            log.error("下载文件失败", e);
        }
    }

    /**
     * 通用上传请求（单个）
     */
    @PostMapping("/upload")
    public AjaxResult uploadFile(MultipartFile file) throws Exception
    {
        try
        {
            // 上传文件路径
            String filePath = RuoYiConfig.getUploadPath();
            // 上传并返回新文件名称
            String fileName = FileUploadUtils.upload(filePath, file);
            String url = serverConfig.getUrl() + fileName;
            AjaxResult ajax = AjaxResult.success();
            ajax.put("url", url);
            ajax.put("fileName", fileName);
            ajax.put("newFileName", FileUtils.getName(fileName));
            ajax.put("originalFilename", file.getOriginalFilename());
            return ajax;
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 通用上传请求（多个）
     */
    @PostMapping("/uploads")
    public AjaxResult uploadFiles(List<MultipartFile> files) throws Exception
    {
        try
        {
            // 上传文件路径
            String filePath = RuoYiConfig.getUploadPath();
            List<String> urls = new ArrayList<String>();
            List<String> fileNames = new ArrayList<String>();
            List<String> newFileNames = new ArrayList<String>();
            List<String> originalFilenames = new ArrayList<String>();
            for (MultipartFile file : files)
            {
                // 上传并返回新文件名称
                String fileName = FileUploadUtils.upload(filePath, file);
                String url = serverConfig.getUrl() + fileName;
                urls.add(url);
                fileNames.add(fileName);
                newFileNames.add(FileUtils.getName(fileName));
                originalFilenames.add(file.getOriginalFilename());
            }
            AjaxResult ajax = AjaxResult.success();
            ajax.put("urls", StringUtils.join(urls, FILE_DELIMITER));
            ajax.put("fileNames", StringUtils.join(fileNames, FILE_DELIMITER));
            ajax.put("newFileNames", StringUtils.join(newFileNames, FILE_DELIMITER));
            ajax.put("originalFilenames", StringUtils.join(originalFilenames, FILE_DELIMITER));
            return ajax;
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 从公开 profile 目录处理本地资源下载，私有工作流附件必须走对象授权接口。
     *
     * @param resource String，形如 /profile/upload/... 的公开资源路径
     * @param request HttpServletRequest，当前下载请求
     * @param response HttpServletResponse，文件或拒绝状态响应
     * @return void，文件内容直接写入响应
     */
    @GetMapping("/download/resource")
    public void resourceDownload(String resource, HttpServletRequest request, HttpServletResponse response)
            throws Exception
    {
        try
        {
            if (!FileUtils.checkAllowDownload(resource))
            {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            // 本地资源路径
            String localPath = RuoYiConfig.getProfile();
            // 数据库资源地址
            Path profileRoot = Path.of(localPath);
            Path candidate = Path.of(localPath + FileUtils.stripPrefix(resource));
            Path safeRealFile = FileUtils.resolvePublicProfileFile(
                    profileRoot, profileRoot, candidate);
            // 下载名称
            String downloadName = safeRealFile.getFileName().toString();
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            FileUtils.setAttachmentResponseHeader(response, downloadName);
            FileUtils.writeBytes(safeRealFile.toString(), response.getOutputStream());
        }
        catch (Exception e)
        {
            if (!response.isCommitted())
            {
                response.reset();
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
            log.error("下载文件失败", e);
        }
    }
}
