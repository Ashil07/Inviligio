package com.invigilo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InvigiloApplication {

    public static void main(String[] args) {
        SpringApplication.run(InvigiloApplication.class, args);
    }
}
