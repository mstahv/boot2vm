package org.example.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Emphasis;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import in.virit.color.NamedColor;
import org.example.GracefulBlueGreenService;
import org.vaadin.firitin.util.style.LumoProps;

@Route
public class MainView extends VerticalLayout {

    private final GracefulBlueGreenService gracefulBlueGreenService;
    private AppStats appStats = new AppStats();

    public MainView(GracefulBlueGreenService gracefulBlueGreenService) {
        this.gracefulBlueGreenService = gracefulBlueGreenService;
        add(new H1("GracefulBlueGreen demo"));

        String slot = gracefulBlueGreenService.getSlot();
        int port = 8080;
        if ("green".equals(slot)) {
            port = 8081;
        }
        add(new Paragraph("Current slot: " + slot + ", on port " + port + ""){{
            if(!slot.equals("local")) {
                NamedColor namedColor = NamedColor.of(slot);
                getStyle().setBackgroundColor(namedColor.toRgbColor().withAlpha(0.2).toString());
                getStyle().setBorder("4px solid " + namedColor.toString());
                getStyle().setPadding(LumoProps.SPACE_M.var());
            }
        }});

        add(new Emphasis("Users should be fixed to version/slot in critical phases of the app automatically, but giving that power to the user here for demo/test purposes."));

        add(new Button("Fix me to this slot!", event -> {
            gracefulBlueGreenService.pinCurrentUi();
            Notification.show("Now you are fixed to " + slot + ". On a new version, you'll have a chance to choose your upgrade time.")
                    .setPosition(Notification.Position.TOP_END);
            appStats.update();
        }));

        add(appStats);
        appStats.update();

        add(new H3("Dummy UI"));

        Button button = new Button("Click me",
                event -> {
                    add(new Paragraph("Clicked!"));
                    appStats.update();
                });

        add(button);
    }

    class AppStats extends Div {

        public void update() {
            removeAll();
            add(new H3("Current app stats"));
            add(new Paragraph("UIs fixed to this version(~ app server): " + gracefulBlueGreenService.fixedCount()));
            add(new Paragraph("UIs total on this app server: " + gracefulBlueGreenService.uiCount()));
        }
    }
}
