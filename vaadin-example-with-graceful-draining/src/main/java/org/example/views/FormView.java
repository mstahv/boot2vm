package org.example.views;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.Route;
import in.virit.gracefuldrain.AutoUpgradeable;
import in.virit.gracefuldrain.Countdown;
import in.virit.gracefuldrain.GracefulDrain;
import in.virit.gracefuldrain.NewVersionEvent;
import in.virit.gracefuldrain.NewVersionObserver;
import org.vaadin.firitin.appframework.MenuItem;

/**
 * Users are moved to a new version automatically only if they have no unsaved changes. As a
 * NewVersionObserver, the view tells about the new version itself, next to the form, instead of
 * the app wide notifier.
 */
@Route("form")
@MenuItem(title = "Form (auto-upgrade when saved)")
public class FormView extends VerticalLayout implements AutoUpgradeable, NewVersionObserver {

    private boolean unsavedChanges;
    private final Div bannerSlot = new Div();

    public FormView(GracefulDrain gracefulDrain) {
        add(new H1("Form"));
        add(new Paragraph("Type something to have unsaved changes. While you have them, a new version only shows a notice above the form. Save, and you are moved to the new version."));
        add(bannerSlot);
        add(new TextArea("Notes") {{
            setWidthFull();
            setValueChangeMode(ValueChangeMode.LAZY);
            addValueChangeListener(e -> unsavedChanges = true);
        }});
        add(new Button("Save", e -> {
            unsavedChanges = false;
            Notification.show("Saved (not really)").setPosition(Notification.Position.TOP_END);
            gracefulDrain.upgradeIfAllowed(UI.getCurrent());
        }));
    }

    @Override
    public boolean isAutoUpgradeAllowed() {
        return !unsavedChanges;
    }

    @Override
    public void onNewVersion(NewVersionEvent event) {
        bannerSlot.removeAll();
        bannerSlot.add(new NewVersionBanner(event));
    }

    static class NewVersionBanner extends Div {
        NewVersionBanner(NewVersionEvent event) {
            add(new Span("A new version is available. Save your notes to continue with it"));
            event.getDeadline().ifPresent(deadline ->
                    add(new Span(", or you'll be moved in "), new Countdown(deadline)));
            add(new Span("."));
            getStyle().setBackgroundColor("color-mix(in srgb, var(--aura-yellow) 18%, transparent)");
            getStyle().setBorderLeft("4px solid var(--aura-yellow)");
            getStyle().setPadding("var(--vaadin-padding-m)");
            getStyle().setBorderRadius("var(--vaadin-radius-m)");
        }
    }
}
