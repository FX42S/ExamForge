package com.ocr.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final ConfigService configService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 调用AI API（带图片）
     */
    public String callAiWithImages(List<String> base64Images, String prompt) {
        try {
            String apiUrl = configService.getApiUrl();
            String modelName = configService.getModelName();
            int maxTokens = configService.getMaxTokens();

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("temperature", 0.3);

            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");

            List<Map<String, Object>> content = new ArrayList<>();
            for (String base64 : base64Images) {
                Map<String, Object> imageContent = new HashMap<>();
                imageContent.put("type", "image_url");
                Map<String, Object> imageUrl = new HashMap<>();
                imageUrl.put("url", "data:image/png;base64," + base64);
                imageContent.put("image_url", imageUrl);
                content.add(imageContent);
            }

            Map<String, Object> textContent = new HashMap<>();
            textContent.put("type", "text");
            textContent.put("text", prompt);
            content.add(textContent);

            message.put("content", content);
            messages.add(message);
            requestBody.put("messages", messages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    String result = choices.get(0).path("message").path("content").asText();
                    return result.trim().isEmpty() ? "AI处理结果为空" : result;
                }
            }
            return "AI处理失败：" + response.getStatusCode();
        } catch (Exception e) {
            log.error("AI API 调用失败：{}", e.getMessage());
            return "AI处理异常：" + e.getMessage();
        }
    }

    /**
     * 调用AI API（纯文本）
     */
    public String callAiTextOnly(String prompt) {
        try {
            String apiUrl = configService.getApiUrl();
            String modelName = configService.getModelName();
            int maxTokens = configService.getMaxTokens();

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("temperature", 0.3);

            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            messages.add(message);
            requestBody.put("messages", messages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    String result = choices.get(0).path("message").path("content").asText();
                    return result.trim().isEmpty() ? "AI处理结果为空" : result;
                }
            }
            return "AI处理失败：" + response.getStatusCode();
        } catch (Exception e) {
            log.error("AI API 调用失败：{}", e.getMessage());
            return "AI处理异常：" + e.getMessage();
        }
    }
}
