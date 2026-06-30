package com.example.springboot.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AITaskLog {
    private Long id;
    private Integer adminId;
    private String taskType;
    private String inputJson;
    private String outputJson;
    private String status;
    private LocalDateTime createTime;
}
