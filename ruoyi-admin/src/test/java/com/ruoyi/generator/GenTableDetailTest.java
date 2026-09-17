package com.ruoyi.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import javax.sql.DataSource;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.generator.controller.GenController;
import com.ruoyi.generator.domain.GenTable;
import com.ruoyi.generator.domain.GenTableColumn;
import com.ruoyi.generator.mapper.GenTableColumnMapper;
import com.ruoyi.generator.mapper.GenTableMapper;
import com.ruoyi.generator.service.GenTableColumnServiceImpl;
import com.ruoyi.generator.service.GenTableServiceImpl;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * 生产 SQL/MyBatis、事务服务、MVC JSON 和 Velocity；默认使用正式生成器 DDL 的 H2。
 * MySQL 子类复用相同业务断言。MVC 不含登录过滤器或方法权限代理，不声称完整 HTTP 验收。
 */
public class GenTableDetailTest
{
    private static final String TABLE_MAPPER = "com.ruoyi.generator.mapper.GenTableMapper.";
    private static final String COLUMN_QUERY = "com.ruoyi.generator.mapper.GenTableColumnMapper.selectGenTableColumnListByTableId";
    protected final String owner = "l03_" + UUID.randomUUID().toString().replace("-", "");
    private long sequence = (1L << 40) + (UUID.randomUUID().getMostSignificantBits() & 0xFFFFFFFFFFL) * 100;
    private final List<Long> tableIds = new ArrayList<>();
    private final Queries queries = new Queries();
    protected JdbcTemplate jdbc;
    protected GenTableMapper tables;
    protected GenTableServiceImpl service;
    protected MockMvc mvc;

    protected DataSource dataSource() throws Exception
    {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + owner + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var schema = new JdbcTemplate(source);
        schema.execute("SET BUILTIN_ALIAS_OVERRIDE TRUE");
        schema.execute("CREATE ALIAS SYSDATE FOR 'com.ruoyi.generator.GenTableDetailTest.sysdate'");
        String baseline = Files.readString(Path.of("../sql/ry_20260417.sql"));
        for (String table : List.of("gen_table", "gen_table_column"))
        {
            var ddl = Pattern.compile("create table " + table + " \\([\\s\\S]*?;").matcher(baseline);
            assertThat(ddl.find()).as("正式 %s DDL", table).isTrue();
            schema.execute(ddl.group());
        }
        return source;
    }

    protected void closeDatabase()
    {
        jdbc.execute("SHUTDOWN");
    }

    /** H2 仅适配 MySQL 时钟，生产 Mapper SQL 原样执行。 */
    public static Timestamp sysdate()
    {
        return new Timestamp(System.currentTimeMillis());
    }

