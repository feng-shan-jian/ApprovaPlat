package com.ruoyi.flowable.service.task;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.image.ProcessDiagramGenerator;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.authorization.WorkflowTaskAccessSnapshot;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDetailQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDetailView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormSnapshotView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.service.process.WorkflowProcessDetailService;

/**
 * 任务变量安全投影和实例级授权流程图读取服务。
 */
@Service
public class WorkflowTaskReadService
{
    /** Flowable 和若依任务主键的安全字符上限。 */
    private static final int MAX_ID_LENGTH = 64;

    /** 服务端生成 PNG 允许的最大字节数。 */
    private static final int MAX_DIAGRAM_BYTES = 10 * 1024 * 1024;

    /** PNG 文件固定的八字节签名。 */
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private final WorkflowEngineOperations engineOperations;

    private final WorkflowProcessAccessService accessService;

    private final WorkflowProcessDetailService detailService;

    private final RepositoryService repositoryService;

    private final HistoryService historyService;

    private final ProcessEngineConfiguration processEngineConfiguration;

    /**
     * 创建任务只读服务。
     *
     * @param engineOperations WorkflowEngineOperations，统一只读事务和异常翻译边界
     * @param accessService WorkflowProcessAccessService，实例及任务对象级授权服务
     * @param detailService WorkflowProcessDetailService，部署表单 schema 安全投影服务
     * @param repositoryService RepositoryService，BPMN 模型公共查询服务
     * @param historyService HistoryService，实例活动轨迹公共查询服务
     * @param processEngineConfiguration ProcessEngineConfiguration，Flowable 8 公共图形生成配置
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowTaskReadService(WorkflowEngineOperations engineOperations,
            WorkflowProcessAccessService accessService, WorkflowProcessDetailService detailService,
            RepositoryService repositoryService, HistoryService historyService,
            ProcessEngineConfiguration processEngineConfiguration)
    {
        this.engineOperations = engineOperations;
        this.accessService = accessService;
        this.detailService = detailService;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.processEngineConfiguration = processEngineConfiguration;
    }

    /**
     * 按任务参与者权限返回部署表单 schema 允许展示的安全流程变量。
     *
     * @param taskId String，活动或历史任务主键
     * @return Map&lt;String, JsonNode&gt;，按流程表单时间线合并且当前任务值优先的安全字段
     */
    public Map<String, JsonNode> getProcessVariables(String taskId)
    {
        String normalizedTaskId = requireId(taskId);
        return engineOperations.read(() ->
        {
            WorkflowTaskAccessSnapshot task = accessService.requireReadableTask(normalizedTaskId);
            WorkflowProcessDetailView detail = detailService.getDetail(
                    new WorkflowProcessDetailQueryDto(task.processInstanceId(), normalizedTaskId));
            if (detail == null || !task.processInstanceId().equals(detail.processInstanceId()))
            {
                throw dataError("流程变量与任务实例关联异常");
            }

            LinkedHashMap<String, JsonNode> safeValues = new LinkedHashMap<>();
            for (WorkflowProcessFormSnapshotView form : detail.processFormList())
            {
                mergeSafeValues(safeValues, form);
            }
            // 当前活动任务表单最后合并，保证页面正在编辑的局部字段覆盖同名历史字段。
            mergeSafeValues(safeValues, detail.currentTaskForm());
            return immutableJsonMap(safeValues);
        });
    }

    /**
     * 按实例参与关系生成包含历史活动和顺序流高亮的 PNG 流程图。
     *
     * @param processInstanceId String，活动或历史流程实例主键
     * @return byte[]，通过大小和 PNG 签名校验的图像字节
     */
    public byte[] generateDiagram(String processInstanceId)
    {
        String normalizedInstanceId = requireId(processInstanceId);
        return engineOperations.read(() ->
        {
            WorkflowProcessAccessSnapshot instance = accessService.requireReadableInstance(
                    normalizedInstanceId);
            if (!StringUtils.hasText(instance.processDefinitionId()))
            {
                throw dataError("流程实例缺少流程定义关联");
            }
            BpmnModel model = repositoryService.getBpmnModel(instance.processDefinitionId());
            if (model == null)
            {
                throw dataError("流程定义缺少 BPMN 模型");
            }
            requireDiagramInterchange(model);

            List<HistoricActivityInstance> activities = historyService
                    .createHistoricActivityInstanceQuery()
                    .processInstanceId(normalizedInstanceId)
                    .orderByHistoricActivityInstanceStartTime()
                    .asc()
                    .list();
            if (activities == null)
            {
                throw dataError("流程活动轨迹读取失败");
            }
            Set<String> highlightedNodes = new LinkedHashSet<>();
            Set<String> highlightedFlows = new LinkedHashSet<>();
            for (HistoricActivityInstance activity : activities)
            {
                if (activity == null || !StringUtils.hasText(activity.getActivityId()))
                {
                    continue;
                }
                if ("sequenceFlow".equals(activity.getActivityType()))
                {
                    highlightedFlows.add(activity.getActivityId());
                }
                else
                {
                    highlightedNodes.add(activity.getActivityId());
                }
            }

            ProcessDiagramGenerator generator = processEngineConfiguration.getProcessDiagramGenerator();
            if (generator == null)
            {
                throw dataError("流程图生成器不可用");
            }
            try (InputStream input = generator.generateDiagram(model, "png",
                    List.copyOf(highlightedNodes), List.copyOf(highlightedFlows),
                    processEngineConfiguration.getActivityFontName(),
                    processEngineConfiguration.getLabelFontName(),
                    processEngineConfiguration.getAnnotationFontName(),
                    processEngineConfiguration.getClassLoader(), 1.0, true))
            {
                byte[] png = readBoundedPng(input);
                assertPngSignature(png);
                return png;
            }
            catch (IOException exception)
            {
                ServiceException failure = dataError("流程图生成失败");
                failure.initCause(exception);
                throw failure;
            }
            catch (ServiceException exception)
            {
                // 签名、大小等受控校验已经具有稳定业务语义，保持原始错误码和提示。
                throw exception;
            }
            catch (RuntimeException exception)
            {
                // Flowable 图形生成器在 BPMN DI 不完整时可能抛出 NPE 等未受控异常，禁止直接泄漏。
                ServiceException failure = dataError("流程图生成失败");
                failure.initCause(exception);
                throw failure;
            }
        });
    }

