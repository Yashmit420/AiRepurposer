package com.ai.repurposer;

import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.io.*;
import java.net.*;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@CrossOrigin
public class AIController {

    private static Map<String,Integer> usage = new HashMap<>();
    private static long lastReset = System.currentTimeMillis();
    private static final int FREE_LIMIT = 3;
    private static final String FILE = "users.json";

    @PostMapping("/generate")
    public List<String> generate(
        @RequestBody Map<String,String> body,
        @RequestParam String email,
        HttpServletRequest request
    ) throws Exception {

        long now = System.currentTimeMillis();
        if(now - lastReset > 24 * 60 * 60 * 1000){
            usage.clear();
            lastReset = now;
        }

        String plan="free";
        ObjectMapper mapper=new ObjectMapper();
        File f=new File(FILE);
        if(f.exists()){
            try{
                User[] users=mapper.readValue(f,User[].class);
                for(User u:users){
                    if(u.email.equals(email)) plan=u.plan;
                }
            }catch(Exception e){
                plan="free";
            }
        }

        String userIP=request.getRemoteAddr();

        if("free".equals(plan)){
            int count = usage.getOrDefault(userIP,0);
            if(count >= FREE_LIMIT){
                return Arrays.asList("Free limit reached. Upgrade.");
            }
            usage.put(userIP,count+1);
        }

        String input = body.get("text");

        String prompt =
        "Break the following idea or video into multiple short-form videos.\n" +
        "For each video provide:\n" +
        "Video number\n" +
        "Timestamp\n" +
        "Caption\n" +
        "3 Tweets\n" +
        "Description with hashtags\n\n" +
        input;

        String apiKey = System.getenv("OPENAI_API_KEY");
        if(apiKey == null || apiKey.isEmpty()){
            return Arrays.asList("API key missing");
        }

        URL url = new URL("https://api.openai.com/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        Map<String,Object> message = new HashMap<>();
        message.put("role","user");
        message.put("content",prompt);

        List<Map<String,Object>> messages = new ArrayList<>();
        messages.add(message);

        Map<String,Object> requestBody = new HashMap<>();
        requestBody.put("model","gpt-4o-mini");
        requestBody.put("messages",messages);

        String json = mapper.writeValueAsString(requestBody);

        try(OutputStream os = conn.getOutputStream()){
            os.write(json.getBytes());
        }

        InputStream stream;
        if(conn.getResponseCode() >= 400){
            stream = conn.getErrorStream();
        }else{
            stream = conn.getInputStream();
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(stream));
        String line;
        StringBuilder response = new StringBuilder();
        while((line = br.readLine()) != null){
            response.append(line);
        }

        Map<String,Object> map = mapper.readValue(response.toString(), Map.class);

        if(map.containsKey("error")){
            return Arrays.asList(map.get("error").toString());
        }

        List<Map<String,Object>> choices = (List<Map<String,Object>>) map.get("choices");
        Map<String,Object> first = choices.get(0);
        Map<String,Object> msg = (Map<String,Object>) first.get("message");

        String content = msg.get("content").toString();

        String[] blocks = content.split("\n\n");
        List<String> result = new ArrayList<>();
        for(String b : blocks){
            if(!b.trim().isEmpty()){
                result.add(b.trim());
            }
        }

        return result;
    }
}