    @BeforeEach
    void setUp() throws Exception
    {
        DataSource source = dataSource();
        jdbc = new JdbcTemplate(source);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliases(GenTable.class, GenTableColumn.class);
        factory.setMapperLocations(new ClassPathResource("mapper/generator/GenTableMapper.xml"),
                new ClassPathResource("mapper/generator/GenTableColumnMapper.xml"));
        factory.setPlugins(queries);
        var session = new SqlSessionTemplate(factory.getObject());
        tables = session.getMapper(GenTableMapper.class);
        var columns = session.getMapper(GenTableColumnMapper.class);
        service = new GenTableServiceImpl();
        ReflectionTestUtils.setField(service, "genTableMapper", tables);
        ReflectionTestUtils.setField(service, "genTableColumnMapper", columns);
        var proxy = new ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (GenTableServiceImpl) proxy.getProxy();
        var columnService = new GenTableColumnServiceImpl();
        ReflectionTestUtils.setField(columnService, "genTableColumnMapper", columns);
        var controller = new GenController();
        ReflectionTestUtils.setField(controller, "genTableService", service);
        ReflectionTestUtils.setField(controller, "genTableColumnService", columnService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void cleanOwnedFixtures()
    {
        if (jdbc == null) return;
        try
        {
            for (Long tableId : tableIds)
            {
                jdbc.update("delete from gen_table_column where table_id=?", tableId);
                jdbc.update("delete from gen_table where table_id=? and table_name like ?", tableId, owner + "%");
                assertThat(jdbc.queryForObject("select count(*) from gen_table_column where table_id=?", Long.class, tableId)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from gen_table where table_id=?", Long.class, tableId)).isZero();
            }
        }
        finally
        {
            closeDatabase();
        }
    }

    @Test
    void detailPreservesEveryColumnFieldAndSortWithoutAnotherColumnRead() throws Exception
    {
        long id = table("invoice", "Invoice", "crud");
        long label = column(id, "display_name", "displayName", "String", 20, false);
        long key = column(id, "record_id", "recordId", "Long", 10, true);
        JSONObject data = detail(id);
        assertThat(queries.ids).containsExactly(TABLE_MAPPER + "selectGenTableById", TABLE_MAPPER + "selectGenTableAll");
        JSONObject info = data.getJSONObject("info");
        assertInfo(info, id, "invoice", "Invoice", "crud", false);
        assertColumns(data.getJSONArray("rows"), id, new long[] {key, label},
                new String[] {"record_id", "display_name"}, new String[] {"recordId", "displayName"},
                new String[] {"Long", "String"}, new int[] {10, 20});
        assertThat(info.getJSONArray("columns")).isEqualTo(data.getJSONArray("rows"));
        JSONObject catalog = catalog(data, id);
        assertInfo(catalog, id, "invoice", "Invoice", "crud", true);
        assertThat(catalog.getJSONArray("columns")).isEqualTo(data.getJSONArray("rows"));
        // By-name 是下载/同步/子表装载的真实入口，也必须保留完整独立元数据。
        GenTable named = tables.selectGenTableByName(owner + "_invoice");
        assertThat(named.getColumns()).extracting(GenTableColumn::getColumnId).containsExactly(key, label);
        assertThat(named.getColumns().get(1).getCreateBy()).isEqualTo("column-" + label);
        assertThat(named.getCreateBy()).isNull();
    }

    @Test
    void emptyLeftJoinNeverCreatesAColumnFromTheParentIdOrAudit() throws Exception
    {
        long id = table("empty", "Empty", "crud");
        JSONObject data = detail(id);
        assertThat(data.getJSONArray("rows")).isEmpty();
        assertThat(data.getJSONObject("info").getJSONArray("columns")).isEmpty();
        assertThat(catalog(data, id).getJSONArray("columns")).isEmpty();
        assertThat(tables.selectGenTableByName(owner + "_empty").getColumns()).isEmpty();
    }

    @Test
    void catalogKeepsEachTablesColumnsWhenNamesAndSortValuesOverlap() throws Exception
    {
        long parent = table("invoice", "Invoice", "sub");
        long child = table("line", "InvoiceLine", "crud");
        long empty = table("empty", "Empty", "crud");
        long parentLabel = column(parent, "display_name", "displayName", "String", 20, false);
        long childLabel = column(child, "display_name", "lineName", "String", 20, false);
        long childKey = column(child, "record_id", "recordId", "Long", 10, true);
        long parentKey = column(parent, "record_id", "recordId", "Long", 10, true);
        long fk = column(child, "parent_id", "parentId", "Long", 30, false);
        JSONObject data = detail(parent);
        assertColumns(catalog(data, parent).getJSONArray("columns"), parent, new long[] {parentKey, parentLabel},
                new String[] {"record_id", "display_name"}, new String[] {"recordId", "displayName"},
                new String[] {"Long", "String"}, new int[] {10, 20});
        assertColumns(catalog(data, child).getJSONArray("columns"), child, new long[] {childKey, childLabel, fk},
                new String[] {"record_id", "display_name", "parent_id"}, new String[] {"recordId", "lineName", "parentId"},
                new String[] {"Long", "String", "Long"}, new int[] {10, 20, 30});
        assertThat(catalog(data, empty).getJSONArray("columns")).isEmpty();
    }

    @Test
    void nullableColumnAuditDoesNotInheritParentAuditAndIndependentColumnEndpointStillWorks() throws Exception
    {
        long id = table("invoice", "Invoice", "crud");
        long key = column(id, "record_id", "recordId", "Long", 10, true);
        jdbc.update("update gen_table_column set create_by=null,create_time=null,update_by=null,update_time=null where column_id=?", key);
        JSONObject column = detail(id).getJSONArray("rows").getJSONObject(0);
        for (String field : List.of("createBy", "createTime", "updateBy", "updateTime"))
            assertThat(column).containsEntry(field, null);
        queries.ids.clear();
        // 既有入口的参数由同名请求参数绑定；本项不改变其签名或路由。
        JSONObject response = response("/tool/gen/column/" + id + "?tableId=" + id);
        assertThat(response.getLongValue("total")).isEqualTo(1);
        assertThat(response.getJSONArray("rows")).containsExactly(column);
        assertThat(queries.ids).containsExactly(COLUMN_QUERY);
    }

    @Test
    void generatorListKeepsItsOriginalParentProjectionAndFiltering() throws Exception
    {
        long first = table("invoice", "Invoice", "crud");
        table("line", "InvoiceLine", "crud");
        var filter = new GenTable();
        filter.setTableName(owner + "_invoice");
        List<GenTable> found = service.selectGenTableList(filter);
        assertThat(found).hasSize(1);
        GenTable info = found.get(0);
        assertThat(info.getTableId()).isEqualTo(first);
        assertThat(info.getCreateBy()).isEqualTo("parent-" + first);
        assertThat(info.getCreateTime()).isEqualTo(Timestamp.valueOf("2025-01-02 03:04:05"));
        assertThat(info.getUpdateBy()).isEqualTo("parent-edit-" + first);
        assertThat(info.getUpdateTime()).isEqualTo(Timestamp.valueOf("2025-02-03 04:05:06"));
        assertThat(info.getGenType()).isEqualTo("0");
        assertThat(info.getGenPath()).isEqualTo("/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"crud", "tree", "sub"})
    void editSaveReloadAndVelocityDownloadUseTheEditedColumns(String category) throws Exception
    {
        long id = table("invoice", "Invoice", category);
        column(id, "record_id", "recordId", "Long", 10, true);
        long label = column(id, "display_name", "displayName", "String", 20, false);
        column(id, "parent_id", "parentId", "Long", 30, false);
        if (category.equals("sub"))
        {
            long child = table("line", "InvoiceLine", "crud");
            column(child, "record_id", "recordId", "Long", 10, true);
            column(child, "parent_id", "parentId", "Long", 20, false);
            column(child, "display_name", "lineName", "String", 30, false);
        }
        JSONObject initial = detail(id);
        JSONObject edit = initial.getJSONObject("info");
        assertThat(edit.getString("treeCode")).isEqualTo("record_id");
        assertThat(edit.getString("treeParentCode")).isEqualTo("parent_id");
        assertThat(edit.getString("treeName")).isEqualTo("display_name");
        edit.put("functionName", "Edited invoices");
        JSONArray rows = initial.getJSONArray("rows");
        JSONObject changed = rows.getJSONObject(1);
        changed.put("javaField", "caption");
        changed.put("columnComment", "Edited caption");
        changed.put("queryType", "LIKE");
        changed.put("htmlType", "textarea");
        changed.put("dictType", "");
        changed.put("sort", 5);
        changed.put("updateBy", "column-editor");
        edit.put("columns", rows);
        edit.put("params", JSON.parseObject("{\"treeCode\":\"record_id\",\"treeParentCode\":\"parent_id\",\"treeName\":\"display_name\",\"genView\":\"1\",\"parentMenuId\":7}"));
        if (category.equals("sub"))
        {
            JSONObject choice = initial.getJSONArray("tables").stream().map(JSONObject.class::cast)
                    .filter(t -> t.getString("tableName").equals(owner + "_line")).findFirst().orElseThrow();
            assertThat(choice.getJSONArray("columns").stream().map(c -> ((JSONObject) c).getString("columnName")))
                    .containsExactly("record_id", "parent_id", "display_name");
            edit.put("subTableName", choice.getString("tableName"));
            edit.put("subTableFkName", "parent_id");
        }
        String saved = mvc.perform(put("/tool/gen").contentType(MediaType.APPLICATION_JSON).content(edit.toJSONString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JSON.parseObject(saved).getIntValue("code")).isEqualTo(200);
        JSONObject reloaded = detail(id);
        JSONArray columns = reloaded.getJSONArray("rows");
        assertThat(columns.stream().map(c -> ((JSONObject) c).getString("columnName")))
                .containsExactly("display_name", "record_id", "parent_id");
        JSONObject first = columns.getJSONObject(0);
        assertThat(first).containsEntry("columnId", label).containsEntry("tableId", id)
                .containsEntry("javaField", "caption").containsEntry("columnComment", "Edited caption")
                .containsEntry("queryType", "LIKE").containsEntry("htmlType", "textarea")
                .containsEntry("dictType", "").containsEntry("createBy", "column-" + label)
                .containsEntry("updateBy", "column-editor");
        assertThat(first.getString("createTime")).isEqualTo(jsonDate("2024-03-04 05:06:07"));
        assertThat(first.getString("updateTime")).isNotBlank().isNotEqualTo(jsonDate("2024-04-05 06:07:08"));
        JSONObject info = reloaded.getJSONObject("info");
        assertThat(info.getString("functionName")).isEqualTo("Edited invoices");
        assertThat(info.getLongValue("parentMenuId")).isEqualTo(7);
        assertThat(info.getBooleanValue("view")).isTrue();
        assertThat(info.getString("treeName")).isEqualTo("display_name");
        assertThat(info.getJSONArray("columns")).isEqualTo(columns);
        assertThat(jdbc.queryForObject("select java_field from gen_table_column where column_id=?", String.class, label)).isEqualTo("caption");

        JSONObject preview = response("/tool/gen/preview/" + id).getJSONObject("data");
        assertGenerated(preview.getString("vm/java/domain.java.vm"), preview.getString("vm/xml/mapper.xml.vm"),
                preview.getString(category.equals("tree") ? "vm/vue/v3/index-tree.vue.vm" : "vm/vue/v3/index.vue.vm"), category);
        assertThat(preview.getString("vm/sql/sql.vm")).contains("7", "fixture:invoice:list");
        assertThat(preview).containsKey("vm/vue/v3/view.vue.vm");
        byte[] downloaded = mvc.perform(get("/tool/gen/download/" + owner + "_invoice"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, String> zip = unzip(downloaded);
        assertGenerated(zip.get("main/java/com/ruoyi/fixture/domain/Invoice.java"),
                zip.get("main/resources/mapper/fixture/InvoiceMapper.xml"), zip.get("vue/views/fixture/invoice/index.vue"), category);
        if (category.equals("sub"))
        {
            assertThat(info.getString("subTableName")).isEqualTo(owner + "_line");
            assertThat(info.getString("subTableFkName")).isEqualTo("parent_id");
            assertThat(zip.get("main/java/com/ruoyi/fixture/domain/InvoiceLine.java")).contains("private String lineName;");
            assertThat(preview.getString("vm/java/sub-domain.java.vm")).contains("private String lineName;");
        }
    }

    private void assertGenerated(String domain, String mapper, String vue, String category)
    {
        assertThat(domain).contains("class Invoice extends " + (category.equals("tree") ? "TreeEntity" : "BaseEntity"),
                "private String caption;", "private Long recordId;").doesNotContain("private String displayName;");
        assertThat(mapper).contains("select display_name, record_id, parent_id from " + owner + "_invoice",
                "display_name like concat('%', #{caption}, '%')", "where record_id = #{recordId}");
        assertThat(vue).contains("form.caption", "Edited caption");
        if (category.equals("tree"))
            assertThat(vue).contains("row-key=\"recordId\"", "\"recordId\", \"parentId\"");
        if (category.equals("sub"))
        {
            assertThat(domain).contains("List<InvoiceLine> invoiceLineList");
            assertThat(mapper).contains("selectInvoiceLineList", "from " + owner + "_line", "where parent_id = #{parent_id}");
            assertThat(vue).contains("invoiceLineList", "lineName");
        }
    }

    protected long table(String suffix, String className, String category)
    {
        long id = ++sequence;
        tableIds.add(id);
        jdbc.update("""
                insert into gen_table (table_id,table_name,table_comment,class_name,tpl_category,tpl_web_type,
                    package_name,module_name,business_name,function_name,function_author,form_col_num,gen_type,gen_path,
                    options,create_by,create_time,update_by,update_time,remark)
                values (?,?,?,?,?,'element-plus','com.ruoyi.fixture','fixture',?,'Invoices','Fixture author',2,'0','/',?,?,?,?,?,?)
                """, id, owner + "_" + suffix, "Table " + suffix, className, category, suffix,
                "{\"treeCode\":\"record_id\",\"treeParentCode\":\"parent_id\",\"treeName\":\"display_name\",\"parentMenuId\":3,\"parentMenuName\":\"Tools\",\"genView\":true}",
                "parent-" + id, Timestamp.valueOf("2025-01-02 03:04:05"), "parent-edit-" + id,
                Timestamp.valueOf("2025-02-03 04:05:06"), "Parent remark");
        return id;
    }

    private long column(long tableId, String name, String field, String type, int sort, boolean pk)
    {
        long id = ++sequence;
        jdbc.update("""
                insert into gen_table_column (column_id,table_id,column_name,column_comment,column_type,java_type,java_field,
                    is_pk,is_increment,is_required,is_insert,is_edit,is_list,is_query,query_type,html_type,dict_type,sort,
                    create_by,create_time,update_by,update_time)
                values (?,?,?,?,?,?,?,?,?,'1','1',?,'1',?,'EQ',?,?,?, ?,?,?,?)
                """, id, tableId, name, "Column " + name, type.equals("String") ? "varchar(80)" : "bigint", type, field,
                pk ? "1" : "0", pk ? "1" : "0", pk ? "0" : "1", pk ? "0" : "1",
                type.equals("String") ? "select" : "input", type.equals("String") ? "fixture_status" : "", sort,
                "column-" + id, Timestamp.valueOf("2024-03-04 05:06:07"), "column-edit-" + id,
                Timestamp.valueOf("2024-04-05 06:07:08"));
        return id;
    }

    private JSONObject detail(long id) throws Exception
    {
        queries.ids.clear();
        JSONObject result = response("/tool/gen/" + id);
        assertThat(result.getIntValue("code")).isEqualTo(200);
        JSONObject data = result.getJSONObject("data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("info", "rows", "tables");
        return data;
    }

    protected JSONObject response(String url) throws Exception
    {
        return JSON.parseObject(mvc.perform(get(url)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JSONObject catalog(JSONObject data, long id)
    {
        return data.getJSONArray("tables").stream().map(JSONObject.class::cast)
                .filter(t -> t.getLongValue("tableId") == id).findFirst().orElseThrow();
    }

    private void assertInfo(JSONObject info, long id, String suffix, String className, String category, boolean catalog)
    {
        assertThat(info.keySet()).containsExactlyInAnyOrder("tableId", "tableName", "tableComment", "subTableName",
                "subTableFkName", "className", "tplCategory", "tplWebType", "packageName", "moduleName", "businessName",
                "functionName", "functionAuthor", "formColNum", "genType", "genPath", "pkColumn", "subTable", "columns",
                "options", "treeCode", "treeParentCode", "treeName", "parentMenuId", "parentMenuName", "view",
                "sub", "tree", "crud", "createBy", "createTime", "updateBy", "updateTime", "remark");
        assertThat(info).containsEntry("tableId", id).containsEntry("tableName", owner + "_" + suffix)
                .containsEntry("tableComment", "Table " + suffix).containsEntry("className", className)
                .containsEntry("tplCategory", category).containsEntry("tplWebType", "element-plus")
                .containsEntry("packageName", "com.ruoyi.fixture").containsEntry("moduleName", "fixture")
                .containsEntry("businessName", suffix).containsEntry("functionName", "Invoices")
                .containsEntry("functionAuthor", "Fixture author").containsEntry("formColNum", 2)
                .containsEntry("genType", catalog ? null : "0").containsEntry("genPath", catalog ? null : "/")
                .containsEntry("remark", "Parent remark");
        // 父表审计原本未在三个联表 SELECT 中投影；新增列审计不能悄悄填充父表字段。
        for (String key : List.of("createBy", "createTime", "updateBy", "updateTime", "subTableName", "subTableFkName", "pkColumn", "subTable"))
            assertThat(info).containsEntry(key, null);
        assertThat(info.getString("treeCode")).isEqualTo(catalog ? null : "record_id");
        assertThat(info.getString("treeParentCode")).isEqualTo(catalog ? null : "parent_id");
        assertThat(info.getString("treeName")).isEqualTo(catalog ? null : "display_name");
        assertThat(info.getString("parentMenuName")).isEqualTo(catalog ? null : "Tools");
        assertThat(info.get("parentMenuId")).isEqualTo(catalog ? null : 3);
        assertThat(info.getBooleanValue("view")).isEqualTo(!catalog);
        assertThat(JSON.parseObject(info.getString("options")).getString("treeName")).isEqualTo("display_name");
    }

    private void assertColumns(JSONArray rows, long tableId, long[] ids, String[] names, String[] fields, String[] types, int[] sort)
    {
        assertThat(rows).hasSize(ids.length);
        for (int i = 0; i < ids.length; i++)
        {
            JSONObject row = rows.getJSONObject(i);
            boolean pk = names[i].equals("record_id");
            boolean text = types[i].equals("String");
            assertThat(row.keySet()).containsExactlyInAnyOrder("columnId", "tableId", "columnName", "columnComment",
                    "columnType", "javaType", "javaField", "capJavaField", "isPk", "pk", "isIncrement", "increment",
                    "isRequired", "required", "isInsert", "insert", "isEdit", "edit", "isList", "list", "isQuery", "query",
                    "queryType", "htmlType", "dictType", "sort", "createBy", "createTime", "updateBy", "updateTime",
                    "remark", "superColumn", "usableColumn");
            assertThat(row).containsEntry("columnId", ids[i]).containsEntry("tableId", tableId)
                    .containsEntry("columnName", names[i]).containsEntry("columnComment", "Column " + names[i])
                    .containsEntry("columnType", text ? "varchar(80)" : "bigint").containsEntry("javaType", types[i])
                    .containsEntry("javaField", fields[i]).containsEntry("capJavaField", Character.toUpperCase(fields[i].charAt(0)) + fields[i].substring(1))
                    .containsEntry("isPk", pk ? "1" : "0").containsEntry("pk", pk)
                    .containsEntry("isIncrement", pk ? "1" : "0").containsEntry("increment", pk)
                    .containsEntry("isRequired", "1").containsEntry("required", true)
                    .containsEntry("isInsert", "1").containsEntry("insert", true)
                    .containsEntry("isEdit", pk ? "0" : "1").containsEntry("edit", !pk)
                    .containsEntry("isList", "1").containsEntry("list", true)
                    .containsEntry("isQuery", pk ? "0" : "1").containsEntry("query", !pk)
                    .containsEntry("queryType", "EQ").containsEntry("htmlType", text ? "select" : "input")
                    .containsEntry("dictType", text ? "fixture_status" : "").containsEntry("sort", sort[i])
                    .containsEntry("createBy", "column-" + ids[i]).containsEntry("updateBy", "column-edit-" + ids[i])
                    .containsEntry("createTime", jsonDate("2024-03-04 05:06:07"))
                    .containsEntry("updateTime", jsonDate("2024-04-05 06:07:08"))
                    .containsEntry("remark", null).containsEntry("superColumn", fields[i].equals("parentId"))
                    .containsEntry("usableColumn", fields[i].equals("parentId"));
        }
    }

    private static String jsonDate(String local)
    {
        var format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(Timestamp.valueOf(local));
    }

    private static Map<String, String> unzip(byte[] data) throws Exception
    {
        var files = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.UTF_8))
        {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(files).isNotEmpty();
        return files;
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class Queries implements Interceptor
    {
        final List<String> ids = new ArrayList<>();
        @Override public Object intercept(Invocation invocation) throws Throwable
        {
            ids.add(((MappedStatement) invocation.getArgs()[0]).getId());
            return invocation.proceed();
        }
    }
}
