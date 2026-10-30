package in.virit.gracefuldrain;

/**
 * Implemented by a route target that decides dynamically whether users can be moved to a new
 * application version without asking. Takes precedence over {@link AutoUpgrade}. Call
 * {@link GracefulDrain#upgradeIfAllowed(com.vaadin.flow.component.UI)} when the answer may have changed,
 * e.g. after saving a form.
 */
public interface AutoUpgradeable {

    /**
     * @return true if the user has nothing to lose, e.g. no unsaved changes
     */
    boolean isAutoUpgradeAllowed();
}
