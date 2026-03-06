package com.payroute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PayRouteApplication {
    public static void main(String[] args) {
        SpringApplication.run(PayRouteApplication.class, args);
    }
}
