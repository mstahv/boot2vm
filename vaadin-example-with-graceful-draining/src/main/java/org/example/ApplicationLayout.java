package org.example;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Emphasis;
import com.vaadin.flow.component.html.H5;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.router.Layout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.vaadin.firitin.appframework.MainLayout;
import org.vaadin.firitin.util.BrowserCookie;
import org.vaadin.firitin.util.style.LumoProps;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

@Layout
public class ApplicationLayout extends MainLayout {

    private Div currentVersion = new Div("unknown"){{
        getStyle().setFontSize(LumoProps.FONT_SIZE_XS.var());
        getStyle().setTextAlign(Style.TextAlign.CENTER);
    }};

    @Autowired
    GracefulBlueGreenService gracefulBlueGreenService;

    @Autowired(required = false)
    BuildProperties buildProperties;

    @Override
    protected Object getDrawerHeader() {
        return "Graceful Blue-Green Demo";
    }

    @Override
    protected void addDrawerContent() {
        super.addDrawerContent();
        addToDrawer(currentVersion);
        if(buildProperties != null) {
            currentVersion.setText("App built: " + buildProperties.getTime().toString());
        }
        greetUserOnArrival();
    }

    private void greetUserOnArrival() {
        BrowserCookie.detectCookieValue(GracefulBlueGreenService.MIGRATION_TYPE_COOKIE, value -> {
            if(value != null) {
                if(GracefulBlueGreenService.AUTO.equals(value)) {
                    // note, for users it is just a "version" although as web engineers, we'd love to
                    // write "moved to new server" here...
                    notifyUser("Hello old user! You were automatically upgraded to a new version.");
                } else if(GracefulBlueGreenService.USER.equals(value)) {
                    notifyUser("Welcome to the new version!");
                } else if(GracefulBlueGreenService.FORCED.equals(value)) {
                    notifyUser("Sorry, we had to migrate you forcefully");
                }
                // Forget the cookie value
                BrowserCookie.setCookie(GracefulBlueGreenService.MIGRATION_TYPE_COOKIE, "", LocalDateTime.now().minusDays(1));
            }
        });

    }

    public void announceNewVersion(Instant upgradeDeadLine) {
        addToDrawer(new NewVersionBadge(upgradeDeadLine));
        // Also show notification
        notifyUser("There is a new application version available, upgrade as soon as possible!");
    }

    public class NewVersionBadge extends VerticalLayout {
        public NewVersionBadge(Instant upgradeDeadLine) {
            add(new H5("New version available!"));
            add(new Emphasis("You have time until %s to save your work and upgrade.".formatted(getTime(upgradeDeadLine))){{
                getStyle().setFontSize(LumoProps.FONT_SIZE_XS.var());
            }});
            add(new Button("Update now!", event -> {
                gracefulBlueGreenService.selfUpgradeUI(UI.getCurrent());
            }));

            setAlignItems(Alignment.CENTER);
            setSpacing(false);
            getStyle().setBackgroundColor(LumoProps.ERROR_COLOR_10PCT.var());
            getStyle().setTextAlign(Style.TextAlign.CENTER);
        }

        private static LocalTime getTime(Instant upgradeDeadLine) {
            String timeZoneId = UI.getCurrent().getPage().getExtendedClientDetails().getTimeZoneId();
            ZonedDateTime zonedDateTime = upgradeDeadLine.atZone(ZoneId.of(timeZoneId));
            return zonedDateTime.toLocalTime().truncatedTo(ChronoUnit.SECONDS);
        }
    }

    private void notifyUser(String msg) {
        Notification.show(msg).setPosition(Notification.Position.TOP_END);
    }

}
