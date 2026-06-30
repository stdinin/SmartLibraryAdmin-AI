package com.example.springboot.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class AIClient {

    @Value("${ai.api-url:}")
    private String apiUrl;

    @Value("${ai.api-key:}")
    private String apiKey;

    @Value("${ai.model:}")
    private String model;

    @Value("${ai.timeout:15000}")
    private int timeout;

    public String complete(String prompt) {
        if (StrUtil.isBlank(apiUrl) || StrUtil.isBlank(apiKey) || StrUtil.isBlank(model)) {
            return null;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            JSONArray messages = new JSONArray();
            JSONObject message = new JSONObject();
            message.set("role", "user");
            message.set("content", prompt);
            messages.add(message);
            payload.put("messages", messages);
            payload.put("temperature", 0.2);

            String body = HttpRequest.post(apiUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .body(JSONUtil.toJsonStr(payload))
                    .execute()
                    .body();
            JSONObject result = JSONUtil.parseObj(body);
            JSONArray choices = result.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            JSONObject first = choices.getJSONObject(0);
            if (first.containsKey("message")) {
                return first.getJSONObject("message").getStr("content");
            }
            return first.getStr("text");
        } catch (Exception e) {
            log.warn("AI服务调用失败，将使用本地兜底结果", e);
            return null;
        }
    }
}
