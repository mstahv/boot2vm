package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The entry point of the Spring Boot application.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        // Uncomment to test a failing deployment (Deploy script should keep the previous version)
        //System.err.println("Killing server for demo purposes...");
        //System.exit(1);
        SpringApplication.run(Application.class, args);
    }

}
