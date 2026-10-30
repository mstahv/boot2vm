package org.example;

import in.virit.gracefuldrain.DrawerBadgeNotifier;
import in.virit.gracefuldrain.UpgradeNotifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

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

    /**
     * Tells users about new versions with a badge in the drawer, instead of the default
     * notification. Can be changed on the fly in MainView for demo purposes.
     */
    @Bean
    UpgradeNotifier upgradeNotifier() {
        return new DrawerBadgeNotifier();
    }

}
