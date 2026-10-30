package in.virit.gracefuldrain;

import com.vaadin.flow.component.UI;

/**
 * Communicates application version changes to users, app wide.
 *
 * @see NewVersionObserver for view specific handling
 * @see ToastNotifier for the default implementation
 */
public interface UpgradeNotifier {

    /**
     * Called in the UI thread of a UI pinned to the old version, when a new version is available.
     */
    void newVersionAvailable(NewVersionEvent event);

    /**
     * Called in the UI thread of a new UI on the new version, when the user arrives from the old one.
     */
    default void arrived(UI ui, MigrationType type) {
    }
}
