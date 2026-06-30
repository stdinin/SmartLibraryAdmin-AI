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
        if (question.contains("逾期") || question.contains("归还") || question.contains("借阅")) {
            list.add(defaultItem("借阅与逾期规则", "管理员应优先查看借阅列表中的归还日期和过期提醒。逾期记录需要联系读者归还，涉及扣减积分时应按系统现有借还流程处理。"));
        }
        if (question.contains("分类") || question.contains("编目")) {
            list.add(defaultItem("图书分类规范", "图书分类应优先使用系统已有分类树。AI编目只能给出建议，管理员确认后才能写入图书表。"));
        }
        if (question.contains("删除") || question.contains("修改")) {
            list.add(defaultItem("后台操作安全规则", "AI助手不得自动删除数据。新增或修改类操作必须生成待确认方案，由管理员确认后再通过原有接口执行。"));
        }
        return list;
    }

    private AIKnowledge defaultItem(String title, String content) {
        AIKnowledge item = new AIKnowledge();
        item.setTitle(title);
        item.setContent(content);
        item.setSourceType("built_in");
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
