package com.pylearn.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PyLearnBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(PyLearnBackendApplication.class, args);
        System.out.println("\n==================================================================");
        System.out.println("  PyLearn Spring Boot Backend running on http://localhost:8080");
        System.out.println("  WebSocket Endpoint : ws://localhost:8080/ws");
        System.out.println("  REST API Endpoint  : http://localhost:8080/api");
        System.out.println("==================================================================\n");
    }
}
