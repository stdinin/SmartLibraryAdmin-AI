package com.example.springboot.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.springboot.entity.Book;
import com.example.springboot.entity.AITaskLog;
import com.example.springboot.mapper.AITaskLogMapper;
import com.example.springboot.service.IBookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private IBookService bookService;

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
        JSONObject output = JSONUtil.parseObj(taskLog.getOutputJson());
        Map<String, Object> execution = executeBookDraft(output);
        boolean executed = Boolean.TRUE.equals(execution.get("executed"));
        output.set("execution_result", execution);
        output.set("need_confirm", false);
        output.set("blockedReason", execution.get("blockedReason"));
        updateOutputAndStatus(taskLogId, JSONUtil.toJsonStr(output), executed ? "executed_success" : "execute_blocked");
        result.put("executed", executed);
        result.put("result", toPlainObject(output));
        result.put("blockedReason", execution.get("blockedReason"));
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
        Map<String, Object> bookDraft = new LinkedHashMap<>();
        boolean update = containsAny(task, "修改", "更新");
        String title = firstNonBlank(
                extract(task, "《([^》]+)》"),
                extract(task, "(?:新增一本|新增图书|添加一本|添加图书|入库一本|入库图书)([^，,。；;\\s]+)")
        );
        Book existingBook = update ? findBookByName(title) : null;

        bookDraft.put("id", existingBook == null ? null : existingBook.getId());
        bookDraft.put("name", firstNonBlank(title, existingBook == null ? "" : existingBook.getName()));
        bookDraft.put("author", firstNonBlank(extract(task, "作者(?:是|为|:|：)?(.+?)(?=出版社|出版方|分类|类别|库存|数量|册数|简介|描述|，|,|。|；|;|$)"), existingBook == null ? "" : existingBook.getAuthor()));
        bookDraft.put("publisher", firstNonBlank(extract(task, "(?:出版社|出版方)(?:是|为|:|：)?(.+?)(?=分类|类别|库存|数量|册数|简介|描述|，|,|。|；|;|$)"), existingBook == null ? "" : existingBook.getPublisher()));
        bookDraft.put("category", firstNonBlank(extract(task, "(?:分类|类别)(?:是|为|:|：)?(.+?)(?=库存|数量|册数|简介|描述|，|,|。|；|;|$)"), existingBook == null ? "" : existingBook.getCategory()));
        bookDraft.put("bookNo", existingBook == null ? "" : existingBook.getBookNo());
        bookDraft.put("publishDate", existingBook == null ? "" : existingBook.getPublishDate());
        bookDraft.put("cover", existingBook == null ? "" : existingBook.getCover());
        bookDraft.put("score", existingBook == null ? null : existingBook.getScore());
        bookDraft.put("nums", firstNonNull(parseInt(firstNonBlank(
                extract(task, "(?:库存|数量|册数)(?:是|为|:|：)?(\\d+)"),
                extract(task, "(\\d+)本")
        )), existingBook == null ? null : existingBook.getNums()));
        bookDraft.put("description", firstNonBlank(extract(task, "(?:简介|描述)(?:是|为|:|：)?(.+?)(?=；|;|$)"), existingBook == null ? "" : existingBook.getDescription()));

        List<String> missingFields = new ArrayList<>();
        if (StrUtil.isBlank(String.valueOf(bookDraft.get("name")))) missingFields.add("书名");
        if (update && existingBook == null) missingFields.add("目标图书");
        if (!update && StrUtil.isBlank(String.valueOf(bookDraft.get("author")))) missingFields.add("作者");
        if (!update && StrUtil.isBlank(String.valueOf(bookDraft.get("publisher")))) missingFields.add("出版社");

        draft.put("operation", update ? "updateBookDraft" : "createBookDraft");
        draft.put("bookDraft", bookDraft);
        draft.put("missingFields", missingFields);
        draft.put("matchedExistingBook", existingBook != null);
        draft.put("rawTask", task);
        draft.put("notice", "这是待确认草案，点击确认后只允许执行新增/更新图书白名单操作。");
        draft.put("nextStep", missingFields.isEmpty() ? "草案已就绪，确认后将调用原图书Service执行。" : "草案仍缺少字段：" + String.join("、", missingFields));
        return draft;
    }

    private Map<String, Object> executeBookDraft(JSONObject output) {
        Map<String, Object> result = new LinkedHashMap<>();
        JSONObject draft = output.getJSONObject("draft_result");
        if (draft == null) {
            result.put("executed", false);
            result.put("blockedReason", "草案不存在，无法执行");
            return result;
        }
        if (draft.containsKey("missingFields") && !draft.getJSONArray("missingFields").isEmpty()) {
            result.put("executed", false);
            result.put("blockedReason", "草案字段不完整，请补全后再执行：" + draft.getJSONArray("missingFields").join("、"));
            return result;
        }
        JSONObject bookDraft = draft.getJSONObject("bookDraft");
        if (bookDraft == null) {
            result.put("executed", false);
            result.put("blockedReason", "图书草案为空，无法执行");
            return result;
        }
        String operation = draft.getStr("operation");
        try {
            if ("createBookDraft".equals(operation)) {
                Book book = buildBookFromDraft(bookDraft, null);
                if (StrUtil.isBlank(book.getName()) || StrUtil.isBlank(book.getAuthor()) || StrUtil.isBlank(book.getPublisher())) {
                    result.put("executed", false);
                    result.put("blockedReason", "新增图书至少需要书名、作者、出版社");
                    return result;
                }
                if (book.getNums() == null) book.setNums(1);
                if (book.getScore() == null) book.setScore(0);
                if (StrUtil.isBlank(book.getBookNo())) book.setBookNo("AI" + System.currentTimeMillis());
                bookService.save(book);
                result.put("executed", true);
                result.put("operation", operation);
                result.put("message", "已新增图书：" + book.getName());
                result.put("bookName", book.getName());
                result.put("bookNo", book.getBookNo());
                result.put("blockedReason", null);
                return result;
            }
            if ("updateBookDraft".equals(operation)) {
                Integer id = bookDraft.getInt("id");
                if (id == null) {
                    result.put("executed", false);
                    result.put("blockedReason", "未定位到要修改的图书，不能执行更新");
                    return result;
                }
                Book oldBook = bookService.getById(id);
                if (oldBook == null) {
                    result.put("executed", false);
                    result.put("blockedReason", "目标图书不存在，不能执行更新");
                    return result;
                }
                Book book = buildBookFromDraft(bookDraft, oldBook);
                bookService.update(book);
                result.put("executed", true);
                result.put("operation", operation);
                result.put("message", "已更新图书：" + book.getName());
                result.put("bookId", book.getId());
                result.put("bookName", book.getName());
                result.put("category", book.getCategory());
                result.put("blockedReason", null);
                return result;
            }
            result.put("executed", false);
            result.put("blockedReason", "不在白名单内的操作：" + operation);
            return result;
        } catch (Exception e) {
            log.warn("执行AI图书草案失败", e);
            result.put("executed", false);
            result.put("blockedReason", "执行失败：" + e.getMessage());
            return result;
        }
    }

    private Book buildBookFromDraft(JSONObject bookDraft, Book oldBook) {
        Book book = oldBook == null ? new Book() : oldBook;
        book.setId(firstNonNull(bookDraft.getInt("id"), book.getId()));
        book.setName(firstNonBlank(bookDraft.getStr("name"), book.getName()));
        book.setAuthor(firstNonBlank(bookDraft.getStr("author"), book.getAuthor()));
        book.setPublisher(firstNonBlank(bookDraft.getStr("publisher"), book.getPublisher()));
        book.setCategory(firstNonBlank(bookDraft.getStr("category"), book.getCategory()));
        book.setCategories(splitCategory(book.getCategory()));
        book.setNums(firstNonNull(bookDraft.getInt("nums"), book.getNums()));
        book.setScore(firstNonNull(bookDraft.getInt("score"), book.getScore()));
        book.setBookNo(firstNonBlank(bookDraft.getStr("bookNo"), book.getBookNo()));
        book.setPublishDate(firstNonBlank(bookDraft.getStr("publishDate"), book.getPublishDate()));
        book.setCover(firstNonBlank(bookDraft.getStr("cover"), book.getCover()));
        book.setDescription(firstNonBlank(bookDraft.getStr("description"), book.getDescription()));
        return book;
    }

    private List<String> splitCategory(String category) {
        if (StrUtil.isBlank(category)) {
            return new ArrayList<>();
        }
        return Arrays.stream(category.split(">"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }

    private Book findBookByName(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        try {
            for (Book book : bookService.list()) {
                if (name.equals(StrUtil.trimToEmpty(book.getName()))) {
                    return book;
                }
            }
            for (Book book : bookService.list()) {
                if (StrUtil.trimToEmpty(book.getName()).contains(name) || name.contains(StrUtil.trimToEmpty(book.getName()))) {
                    return book;
                }
            }
        } catch (Exception e) {
            log.warn("查找图书失败", e);
        }
        return null;
    }

    private String extract(String text, String regex) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        Matcher matcher = Pattern.compile(regex).matcher(text);
        if (!matcher.find()) {
            return "";
        }
        return StrUtil.trimToEmpty(matcher.group(1));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private Integer parseInt(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return null;
        }
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private Object toPlainObject(Object value) {
        if (value == null || value instanceof JSONNull) {
            return null;
        }
        if (value instanceof JSONObject) {
            Map<String, Object> map = new LinkedHashMap<>();
            JSONObject json = (JSONObject) value;
            for (Map.Entry<String, Object> entry : json.entrySet()) {
                map.put(entry.getKey(), toPlainObject(entry.getValue()));
            }
            return map;
        }
        if (value instanceof JSONArray) {
            List<Object> list = new ArrayList<>();
            JSONArray array = (JSONArray) value;
            for (Object item : array) {
                list.add(toPlainObject(item));
            }
            return list;
        }
        return value;
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
        if ("success".equals(status) || "manual_required".equals(status) || "executed_success".equals(status)) return "COMPLETED";
        if ("pending_confirm".equals(status)) return "WAIT_CONFIRM";
        if ("blocked".equals(status) || "execute_blocked".equals(status)) return "BLOCKED";
        if ("cancelled".equals(status)) return "CANCELLED";
        return "UNKNOWN";
    }

    private int progressOf(String status) {
        if ("success".equals(status) || "manual_required".equals(status) || "executed_success".equals(status)
                || "blocked".equals(status) || "execute_blocked".equals(status) || "cancelled".equals(status)) {
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
            Object plain = toPlainObject(JSONUtil.parseObj(json));
            if (plain instanceof Map) {
                return (Map<String, Object>) plain;
            }
            return new LinkedHashMap<>();
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

    private void updateOutputAndStatus(Long taskLogId, String outputJson, String status) {
        try {
            aiTaskLogMapper.updateOutputAndStatus(taskLogId, outputJson, status);
        } catch (Exception e) {
            log.warn("更新AI任务输出失败", e);
        }
    }
}
