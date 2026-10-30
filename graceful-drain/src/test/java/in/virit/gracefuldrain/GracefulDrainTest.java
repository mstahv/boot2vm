package in.virit.gracefuldrain;

import com.vaadin.browserless.BrowserlessTest;
import com.vaadin.browserless.ViewPackages;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ViewPackages(classes = GracefulDrainTest.PlainView.class)
class GracefulDrainTest extends BrowserlessTest {

    @Route("plain")
    public static class PlainView extends Div {
    }

    @Route("auto")
    @AutoUpgrade
    public static class AutoView extends Div {
    }

    @Route("form")
    public static class FormView extends Div implements AutoUpgradeable {
        boolean saved;

        @Override
        public boolean isAutoUpgradeAllowed() {
            return saved;
        }
    }

    @Route("observing")
    public static class ObservingView extends Div implements NewVersionObserver {
        final List<NewVersionEvent> events = new ArrayList<>();

        @Override
        public void onNewVersion(NewVersionEvent event) {
            events.add(event);
        }
    }

    static class RecordingNotifier implements UpgradeNotifier {
        final List<NewVersionEvent> events = new ArrayList<>();

        @Override
        public void newVersionAvailable(NewVersionEvent event) {
            events.add(event);
        }
    }

    private final GracefulDrain drain = GracefulDrain.get();
    private final RecordingNotifier notifier = new RecordingNotifier();

    @AfterEach
    void reset() {
        drain.reset();
    }

    private void announce() {
        drain.setNotifier(notifier);
        drain.newVersionAvailable(Instant.now().plusSeconds(300));
        runAccessTasks();
    }

    private void runAccessTasks() {
        VaadinSession session = VaadinSession.getCurrent();
        session.getService().runPendingAccessTasks(session);
    }

    @Test
    void uiIsPinnedOnCreation() {
        assertEquals(1, drain.getUiCount());
        assertEquals(1, drain.getPinnedUiCount());
        assertTrue(drain.isPinned(UI.getCurrent()));
    }

    @Test
    void pinnedUserIsNotified() {
        navigate(PlainView.class);
        announce();
        assertEquals(1, notifier.events.size());
        assertTrue(notifier.events.getFirst().getDeadline().isPresent());
        assertEquals(1, drain.getPinnedUiCount());

        // the notifier is not called again on navigation
        navigate(PlainView.class);
        assertEquals(1, notifier.events.size());
    }

    @Test
    void autoUpgradeViewIsUpgraded() {
        navigate(AutoView.class);
        announce();
        assertTrue(notifier.events.isEmpty());
        assertEquals(0, drain.getPinnedUiCount());
    }

    @Test
    void enteringAutoUpgradeViewUpgrades() {
        navigate(PlainView.class);
        announce();
        assertEquals(1, drain.getPinnedUiCount());
        navigate(AutoView.class);
        assertEquals(0, drain.getPinnedUiCount());
    }

    @Test
    void autoUpgradeableDecidesDynamically() {
        FormView form = navigate(FormView.class);
        announce();
        assertEquals(1, drain.getPinnedUiCount());
        form.saved = true;
        drain.upgradeIfAllowed(UI.getCurrent());
        assertEquals(0, drain.getPinnedUiCount());
    }

    @Test
    void globalPolicy() {
        drain.setAutoUpgradePolicy(ui -> true);
        navigate(PlainView.class);
        announce();
        assertEquals(0, drain.getPinnedUiCount());
    }

    @Test
    void observerReplacesNotifier() {
        ObservingView view = navigate(ObservingView.class);
        announce();
        assertEquals(1, view.events.size());
        assertTrue(notifier.events.isEmpty());

        // an updated announcement is delivered again
        drain.newVersionAvailable(Instant.now().plusSeconds(60));
        runAccessTasks();
        assertEquals(2, view.events.size());
    }

    @Test
    void unpinnedUiIsUpgradedRightAway() {
        navigate(PlainView.class);
        drain.release(UI.getCurrent());
        assertEquals(0, drain.getPinnedUiCount());
        assertEquals(1, drain.getUiCount());
        announce();
        assertEquals(0, drain.getUiCount());
        assertTrue(notifier.events.isEmpty());
    }

    @Test
    void releaseAfterAnnouncementUpgrades() {
        navigate(PlainView.class);
        announce();
        drain.release(UI.getCurrent());
        assertEquals(0, drain.getUiCount());
    }

    @Test
    void upgradeNow() {
        navigate(PlainView.class);
        announce();
        notifier.events.getFirst().upgradeNow();
        assertEquals(0, drain.getUiCount());
        assertFalse(drain.isPinned(UI.getCurrent()));
        String js = UI.getCurrent().getInternals().dumpPendingJavaScriptInvocations().stream()
                .map(i -> i.getInvocation().getExpression())
                .filter(e -> e.contains("location.assign"))
                .findFirst().orElseThrow();
        assertTrue(js.contains("location.assign"));
    }
}
