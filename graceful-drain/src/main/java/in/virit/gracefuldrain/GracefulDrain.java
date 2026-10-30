package in.virit.gracefuldrain;

import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vaadin.firitin.util.BrowserCookie;

import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Keeps Vaadin users on the old application version during a boot2vm graceful blue-green deployment,
 * until they are in a state where moving to the new version doesn't lose their work.
 * <p>
 * The deploy script splits traffic so that browsers carrying the slot cookie keep reaching the old
 * server, notifies the old server about the new version (see {@link #newVersionAvailable(Instant)}) and
 * polls {@link #getPinnedUiCount()} until it drops to zero or the deadline passes.
 * <p>
 * There is one instance per JVM, as the deployment slot is a property of the process.
 */
public final class GracefulDrain {

    /**
     * Cookie telling the server receiving the user how and from which slot the user was moved,
     * value is {@code <MigrationType>:<slot>}.
     */
    public static final String MIGRATION_COOKIE = "graceful-drain-migration";

    private static final Logger log = LoggerFactory.getLogger(GracefulDrain.class);
    private static final GracefulDrain INSTANCE = new GracefulDrain();

    private volatile String slot = Objects.requireNonNullElse(System.getenv("APP_SLOT"), "local");
    private volatile String slotCookie = "X-Slot";
    private volatile boolean pinAllUis = true;
    private volatile Predicate<UI> autoUpgradePolicy = ui -> false;
    private volatile UpgradeNotifier notifier = new ToastNotifier();

    private volatile Announcement announcement;
    private final Map<UI, UiState> uis = new ConcurrentHashMap<>();
    private final AtomicBoolean pushWarningLogged = new AtomicBoolean();

    private record Announcement(Instant deadline) {
    }

    private static class UiState {
        boolean pinned;
        Announcement handled;
        boolean notifierCalled;
        final Set<NewVersionObserver> notifiedObservers = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private GracefulDrain() {
    }

    public static GracefulDrain get() {
        return INSTANCE;
    }

    public String getSlot() {
        return slot;
    }

    /**
     * @param slot the deployment slot of this process, defaults to the APP_SLOT environment variable
     *             set by boot2vm, or "local" if it is missing
     */
    public void setSlot(String slot) {
        this.slot = Objects.requireNonNull(slot);
    }

    public String getSlotCookie() {
        return slotCookie;
    }

    /**
     * @param slotCookie the cookie the reverse proxy uses to keep users on the old slot, must match
     *                   SLOT_COOKIE in the boot2vm configuration
     */
    public void setSlotCookie(String slotCookie) {
        this.slotCookie = Objects.requireNonNull(slotCookie);
    }

    public boolean isPinAllUis() {
        return pinAllUis;
    }

    /**
     * @param pinAllUis true (the default) pins every UI to this slot when it is created. If false,
     *                  the app pins UIs itself with {@link #pin(UI)} and the rest are moved to the
     *                  new version right away.
     */
    public void setPinAllUis(boolean pinAllUis) {
        this.pinAllUis = pinAllUis;
    }

    /**
     * Decides whether a pinned UI can be moved to the new version without asking the user. Consulted
     * only for views that neither implement {@link AutoUpgradeable} nor have {@link AutoUpgrade}.
     *
     * @param autoUpgradePolicy the policy, by default no UI is upgraded automatically
     */
    public void setAutoUpgradePolicy(Predicate<UI> autoUpgradePolicy) {
        this.autoUpgradePolicy = Objects.requireNonNull(autoUpgradePolicy);
    }

    public UpgradeNotifier getNotifier() {
        return notifier;
    }

    /**
     * @param notifier tells users about a new version, used when no {@link NewVersionObserver} is
     *                 in the active route chain, and greets users arriving from the old version
     */
    public void setNotifier(UpgradeNotifier notifier) {
        this.notifier = Objects.requireNonNull(notifier);
    }

    public boolean isNewVersionAvailable() {
        return announcement != null;
    }

    /**
     * @return when the deploy script will move the remaining users to the new version
     */
    public Optional<Instant> getDeadline() {
        Announcement a = announcement;
        return a == null ? Optional.empty() : Optional.ofNullable(a.deadline());
    }

    /**
     * @return the number of UIs that keep this slot alive, the deploy script waits for this to reach 0
     */
    public int getPinnedUiCount() {
        return (int) uis.values().stream().filter(s -> s.pinned).count();
    }

    /**
     * @return the number of UIs served by this process
     */
    public int getUiCount() {
        return uis.size();
    }

    /**
     * Called by the deploy script (via an endpoint) once a new version is serving new users.
     *
     * @param deadline when remaining users will be moved forcibly, null if not known
     */
    public void newVersionAvailable(Instant deadline) {
        Announcement a = new Announcement(deadline);
        announcement = a;
        log.info("New version available, deadline {}, {} UIs pinned", deadline, getPinnedUiCount());
        uis.keySet().forEach(ui -> ui.access(() -> handleNewVersion(ui)));
    }

    /**
     * Keeps the user on this slot until they upgrade, the UI is closed or the deadline passes.
     * Must be called from the UI thread.
     */
    public void pin(UI ui) {
        UiState state = uis.get(ui);
        if (state == null || state.pinned) {
            return;
        }
        state.pinned = true;
        // Set through the browser, as with websocket push there is no HTTP response to carry the cookie
        BrowserCookie.setCookie(slotCookie, slot);
        // Overridden if the user is upgraded in a controlled manner
        BrowserCookie.setCookie(MIGRATION_COOKIE, MigrationType.FORCED + ":" + slot);
        if (announcement != null) {
            handleNewVersion(ui);
        }
    }

    /**
     * Tells that the UI no longer needs to stay on this slot. If a new version is available, the UI is
     * upgraded right away. Must be called from the UI thread.
     */
    public void release(UI ui) {
        UiState state = uis.get(ui);
        if (state == null) {
            return;
        }
        state.pinned = false;
        if (announcement != null) {
            upgrade(ui, MigrationType.AUTO);
        }
    }

    /**
     * @return true if the UI is kept on this slot
     */
    public boolean isPinned(UI ui) {
        UiState state = uis.get(ui);
        return state != null && state.pinned;
    }

    /**
     * Moves the user to the new version now, on the user's request. Must be called from the UI thread.
     */
    public void upgradeNow(UI ui) {
        upgrade(ui, MigrationType.USER);
    }

    /**
     * Re-evaluates whether the UI can be upgraded automatically, e.g. after the user has saved their
     * work in a view implementing {@link AutoUpgradeable}. Does nothing unless a new version is
     * available. Must be called from the UI thread.
     */
    public void upgradeIfAllowed(UI ui) {
        if (announcement != null && uis.containsKey(ui)) {
            handleNewVersion(ui);
        }
    }

    public boolean isAutoUpgradeAllowed(UI ui) {
        List<HasElement> chain = ui.getActiveRouterTargetsChain();
        if (!chain.isEmpty()) {
            HasElement view = chain.getFirst();
            if (view instanceof AutoUpgradeable autoUpgradeable) {
                return autoUpgradeable.isAutoUpgradeAllowed();
            }
            if (view.getClass().isAnnotationPresent(AutoUpgrade.class)) {
                return true;
            }
        }
        return autoUpgradePolicy.test(ui);
    }

    void uiInit(UI ui) {
        uis.put(ui, new UiState());
        ui.addDetachListener(e -> uis.remove(ui));
        if (!ui.getPushConfiguration().getPushMode().isEnabled() && pushWarningLogged.compareAndSet(false, true)) {
            log.warn("Push is not enabled, users won't be told about new versions until they interact with the UI. Add @Push to your AppShellConfigurator.");
        }
        // Read through the browser, as request cookies may be stale with websocket push. The browser
        // runs the cookie scripts in order, so the value is read before it is cleared or replaced by pin.
        BrowserCookie.detectCookieValue(MIGRATION_COOKIE, value -> greet(ui, value));
        BrowserCookie.deleteCookie(MIGRATION_COOKIE);
        if (pinAllUis) {
            pin(ui);
        }
        // Also handles UIs arriving at this slot after the announcement
        ui.addAfterNavigationListener(e -> {
            if (announcement != null && uis.containsKey(ui)) {
                handleNewVersion(ui);
            }
        });
    }

    private void greet(UI ui, String cookieValue) {
        String[] parts = cookieValue.split(":", 2);
        if (parts.length != 2 || parts[1].equals(slot)) {
            // e.g. a page reload on the same slot
            return;
        }
        try {
            notifier.arrived(ui, MigrationType.valueOf(parts[0]));
        } catch (IllegalArgumentException e) {
            log.debug("Unknown migration cookie value {}", cookieValue);
        }
    }

    private void handleNewVersion(UI ui) {
        UiState state = uis.get(ui);
        Announcement a = announcement;
        if (state == null || a == null) {
            return;
        }
        if (!state.pinned || isAutoUpgradeAllowed(ui)) {
            upgrade(ui, MigrationType.AUTO);
            return;
        }
        if (state.handled != a) {
            // first or updated announcement, tell everybody again
            state.handled = a;
            state.notifierCalled = false;
            state.notifiedObservers.clear();
        }
        NewVersionEvent event = new NewVersionEvent(ui, a.deadline());
        List<NewVersionObserver> observers = ui.getActiveRouterTargetsChain().stream()
                .filter(NewVersionObserver.class::isInstance)
                .map(NewVersionObserver.class::cast)
                .toList();
        if (observers.isEmpty()) {
            if (!state.notifierCalled) {
                state.notifierCalled = true;
                notifier.newVersionAvailable(event);
            }
        } else {
            observers.stream()
                    .filter(state.notifiedObservers::add)
                    .forEach(o -> o.onNewVersion(event));
        }
    }

    private void upgrade(UI ui, MigrationType type) {
        if (uis.remove(ui) == null) {
            return;
        }
        String location = ui.getActiveViewLocation().getPathWithQueryParameters();
        // Executed in this order in the browser: release from the old slot, leave a note for the new
        // version and reload the current view
        BrowserCookie.deleteCookie(slotCookie);
        BrowserCookie.setCookie(MIGRATION_COOKIE, type + ":" + slot);
        ui.getPage().executeJs("window.location.assign(new URL($0, document.baseURI))", location);
    }

    /**
     * Restores the initial state, for tests.
     */
    void reset() {
        uis.clear();
        announcement = null;
        slot = Objects.requireNonNullElse(System.getenv("APP_SLOT"), "local");
        slotCookie = "X-Slot";
        pinAllUis = true;
        autoUpgradePolicy = ui -> false;
        notifier = new ToastNotifier();
    }
}
