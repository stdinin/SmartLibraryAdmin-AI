package com.example.springboot.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.example.springboot.controller.request.ai.AIKnowledgeRequest;
import com.example.springboot.entity.AIChatLog;
import com.example.springboot.entity.AIKnowledge;
import com.example.springboot.entity.Book;
import com.example.springboot.mapper.AIChatLogMapper;
import com.example.springboot.mapper.AIKnowledgeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RagService {

    @Resource
    private AIKnowledgeMapper aiKnowledgeMapper;

    @Resource
    private AIChatLogMapper aiChatLogMapper;

    @Resource
    private AIClient aiClient;

    @Resource
    private AIService aiService;

    public List<AIKnowledge> listKnowledge() {
        try {
            return aiKnowledgeMapper.list();
        } catch (Exception e) {
            log.warn("知识库列表读取失败，可能尚未执行增量SQL", e);
            return new ArrayList<>();
        }
    }

    public AIKnowledge saveKnowledge(AIKnowledgeRequest request) {
        AIKnowledge knowledge = toKnowledge(request);
        if (knowledge.getId() == null) {
            aiKnowledgeMapper.save(knowledge);
        } else {
            aiKnowledgeMapper.updateById(knowledge);
        }
        return knowledge;
    }

    public void deleteKnowledge(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("知识编号不能为空");
        }
        aiKnowledgeMapper.deleteById(id);
    }

    public Map<String, Object> seedDefaultKnowledge() {
        List<AIKnowledge> current = listKnowledge();
        Set<String> existingTitles = current.stream()
                .map(AIKnowledge::getTitle)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        int inserted = 0;
        for (AIKnowledge knowledge : defaultKnowledgeTemplates()) {
            if (existingTitles.contains(knowledge.getTitle())) {
                continue;
            }
            try {
                aiKnowledgeMapper.save(knowledge);
                inserted++;
            } catch (Exception e) {
                log.warn("默认知识写入失败: {}", knowledge.getTitle(), e);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", inserted);
        result.put("total", listKnowledge().size());
        return result;
    }

    public List<AIKnowledge> retrieve(String question) {
        Set<Long> seenIds = new LinkedHashSet<>();
        List<AIKnowledge> result = new ArrayList<>();
        for (String keyword : keywords(question)) {
            try {
                List<AIKnowledge> rows = aiKnowledgeMapper.searchByKeyword(keyword, 3);
                for (AIKnowledge row : rows) {
                    if (row.getId() == null || seenIds.add(row.getId())) {
                        result.add(row);
                    }
                    if (result.size() >= 3) {
                        return result;
                    }
                }
            } catch (Exception e) {
                log.warn("知识库检索失败，可能尚未执行增量SQL", e);
                break;
            }
        }
        if (result.isEmpty()) {
            result.addAll(defaultKnowledge(question));
        }
        return result.stream().limit(3).collect(Collectors.toList());
    }

    public Map<String, Object> chat(Integer adminId, String question) {
        if (StrUtil.isBlank(question)) {
            throw new IllegalArgumentException("问题不能为空");
        }
        List<AIKnowledge> contexts = retrieve(question);
        Map<String, Object> dbFacts = retrieveBookFacts(question);
        Map<String, Object> result = new LinkedHashMap<>();
        if (contexts.isEmpty() && isDbFactsEmpty(dbFacts)) {
            result.put("answer", "知识库中没有找到依据，无法判断。");
            result.put("retrieved_context", new ArrayList<>());
            result.put("source_list", new ArrayList<>());
            result.put("db_facts", new LinkedHashMap<>());
            result.put("uncertain", true);
            saveChat(adminId, question, result, "rag_chat");
            return result;
        }
        String prompt = "管理员RAG问答：你只能基于检索到的知识库内容和数据库查询结果回答。没有依据时回答无法判断。"
                + "输出JSON：answer,source_list,uncertain。问题：" + question
                + "；知识库：" + JSONUtil.toJsonStr(contexts)
                + "；数据库事实：" + JSONUtil.toJsonStr(dbFacts);
        Map<String, Object> aiResult = aiService.parseJsonMap(aiClient.complete(prompt));
        if (!aiResult.isEmpty() && aiResult.containsKey("answer")) {
            result.putAll(aiResult);
        } else {
            result.put("answer", buildAnswer(question, contexts, dbFacts));
            result.put("uncertain", isDbFactsEmpty(dbFacts) && contexts.isEmpty());
        }
        result.put("retrieved_context", contexts.stream().map(AIKnowledge::getContent).collect(Collectors.toList()));
        List<Map<String, Object>> sources = contexts.stream().map(item -> {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("id", item.getId());
            source.put("title", item.getTitle());
            source.put("sourceType", item.getSourceType());
            return source;
        }).collect(Collectors.toList());
        if (!isDbFactsEmpty(dbFacts)) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("id", "book_table");
            source.put("title", "馆藏图书表");
            source.put("sourceType", "database");
            sources.add(source);
        }
        result.put("source_list", sources);
        result.put("db_facts", dbFacts);
        saveChat(adminId, question, result, "rag_chat");
        return result;
    }

    private List<String> keywords(String question) {
        if (StrUtil.isBlank(question)) {
            return new ArrayList<>();
        }
        String compact = question.replaceAll("[，。？！、\\s]", " ");
        List<String> parts = Arrays.stream(compact.split(" "))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
        parts.add(question);
        return parts;
    }

    private AIKnowledge toKnowledge(AIKnowledgeRequest request) {
        if (request == null || StrUtil.isBlank(request.getTitle()) || StrUtil.isBlank(request.getContent())) {
            throw new IllegalArgumentException("知识标题和内容不能为空");
        }
        AIKnowledge knowledge = new AIKnowledge();
        knowledge.setId(request.getId());
        knowledge.setTitle(request.getTitle().trim());
        knowledge.setContent(request.getContent().trim());
        knowledge.setSourceType(StrUtil.blankToDefault(request.getSourceType(), "manual"));
        return knowledge;
    }

    private List<AIKnowledge> defaultKnowledge(String question) {
        List<AIKnowledge> list = new ArrayList<>();
        for (AIKnowledge item : defaultKnowledgeTemplates()) {
            if (matchesKnowledge(question, item)) {
                list.add(item);
            }
            if (list.size() >= 3) {
                break;
            }
        }
        return list;
    }

    private boolean matchesKnowledge(String question, AIKnowledge item) {
        String text = item.getTitle() + item.getContent();
        for (String keyword : keywords(question)) {
            if (StrUtil.isNotBlank(keyword) && text.contains(keyword)) {
                return true;
            }
        }
        return (question.contains("逾期") || question.contains("归还") || question.contains("借阅")) && item.getTitle().contains("借阅")
                || (question.contains("分类") || question.contains("编目")) && item.getTitle().contains("分类")
                || (question.contains("删除") || question.contains("修改") || question.contains("新增")) && item.getTitle().contains("安全");
    }

    private List<AIKnowledge> defaultKnowledgeTemplates() {
        List<AIKnowledge> list = new ArrayList<>();
        list.add(defaultItem("借阅与逾期处理规则", "借阅记录应优先依据归还日期、借阅状态和读者联系方式处理。发现逾期风险时，管理员应先联系读者归还，再按系统现有借还流程处理积分或状态，不建议由AI自动修改借阅记录。"));
        list.add(defaultItem("馆藏分类与编目规范", "新增图书应优先使用系统已有分类树。AI智能编目只能生成分类、标签、简介、适读人群和推荐理由，管理员确认后才可应用到表单并保存。"));
        list.add(defaultItem("馆藏数据质检规则", "馆藏质检关注缺失作者、出版社、简介、标准码、分类异常、重复书号、重复书名作者、库存为空、库存为0或库存为负数。疑似重复和异常库存必须人工核对。"));
        list.add(defaultItem("借阅统计分析口径", "热门图书按周期内借阅次数排序，热门分类按图书分类聚合，冷门图书指当前周期内没有借阅记录的馆藏，逾期风险指已借出且应还日期不晚于今天的记录。"));
        list.add(defaultItem("Agent操作安全边界", "AI Agent可以自动执行查询、统计、质检和建议生成。新增或修改类操作只生成草案，删除图书、删除借阅、删除管理员、修改密码和任意SQL默认禁止自动执行。"));
        list.add(defaultItem("管理员使用建议", "AI结果应作为辅助判断。涉及写入数据库、删除数据、调整库存、处理逾期或修改会员信息时，管理员需要回到原业务页面核对后手动执行。"));
        return list;
    }

    private AIKnowledge defaultItem(String title, String content) {
        AIKnowledge item = new AIKnowledge();
        item.setTitle(title);
        item.setContent(content);
        item.setSourceType("default");
        return item;
    }

    private String buildAnswer(String question, List<AIKnowledge> contexts, Map<String, Object> dbFacts) {
        if (!isDbFactsEmpty(dbFacts)) {
            Integer totalMatched = (Integer) dbFacts.get("totalMatched");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> books = (List<Map<String, Object>>) dbFacts.get("matchedBooks");
            if (totalMatched == null || totalMatched <= 0) {
                return "根据当前馆藏数据，没有找到与问题“" + question + "”匹配的图书。"
                        + "建议换用更短的关键词，或在图书列表按书名、作者、分类继续检索。";
            }
            String examples = books.stream()
                    .map(book -> "《" + book.get("name") + "》" + formatBookMeta(book))
                    .collect(Collectors.joining("；"));
            return "根据当前馆藏数据，找到 " + totalMatched + " 本相关图书。"
                    + (StrUtil.isBlank(examples) ? "" : "示例：" + examples + "。")
                    + "如果需要精确筛选，可在图书列表按分类、作者或书名继续检索。";
        }
        String context = contexts.stream().map(AIKnowledge::getContent).collect(Collectors.joining("；"));
        return "根据知识库内容：" + context + "。针对你的问题“" + question + "”，建议按上述规则处理。";
    }

    private Map<String, Object> retrieveBookFacts(String question) {
        Map<String, Object> facts = new LinkedHashMap<>();
        if (!looksLikeBookQuery(question)) {
            return facts;
        }
        List<Book> books = aiService.safeBooks();
        List<String> terms = extractBookTerms(question, books);
        List<Map<String, Object>> matched = books.stream()
                .filter(book -> matchesAny(book, terms))
                .map(this::bookFactRow)
                .limit(10)
                .collect(Collectors.toList());
        long total = books.stream().filter(book -> matchesAny(book, terms)).count();
        facts.put("intent", "book_inventory_search");
        facts.put("queryTerms", terms);
        facts.put("totalMatched", (int) total);
        facts.put("matchedBooks", matched);
        facts.put("totalBooks", books.size());
        return facts;
    }

    private boolean looksLikeBookQuery(String question) {
        return containsAny(question, "书", "图书", "馆藏", "库存", "作者", "出版社", "分类", "有没有", "哪些", "推荐");
    }

    private List<String> extractBookTerms(String question, List<Book> books) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String cleaned = question.replaceAll("[有没有哪些相关的图书书籍馆藏库存推荐一下请问吗？?，。、\\s]", " ");
        for (String part : cleaned.split(" ")) {
            if (StrUtil.isNotBlank(part) && part.trim().length() >= 2) {
                terms.add(part.trim().toLowerCase());
            }
        }
        for (Book book : books) {
            addKnownTermIfContained(terms, question, book.getCategory());
            addKnownTermIfContained(terms, question, book.getAuthor());
            addKnownTermIfContained(terms, question, book.getPublisher());
            addKnownTermIfContained(terms, question, book.getName());
        }
        if (terms.isEmpty()) {
            terms.add(question.trim().toLowerCase());
        }
        return new ArrayList<>(terms).stream().limit(6).collect(Collectors.toList());
    }

    private void addKnownTermIfContained(Set<String> terms, String question, String value) {
        if (StrUtil.isNotBlank(value) && value.length() >= 2 && question.contains(value)) {
            terms.add(value.toLowerCase());
        }
    }

    private boolean matchesAny(Book book, List<String> terms) {
        if (terms.isEmpty()) {
            return false;
        }
        String haystack = (StrUtil.nullToEmpty(book.getName()) + " "
                + StrUtil.nullToEmpty(book.getAuthor()) + " "
                + StrUtil.nullToEmpty(book.getPublisher()) + " "
                + StrUtil.nullToEmpty(book.getCategory()) + " "
                + StrUtil.nullToEmpty(book.getDescription()) + " "
                + StrUtil.nullToEmpty(book.getBookNo())).toLowerCase();
        for (String term : terms) {
            if (StrUtil.isNotBlank(term) && haystack.contains(term.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> bookFactRow(Book book) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", book.getId());
        row.put("name", book.getName());
        row.put("author", book.getAuthor());
        row.put("publisher", book.getPublisher());
        row.put("category", book.getCategory());
        row.put("bookNo", book.getBookNo());
        row.put("nums", book.getNums());
        return row;
    }

    private String formatBookMeta(Map<String, Object> book) {
        List<String> meta = new ArrayList<>();
        if (book.get("author") != null) meta.add("作者：" + book.get("author"));
        if (book.get("category") != null) meta.add("分类：" + book.get("category"));
        if (book.get("nums") != null) meta.add("库存：" + book.get("nums"));
        return meta.isEmpty() ? "" : "（" + String.join("，", meta) + "）";
    }

    private boolean isDbFactsEmpty(Map<String, Object> facts) {
        return !facts.containsKey("intent");
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private void saveChat(Integer adminId, String question, Map<String, Object> result, String intent) {
        AIChatLog chatLog = new AIChatLog();
        chatLog.setAdminId(adminId);
        chatLog.setQuestion(question);
        chatLog.setAnswer(String.valueOf(result.get("answer")));
        chatLog.setRetrievedContext(JSONUtil.toJsonStr(result.get("retrieved_context")));
        chatLog.setIntent(intent);
        try {
            aiChatLogMapper.save(chatLog);
        } catch (Exception e) {
            log.warn("AI问答日志保存失败，可能尚未执行增量SQL", e);
        }
    }
}
