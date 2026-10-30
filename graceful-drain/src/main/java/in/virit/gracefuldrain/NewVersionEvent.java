package in.virit.gracefuldrain;

import com.vaadin.flow.component.UI;

import java.time.Instant;
import java.util.Optional;

/**
 * A new application version is available and the user is still on the old one.
 */
public class NewVersionEvent {

    private final UI ui;
    private final Instant deadline;

    NewVersionEvent(UI ui, Instant deadline) {
        this.ui = ui;
        this.deadline = deadline;
    }

    public UI getUI() {
        return ui;
    }

    /**
     * @return when the user will be moved to the new version forcibly, possibly losing unsaved work
     */
    public Optional<Instant> getDeadline() {
        return Optional.ofNullable(deadline);
    }

    /**
     * Moves the user to the new version, e.g. from an "Update now" button.
     */
    public void upgradeNow() {
        GracefulDrain.get().upgradeNow(ui);
    }
}
