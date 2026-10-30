package boot2vm.it;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.Network;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Vaadin example with graceful blue-green deployments: set the server up with
 * the init form, then deploy a new version while two users are using the app. The
 * one in a view marked for automatic upgrades is moved right away, the other one
 * is told about the new version and moves when clicking "Update now!".
 * <p>
 * With -Dit.video=dir every step is recorded: the init form, the deploy in the
 * terminal (replayed in xterm.js) and both users' browsers, with a timeline.txt
 * of when each recording started, for putting them together.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GracefulDrainIT {

    static final Path VIDEO_DIR = System.getProperty("it.video") == null ? null : Path.of(System.getProperty("it.video"));

    static Network network;
    static Vm vm;
    static Workstation workstation;
    static String app;
    static Playwright playwright;
    static Browser browser;

    @BeforeAll
    static void start() {
        network = Network.newNetwork();
        vm = new Vm(network, "vm1");
        workstation = new Workstation(network).withInitForm();
        vm.start();
        workstation.start();
        vm.authorize(workstation.publicKey());
        // Neither is released yet: viritin from this machine's Maven repository, the library from source
        workstation.copyFromLocalRepository("in/virit/viritin/3.9.2-SNAPSHOT");
        assertEquals(0, workstation.sh(workstation.copyProject("graceful-drain"),
                "mvn -q -B install -Dmaven.test.skip=true").exitCode(), "could not install graceful-drain");
        app = workstation.copyProject("vaadin-example-with-graceful-draining");
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setSlowMo(Double.parseDouble(System.getProperty("it.slowMo", "0")))
                .setArgs(List.of("--host-resolver-rules=MAP vm1:80 " + vm.getHost() + ":" + vm.getMappedPort(80))));
        if (VIDEO_DIR != null) {
            try {
                Files.createDirectories(VIDEO_DIR);
                Files.deleteIfExists(VIDEO_DIR.resolve("timeline.txt"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @AfterAll
    static void stop() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (workstation != null) workstation.stop();
        if (vm != null) vm.stop();
        if (network != null) network.close();
    }

    @Test
    @Order(1)
    void initSetsUpGracefulBlueGreen() throws Exception {
        var window = new Window("1-init");
        var page = window.page;
        var init = workstation.initWithForm(app);
        openForm(page, init);

        field(page, "Target name").fill("vm1");
        field(page, "Host").fill("vm1");
        field(page, "App user").fill("demo");
        pause(page);
        checkbox(page, "Blue-green deployment").check();
        checkbox(page, "Graceful drain").check();
        field(page, "Drain timeout (seconds)").fill("180");
        pause(page);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Initialize server")).click();

        var outcome = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions()
                .setName(Pattern.compile("is ready|Setup failed")));
        assertThat(outcome).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(Duration.ofMinutes(20).toMillis()));
        assertEquals("@vm1 is ready", outcome.textContent(), () -> page.locator("pre").textContent());
        pause(page);
        window.close();

        var result = init.get(1, TimeUnit.MINUTES);
        assertEquals(0, result.exitCode(), "init failed");
        String conf = workstation.sh(app, "cat vmhosting.vm1.conf").output();
        assertTrue(conf.contains("BLUE_GREEN_GRACEFUL=yes\n"), conf);
    }

    @Test
    @Order(2)
    void usersMoveToTheNewVersionAsTheirViewsAllow() throws Exception {
        // One user works in a view that keeps them on their version until they choose to update...
        var pinned = new Window("3-user-main");
        pinned.page.navigate("http://vm1/");
        var slot = pinned.page.getByText(Pattern.compile("^Current slot: "));
        assertThat(slot).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(60_000));
        String oldSlot = slot.textContent();
        // ...the other one in a view marked with @AutoUpgrade
        var auto = new Window("4-user-info");
        auto.page.navigate("http://vm1/info");
        assertThat(auto.page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Info").setExact(true))).isVisible();
        pause(auto.page);

        workstation.sh(app, "sed -i 's/\"Graceful Blue-Green Demo\"/\"Graceful Blue-Green Demo, version 2\"/'"
                + " src/main/java/org/example/ApplicationLayout.java");
        timeline("2-deploy-terminal");
        var deploy = workstation.recordTerminal(app, "jbang /work/Deploy.java");

        assertThat(auto.page.getByText("You were automatically moved to a new version"))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(Duration.ofMinutes(15).toMillis()));
        timeline("auto-upgraded");
        assertThat(auto.page.getByText("Graceful Blue-Green Demo, version 2")).isVisible();

        assertThat(pinned.page.getByText("New version available!")).isVisible();
        timeline("new version announced");
        assertThat(pinned.page.getByText("Graceful Blue-Green Demo, version 2")).not().isVisible();
        // Let the user finish what they were doing, then update
        pinned.page.waitForTimeout(VIDEO_DIR != null ? 8000 : 1000);
        pinned.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Update now!")).click();
        timeline("update clicked");
        assertThat(pinned.page.getByText("Welcome to the new version!")).isVisible();
        assertThat(pinned.page.getByText("Graceful Blue-Green Demo, version 2")).isVisible();
        assertThat(slot).not().hasText(oldSlot);

        // The automatically moved user looks around in the new version
        auto.page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Main (pinned)")).click();
        assertThat(auto.page.getByText(Pattern.compile("^Current slot: "))).not().hasText(oldSlot);

        var recording = deploy.get(5, TimeUnit.MINUTES);
        timeline("deploy done");
        assertEquals(0, recording.exitCode(), "deploy failed");
        assertTrue(recording.output().contains("All users drained"), "the deploy must wait for the users to move");
        assertTrue(recording.output().contains("Graceful blue-green deploy complete"));
        pause(pinned.page);
        pinned.close();
        auto.close();

        if (VIDEO_DIR != null) {
            // Not real time: the deploy is replayed now, its real start is in the timeline
            var terminal = new Window("2-deploy-terminal", 1280, 720, false);
            recording.play(terminal.page, "\u001B[1;32mdeveloper@laptop\u001B[0m:\u001B[1;34m~/demo\u001B[0m$ ",
                    "jbang Deploy.java");
            terminal.close();
        }
    }

    /** A browser window of its own (no shared cookies), recorded as name.webm with -Dit.video. */
    static class Window {
        final String name;
        final BrowserContext context;
        final Page page;

        Window(String name) {
            this(name, 1280, 800, true);
        }

        Window(String name, int width, int height, boolean inTimeline) {
            this.name = name;
            var options = new Browser.NewContextOptions().setViewportSize(width, height);
            if (VIDEO_DIR != null) options.setRecordVideoDir(VIDEO_DIR.resolve("raw")).setRecordVideoSize(width, height);
            context = browser.newContext(options);
            page = context.newPage();
            if (inTimeline) timeline(name);
        }

        void close() {
            page.close();
            if (VIDEO_DIR != null) {
                page.video().saveAs(VIDEO_DIR.resolve(name + ".webm"));
                page.video().delete();
            }
            context.close();
        }
    }

    /** Note when something happened, for lining the recordings up. */
    static void timeline(String event) {
        if (VIDEO_DIR == null) return;
        try {
            Files.writeString(VIDEO_DIR.resolve("timeline.txt"), System.currentTimeMillis() + " " + event + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void pause(Page page) {
        if (VIDEO_DIR != null) page.waitForTimeout(1500);
    }

    static Locator field(Page page, String label) {
        return page.getByLabel(label, new Page.GetByLabelOptions().setExact(true));
    }

    static Locator checkbox(Page page, String label) {
        return page.getByRole(AriaRole.CHECKBOX, new Page.GetByRoleOptions().setName(label).setExact(true));
    }

    static void openForm(Page page, CompletableFuture<Workstation.Result> init) {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        while (true) {
            if (init.isDone()) {
                throw new AssertionError("Deploy exited before serving the form: " + init.join().output());
            }
            try {
                page.navigate(workstation.formUrl());
                page.waitForSelector("vaadin-button");
                return;
            } catch (PlaywrightException e) {
                if (System.currentTimeMillis() > deadline) throw e;
                Vm.sleep(1000);
            }
        }
    }
}
