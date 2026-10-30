package org.example;

import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Emphasis;
import com.vaadin.flow.component.html.H5;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.router.Layout;
import in.virit.gracefuldrain.Countdown;
import in.virit.gracefuldrain.NewVersionEvent;
import in.virit.gracefuldrain.NewVersionObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.vaadin.firitin.appframework.MainLayout;
import org.vaadin.firitin.util.style.LumoProps;

/**
 * As the layout implements NewVersionObserver, it replaces the default notification of the
 * graceful-drain library with a badge in the drawer. Users arriving at the new version are still
 * greeted by the default notifier.
 */
@Layout
public class ApplicationLayout extends MainLayout implements NewVersionObserver {

    private Div currentVersion = new Div("unknown"){{
        getStyle().setFontSize(LumoProps.FONT_SIZE_XS.var());
        getStyle().setTextAlign(Style.TextAlign.CENTER);
    }};

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
    }

    @Override
    public void onNewVersion(NewVersionEvent event) {
        addToDrawer(new NewVersionBadge(event));
        Notification.show("There is a new application version available, upgrade as soon as possible!")
                .setPosition(Notification.Position.TOP_END);
    }

    public static class NewVersionBadge extends VerticalLayout {
        public NewVersionBadge(NewVersionEvent event) {
            add(new H5("New version available!"));
            event.getDeadline().ifPresent(deadline -> add(new Emphasis(
                    new Text("Save your work, you'll be moved to the new version in "),
                    new Countdown(deadline)){{
                getStyle().setFontSize(LumoProps.FONT_SIZE_XS.var());
            }}));
            add(new Button("Update now!", e -> event.upgradeNow()));

            setAlignItems(Alignment.CENTER);
            setSpacing(false);
            getStyle().setBackgroundColor(LumoProps.ERROR_COLOR_10PCT.var());
            getStyle().setTextAlign(Style.TextAlign.CENTER);
        }
    }

}
