package com.ai.repurposer;

public class User {
    public String firstName;
    public String lastName;
    public Integer age;
    public String gender;
    public String email;
    public String password;
    public String otp;
    public String plan;
    public String billingCycle;
    public Long planExpiresAtEpochDay;

    public User(){}

    public User(String email,String password,String plan){
        this.firstName="";
        this.lastName="";
        this.age=null;
        this.gender="";
        this.email=email;
        this.password=password;
        this.otp="";
        this.plan=plan;
        this.billingCycle="none";
        this.planExpiresAtEpochDay=null;
    }
}
