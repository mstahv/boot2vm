# Integration tests

These tests run `Deploy.java` for real, against Docker containers that act as servers.

- **vm** (`images/vm`): Ubuntu 24.04 with systemd as PID 1 and sshd, so the setup script installs the JDK, Caddy and ufw and starts services like on a fresh cloud VM. It runs privileged, because systemd and ufw expect a whole machine.
- **workstation** (`images/workstation`): the developer's machine, with JBang, ssh, rsync and Maven. `Deploy.java` from the checkout is copied to `/work`, and the test apps from `apps/` are copied next to it. Deploy runs here rather than on your own machine, so the tests leave your `~/.ssh` alone and also work on macOS, where the host can't reach container IPs.

Both containers share a Docker network, where the server is reachable by its name (for example `vm1`).

The terminal UI is driven by `images/workstation/Drive.java`, a small expect-like JBang script that gets a terminal for Deploy from `script`. `Prompts` lists the questions in order, each with an answer or with the default it should suggest. A question that is added, removed or reordered makes the test stop at that prompt with the output so far, instead of feeding answers into the wrong questions.

## Running

```bash
cd integration-tests
mvn verify
mvn verify -Dit.test=PlainAppIT    # one class
```

Requirements: Docker that can run privileged containers, and Java 21+ with Maven. The first run builds the images and fills the Maven and JBang caches (Docker volumes `boot2vm-it-m2`, `boot2vm-it-jbang`, and `boot2vm-it-vaadin` and `boot2vm-it-npm` for Vaadin's frontend build). After that, `PlainAppIT` takes about 1.5 minutes, most of it in apt on the server.

The browser tests run Playwright in the test JVM, which downloads Chromium on the first run. Deploy serves the init form in the workstation container on port 8888 (`--gui`, with `SERVER_ADDRESS` and `SERVER_PORT` set), mapped to a free port on your machine.

To watch what the browser tests do, record them, slowed down:

```bash
mvn verify -Dit.test=InitFormIT -Dit.video=target/videos -Dit.slowMo=400
```

Each page becomes a `.webm` in `target/videos` (plays in Chrome, Firefox and VLC).

`GracefulDrainIT` records the whole story of a graceful blue-green deployment, for a demo video:

```bash
mvn verify -Dit.test=GracefulDrainIT -Dit.video=target/videos -Dit.slowMo=300
```

- `1-init.webm`: the init form, with blue-green and graceful drain on, and the setup until the app runs
- `2-deploy-window.webm`: the deploy window (`deploy --gui`), waiting for the user who stays
- `3-user-main.webm`: a user in a view that keeps them on their version: told about the new version, clicks *Update now!*
- `4-user-info.webm`: a user in an `@AutoUpgrade` view, moved to the new version right away
- `5-deploy-window.webm` and `6-user-moved.webm`: the next deploy, where the developer clicks *Move them now* for a user who doesn't update

`timeline.txt` has the wall-clock milliseconds of when each recording started and of the main events, for lining them up.

`promo/Promo.java` cuts them into one demo video with title cards and captions, the waits sped up and the deploy window next to the users' windows, in sync (ffmpeg runs in Docker):

```bash
jbang promo/Promo.java    # target/promo/boot2vm-graceful-deploy.mp4
```

The cut points of the init part are tuned for the recordings at `-Dit.slowMo=300`; the deploy parts follow `timeline.txt`.

Each command run in a container is echoed to the test output, see `target/failsafe-reports/*.xml`.

## Scenarios

Done:

- `PlainAppIT`: init a fresh server with the terminal UI (checking the suggested defaults), serve a plain jar through Caddy with an env var from init, redeploy a changed version.
- `InitFormIT`: the same with the init form, filled in with Playwright, following the setup in the window to the link to the app; a host that doesn't resolve, reported in the window with the ssh error; and a new target that inherits Raspberry Pi settings from the first: the presets show them, unticking and *Clear* remove them, and *Cancel* writes nothing.
- `GracefulDrainIT`: the Vaadin example in `../vaadin-example-with-graceful-draining` with the graceful-drain library built from `../graceful-drain`. Init with the form (blue-green, graceful drain), then a deploy followed in the deploy window while two users are in the app: the one in an `@AutoUpgrade` view moves to the new version at once, the deploy waits for the other one until they click *Update now!*. A third deploy moves a user who stays with the window's *Move them now* button. The example needs viritin 3.9.2-SNAPSHOT, which is copied from your `~/.m2` until it is released.

TODO:

- Two servers: `init @a`, `init @b --from @a`, `Deploy` to both, clearing inherited hardware settings
- `env set/remove`, `add-key`, `logs`, `clean`
- Headless service: no Caddy, nothing on port 80
- Blue-green: poll during a deploy, no failed requests
- Graceful drain: the forced cutover at the deadline, the D key in the terminal, and the form view that upgrades when saved
- `HTTPS=internal` and `root-cert`: curl with `--cacert`
- Zulu and Semeru, and a non-root admin user with sudo
- A "warm" vm image with the JDK and Caddy preinstalled, for faster scenarios
- Running the suite in GitHub Actions (PR: the quick scenarios, nightly: the rest)

Can't be tested with containers: reboots (unattended-upgrades), Raspberry Pi hardware groups, real Let's Encrypt certificates (a local ACME server such as Pebble could stand in), and kernel-level differences.
