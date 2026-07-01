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
import java.util.Comparator;
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
                List<AIKnowledge> rows = aiKnowledgeMapper.searchByKeyword(keyword, 10);
                for (AIKnowledge row : rows) {
                    if (row.getId() == null || seenIds.add(row.getId())) {
                        result.add(row);
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
        return result.stream()
                .sorted(Comparator.comparingInt(item -> -knowledgeScore(question, item)))
                .limit(3)
                .collect(Collectors.toList());
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
        LinkedHashSet<String> terms = Arrays.stream(compact.split(" "))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String term : domainTerms()) {
            if (question.contains(term)) {
                terms.add(term);
            }
        }
        terms.add(question);
        return new ArrayList<>(terms);
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

    private int knowledgeScore(String question, AIKnowledge item) {
        int score = 0;
        String title = StrUtil.nullToEmpty(item.getTitle());
        String content = StrUtil.nullToEmpty(item.getContent());
        for (String keyword : keywords(question)) {
            if (StrUtil.isBlank(keyword)) {
                continue;
            }
            if (title.contains(keyword)) score += 8;
            if (content.contains(keyword)) score += 2;
        }
        if (question.contains("赔偿") && title.contains("赔偿")) score += 10;
        if ((question.contains("遗失") || question.contains("丢")) && title.contains("遗失")) score += 10;
        if ((question.contains("逾期") || question.contains("超期")) && title.contains("逾期")) score += 10;
        return score;
    }

    private List<AIKnowledge> defaultKnowledgeTemplates() {
        List<AIKnowledge> list = new ArrayList<>();
        list.add(defaultItem("借阅与逾期处理规则", "借阅记录应优先依据归还日期、借阅状态和读者联系方式处理。发现逾期风险时，管理员应先联系读者归还，再按系统现有借还流程处理积分或状态，不建议由AI自动修改借阅记录。"));
        list.add(defaultItem("馆藏分类与编目规范", "新增图书应优先使用系统已有分类树。AI智能编目只能生成分类、标签、简介、适读人群和推荐理由，管理员确认后才可应用到表单并保存。"));
        list.add(defaultItem("馆藏数据质检规则", "馆藏质检关注缺失作者、出版社、简介、标准码、分类异常、重复书号、重复书名作者、库存为空、库存为0或库存为负数。疑似重复和异常库存必须人工核对。"));
        list.add(defaultItem("借阅统计分析口径", "热门图书按周期内借阅次数排序，热门分类按图书分类聚合，冷门图书指当前周期内没有借阅记录的馆藏，逾期风险指已借出且应还日期不晚于今天的记录。"));
        list.add(defaultItem("Agent操作安全边界", "AI Agent可以自动执行查询、统计、质检和建议生成。新增或修改类操作只生成草案，删除图书、删除借阅、删除管理员、修改密码和任意SQL默认禁止自动执行。"));
        list.add(defaultItem("管理员使用建议", "AI结果应作为辅助判断。涉及写入数据库、删除数据、调整库存、处理逾期或修改会员信息时，管理员需要回到原业务页面核对后手动执行。"));
        list.add(defaultItem("官方细则样例：北京大学图书馆逾期、损坏及遗失处理规定",
                "来源：北京大学图书馆《读者借阅书刊逾期、损坏及遗失的处理规定》；链接：https://www.lib.pku.edu.cn/portal/cn/fw/rgzn/guizhangzhidu/notice11。\n"
                        + "适用场景：管理员处理读者逾期、催还、损坏、遗失、赔书和赔款时，可参考该类高校图书馆细则的处理逻辑。\n"
                        + "一、借阅权限限制。若读者存在较多逾期未还图书、未还图书累计逾期天数达到阈值、短期教学参考书超期、或被催还多次仍不归还，图书馆可暂停其借书、预约等流通权限。管理员处理本系统逾期记录时，应先核对读者未还册数、累计逾期天数、是否多次催还，再决定提醒、限制或人工处理。\n"
                        + "二、出差实习与特殊情况。读者长期离校、出差、实习前应主动归还或续借。若读者主张特殊情况，管理员不应直接自动免除责任，应要求提供说明材料，再由人工按制度处理。\n"
                        + "三、遗失处理。遗失图书一般优先赔偿原书，需核对书名、责任者、出版社、版本等信息。若用同一出版社新版抵赔，应由馆员审核是否可替代。赔书后通常仍需支付加工、条码、编目等处理成本。\n"
                        + "四、无法赔原书时的赔款。可按出版年代、原价、套书情况、馆藏稀缺程度等因素计算赔偿。多卷册图书遗失一册时，很多制度会按整套价格或整套完整性处理。管理员在本系统中只应生成赔偿建议，不应由AI自动扣费或删除记录。\n"
                        + "五、损坏处理。勾画、批注、撕页、水渍、污染、条码损坏、封面损坏等都属于馆藏损坏风险。轻微损坏可要求修补或赔偿处理费，严重损坏影响继续流通时可按遗失处理。系统记录时应保留书号、读者、损坏描述、处理方式和经办人。\n"
                        + "六、本系统建议口径。AI回答逾期或遗失问题时，应先给出核查步骤：查借阅状态、查应还日期、查读者联系方式、查是否已归还、查是否存在遗失或损坏，再建议联系读者、登记处理结果和回到原业务页面人工更新状态。",
                "official_rule"));
        list.add(defaultItem("官方细则样例：清华大学图书馆图书借还与遗失赔偿规则",
                "来源：清华大学图书馆“图书借还”“馆藏书刊遗失、损坏赔偿办法”；链接：https://lib.tsinghua.edu.cn/jy/tsjh.htm；https://lib.tsinghua.edu.cn/info/1115/2621.htm。\n"
                        + "适用场景：用于回答借期、续借、预约催还、逾期使用费、遗失赔偿、污损赔偿和多卷书处理问题。\n"
                        + "一、借还与续借。高校图书馆通常按读者身份、图书类型、借期类型设置借阅册数和借期。部分短期图书、教学参考书、特藏或馆内使用资料借期较短，不能简单按普通图书处理。管理员在系统中解释借期时，应说明以具体图书类型和读者身份为准。\n"
                        + "二、预约与催还。若外借图书被其他读者预约，图书馆可能缩短借期并发出催还通知。催还通知属于流通提醒，读者仍需主动关注自己的到期日期。系统中若未来增加预约功能，应把预约违约、催还次数和到期日期单独记录，避免只看普通借期。\n"
                        + "三、逾期费用与提醒。未按期归还图书时，部分规则按借期类型、超期天数计算逾期使用费，也会提供线上或线下缴费方式。当前项目没有收费模块，AI只能提示“存在逾期风险、建议联系读者、按馆内制度处理”，不能自动扣款。\n"
                        + "四、遗失赔偿。遗失图书可优先用同版图书赔偿；无法获得同版图书时，可根据国内/国外出版、原价、出版年、处理费、保管费等因素计算赔款。无法确定原价时，可按固定基准价加处理费估算。学位论文、合订期刊、多卷册图书、库存本或赠书可能有特别规则。\n"
                        + "五、损坏与污损。图书污损包括圈点、涂写、撕割、浸水、条码损坏、磁条损坏等。轻微污损可按处数或修复成本处理，严重污损导致无法继续流通时可按遗失赔偿处理。管理员应把损坏图片、描述和处理结论作为人工记录保存。\n"
                        + "六、本系统建议口径。AI回答时应把“制度依据”和“系统可执行动作”分开：制度可说明逾期、催还、赔偿逻辑；系统动作只能建议管理员在借阅、归还、图书页面核对和手动操作。",
                "official_rule"));
        list.add(defaultItem("官方细则样例：中山大学图书馆图书借阅管理与文献资源合理利用规定",
                "来源：中山大学图书馆《图书借阅管理细则》《文献资源合理利用规定》；链接：https://library.sysu.edu.cn/article/3778；https://library.sysu.edu.cn/article/450。\n"
                        + "适用场景：用于回答预约、续借、超期、违约、遗失赔偿、合理使用和逾期恢复权限问题。\n"
                        + "一、预约与取书违约。读者预约图书后，应在规定时间内取书。若预约图书逾期不取，可记录违约；短期内多次预约违约时，可暂停预约权限一段时间。当前项目暂无预约表，AI只能说明该类规则可作为后续扩展字段：预约时间、保留期限、取书状态、违约次数。\n"
                        + "二、超期责任。对逾期未还图书，图书馆可暂停读者外借服务，要求读者完成超期教育、测试或等待一定期限后恢复外借。不同逾期天数区间可对应不同题量、等待天数或恢复条件。当前系统可把逾期风险分级：轻度逾期、较长逾期、严重逾期，并给管理员处理建议。\n"
                        + "三、遗失赔偿。遗失文献通常按出版年代设置赔偿系数，并加收文献加工费。越早出版、越稀缺、越难购回的文献，赔偿系数通常越高。办理遗失赔偿时若已超过借阅期限，还应同时承担逾期责任。\n"
                        + "四、合理利用文献资源。制度强调公平合理使用馆藏，不应长期占用热门文献，不应通过逾期、恶意预约、损坏、丢失等方式影响其他读者。系统中的热门图书、逾期风险、冷门图书看板可用于辅助发现资源占用和流通异常。\n"
                        + "五、本系统建议口径。问答涉及“预约没取”“逾期多久恢复”“遗失如何赔偿”时，应先说明本项目未配置完整罚款或预约模块，再给出参考制度逻辑和管理员人工处理步骤。",
                "official_rule"));
        list.add(defaultItem("官方细则样例：中国海洋大学图书馆外借、逾期占用与违规使用处理办法",
                "来源：中国海洋大学图书馆《图书外借规则》《关于违规使用图书馆馆藏文献的处理办法》《校外人员办理读者证管理办法》；链接：https://library.ouc.edu.cn/Home/Detail/20；https://library.ouc.edu.cn/Home/Detail/18；https://library.ouc.edu.cn/Home/Detail/19。\n"
                        + "适用场景：用于回答外借查询、超期宽限、逾期占用费、寒暑假到期、赔书、赔款、校外读者证限制等问题。\n"
                        + "一、借阅查询。读者可通过系统、服务台或个人账户查询借阅状态。管理员提醒读者时，应明确“系统记录的应还日期”为准，通知只是辅助提醒。当前项目的借阅列表应优先显示书名、书号、读者、电话、状态和应还日期。\n"
                        + "二、逾期占用。部分图书馆对逾期设置短期宽限或按天收取占用补偿费。寒暑假期间到期的图书可能有返校后宽限期，但假期前已经到期的图书通常仍计入逾期。当前系统没有费用模块，AI只能标记逾期风险和建议联系归还。\n"
                        + "三、遗失赔偿。遗失图书可赔同版书并支付文献加工费，也可按出版年代和原价倍数赔款。外文赠书、成套书遗失单册、赔偿后找回原书等情况通常有单独规则。系统处理时应保留赔偿方式、金额建议、是否找回原书、是否退还赔款等人工字段。\n"
                        + "四、校外读者证。校外人员读者证通常不得转借，仅限本人借阅使用。若逾期或遗失，可按逾期天数分级暂停资格，严重逾期可能终止借阅资格。当前项目会员管理可借鉴该思路增加读者状态字段，但AI不得直接修改会员状态。\n"
                        + "五、本系统建议口径。涉及费用、押金、证件资格、校外读者的回答要提示“以本馆正式制度为准”，AI只能基于知识库说明参考处理流程。",
                "official_rule"));
        list.add(defaultItem("官方细则样例：电子阅读器、电纸书等设备类馆藏借阅办法",
                "来源：北京大学图书馆电纸书借阅办法；链接：https://lib.pku.edu.cn/portal/cn/fw/jyfw/dianzhishu/jieyuebanfa。\n"
                        + "适用场景：用于回答非纸质图书、设备类馆藏、电子阅读器、配件损坏、设备遗失和逾期问题。\n"
                        + "一、设备类馆藏不同于普通图书。电纸书、阅读器、平板、随书光盘、配套设备等通常有更高单价、配件清单和借用确认要求。管理员应在借出前核对设备编号、外观、配件和读者信息，归还时再次验收。\n"
                        + "二、逾期处理。设备类馆藏逾期可能按套、按天计算费用，且可能影响读者后续借阅资格。读者因出差、实习等原因逾期，通常需要提供证明并由馆员人工判断是否减免。系统中不应由AI自动减免或扣款。\n"
                        + "三、遗失与损坏。设备遗失应按采购或引进价格、设备型号、配件缺失情况和加工处理成本赔偿。若赔款后找回设备，是否退款、是否追缴逾期费用、设备能否继续流通都应由管理员按制度处理。损坏包括屏幕损伤、外壳磨损、撞击、无法开机、配件缺失等。\n"
                        + "四、本系统建议口径。当前项目主要管理图书，若未来扩展设备类馆藏，应新增资产编号、设备型号、配件清单、押金或赔偿记录字段。AI可用于生成验收清单和赔偿说明，不应直接执行资产处置。",
                "official_rule"));
        return list;
    }

    private List<String> domainTerms() {
        return Arrays.asList(
                "借阅", "还书", "归还", "续借", "预约", "催还", "逾期", "超期", "遗失", "丢失", "损坏", "污损",
                "赔偿", "赔书", "赔款", "加工费", "处理费", "暂停", "权限", "读者证", "校外人员", "寒暑假",
                "热门", "冷门", "库存", "分类", "编目", "质检", "删除", "修改", "新增", "电纸书", "设备"
        );
    }

    private AIKnowledge defaultItem(String title, String content) {
        return defaultItem(title, content, "default");
    }

    private AIKnowledge defaultItem(String title, String content, String sourceType) {
        AIKnowledge item = new AIKnowledge();
        item.setTitle(title);
        item.setContent(content);
        item.setSourceType(sourceType);
        return item;
    }

    private String buildAnswer(String question, List<AIKnowledge> contexts, Map<String, Object> dbFacts) {
        if (!isDbFactsEmpty(dbFacts)) {
            Integer totalMatched = (Integer) dbFacts.get("totalMatched");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> books = (List<Map<String, Object>>) dbFacts.get("matchedBooks");
            if (totalMatched == null || totalMatched <= 0) {
                if (!contexts.isEmpty()) {
                    String context = contexts.stream().map(AIKnowledge::getContent).collect(Collectors.joining("；"));
                    return "根据知识库内容：" + context + "。针对你的问题“" + question + "”，建议按上述规则处理。";
                }
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
        boolean policyQuestion = containsAny(question, "逾期", "超期", "续借", "预约", "催还", "遗失", "丢失", "损坏", "污损", "赔偿", "赔书", "赔款", "读者证");
        boolean inventoryQuestion = containsAny(question, "有没有", "哪些", "推荐", "库存", "作者", "出版社", "分类", "书名", "馆藏查询", "图书列表", "冷门", "热门");
        if (policyQuestion && !inventoryQuestion) {
            return false;
        }
        return inventoryQuestion || containsAny(question, "图书馆藏", "馆藏图书");
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
