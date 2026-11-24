package com.localexperts.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import java.util.*;

/**
 * POST /api/chat
 * Proxies LEXI chatbot requests to Anthropic API.
 * Keeps the API key server-side (never exposed to browser).
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    @Value("${anthropic.api.key:}")
    private String anthropicApiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            // Build Anthropic request
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "claude-sonnet-4-20250514");
            requestBody.put("max_tokens", 1000);
            if (body.containsKey("system"))   requestBody.put("system",   body.get("system"));
            if (body.containsKey("messages")) requestBody.put("messages", body.get("messages"));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", anthropicApiKey);
            headers.set("anthropic-version", "2023-06-01");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> anthropicResp = restTemplate.postForEntity(
                ANTHROPIC_URL, entity, Map.class
            );

            // Extract text from response
            Map respBody = anthropicResp.getBody();
            String text = "";
            if (respBody != null && respBody.containsKey("content")) {
                List contentList = (List) respBody.get("content");
                if (!contentList.isEmpty()) {
                    Map firstBlock = (Map) contentList.get(0);
                    text = (String) firstBlock.getOrDefault("text", "");
                }
            }

            response.put("success", true);
            response.put("text", text);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            System.err.println("❌ Chat API error: " + e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}
