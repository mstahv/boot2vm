# graceful-drain

Graceful blue-green deployments for Vaadin apps deployed with [boot2vm](../README.md#graceful-drain-mode) (`BLUE_GREEN_GRACEFUL=yes`). During a deployment, users keep the application version they started with until:

- they click **Update now**,
- they are in a view where moving them to the new version loses nothing, or
- the deploy deadline passes.

The deploy script waits until no users are left on the old version, so the old version is usually stopped long before the deadline.

## Setup with Spring Boot

```xml
<dependency>
    <groupId>in.virit</groupId>
    <artifactId>graceful-drain</artifactId>
    <version>0.1-SNAPSHOT</version>
</dependency>
```

```properties
management.endpoints.web.exposure.include=health,new-version,active-users
```

Push must be enabled (`@Push` on your `AppShellConfigurator`), so that users can be told about new versions.

The library auto-configures the `new-version` and `active-users` actuator endpoints the deploy script calls, and picks up these optional properties:

| Property | Default | |
|---|---|---|
| `graceful-drain.slot` | `APP_SLOT` env variable, or `local` | The deployment slot of this process |
| `graceful-drain.slot-cookie` | `X-Slot` | Must match `SLOT_COOKIE` in `vmhosting.conf` |
| `graceful-drain.pin-all-uis` | `true` | Pin every UI on creation. If `false`, pin critical UIs with `GracefulDrain.get().pin(ui)`, the rest are moved to the new version right away |

Without Spring, the library hooks into Vaadin automatically (ServiceLoader). Configure it through `GracefulDrain.get()`, and call `newVersionAvailable(deadline)` and `getPinnedUiCount()` from your own endpoints (see `NewVersionEndpoint`/`ActiveUsersEndpoint` for the protocol).

## Deciding where users are moved automatically

By default every user is kept on the old version until they choose to update. Mark views where moving is safe:

```java
@Route("dashboard")
@AutoUpgrade
public class DashboardView extends VerticalLayout { }
```

Users in such views are moved right away. Users navigating to one while a new version is available are moved too.

When safety depends on state, implement `AutoUpgradeable`. It takes precedence over the annotation. Call `upgradeIfAllowed` when the state changes:

```java
public class OrderView extends VerticalLayout implements AutoUpgradeable {
    public boolean isAutoUpgradeAllowed() {
        return !binder.hasChanges();
    }

    void save() {
        // ...
        GracefulDrain.get().upgradeIfAllowed(UI.getCurrent());
    }
}
```

For views with neither, an app-wide rule can be set with `GracefulDrain.get().setAutoUpgradePolicy(ui -> ...)`.

## Customizing how users are told

There are two levels, the more specific wins:

1. **Per view or layout:** implement `NewVersionObserver` in a route target or router layout, e.g. to show a badge in the drawer. If any component in the active route chain implements it, the app-wide notifier is not used for the new-version announcement.
2. **App wide:** provide an `UpgradeNotifier`, as a Spring bean or with `GracefulDrain.get().setNotifier(...)`. It also greets users arriving at the new version (`arrived(UI, MigrationType)`). The default `ToastNotifier` shows a notification with a countdown. Extend it to change texts or position.

The `Countdown` component shows the remaining time and ticks in the browser.

## How it works

- Every pinned UI sets the slot cookie through the browser. With websocket push there is no HTTP response to carry it. The reverse proxy routes browsers carrying the cookie to the old slot.
- A UI is counted as active from its creation until it is detached, upgraded or released.
- On an upgrade, the slot cookie is cleared, a migration cookie (`<AUTO|USER|FORCED>:<old slot>`) is set and the current view is reloaded. Requests then go to the new version, which reads the migration cookie to greet the user.
- Pinned UIs hold a `FORCED` migration cookie. If the old version is stopped under them, the new version can tell the user why the page was reset.

Note that the slot cookie is shared by all tabs of a browser. Upgrading one tab releases the cookie for the other tabs too. A pinned tab keeps working through its websocket connection, but if it reconnects, it lands on the new version.
