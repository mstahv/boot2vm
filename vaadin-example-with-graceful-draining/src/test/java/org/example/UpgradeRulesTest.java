package org.example;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.ViewPackages;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.textfield.TextArea;
import in.virit.gracefuldrain.GracefulDrain;
import org.example.views.FormView;
import org.example.views.InfoView;
import org.example.views.MainView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the app's own upgrade rules: which views let users move to a new version without asking.
 * The deployment itself is simulated for the current UI only, with GracefulDrain.simulateNewVersion.
 */
@SpringBootTest
@ViewPackages(packages = "org.example")
class UpgradeRulesTest extends SpringBrowserlessTest {

    @Autowired
    GracefulDrain gracefulDrain;

    private void simulateNewVersion() {
        gracefulDrain.simulateNewVersion(UI.getCurrent(), Instant.now().plusSeconds(300));
    }

    private boolean stillOnOldVersion() {
        return gracefulDrain.isPinned(UI.getCurrent());
    }

    @Test
    void unsavedChangesKeepUserOnOldVersionUntilSaved() {
        navigate(FormView.class);
        test(find(TextArea.class).single()).setValue("work in progress");

        simulateNewVersion();
        assertTrue(stillOnOldVersion(), "Unsaved work must not be lost");

        test(find(Button.class).withText("Save").single()).click();
        assertFalse(stillOnOldVersion(), "Saving should move the user to the new version");
    }

    @Test
    void formWithoutChangesIsUpgradedRightAway() {
        navigate(FormView.class);
        simulateNewVersion();
        assertFalse(stillOnOldVersion());
    }

    @Test
    void infoViewIsUpgradedRightAway() {
        navigate(InfoView.class);
        simulateNewVersion();
        assertFalse(stillOnOldVersion());
    }

    @Test
    void mainViewUserIsToldAboutNewVersion() {
        navigate(MainView.class);
        simulateNewVersion();
        assertTrue(stillOnOldVersion());
        find(Button.class).withText("Update now").single();
    }
}
