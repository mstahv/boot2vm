package in.virit.gracefuldrain;

/**
 * How a user was moved from the old application version to the new one.
 */
public enum MigrationType {
    /**
     * The user was in a state where the upgrade could be done without asking.
     */
    AUTO,
    /**
     * The user chose to upgrade.
     */
    USER,
    /**
     * The deadline passed and the old version was shut down, possibly losing unsaved work.
     */
    FORCED
}
