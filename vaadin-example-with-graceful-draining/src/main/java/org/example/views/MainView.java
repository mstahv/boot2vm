package org.example.views;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Emphasis;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.router.Route;
import in.virit.color.NamedColor;
import in.virit.gracefuldrain.DrawerBadgeNotifier;
import in.virit.gracefuldrain.GracefulDrain;
import in.virit.gracefuldrain.ToastNotifier;
import in.virit.gracefuldrain.UpgradeNotifier;
import org.vaadin.firitin.appframework.MenuItem;
import org.vaadin.firitin.util.style.LumoProps;

import java.time.Instant;
import java.util.function.Supplier;

@Route
@MenuItem(title = "Main (pinned)", order = MenuItem.BEGINNING)
public class MainView extends VerticalLayout {

    private final GracefulDrain gracefulDrain;
    private AppStats appStats = new AppStats();

    public MainView(GracefulDrain gracefulDrain) {
        this.gracefulDrain = gracefulDrain;
        add(new H1("GracefulBlueGreen demo"));

        String slot = gracefulDrain.getSlot();
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

        add(new Emphasis("All views keep users on the version they started with, until they choose to update or the deploy deadline passes. The Info view is marked with @AutoUpgrade, and the Form view upgrades automatically when it has no unsaved changes."));

        add(new PinToggle());

        add(new H3("Try it without a deployment"));
        add(new HorizontalLayout(new NotifierSelect(), new SimulateButton()) {{
            setAlignItems(Alignment.BASELINE);
        }});

        add(appStats);
        appStats.update();

        add(new H3("Dummy UI"));

        add(new Button("Click me", event -> {
            add(new Paragraph("Clicked!"));
            appStats.update();
        }));
    }

    /**
     * For demo/test purposes only, real apps rather use @AutoUpgrade or AutoUpgradeable on views.
     */
    class PinToggle extends Button {
        PinToggle() {
            updateText();
            addClickListener(e -> {
                UI ui = UI.getCurrent();
                if (gracefulDrain.isPinned(ui)) {
                    gracefulDrain.release(ui);
                } else {
                    gracefulDrain.pin(ui);
                }
                updateText();
                appStats.update();
            });
        }

        private void updateText() {
            setText(gracefulDrain.isPinned(UI.getCurrent()) ? "Release me from this slot" : "Pin me to this slot");
        }
    }

    enum NotifierStyle {
        DRAWER("Badge in the drawer", DrawerBadgeNotifier::new),
        TOAST("Notification", ToastNotifier::new);

        final String caption;
        final Supplier<UpgradeNotifier> factory;

        NotifierStyle(String caption, Supplier<UpgradeNotifier> factory) {
            this.caption = caption;
            this.factory = factory;
        }
    }

    /**
     * Switches the notifier app wide, for demo purposes.
     */
    class NotifierSelect extends Select<NotifierStyle> {
        NotifierSelect() {
            setLabel("How users are told (for all users)");
            setWidth("18em");
            setItems(NotifierStyle.values());
            setItemLabelGenerator(style -> style.caption);
            setValue(gracefulDrain.getNotifier() instanceof DrawerBadgeNotifier ? NotifierStyle.DRAWER : NotifierStyle.TOAST);
            addValueChangeListener(e -> gracefulDrain.setNotifier(e.getValue().factory.get()));
        }
    }

    /**
     * Shows what this user would see if a new version was deployed now. Without the boot2vm proxy,
     * "Update now" just reloads the page.
     */
    class SimulateButton extends Button {
        SimulateButton() {
            super("Simulate new version", e -> gracefulDrain.simulateNewVersion(UI.getCurrent(), Instant.now().plusSeconds(300)));
        }
    }

    class AppStats extends Div {

        public void update() {
            removeAll();
            add(new H3("Current app stats"));
            add(new Paragraph("UIs pinned to this version (~ app server): " + gracefulDrain.getPinnedUiCount()));
            add(new Paragraph("UIs total on this app server: " + gracefulDrain.getUiCount()));
        }
    }
}
