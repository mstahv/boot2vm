package in.virit.gracefuldrain;

/**
 * Implemented by a route target or a router layout that wants to tell the user about a new
 * application version itself, e.g. with a badge in the drawer. If any component in the active route
 * chain implements this, the {@link UpgradeNotifier} is not used. Called once per observer instance,
 * also for views the user navigates to after the announcement.
 */
public interface NewVersionObserver {

    void onNewVersion(NewVersionEvent event);
}
