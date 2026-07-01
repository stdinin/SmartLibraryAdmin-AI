package com.example.springboot.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.example.springboot.controller.request.ai.CatalogRequest;
import com.example.springboot.entity.AITaskLog;
import com.example.springboot.entity.Book;
import com.example.springboot.entity.Borrow;
import com.example.springboot.entity.Category;
import com.example.springboot.mapper.AITaskLogMapper;
import com.example.springboot.mapper.BookMapper;
import com.example.springboot.mapper.BorrowMapper;
import com.example.springboot.mapper.CategoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AIService {

    @Resource
    private BookMapper bookMapper;

    @Resource
    private BorrowMapper borrowMapper;

    @Resource
    private CategoryMapper categoryMapper;

    @Resource
    private AITaskLogMapper aiTaskLogMapper;

    @Resource
    private AIClient aiClient;

    public Map<String, Object> generateCatalog(CatalogRequest request, Integer adminId) {
        if (request == null || StrUtil.isBlank(request.getTitle())) {
            throw new IllegalArgumentException("图书名称不能为空");
        }
        List<Category> categories = safeCategories();
        String prompt = "AI智能编目：你是图书馆管理员助手。仅根据输入图书信息和已有分类列表生成建议，不要保存数据库。"
                + "输出JSON：category,tags,summary,suitableReaders,recommendReason,uncertain。输入："
                + JSONUtil.toJsonStr(request) + "；分类列表：" + JSONUtil.toJsonStr(categories);
        Map<String, Object> result = parseJsonMap(aiClient.complete(prompt));
        if (result.isEmpty()) {
            result = fallbackCatalog(request, categories);
        }
        saveTaskLog(adminId, "catalog_generate", request, result, "success");
        return result;
    }

    public Map<String, Object> checkBookQuality(Integer adminId) {
        List<Book> books = safeBooks();
        List<Category> categories = safeCategories();
        Set<String> categoryNames = categories.stream().map(Category::getName).filter(StrUtil::isNotBlank).collect(Collectors.toSet());

        Map<String, Integer> missingFieldStats = new LinkedHashMap<>();
        List<Map<String, Object>> missingBooks = new ArrayList<>();
        Map<String, List<Book>> bookNoGroups = new HashMap<>();
        Map<String, List<Book>> nameAuthorGroups = new HashMap<>();
        List<Map<String, Object>> stockAbnormalBooks = new ArrayList<>();
        List<Map<String, Object>> categoryAbnormalBooks = new ArrayList<>();

        for (Book book : books) {
            List<String> missingFields = new ArrayList<>();
            collectMissing(book.getName(), "name", missingFields, missingFieldStats);
            collectMissing(book.getAuthor(), "author", missingFields, missingFieldStats);
            collectMissing(book.getBookNo(), "bookNo", missingFields, missingFieldStats);
            collectMissing(book.getCategory(), "category", missingFields, missingFieldStats);
            collectMissing(book.getDescription(), "description", missingFields, missingFieldStats);
            collectMissing(book.getPublisher(), "publisher", missingFields, missingFieldStats);
            if (!missingFields.isEmpty()) {
                Map<String, Object> row = bookRow(book);
                row.put("missingFields", missingFields);
                missingBooks.add(row);
            }
            if (StrUtil.isNotBlank(book.getBookNo())) {
                bookNoGroups.computeIfAbsent(book.getBookNo().trim().toLowerCase(), k -> new ArrayList<>()).add(book);
            }
            if (StrUtil.isNotBlank(book.getName())) {
                String key = book.getName().trim().toLowerCase() + "|" + StrUtil.nullToEmpty(book.getAuthor()).trim().toLowerCase();
                nameAuthorGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(book);
            }
            if (book.getNums() == null || book.getNums() < 0 || book.getNums() == 0) {
                Map<String, Object> row = bookRow(book);
                row.put("reason", book.getNums() == null ? "库存为空" : (book.getNums() < 0 ? "库存为负数" : "库存为0"));
                stockAbnormalBooks.add(row);
            }
            if (StrUtil.isNotBlank(book.getCategory()) && hasInvalidCategory(book.getCategory(), categoryNames)) {
                Map<String, Object> row = bookRow(book);
                row.put("reason", "分类不在当前分类树中");
                categoryAbnormalBooks.add(row);
            }
        }

        List<Map<String, Object>> duplicateBooks = new ArrayList<>();
        collectDuplicateBooks(bookNoGroups, "bookNo", duplicateBooks);
        collectDuplicateBooks(nameAuthorGroups, "nameAuthor", duplicateBooks);

        Map<String, Object> ruleResult = new LinkedHashMap<>();
        ruleResult.put("totalBooks", books.size());
        ruleResult.put("missingFieldStats", missingFieldStats);
        ruleResult.put("missingBooks", missingBooks);
        ruleResult.put("duplicateBooks", duplicateBooks);
        ruleResult.put("stockAbnormalBooks", stockAbnormalBooks);
        ruleResult.put("categoryAbnormalBooks", categoryAbnormalBooks);
        ruleResult.put("suggestions", qualitySuggestions(ruleResult));

        String prompt = "馆藏数据质检：你是图书馆数据质检助手。根据后端规则检查结果生成整改建议，不要编造不存在的数据，不要建议自动删除。"
                + "输出JSON：summary,suggestions,riskLevel,uncertain。规则结果：" + JSONUtil.toJsonStr(ruleResult);
        Map<String, Object> aiResult = parseJsonMap(aiClient.complete(prompt));
        if (!aiResult.isEmpty()) {
            ruleResult.put("aiSummary", aiResult.get("summary"));
            ruleResult.put("suggestions", aiResult.get("suggestions"));
            ruleResult.put("riskLevel", aiResult.get("riskLevel"));
            ruleResult.put("uncertain", aiResult.get("uncertain"));
        } else {
            ruleResult.put("aiSummary", "共检查图书 " + books.size() + " 本，发现缺失字段、疑似重复、库存和分类异常项。");
            ruleResult.put("riskLevel", duplicateBooks.isEmpty() && stockAbnormalBooks.isEmpty() ? "low" : "medium");
            ruleResult.put("uncertain", false);
        }
        saveTaskLog(adminId, "book_quality_check", Collections.singletonMap("totalBooks", books.size()), ruleResult, "success");
        return ruleResult;
    }

    public Map<String, Object> analyzeBorrow(String range, Integer adminId) {
        List<Book> books = safeBooks();
        List<Borrow> allBorrows = safeBorrows();
        List<Borrow> borrows = filterBorrowsByRange(allBorrows, range);
        Map<String, Book> bookByNo = books.stream()
                .filter(book -> StrUtil.isNotBlank(book.getBookNo()))
                .collect(Collectors.toMap(Book::getBookNo, book -> book, (a, b) -> a));

        Map<String, Long> bookCounts = borrows.stream()
                .filter(borrow -> StrUtil.isNotBlank(borrow.getBookName()))
                .collect(Collectors.groupingBy(Borrow::getBookName, LinkedHashMap::new, Collectors.counting()));
        List<Map<String, Object>> hotBooks = topCountRows(bookCounts, "bookName", 5);

        Map<String, Long> categoryCounts = new HashMap<>();
        for (Borrow borrow : borrows) {
            Book book = bookByNo.get(borrow.getBookNo());
            if (book != null && StrUtil.isNotBlank(book.getCategory())) {
                categoryCounts.put(book.getCategory(), categoryCounts.getOrDefault(book.getCategory(), 0L) + 1);
            }
        }

        Set<String> borrowedBookNos = borrows.stream().map(Borrow::getBookNo).filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        List<Map<String, Object>> coldBooks = books.stream()
                .filter(book -> StrUtil.isNotBlank(book.getBookNo()) && !borrowedBookNos.contains(book.getBookNo()))
                .limit(10)
                .map(this::bookRow)
                .collect(Collectors.toList());

        LocalDate today = LocalDate.now();
        List<Map<String, Object>> overdueRisks = borrows.stream()
                .filter(borrow -> "已借出".equals(borrow.getStatus()) && borrow.getReturnDate() != null && !borrow.getReturnDate().isAfter(today))
                .map(this::borrowRow)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range", StrUtil.blankToDefault(range, "month"));
        result.put("totalBorrows", borrows.size());
        result.put("totalAllBorrows", allBorrows.size());
        result.put("hotBooks", hotBooks);
        result.put("hotCategories", topCountRows(categoryCounts, "category", 5));
        result.put("coldBooks", coldBooks);
        result.put("overdueRisks", overdueRisks);
        result.put("summary", "当前共有借阅记录 " + borrows.size() + " 条，逾期风险 " + overdueRisks.size() + " 条。");
        result.put("suggestions", borrowSuggestions(hotBooks, coldBooks, overdueRisks));
        result.put("uncertain", false);

        String prompt = "借阅统计分析：你是管理员数据分析助手。只能解释给定借阅统计、逾期记录、图书库存。"
                + "输出JSON：summary,hotBooks,hotCategories,coldBooks,overdueRisks,suggestions,uncertain。数据：" + JSONUtil.toJsonStr(result);
        Map<String, Object> aiResult = parseJsonMap(aiClient.complete(prompt));
        if (!aiResult.isEmpty()) {
            result.put("summary", aiResult.get("summary"));
            result.put("suggestions", aiResult.get("suggestions"));
            result.put("uncertain", aiResult.get("uncertain"));
        }
        saveTaskLog(adminId, "borrow_analyze", Collections.singletonMap("range", range), result, "success");
        return result;
    }

    public Map<String, Object> searchBooksForTask(String task, Integer adminId) {
        String normalized = StrUtil.nullToEmpty(task);
        List<Book> books = safeBooks();
        Set<String> borrowedBookNos = safeBorrows().stream()
                .map(Borrow::getBookNo)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        List<String> filters = new ArrayList<>();
        boolean zeroStock = containsAny(normalized, "库存为0", "库存 0", "零库存", "无库存");
        boolean cold = containsAny(normalized, "没人借", "无人借", "长期没人借", "冷门", "没有借阅");
        boolean abnormalStock = containsAny(normalized, "库存异常", "库存为空", "库存为负");
        if (zeroStock) filters.add("库存为0");
        if (cold) filters.add("周期内无借阅记录");
        if (abnormalStock) filters.add("库存异常");

        List<String> terms = extractBookSearchTerms(normalized);
        List<Map<String, Object>> matchedBooks = books.stream()
                .filter(book -> !zeroStock || (book.getNums() != null && book.getNums() == 0))
                .filter(book -> !abnormalStock || book.getNums() == null || book.getNums() <= 0)
                .filter(book -> !cold || StrUtil.isBlank(book.getBookNo()) || !borrowedBookNos.contains(book.getBookNo()))
                .filter(book -> terms.isEmpty() || bookContainsAny(book, terms))
                .map(this::bookRow)
                .limit(30)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", "按任务条件找到 " + matchedBooks.size() + " 本候选图书。");
        result.put("filters", filters);
        result.put("queryTerms", terms);
        result.put("matchedBooks", matchedBooks);
        result.put("suggestions", bookSearchSuggestions(matchedBooks, zeroStock, cold, abnormalStock));
        result.put("uncertain", false);
        saveTaskLog(adminId, "book_search_analysis", Collections.singletonMap("task", task), result, "success");
        return result;
    }

    public List<Book> safeBooks() {
        try {
            return bookMapper.list();
        } catch (Exception e) {
            log.warn("读取图书列表失败", e);
            return new ArrayList<>();
        }
    }

    public List<Borrow> safeBorrows() {
        try {
            return borrowMapper.list();
        } catch (Exception e) {
            log.warn("读取借阅列表失败", e);
            return new ArrayList<>();
        }
    }

    public List<Category> safeCategories() {
        try {
            return categoryMapper.list();
        } catch (Exception e) {
            log.warn("读取分类列表失败", e);
            return new ArrayList<>();
        }
    }

    public Map<String, Object> parseJsonMap(String content) {
        if (StrUtil.isBlank(content)) {
            return new LinkedHashMap<>();
        }
        try {
            String json = content.trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            return JSONUtil.parseObj(json);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("rawAnswer", content);
            fallback.put("uncertain", true);
            return fallback;
        }
    }

    public AITaskLog saveTaskLog(Integer adminId, String taskType, Object input, Object output, String status) {
        AITaskLog taskLog = new AITaskLog();
        taskLog.setAdminId(adminId);
        taskLog.setTaskType(taskType);
        taskLog.setInputJson(JSONUtil.toJsonStr(input));
        taskLog.setOutputJson(JSONUtil.toJsonStr(output));
        taskLog.setStatus(status);
        try {
            aiTaskLogMapper.save(taskLog);
        } catch (Exception e) {
            log.warn("AI任务日志保存失败，可能尚未执行增量SQL", e);
        }
        return taskLog;
    }

    private List<Borrow> filterBorrowsByRange(List<Borrow> borrows, String range) {
        LocalDate start = rangeStartDate(range);
        if (start == null) {
            return borrows;
        }
        return borrows.stream()
                .filter(borrow -> borrow.getCreatetime() != null && !borrow.getCreatetime().isBefore(start))
                .collect(Collectors.toList());
    }

    private LocalDate rangeStartDate(String range) {
        String normalized = StrUtil.blankToDefault(range, "month").trim().toLowerCase();
        LocalDate today = LocalDate.now();
        switch (normalized) {
            case "week":
                return today.minusWeeks(1);
            case "month":
                return today.minusMonths(1);
            case "month2":
                return today.minusMonths(2);
            case "month3":
            case "quarter":
                return today.minusMonths(3);
            default:
                return today.minusMonths(1);
        }
    }

    private Map<String, Object> fallbackCatalog(CatalogRequest request, List<Category> categories) {
        Map<String, Object> result = new LinkedHashMap<>();
        String category = chooseCategory(request, categories);
        List<String> tags = new ArrayList<>();
        if (StrUtil.isNotBlank(category)) {
            tags.addAll(Arrays.asList(category.split(" > ")));
        }
        if (StrUtil.isNotBlank(request.getAuthor())) {
            tags.add(request.getAuthor());
        }
        tags.add("馆藏推荐");

        List<String> uncertain = new ArrayList<>();
        if (StrUtil.isBlank(request.getAuthor())) uncertain.add("author");
        if (StrUtil.isBlank(request.getPublisher())) uncertain.add("publisher");
        if (StrUtil.isBlank(request.getIsbn())) uncertain.add("isbn");

        result.put("category", category);
        result.put("tags", tags.stream().filter(StrUtil::isNotBlank).distinct().limit(5).collect(Collectors.toList()));
        result.put("summary", buildSummary(request));
        result.put("suitableReaders", "适合对" + (StrUtil.isBlank(category) ? "相关主题" : category.replace(" > ", "、")) + "感兴趣的读者。");
        result.put("recommendReason", "可作为馆藏补充条目，建议管理员结合馆内分类规范和实际馆藏情况确认后入库。");
        result.put("uncertain", uncertain);
        return result;
    }

    private String chooseCategory(CatalogRequest request, List<Category> categories) {
        String text = StrUtil.nullToEmpty(request.getTitle()) + StrUtil.nullToEmpty(request.getDescription());
        List<String> names = categories.stream().map(Category::getName).filter(StrUtil::isNotBlank).collect(Collectors.toList());
        if (text.contains("魔幻") && names.contains("现实主义") && names.contains("魔幻现实主义")) return "现实主义 > 魔幻现实主义";
        if ((text.contains("小说") || text.contains("世界") || text.contains("边城") || text.contains("文学")) && names.contains("小说")) return "散文小说 > 小说";
        if (text.contains("散文") && names.contains("散文")) return "散文小说 > 散文";
        if (text.contains("历史") && names.contains("历史文化")) return "历史文化";
        if (!names.isEmpty()) return names.get(0);
        return "";
    }

    private String buildSummary(CatalogRequest request) {
        if (StrUtil.isNotBlank(request.getDescription())) {
            return request.getDescription();
        }
        String author = StrUtil.blankToDefault(request.getAuthor(), "作者信息待补充");
        String publisher = StrUtil.blankToDefault(request.getPublisher(), "出版社信息待补充");
        return "《" + request.getTitle() + "》由" + author + "创作，" + publisher + "出版。建议管理员补充出版年份、馆藏分类和内容简介后入库。";
    }

    private void collectMissing(String value, String field, List<String> missingFields, Map<String, Integer> stats) {
        if (StrUtil.isBlank(value)) {
            missingFields.add(field);
            stats.put(field, stats.getOrDefault(field, 0) + 1);
        }
    }

    private boolean hasInvalidCategory(String category, Set<String> categoryNames) {
        if (categoryNames.isEmpty()) {
            return false;
        }
        for (String part : category.split(">")) {
            if (StrUtil.isNotBlank(part) && !categoryNames.contains(part.trim())) {
                return true;
            }
        }
        return false;
    }

    private void collectDuplicateBooks(Map<String, List<Book>> groups, String type, List<Map<String, Object>> duplicateBooks) {
        for (Map.Entry<String, List<Book>> entry : groups.entrySet()) {
            if (entry.getValue().size() > 1) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", type);
                row.put("key", entry.getKey());
                row.put("books", entry.getValue().stream().map(this::bookRow).collect(Collectors.toList()));
                duplicateBooks.add(row);
            }
        }
    }

    private List<String> qualitySuggestions(Map<String, Object> result) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("优先补齐作者、标准码、分类、出版社和简介等影响检索的字段。");
        suggestions.add("疑似重复图书先人工核对标准码和版本信息，不建议自动删除。");
        suggestions.add("库存为0的图书可结合借阅热度决定是否补充副本或下架展示。");
        return suggestions;
    }

    private List<String> borrowSuggestions(List<Map<String, Object>> hotBooks, List<Map<String, Object>> coldBooks, List<Map<String, Object>> overdueRisks) {
        List<String> suggestions = new ArrayList<>();
        if (!hotBooks.isEmpty()) suggestions.add("热门图书建议检查库存，必要时增加副本或缩短借阅周期。");
        if (!coldBooks.isEmpty()) suggestions.add("长期无人借阅图书建议调整展示位置、更新分类标签或开展主题推荐。");
        if (!overdueRisks.isEmpty()) suggestions.add("逾期风险记录建议优先生成提醒文本并联系读者归还。");
        if (suggestions.isEmpty()) suggestions.add("当前借阅风险较低，可持续观察趋势变化。");
        return suggestions;
    }

    private List<String> bookSearchSuggestions(List<Map<String, Object>> matchedBooks, boolean zeroStock, boolean cold, boolean abnormalStock) {
        List<String> suggestions = new ArrayList<>();
        if (matchedBooks.isEmpty()) {
            suggestions.add("当前条件没有匹配图书，可以放宽关键词或只按库存、分类单独筛选。");
            return suggestions;
        }
        if (zeroStock) suggestions.add("库存为0的图书建议结合借阅热度决定补充副本或暂停展示。");
        if (cold) suggestions.add("长期无人借阅图书建议优化分类、封面、简介或加入主题推荐。");
        if (abnormalStock) suggestions.add("库存为空或为负数的数据建议优先人工核对原始入库记录。");
        if (suggestions.isEmpty()) suggestions.add("候选图书可在图书列表按书名、作者、分类继续精确筛选。");
        return suggestions;
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractBookSearchTerms(String task) {
        List<String> noise = Arrays.asList(
                "帮我", "找出", "查询", "搜索", "筛选", "图书", "书籍", "库存为0", "库存 0", "库存",
                "为0", "零库存", "无库存", "长期", "没人借", "无人借", "冷门", "相关", "关于", "的书", "且"
        );
        String cleaned = task;
        for (String word : noise) {
            cleaned = cleaned.replace(word, " ");
        }
        return Arrays.stream(cleaned.split("[，。？！、\\s]+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .filter(item -> item.length() >= 2)
                .distinct()
                .limit(5)
                .collect(Collectors.toList());
    }

    private boolean bookContainsAny(Book book, List<String> terms) {
        String text = StrUtil.nullToEmpty(book.getName()) + " "
                + StrUtil.nullToEmpty(book.getAuthor()) + " "
                + StrUtil.nullToEmpty(book.getPublisher()) + " "
                + StrUtil.nullToEmpty(book.getCategory()) + " "
                + StrUtil.nullToEmpty(book.getDescription());
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> topCountRows(Map<String, Long> counts, String nameField, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(nameField, entry.getKey());
                    row.put("count", entry.getValue());
                    return row;
                }).collect(Collectors.toList());
    }

    private Map<String, Object> bookRow(Book book) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", book.getId());
        row.put("name", book.getName());
        row.put("bookNo", book.getBookNo());
        row.put("author", book.getAuthor());
        row.put("publisher", book.getPublisher());
        row.put("category", book.getCategory());
        row.put("nums", book.getNums());
        return row;
    }

    private Map<String, Object> borrowRow(Borrow borrow) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", borrow.getId());
        row.put("bookName", borrow.getBookName());
        row.put("bookNo", borrow.getBookNo());
        row.put("userName", borrow.getUserName());
        row.put("userPhone", borrow.getUserPhone());
        row.put("status", borrow.getStatus());
        row.put("returnDate", borrow.getReturnDate());
        return row;
    }
}
