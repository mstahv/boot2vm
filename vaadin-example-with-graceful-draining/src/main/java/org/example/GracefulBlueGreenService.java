package org.example;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.ServiceInitEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.vaadin.firitin.util.BrowserCookie;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This service is related to boot2vm's graceful blue green deployments
 */
@Service
public class GracefulBlueGreenService {

    public static final String SLOT_COOKIE = "X-Slot";

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
            // ApplicationLayout greets the user based on MIGRATION_TYPE_COOKIE (see greetUserOnArrival)
            event.getUI().addDetachListener(event1 -> {
                unregisterUI(ui);
                fixedUiSet.remove(ui);
            });
            // Alternatively you could just fix all sessions to servers right away
        });
    }

    public void notifyUIs(String deadline) {
        if (deadline == null || deadline.isBlank()) return;
        Instant deadLine = Instant.parse(deadline);
        uiSet.forEach(ui -> {
            if(fixedUiSet.contains(ui)) {
                ui.access(() -> {
                    // In this simple demo ApplicationLayout is always the root
                    ApplicationLayout applicationLayout = (ApplicationLayout) ui.getChildren().findFirst().get();
                    applicationLayout.announceNewVersion(deadLine);
                });
            } else {
                // This user is not on a critical state of the app, just reload brutally, but first
                // set a cookie, so we can detect these and show a notification from the new server
                // that the UI was migrated
                ui.access(() -> {
                    autoUpgradeUI(ui);
                });

            }

        });

    }

    public void autoUpgradeUI(UI ui) {
        unregisterUI(ui);
        // This goes via websocket still so it works nice
        // If websockets not in use, should fix all for slot and clear fixing after
        // cookie set or some tiny timeout.
        BrowserCookie.setCookie(MIGRATION_TYPE_COOKIE, AUTO);
        // reload page to navigate to new server, use JS call so it happens AFTER
        // setting the cookie...
        UI.getCurrent().getPage().executeJs("window.location.reload()");
    }


    public void selfUpgradeUI(UI ui) {
        unregisterUI(ui);
        // Reset cookie that fixes to slot/application server
        BrowserCookie.setCookie(SLOT_COOKIE, null);
        // Indicate to new host that user triggered upgrade
        BrowserCookie.setCookie(MIGRATION_TYPE_COOKIE, USER);
        // reload page to navigate to new server, use JS call so it happens AFTER
        // setting the cookie...
        ui.getPage().executeJs("window.location.reload()");
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
