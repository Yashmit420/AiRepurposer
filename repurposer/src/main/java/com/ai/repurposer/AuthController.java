package com.ai.repurposer;

import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.io.*;

@RestController
@CrossOrigin
public class AuthController {

    private static final String FILE="users.json";
    private ObjectMapper mapper=new ObjectMapper();

    private List<User> load() throws Exception{
        File f=new File(FILE);
        if(!f.exists()) return new ArrayList<>();
        try{
            return Arrays.asList(mapper.readValue(f,User[].class));
        }catch(Exception e){
            return new ArrayList<>();
        }
    }

    private void save(List<User> users) throws Exception{
        File f = new File(FILE);
        File parent = f.getParentFile();
        if(parent != null && !parent.exists()){
            parent.mkdirs();
        }
        if(!f.exists()){
            f.createNewFile();
        }
        mapper.writeValue(f,users);
    }

    @PostMapping("/signup")
    public String signup(@RequestBody User body) throws Exception{
        List<User> users=new ArrayList<>(load());

        for(User u:users){
            if(u.email.equals(body.email)) return "User exists";
        }

        users.add(new User(body.email,body.password,"free"));
        save(users);
        return "Signup success";
    }

    @PostMapping("/login")
    public String login(@RequestBody User body) throws Exception{
        List<User> users=load();

        for(User u:users){
            if(u.email.equals(body.email) && u.password.equals(body.password)){
                return u.email; // token
            }
        }
        return "Invalid";
    }

    @GetMapping("/plan")
    public String plan(@RequestParam String email) throws Exception{
        List<User> users=load();

        for(User u:users){
            if(u.email.equals(email)){
                return u.plan;
            }
        }
        return "free";
    }

    @PostMapping("/upgrade")
    public String upgrade(@RequestParam String email, @RequestParam(defaultValue = "pro") String plan) throws Exception{
        List<User> users=new ArrayList<>(load());
        String normalized = plan == null ? "pro" : plan.trim().toLowerCase();
        if(!normalized.equals("advanced") && !normalized.equals("pro")){
            normalized = "pro";
        }

        for(User u:users){
            if(u.email.equals(email)){
                u.plan=normalized;
            }
        }

        save(users);
        return "Upgraded";
    }

    @GetMapping("/account")
    public Map<String,String> account(@RequestParam String email) throws Exception{
        List<User> users=load();
        Map<String,String> out=new HashMap<>();

        for(User u:users){
            if(u.email.equals(email)){
                out.put("email",u.email);
                out.put("plan",u.plan);
                return out;
            }
        }

        out.put("email",email);
        out.put("plan","free");
        return out;
    }

    @PostMapping("/account/update")
    public String updateAccount(@RequestBody Map<String,String> body) throws Exception{
        String email = body.getOrDefault("email","");
        String newEmail = body.getOrDefault("newEmail","").trim();
        String password = body.getOrDefault("password","");

        if(email.isEmpty()) return "Missing email";

        List<User> users=new ArrayList<>(load());
        User target=null;

        for(User u:users){
            if(u.email.equals(email)){
                target=u;
                break;
            }
        }

        if(target==null) return "User not found";

        if(!newEmail.isEmpty() && !newEmail.equals(email)){
            for(User u:users){
                if(u.email.equals(newEmail)) return "Email already used";
            }
            target.email=newEmail;
        }

        if(!password.isEmpty()){
            target.password=password;
        }

        save(users);
        return "Account updated";
    }

    @PostMapping("/account/delete")
    public String deleteAccount(@RequestParam String email) throws Exception{
        List<User> users=new ArrayList<>(load());
        boolean removed = users.removeIf(u -> u.email.equals(email));
        save(users);
        return removed ? "Deleted" : "User not found";
    }
}
