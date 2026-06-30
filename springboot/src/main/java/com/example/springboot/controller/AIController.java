package com.example.springboot.controller;

import com.example.springboot.common.Result;
import com.example.springboot.controller.request.ai.AgentConfirmRequest;
import com.example.springboot.controller.request.ai.AgentPlanRequest;
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

    @GetMapping("/agent/tools")
    public Result agentTools() {
        return Result.success(agentService.toolPolicy());
    }

    private Integer currentAdminId() {
        Admin admin = TokenUtils.getCurrentAdmin();
        return admin == null ? null : admin.getId();
    }
}
