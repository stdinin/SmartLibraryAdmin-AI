package com.example.springboot.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AIChunk {
    private Long id;
    private Long knowledgeId;
    private String chunkText;
    private String embeddingId;
    private String embeddingText;
    private String metadata;
    private LocalDateTime createTime;
}
