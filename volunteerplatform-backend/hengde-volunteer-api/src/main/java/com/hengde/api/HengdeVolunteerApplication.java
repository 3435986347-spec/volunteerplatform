package com.hengde.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.hengde")
@MapperScan("com.hengde.**.dao")
public class HengdeVolunteerApplication {
    public static void main(String[] args) {
        SpringApplication.run(HengdeVolunteerApplication.class, args);
    }
}
