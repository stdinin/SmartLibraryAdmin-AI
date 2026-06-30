package com.example.springboot.controller.request.ai;

import lombok.Data;

@Data
public class AgentConfirmRequest {
    private Long taskLogId;
    private Boolean confirm;
}
