package com.example.springboot.controller;

import com.example.springboot.common.Result;
import com.example.springboot.controller.request.ai.AgentConfirmRequest;
import com.example.springboot.controller.request.ai.AgentPlanRequest;
import com.example.springboot.controller.request.ai.AIKnowledgeRequest;
import com.example.springboot.controller.request.ai.CatalogRequest;
import com.example.springboot.controller.request.ai.RagChatRequest;
import com.example.springboot.entity.Admin;
import com.example.springboot.service.AIService;
import com.example.springboot.service.AgentService;
import com.example.springboot.service.RagService;
import com.example.springboot.utils.TokenUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@CrossOrigin
@RestController
@RequestMapping("/ai")
public class AIController {

    @Autowired
    private AIService aiService;

    @Autowired
    private RagService ragService;

    @Autowired
    private AgentService agentService;

    @PostMapping("/catalog/generate")
    public Result generateCatalog(@RequestBody CatalogRequest request) {
        try {
            return Result.success(aiService.generateCatalog(request, currentAdminId()));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/books/quality-check")
    public Result qualityCheck() {
        return Result.success(aiService.checkBookQuality(currentAdminId()));
    }

    @GetMapping("/borrow/analyze")
    public Result borrowAnalyze(@RequestParam(defaultValue = "month") String range) {
        return Result.success(aiService.analyzeBorrow(range, currentAdminId()));
    }

    @PostMapping("/rag/chat")
    public Result ragChat(@RequestBody RagChatRequest request) {
        try {
            return Result.success(ragService.chat(currentAdminId(), request.getQuestion()));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/knowledge/list")
    public Result knowledgeList() {
        return Result.success(ragService.listKnowledge());
    }

    @PostMapping("/knowledge/save")
    public Result knowledgeSave(@RequestBody AIKnowledgeRequest request) {
        try {
            return Result.success(ragService.saveKnowledge(request));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/knowledge/seed-default")
    public Result knowledgeSeedDefault() {
        return Result.success(ragService.seedDefaultKnowledge());
    }

    @DeleteMapping("/knowledge/{id}")
    public Result knowledgeDelete(@PathVariable Long id) {
        try {
            ragService.deleteKnowledge(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/agent/plan")
    public Result agentPlan(@RequestBody AgentPlanRequest request) {
        try {
            return Result.success(agentService.plan(currentAdminId(), request.getTask()));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/agent/confirm")
    public Result agentConfirm(@RequestBody AgentConfirmRequest request) {
        return Result.success(agentService.confirm(currentAdminId(), request.getTaskLogId(), request.getConfirm()));
    }

    @GetMapping("/agent/tasks")
    public Result agentTasks(@RequestParam(defaultValue = "10") Integer limit) {
        return Result.success(agentService.listTasks(currentAdminId(), limit));
    }

    @GetMapping("/agent/tasks/{taskLogId}")
    public Result agentTask(@PathVariable Long taskLogId) {
        return Result.success(agentService.taskStatus(currentAdminId(), taskLogId));
    }

    @PostMapping("/agent/tasks/{taskLogId}/cancel")
    public Result agentTaskCancel(@PathVariable Long taskLogId) {
        return Result.success(agentService.cancel(currentAdminId(), taskLogId));
    }

    @GetMapping("/agent/tools")
    public Result agentTools() {
        return Result.success(agentService.toolPolicy());
    }

    private Integer currentAdminId() {
        Admin admin = TokenUtils.getCurrentAdmin();
        return admin == null ? null : admin.getId();
    }
}
