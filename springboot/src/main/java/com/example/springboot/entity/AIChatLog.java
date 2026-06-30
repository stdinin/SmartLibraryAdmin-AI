package com.example.springboot.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AIChatLog {
    private Long id;
    private Integer adminId;
    private String question;
    private String answer;
    private String retrievedContext;
    private String intent;
    private LocalDateTime createTime;
}