    /**
     * 校验 BPMN 模型至少包含可供服务端图形生成器定位节点的 BPMN DI 信息。
     *
     * @param model BpmnModel，已从正式部署读取的流程模型
     * @return 无返回值，图形位置信息缺失时抛出稳定 HTTP 500 业务异常
     */
    private void requireDiagramInterchange(BpmnModel model)
    {
        // locationMap 是 Flowable 图形生成器计算画布边界的前置数据；空映射会触发未受控异常。
        if (model.getLocationMap() == null || model.getLocationMap().isEmpty())
        {
            throw dataError("流程定义缺少 BPMN DI 图形信息");
        }
    }

    /**
     * 将单个安全表单视图的字段深复制到结果映射。
     *
     * @param target Map&lt;String, JsonNode&gt;，正在构建的有序结果映射
     * @param form WorkflowProcessFormSnapshotView，已经过详情服务白名单投影的表单视图
     * @return 无返回值，form 为空时不修改结果
     */
    private void mergeSafeValues(Map<String, JsonNode> target,
            WorkflowProcessFormSnapshotView form)
    {
        if (form == null)
        {
            return;
        }
        form.values().forEach((name, value) -> target.put(name,
                value == null ? null : value.deepCopy()));
    }

    /**
     * 深复制并冻结安全 JSON 字段映射，避免 Controller 序列化前被调用方修改。
     *
     * @param values Map&lt;String, JsonNode&gt;，已合并的安全字段
     * @return Map&lt;String, JsonNode&gt;，不可修改的深复制映射
     */
    private Map<String, JsonNode> immutableJsonMap(Map<String, JsonNode> values)
    {
        LinkedHashMap<String, JsonNode> copied = new LinkedHashMap<>();
        values.forEach((name, value) -> copied.put(name,
                value == null ? null : value.deepCopy()));
        return Collections.unmodifiableMap(copied);
    }

    /**
     * 以固定缓冲区读取内部生成图像，并在分配过量内存前拒绝超限内容。
     *
     * @param input InputStream，Flowable 图形生成器返回的 PNG 流
     * @return byte[]，大小不超过十 MiB 的完整图像字节
     * @throws IOException 读取图像流失败时抛出
     */
    private byte[] readBoundedPng(InputStream input) throws IOException
    {
        if (input == null)
        {
            throw new IOException("流程图生成器返回空流");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1)
        {
            total += read;
            if (total > MAX_DIAGRAM_BYTES)
            {
                throw new IOException("流程图超过服务端大小上限");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /**
     * 校验生成结果具有标准 PNG 文件签名，拒绝空白或错误媒体内容。
     *
     * @param bytes byte[]，完整图像字节
     * @return 无返回值，签名不匹配时抛出 HTTP 500 业务异常
     */
    private void assertPngSignature(byte[] bytes)
    {
        if (bytes == null || bytes.length < PNG_SIGNATURE.length)
        {
            throw dataError("流程图生成结果无效");
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++)
        {
            if (bytes[index] != PNG_SIGNATURE[index])
            {
                throw dataError("流程图生成结果无效");
            }
        }
    }

    /**
     * 校验任务或实例主键并规范化首尾空白。
     *
     * @param value String，客户端提交的任务或实例主键
     * @return String，长度受控的非空主键
     */
    private String requireId(String value)
    {
        if (!StringUtils.hasText(value))
        {
            throw new ServiceException("工作流请求参数不合法", HttpStatus.BAD_REQUEST);
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_ID_LENGTH)
        {
            throw new ServiceException("工作流请求参数不合法", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 创建不暴露底层模型、变量或文件细节的稳定服务端异常。
     *
     * @param message String，供服务端和受控响应使用的稳定错误提示
     * @return ServiceException，HTTP 500 业务异常
     */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }
}
