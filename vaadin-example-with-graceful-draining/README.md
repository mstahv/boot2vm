# Vaadin app example with graceful upgrades

Vaadin apps are stateful by design. This example showcases the graceful blue-green deployment feature of boot2vm: users keep using the version they started with until they choose to update, reach a view where moving them doesn't lose anything, or the deploy deadline passes.

The heavy lifting is done by the [graceful-drain](../graceful-drain) library, which provides the actuator endpoints the deploy script calls. Install it locally first (`mvn install` in `../graceful-drain`), as it is not yet released. It currently also needs viritin 3.9.2-SNAPSHOT (cookie fixes) in your local Maven repository.

- `Application` registers `DrawerBadgeNotifier` as the `UpgradeNotifier` bean, so users are told about new versions with a badge in the drawer instead of the default notification.
- `InfoView` is annotated with `@AutoUpgrade`: users there are moved to the new version without asking.
- `FormView` implements `AutoUpgradeable`: users are moved automatically only when they have no unsaved changes. As a `NewVersionObserver`, it shows its own notice above the form.
- `MainView` shows the slot and pinned UI counts, lets you release/pin the UI, switch between the notifiers and simulate a new version without deploying.
- `UpgradeRulesTest` checks these rules with browserless tests, simulating a new version with `GracefulDrain.simulateNewVersion`.

The management endpoints are exposed in `application.properties`. Deploy with `BLUE_GREEN=yes`, `BLUE_GREEN_GRACEFUL=yes` and a separate `MANAGEMENT_PORT` in `vmhosting.conf`, so the endpoints are not reachable through the public proxy.
