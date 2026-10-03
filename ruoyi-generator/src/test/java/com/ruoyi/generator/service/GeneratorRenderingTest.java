package com.ruoyi.generator.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.generator.domain.*;
import com.ruoyi.generator.mapper.GenTableMapper;
import com.ruoyi.generator.service.GenTableServiceImpl;
import com.ruoyi.generator.util.VelocityUtils;

class GeneratorRenderingTest
{
    @TempDir Path directory;

    @Test
    void previewZipAndCustomPathUseIdenticalRealTemplatesForEveryVariant() throws Exception
    {
        var mapper = mock(GenTableMapper.class);
        var service = new GenTableServiceImpl();
        ReflectionTestUtils.setField(service, "genTableMapper", mapper);
        for (String web : List.of("element-ui", "element-plus", "element-plus-typescript"))
        {
            for (String category : List.of("crud", "tree", "sub"))
            {
                var table = table("example", web, category);
                table.setGenPath(directory.resolve(web + "-" + category).toString());
                when(mapper.selectGenTableById(1L)).thenReturn(table);
                when(mapper.selectGenTableByName("example")).thenReturn(table);
                if ("sub".equals(category))
                {
                    table.setSubTableName("item"); table.setSubTableFkName("parent_id");
                    when(mapper.selectGenTableByName("item")).thenReturn(table("item", web, "crud"));
                }
                var preview = service.previewCode(1L);
                var zip = unzip(service.downloadCode("example"));
                assertEquals(preview.size(), zip.size(), web + ":" + category);
                for (var entry : preview.entrySet())
                    assertEquals(entry.getValue(), zip.get(VelocityUtils.getFileName(entry.getKey(), table)), entry.getKey());
                assertTrue(preview.keySet().stream().anyMatch(name -> name.endsWith("view.vue.vm")));
                service.generatorCode("example");
                try (var written = Files.walk(Path.of(table.getGenPath())))
                {
                    var files = written.filter(Files::isRegularFile).toList();
                    assertFalse(files.isEmpty());
                    for (var file : files)
                    {
                        String relative = Path.of(table.getGenPath()).relativize(file).toString().replace('\\', '/');
                        assertTrue(relative.endsWith(".java") || relative.endsWith(".xml"));
                        assertEquals(zip.get(relative), Files.readString(file));
                    }
                }
            }
        }
    }

    @Test
    void batchTypescriptIndexRetainsBothExports() throws Exception
    {
        var mapper = mock(GenTableMapper.class);
        var service = new GenTableServiceImpl();
        ReflectionTestUtils.setField(service, "genTableMapper", mapper);
        when(mapper.selectGenTableByName("first")).thenReturn(table("first", "element-plus-typescript", "crud"));
        when(mapper.selectGenTableByName("second")).thenReturn(table("second", "element-plus-typescript", "crud"));
        var zip = unzip(service.downloadCode(new String[] {"first", "second"}));
        var index = zip.entrySet().stream().filter(e -> e.getKey().contains("index-bak.ts")).toList();
        assertEquals(1, index.size());
        assertTrue(index.get(0).getValue().contains("./example/first"));
        assertTrue(index.get(0).getValue().contains("./example/second"));
    }

    private static GenTable table(String name, String web, String category)
    {
        var table = new GenTable();
        table.setTableId(1L); table.setTableName(name); table.setTableComment("Synthetic table");
        table.setClassName(Character.toUpperCase(name.charAt(0)) + name.substring(1));
        table.setModuleName("example"); table.setBusinessName(name); table.setPackageName("com.example.test");
        table.setFunctionName("Example"); table.setFunctionAuthor("test"); table.setFormColNum(1);
        table.setTplCategory(category); table.setTplWebType(web);
        table.setOptions("{\"genView\":true,\"treeCode\":\"id\",\"treeParentCode\":\"parent_id\",\"treeName\":\"name\"}");
        List<GenTableColumn> columns = new ArrayList<>();
        for (String columnName : List.of("id", "parent_id", "name"))
        {
            var column = new GenTableColumn();
            column.setColumnName(columnName); column.setColumnComment(columnName);
            column.setJavaField("parent_id".equals(columnName) ? "parentId" : columnName);
            column.setJavaType("name".equals(columnName) ? "String" : "Long");
            column.setColumnType("name".equals(columnName) ? "varchar(64)" : "bigint");
            column.setIsPk("id".equals(columnName) ? "1" : "0"); column.setIsIncrement("0");
            column.setIsInsert("1"); column.setIsEdit("1"); column.setIsList("1"); column.setIsQuery("1");
            column.setIsRequired("0"); column.setQueryType("EQ"); column.setHtmlType("input"); column.setDictType("");
            columns.add(column);
        }
        table.setColumns(columns);
        return table;
    }

    private static Map<String, String> unzip(byte[] bytes) throws Exception
    {
        var entries = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))
        {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                assertNull(entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8)));
        }
        return entries;
    }
}
