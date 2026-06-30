package com.example.springboot.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AIKnowledge {
    private Long id;
    private String title;
    private String content;
    private String sourceType;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
