package com.ai.repurposer;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class AIController {
    private static final int FREE_LIMIT = 3;
    private static final long RESET_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private final UserStore userStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Integer> usageByIp = new ConcurrentHashMap<>();
    private final AtomicLong lastReset = new AtomicLong(System.currentTimeMillis());

    public AIController(UserStore userStore) {
        this.userStore = userStore;
    }

    @PostMapping("/generate")
    public ResponseEntity<List<String>> generate(
        @RequestBody Map<String, String> body,
        @RequestParam String email,
        HttpServletRequest request
    ) {
        String requestedEmail = normalizeEmail(email);
        String token = AuthTokenInterceptor.extractToken(request);
        if (token.isBlank() || !token.equalsIgnoreCase(requestedEmail)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(List.of("Unauthorized"));
        }

        User user = userStore.findByEmail(requestedEmail).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(List.of("Unauthorized"));
        }

        String plan = normalizePlan(user.plan);
        if ("free".equals(plan)) {
            enforceDailyReset();
            String ip = extractClientIp(request);
            int count = usageByIp.getOrDefault(ip, 0);
            if (count >= FREE_LIMIT) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(List.of("Free limit reached. Upgrade."));
            }
            usageByIp.put(ip, count + 1);
        }

        String input = body.getOrDefault("text", "").trim();
        if (input.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(List.of("Input text is required."));
        }

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(List.of("API key missing"));
        }

        String prompt =
            "You are an expert short-form content strategist.

Goal:
If user gives an IDEA, expand it and create 4 short videos.
If user gives a YouTube URL, assume it is a long video and repurpose it into 4 best short videos based on the most valuable moments.

Important rules:
1. Output exactly 4 sections only: Video 1, Video 2, Video 3, Video 4.
2. For each section include:
   - Duration (recommended short length, e.g. 00:30 to 00:45)
   - Best Part / Hook (what moment to use and why)
   - Caption (1 strong line)
   - Description (platform-ready, clear CTA)
   - Tips (2-4 practical tips for editing/posting)
3. Keep language simple, engaging, and creator-friendly.
4. No extra intro/outro text outside the 4 sections.
5. If input is an IDEA (not URL), first infer a logical long-form structure, then split into 4 strongest short-video angles.
6. If input is a URL and no transcript/content is available, still produce high-quality inferred output and clearly label inferred assumptions inside each section in one short line.

User input:" +
                input;

        try {
            String content = callOpenAI(prompt, apiKey);
            List<String> blocks = splitBlocks(content);
            if (blocks.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(List.of("No generated content returned"));
            }
            return ResponseEntity.ok(blocks);
        } catch (IOException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(List.of("AI service unavailable"));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(List.of(ex.getMessage()));
        }
    }

    private void enforceDailyReset() {
        long now = System.currentTimeMillis();
        long prev = lastReset.get();
        if (now - prev <= RESET_INTERVAL_MS) {
            return;
        }
        if (lastReset.compareAndSet(prev, now)) {
            usageByIp.clear();
        }
    }

    private String callOpenAI(String prompt, String apiKey) throws IOException {
        URL url = new URL("https://api.openai.com/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(45000);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", "gpt-4o-mini");
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        String json = mapper.writeValueAsString(requestBody);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        int statusCode = conn.getResponseCode();
        String response = readResponse(conn, statusCode);

        Map<String, Object> map = mapper.readValue(response, Map.class);
        if (statusCode >= 400 || map.containsKey("error")) {
            Object errorObj = map.get("error");
            if (errorObj instanceof Map<?, ?> errorMap) {
                Object msg = errorMap.get("message");
                if (msg != null) {
                    throw new RuntimeException("OpenAI error: " + msg);
                }
            }
            throw new RuntimeException("OpenAI error: request failed");
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("OpenAI error: empty response");
        }

        Map<String, Object> first = choices.get(0);
        Map<String, Object> message = (Map<String, Object>) first.get("message");
        if (message == null || message.get("content") == null) {
            throw new RuntimeException("OpenAI error: missing content");
        }
        return message.get("content").toString();
    }

    private static String readResponse(HttpURLConnection conn, int code) throws IOException {
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) {
            return "{}";
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder out = new StringBuilder();
            while ((line = br.readLine()) != null) {
                out.append(line);
            }
            return out.toString();
        }
    }

    private static List<String> splitBlocks(String content) {
        String[] parts = content.split("\\R\\R+");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String normalizePlan(String plan) {
        String normalized = plan == null ? "free" : plan.trim().toLowerCase();
        if ("advanced".equals(normalized) || "agency".equals(normalized)) {
            return "advanced";
        }
        if ("pro".equals(normalized) || "premium".equals(normalized)) {
            return "pro";
        }
        return "free";
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isBlank()) {
                return first;
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr == null || remoteAddr.isBlank()) ? "unknown" : remoteAddr;
    }
}
