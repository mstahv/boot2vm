package org.example.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Emphasis;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import org.example.GracefulBlueGreenService;
import org.springframework.boot.info.BuildProperties;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Route
public class MainView extends VerticalLayout {

    private static final DateTimeFormatter BUILD_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    public MainView(GracefulBlueGreenService gracefulBlueGreenService, Optional<BuildProperties> buildProperties) {
        String buildTime = buildProperties
                .map(bp -> BUILD_TIME_FORMAT.format(bp.getTime()))
                .orElse("unknown");
        add(new H1("GracefulBlueGreen demo, build time: " + buildTime));

        String slot = gracefulBlueGreenService.getSlot();
        int port = 8080;
        if("green".equals(slot)) {
            port = 8081;
        }
        add(new Paragraph("Current server slot: " + slot + " app server running on port" + port + " (if defaults in use) "));

        add(new Paragraph("UIs fixed to this version(~ app server): " + gracefulBlueGreenService.fixedCount()));
        add(new Paragraph("UIs total on this app server: " + gracefulBlueGreenService.uiCount()));

        add(new Emphasis("In Vaadin app you most often want to fix to current app server on UI attach, but you can do it here explicitly for demo/test purposes."));

        add(new Button("Fix me to this server!", event -> {
            gracefulBlueGreenService.pinCurrentUi();
            Notification.show("Now you are fixed to " + slot + ". On a new version, you'll have a chance to choose your upgrade time.");
        }));

        Button button = new Button("Click me",
                event -> add(new Paragraph("Clicked!")));

        add(button);
    }
}
