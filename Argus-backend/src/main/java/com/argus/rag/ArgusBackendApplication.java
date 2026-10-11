package com.argus.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.argus.rag.**.mapper")
public class ArgusBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArgusBackendApplication.class, args);
    }
}
