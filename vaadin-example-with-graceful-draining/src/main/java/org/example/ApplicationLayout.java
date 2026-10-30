package org.example;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.router.Layout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.vaadin.firitin.appframework.MainLayout;

@Layout
public class ApplicationLayout extends MainLayout {

    private Div currentVersion = new Div("unknown"){{
        getStyle().setFontSize("var(--aura-font-size-xs, 0.75rem)");
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

}
