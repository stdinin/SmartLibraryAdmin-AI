package com.example.springboot.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.example.springboot.entity.AITaskLog;
import com.example.springboot.mapper.AITaskLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AgentService {

    private static final List<String> AUTO_TOOLS = Arrays.asList(
            "searchBooks", "getBookDetail", "checkBookQuality", "getBorrowStatistics",
            "findOverdueRecords", "generateBookMetadata", "generateOverdueNotice"
    );

    @Resource
    private AIService aiService;

    @Resource
    private AITaskLogMapper aiTaskLogMapper;

    public Map<String, Object> plan(Integer adminId, String task) {
        if (StrUtil.isBlank(task)) {
            throw new IllegalArgumentException("任务不能为空");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        String normalized = task.trim();
        boolean deleteIntent = containsAny(normalized, "删除", "清空", "移除");
        boolean writeIntent = containsAny(normalized, "新增", "添加", "修改", "更新", "保存", "入库");
        boolean borrowIntent = containsAny(normalized, "借阅", "逾期", "归还", "热门");
        boolean bookSearchIntent = containsAny(normalized, "找出", "查询", "搜索", "筛选", "库存", "冷门", "没人借", "无人借");

        if (deleteIntent) {
            result.put("intent", "blocked_delete_operation");
            result.put("steps", Arrays.asList("识别到删除类操作", "停止自动执行", "仅向管理员提供风险说明"));
            result.put("tools_to_call", Arrays.asList("searchBooks"));
            result.put("risk_level", "high");
            result.put("need_confirm", false);
            result.put("draft_result", new LinkedHashMap<>());
            result.put("blockedReason", "删除类操作禁止由Agent自动执行，请管理员在原页面人工确认处理。");
            aiService.saveTaskLog(adminId, "agent_plan", task, result, "blocked");
            return result;
        }

        if (writeIntent) {
            result.put("intent", "book_write_draft");
            result.put("steps", Arrays.asList("解析管理员任务", "生成新增或修改草案", "等待管理员二次确认", "确认后才允许调用原有安全接口"));
            result.put("tools_to_call", Arrays.asList("generateBookMetadata", "createBookDraft", "updateBookDraft"));
            result.put("risk_level", "medium");
            result.put("need_confirm", true);
            result.put("draft_result", draftFromTask(normalized));
            result.put("blockedReason", null);
            AITaskLog taskLog = aiService.saveTaskLog(adminId, "agent_plan", task, result, "pending_confirm");
            result.put("taskLogId", taskLog.getId());
            return result;
        }

        if (borrowIntent) {
            result.put("intent", "borrow_analysis");
            result.put("steps", Arrays.asList("读取借阅记录", "统计热门和逾期风险", "生成管理建议"));
            result.put("tools_to_call", Arrays.asList("getBorrowStatistics", "findOverdueRecords"));
            result.put("draft_result", aiService.analyzeBorrow("month", adminId));
        } else if (bookSearchIntent) {
            result.put("intent", "book_search_analysis");
            result.put("steps", Arrays.asList("解析筛选条件", "读取馆藏和借阅记录", "返回候选图书和处理建议"));
            result.put("tools_to_call", Arrays.asList("searchBooks", "getBookDetail"));
            result.put("draft_result", aiService.searchBooksForTask(normalized, adminId));
        } else {
            result.put("intent", "book_quality_analysis");
            result.put("steps", Arrays.asList("查询图书数据", "执行馆藏质检规则", "生成整改建议"));
            result.put("tools_to_call", Arrays.asList("searchBooks", "checkBookQuality"));
            result.put("draft_result", aiService.checkBookQuality(adminId));
        }
        result.put("risk_level", "low");
        result.put("need_confirm", false);
        result.put("blockedReason", null);
        AITaskLog taskLog = aiService.saveTaskLog(adminId, "agent_plan", task, result, "success");
        result.put("taskLogId", taskLog.getId());
        return result;
    }

    public Map<String, Object> confirm(Integer adminId, Long taskLogId, Boolean confirm) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (taskLogId == null) {
            result.put("executed", false);
            result.put("result", new LinkedHashMap<>());
            result.put("blockedReason", "缺少待确认任务编号");
            return result;
        }
        if (!Boolean.TRUE.equals(confirm)) {
            updateStatus(taskLogId, "cancelled");
            result.put("executed", false);
            result.put("result", new LinkedHashMap<>());
            result.put("blockedReason", "管理员已取消执行");
            return result;
        }
        AITaskLog taskLog = null;
        try {
            taskLog = aiTaskLogMapper.getById(taskLogId);
        } catch (Exception e) {
            log.warn("读取AI任务日志失败", e);
        }
        if (taskLog == null || !"pending_confirm".equals(taskLog.getStatus())) {
            result.put("executed", false);
            result.put("result", new LinkedHashMap<>());
            result.put("blockedReason", "未找到可执行的待确认方案");
            return result;
        }
        updateStatus(taskLogId, "manual_required");
        result.put("executed", false);
        result.put("result", JSONUtil.parseObj(taskLog.getOutputJson()));
        result.put("blockedReason", "当前版本只生成写操作草案，请管理员在原图书页面核对后手动保存。");
        return result;
    }

    public List<Map<String, Object>> listTasks(Integer adminId, Integer limit) {
        int safeLimit = limit == null || limit <= 0 ? 10 : Math.min(limit, 50);
        try {
            return aiTaskLogMapper.listByAdminId(adminId, safeLimit).stream()
                    .map(task -> buildTaskStatus(adminId, task))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("读取AI任务列表失败", e);
            return new ArrayList<>();
        }
    }

    public Map<String, Object> taskStatus(Integer adminId, Long taskLogId) {
        AITaskLog taskLog = loadOwnedTask(adminId, taskLogId);
        if (taskLog == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskLogId", taskLogId);
            result.put("status", "not_found");
            result.put("progress", 0);
            result.put("blockedReason", "任务不存在或无权查看");
            return result;
        }
        return buildTaskStatus(adminId, taskLog);
    }

    public Map<String, Object> cancel(Integer adminId, Long taskLogId) {
        AITaskLog taskLog = loadOwnedTask(adminId, taskLogId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskLogId", taskLogId);
        if (taskLog == null) {
            result.put("cancelled", false);
            result.put("blockedReason", "任务不存在或无权取消");
            return result;
        }
        if (!"pending_confirm".equals(taskLog.getStatus())) {
            result.put("cancelled", false);
            result.put("blockedReason", "只有等待确认的AI草案可以取消");
            result.put("task", buildTaskStatus(adminId, taskLog));
            return result;
        }
        updateStatus(taskLogId, "cancelled");
        result.put("cancelled", true);
        result.put("blockedReason", null);
        result.put("task", taskStatus(adminId, taskLogId));
        return result;
    }

    public Map<String, Object> toolPolicy() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("autoTools", AUTO_TOOLS);
        result.put("confirmTools", Arrays.asList("createBookDraft", "updateBookDraft"));
        result.put("blockedTools", Arrays.asList("deleteBook", "deleteBorrow", "deleteAdmin", "rawSql", "changePassword"));
        return result;
    }

    private Map<String, Object> draftFromTask(String task) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("rawTask", task);
        draft.put("notice", "请在原业务页面核对字段后保存，Agent不会直接写入数据库。");
        return draft;
    }

    private AITaskLog loadOwnedTask(Integer adminId, Long taskLogId) {
        if (taskLogId == null) {
            return null;
        }
        try {
            AITaskLog taskLog = aiTaskLogMapper.getById(taskLogId);
            if (taskLog == null) {
                return null;
            }
            if (adminId != null && taskLog.getAdminId() != null && !adminId.equals(taskLog.getAdminId())) {
                return null;
            }
            return taskLog;
        } catch (Exception e) {
            log.warn("读取AI任务状态失败", e);
            return null;
        }
    }

    private Map<String, Object> buildTaskStatus(Integer adminId, AITaskLog taskLog) {
        Map<String, Object> output = parseObject(taskLog.getOutputJson());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskLogId", taskLog.getId());
        result.put("taskType", taskLog.getTaskType());
        result.put("status", taskLog.getStatus());
        result.put("progress", progressOf(taskLog.getStatus()));
        result.put("createdAt", taskLog.getCreateTime());
        result.put("intent", output.get("intent"));
        result.put("risk_level", output.get("risk_level"));
        result.put("need_confirm", output.get("need_confirm"));
        result.put("blockedReason", output.get("blockedReason"));
        result.put("steps", output.get("steps"));
        result.put("tools_to_call", output.get("tools_to_call"));
        result.put("draft_result", output.get("draft_result"));
        result.put("subTasks", buildSubTasks(output, taskLog.getStatus()));
        result.put("raw", output);
        return result;
    }

    private List<Map<String, Object>> buildSubTasks(Map<String, Object> output, String status) {
        List<Map<String, Object>> subTasks = new ArrayList<>();
        Object tools = output.get("tools_to_call");
        if (tools instanceof Iterable) {
            for (Object tool : (Iterable<?>) tools) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", String.valueOf(tool));
                item.put("name", toolName(String.valueOf(tool)));
                item.put("status", subTaskStatus(status));
                subTasks.add(item);
            }
        }
        return subTasks;
    }

    private String subTaskStatus(String status) {
        if ("success".equals(status) || "manual_required".equals(status)) return "COMPLETED";
        if ("pending_confirm".equals(status)) return "WAIT_CONFIRM";
        if ("blocked".equals(status)) return "BLOCKED";
        if ("cancelled".equals(status)) return "CANCELLED";
        return "UNKNOWN";
    }

    private int progressOf(String status) {
        if ("success".equals(status) || "manual_required".equals(status) || "blocked".equals(status) || "cancelled".equals(status)) {
            return 100;
        }
        if ("pending_confirm".equals(status)) {
            return 80;
        }
        return 0;
    }

    private String toolName(String tool) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("searchBooks", "检索图书");
        names.put("getBookDetail", "读取图书详情");
        names.put("checkBookQuality", "馆藏质检");
        names.put("getBorrowStatistics", "借阅统计");
        names.put("findOverdueRecords", "逾期检索");
        names.put("generateBookMetadata", "生成编目建议");
        names.put("generateOverdueNotice", "生成逾期提醒");
        names.put("createBookDraft", "新增图书草案");
        names.put("updateBookDraft", "修改图书草案");
        return names.getOrDefault(tool, tool);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObject(String json) {
        if (StrUtil.isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return JSONUtil.parseObj(json);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private void updateStatus(Long taskLogId, String status) {
        try {
            aiTaskLogMapper.updateStatus(taskLogId, status);
        } catch (Exception e) {
            log.warn("更新AI任务状态失败", e);
        }
    }
}
