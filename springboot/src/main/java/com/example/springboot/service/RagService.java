package com.example.springboot.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.example.springboot.entity.AIChatLog;
import com.example.springboot.entity.AIKnowledge;
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
        Map<String, Object> result = new LinkedHashMap<>();
        if (contexts.isEmpty()) {
            result.put("answer", "知识库中没有找到依据，无法判断。");
            result.put("retrieved_context", new ArrayList<>());
            result.put("source_list", new ArrayList<>());
            result.put("uncertain", true);
            saveChat(adminId, question, result, "rag_chat");
            return result;
        }
        String prompt = "管理员RAG问答：你只能基于检索到的知识库内容和数据库查询结果回答。没有依据时回答无法判断。"
                + "输出JSON：answer,source_list,uncertain。问题：" + question + "；知识库：" + JSONUtil.toJsonStr(contexts);
        Map<String, Object> aiResult = aiService.parseJsonMap(aiClient.complete(prompt));
        if (!aiResult.isEmpty() && aiResult.containsKey("answer")) {
            result.putAll(aiResult);
        } else {
            result.put("answer", buildAnswer(question, contexts));
            result.put("uncertain", false);
        }
        result.put("retrieved_context", contexts.stream().map(AIKnowledge::getContent).collect(Collectors.toList()));
        result.put("source_list", contexts.stream().map(item -> {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("id", item.getId());
            source.put("title", item.getTitle());
            source.put("sourceType", item.getSourceType());
            return source;
        }).collect(Collectors.toList()));
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

    private String buildAnswer(String question, List<AIKnowledge> contexts) {
        String context = contexts.stream().map(AIKnowledge::getContent).collect(Collectors.joining("；"));
        return "根据知识库内容：" + context + "。针对你的问题“" + question + "”，建议按上述规则处理。";
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
