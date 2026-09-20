package com.ruoyi.common.utils.file;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.poifs.filesystem.FileMagic;

/**
 * 文件类型工具类
 *
 * @author ruoyi
 */
public class FileTypeUtils
{
    /**
     * 获取文件类型
     * <p>
     * 例如: ruoyi.txt, 返回: txt
     * 
     * @param file 文件名
     * @return 后缀（不含".")
     */
    public static String getFileType(File file)
    {
        if (null == file)
        {
            return StringUtils.EMPTY;
        }
        return getFileType(file.getName());
    }

    /**
     * 获取文件类型
     * <p>
     * 例如: ruoyi.txt, 返回: txt
     *
     * @param fileName 文件名
     * @return 后缀（不含".")
     */
    public static String getFileType(String fileName)
    {
        int separatorIndex = fileName.lastIndexOf(".");
        if (separatorIndex < 0)
        {
            return "";
        }
        return fileName.substring(separatorIndex + 1).toLowerCase();
    }

    /**
     * 获取文件类型
     * 
     * @param photoByte 文件字节码
     * @return 后缀（不含".")
     * @throws IllegalArgumentException 数据为空、图片不完整或无法识别
     */
    public static String getFileExtendName(byte[] photoByte)
    {
        if (photoByte == null || photoByte.length == 0)
        {
            throw new IllegalArgumentException("图片数据为空或不完整");
        }
        FileMagic type = FileMagic.valueOf(photoByte);
        String extension = switch (type)
        {
            case JPEG -> "JPG";
            case PNG, GIF, BMP, TIFF, WMF, EMF -> type.name();
            default -> throw new IllegalArgumentException("无法识别图片类型");
        };
        // ImageIO 解码栅格图片一次，避免把只有魔数的截断数据写入导入文件或 Excel。
        if (type == FileMagic.EMF || type == FileMagic.WMF)
        {
            // 矢量格式由 POI 原样嵌入；仅要求完整的 EMF/可放置 WMF 文件头。
            if (photoByte.length < (type == FileMagic.EMF ? 88 : 40))
            {
                throw new IllegalArgumentException("图片数据不完整");
            }
        }
        else
        {
            try (ByteArrayInputStream input = new ByteArrayInputStream(photoByte))
            {
                if (ImageIO.read(input) == null)
                {
                    throw new IllegalArgumentException("无法读取图片数据");
                }
            }
            catch (IOException exception)
            {
                throw new IllegalArgumentException("图片数据不完整或损坏", exception);
            }
        }
        return extension;
    }
}
