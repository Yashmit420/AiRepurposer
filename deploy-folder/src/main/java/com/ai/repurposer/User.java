package com.ai.repurposer;

public class User {
    public String email;
    public String password;
    public String plan;

    public User(){}

    public User(String email,String password,String plan){
        this.email=email;
        this.password=password;
        this.plan=plan;
    }
}