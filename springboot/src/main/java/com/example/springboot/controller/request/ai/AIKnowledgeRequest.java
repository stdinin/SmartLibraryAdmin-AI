package com.example.springboot.controller.request.ai;

import lombok.Data;

@Data
public class AIKnowledgeRequest {
    private Long id;
    private String title;
    private String content;
    private String sourceType;
}
