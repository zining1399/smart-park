package com.smartpark;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan("com.smartpark.mapper")
@SpringBootApplication
public class SmartParkApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartParkApplication.class, args);
    }
}
