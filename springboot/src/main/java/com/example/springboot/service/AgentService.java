package com.example.springboot.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.example.springboot.entity.AITaskLog;
import com.example.springboot.mapper.AITaskLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
