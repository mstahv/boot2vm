package org.example.views;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import in.virit.gracefuldrain.AutoUpgrade;
import org.vaadin.firitin.appframework.MenuItem;

/**
 * A view without any state worth keeping: users here are moved to a new version without asking,
 * also when they navigate here while a new version is available.
 */
@Route("info")
@AutoUpgrade
@MenuItem(title = "Info (auto-upgrade)")
public class InfoView extends VerticalLayout {

    public InfoView() {
        add(new H1("Info"));
        add(new Paragraph("This view is annotated with @AutoUpgrade. If a new version is deployed while you are here, the page reloads to the new version right away, and the new version greets you with a notification."));
    }
}
