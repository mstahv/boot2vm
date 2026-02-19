package org.example;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.server.ServiceInitEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.vaadin.firitin.util.BrowserCookie;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This service is related to boot2vm's graceful blue green deployments
 */
@Service
public class GracefulBlueGreenService {

    public static final String SLOT_COOKIE = "X-Server-Slot";

    public static final String MIGRATION_TYPE_COOKIE = "MIGRATION_TYPE";
    public static final String USER = "USER";
    public static final String FORCED = "FORCED";
    public static final String AUTO = "AUTO";

    private Set<UI> uiSet = ConcurrentHashMap.newKeySet();

    private Set<UI> fixedUiSet = ConcurrentHashMap.newKeySet();

    // APP_SLOT environment variable (set by systemd service, e.g. APP_SLOT=blue)
    @Value("${app.slot:local}")
    private String slot;

    public String getSlot() {
        return slot;
    }

    public void registerUI(UI ui) {
        uiSet.add(ui);
    }

    public void unregisterUI(UI ui) {
        uiSet.remove(ui);
        fixedUiSet.remove(ui);
    }

    public int uiCount() {
        return uiSet.size();
    }

    @EventListener
    private void onServiceInit(ServiceInitEvent serviceInitEvent) {
        serviceInitEvent.getSource().addUIInitListener(event -> {
            UI ui = event.getUI();
            registerUI(ui);
            BrowserCookie.detectCookieValue(MIGRATION_TYPE_COOKIE, value -> {
                if(value != null) {
                    if(AUTO.equals(value)) {
                        // note, for users it is just a "version" although as web engineers, we'd love to
                        // write "moved to new server" here...
                        Notification.show("Hello old user! You were automatically upgraded to a new version.");
                    } else if(USER.equals(value)) {
                        Notification.show("Welcome to the new version!");
                    } else if(FORCED.equals(value)) {
                        Notification.show("Sorry, we had to migrate you forcefully");
                    } else {
                        Notification.show("Hello user, you were just brought to a new version, type" + value);
                    }
                    BrowserCookie.setCookie(MIGRATION_TYPE_COOKIE, "", LocalDateTime.now().minusDays(1));
                }
            });

            event.getUI().addDetachListener(event1 -> {
                unregisterUI(ui);
                fixedUiSet.remove(ui);
            });
            // Note, in a real Vaadin app, this can be a good place to fix users to this node with cookie
        });
    }

    public void notifyUIs(String deadline) {
        uiSet.forEach(ui -> {
            if(fixedUiSet.contains(ui)) {
                ui.access(() -> {
                    // Note, in a real world setup Notification is not the best way to let users upgrade,
                    // this is just handy in this example.
                    // You'll most likely want to show a notification (that dissappears) and then
                    // keep some sort of badge in the UI hinting users to upgrade. Possibly also showing
                    // how much time they have still to e.g. save the work on old server. And remind closer
                    // to the deadline.
                    Notification notification = new Notification();
                    notification.add("There is a new version available!");
                    if (deadline != null && !deadline.isBlank()) {
                        try {
                            String formatted = DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'")
                                    .withZone(ZoneOffset.UTC)
                                    .format(Instant.parse(deadline));
                            notification.add(new Paragraph("Auto-upgrade at " + formatted));
                        } catch (Exception ignored) {
                        }
                    }
                    notification.add(new Button("Upgrade now!", click -> {
                        unregisterUI(ui);
                        // Reset cookie
                        BrowserCookie.setCookie(SLOT_COOKIE, null);
                        // Indicate to new host that user triggered upgrade
                        BrowserCookie.setCookie(MIGRATION_TYPE_COOKIE, USER);
                        // reload page to navigate to new server, use JS call so it happens AFTER
                        // setting the cookie...
                        UI.getCurrent().getPage().executeJs("window.location.reload()");
                    }));
                    notification.addThemeVariants(NotificationVariant.LUMO_WARNING);
                    ui.add(notification);
                    notification.open();
                });
            } else {
                // This user is not on a critical state of the app, just reload brutally, but first
                // set a cookie, so we can detect these and show a notification from the new server
                // that the UI was migrated
                ui.access(() -> {
                    System.out.println("AUTO COOKIE, MIGRATE RIGHT AWAY");
                    // This goes via websocket still so it works nice
                    // If websockets not in use, should fix all for slot and clear fixing after
                    // cookie set or some tiny timeout.
                    BrowserCookie.setCookie(MIGRATION_TYPE_COOKIE, AUTO);
                    // reload page to navigate to new server, use JS call so it happens AFTER
                    // setting the cookie...
                    UI.getCurrent().getPage().executeJs("window.location.reload()");
                });

            }

        });

    }

    /**
     * Pins the current UI to this slot: sets the reverse-proxy slot cookie, pre-sets the
     * migration-type cookie to FORCED (overwritten to USER if the user later voluntarily upgrades),
     * and registers the UI so active-users count includes it.
     * Must be called from the UI thread.
     */
    public void pinCurrentUi() {
        // hint the proxy that this user should not be moved to the new server
        BrowserCookie.setCookie(SLOT_COOKIE, slot);
        // This marker cookie is set to something else if e.g. user triggers the upgrade manually
        BrowserCookie.setCookie(MIGRATION_TYPE_COOKIE, FORCED);
        registerFixedUi(UI.getCurrent());
    }

    public void registerFixedUi(UI ui) {
        fixedUiSet.add(ui);
    }

    public int fixedCount() {
        return fixedUiSet.size();
    }
}
