package org.example.views;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.Route;
import in.virit.gracefuldrain.AutoUpgradeable;
import in.virit.gracefuldrain.GracefulDrain;
import org.vaadin.firitin.appframework.MenuItem;

/**
 * Users are moved to a new version automatically only if they have no unsaved changes.
 */
@Route("form")
@MenuItem(title = "Form (auto-upgrade when saved)")
public class FormView extends VerticalLayout implements AutoUpgradeable {

    private boolean unsavedChanges;

    public FormView(GracefulDrain gracefulDrain) {
        add(new H1("Form"));
        add(new Paragraph("Type something to have unsaved changes. While you have them, a new version only shows a notification. Save, and you are moved to the new version."));
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
}
