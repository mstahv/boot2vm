package org.example;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.theme.aura.Aura;

/**
 * Aura with colors and a font of its own (styles.css), so that the app is easy to tell apart
 * from boot2vm's own windows. The graceful-drain library works with Lumo too.
 */
@Push
@StyleSheet(Aura.STYLESHEET)
@StyleSheet("styles.css")
public class AppShell implements AppShellConfigurator {

}
