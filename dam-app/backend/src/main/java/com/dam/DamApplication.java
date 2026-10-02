package com.dam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Data Asset Management application backend.
 * M0 POC scope: parse test_erp.sql (DDL) into dam_meta tables and expose via REST.
 */
@SpringBootApplication
public class DamApplication {
    public static void main(String[] args) {
        SpringApplication.run(DamApplication.class, args);
    }
}
