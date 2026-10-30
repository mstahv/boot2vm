package boot2vm.it;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.Network;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Init with the form in the browser: fill it in for a fresh server, then check
 * that settings inherited from another target are shown and can be cleared.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InitFormIT {

    static final Path VIDEO_DIR = System.getProperty("it.video") == null ? null : Path.of(System.getProperty("it.video"));

    static Network network;
    static Vm vm;
    static Workstation workstation;
    static String app;
    static Playwright playwright;
    static Browser browser;
    BrowserContext context;
    Page page;
    String testName;
    int videoNumber;

    @BeforeAll
    static void start() {
        network = Network.newNetwork();
        vm = new Vm(network, "vm1");
        workstation = new Workstation(network).withInitForm();
        vm.start();
        workstation.start();
        vm.authorize(workstation.publicKey());
        app = workstation.copyApp("hello-plain");
        playwright = Playwright.create();
        // -Dit.slowMo=500 slows each browser action down, to follow along in a video
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setSlowMo(Double.parseDouble(System.getProperty("it.slowMo", "0")))
                // Open http://vm1/ like a user would: Caddy picks the site by the host name
                .setArgs(List.of("--host-resolver-rules=MAP vm1:80 " + vm.getHost() + ":" + vm.getMappedPort(80))));
    }

    @AfterAll
    static void stop() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (workstation != null) workstation.stop();
        if (vm != null) vm.stop();
        if (network != null) network.close();
    }

    @BeforeEach
    void openPage(TestInfo test) {
        testName = test.getTestMethod().orElseThrow().getName();
        videoNumber = 0;
        var options = new Browser.NewContextOptions().setViewportSize(1280, 900);
        // -Dit.video=target/videos records each test as a video
        if (VIDEO_DIR != null) options.setRecordVideoDir(VIDEO_DIR).setRecordVideoSize(1280, 900);
        context = browser.newContext(options);
        page = context.newPage();
    }

    @AfterEach
    void closePage(TestInfo test) {
        closeAndSaveVideo(page);
        context.close();
        // A failed test may leave its Deploy serving the form
        workstation.sh("/work", "pkill -f '[D]eploy.java' || true");
    }

    @Test
    @Order(1)
    void formSetsUpServerAndDeploys() throws Exception {
        var init = workstation.initWithForm(app);
        openForm(init);

        // A fresh project asks for the target's name
        field("Target name").fill("vm1");
        field("Host").fill("vm1");
        field("App user").fill("hello");
        field("Environment variables").fill("GREETING=browser");
        pause();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Initialize server")).click();
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Setting up vm1"))).isVisible();
        pause();
        // The setup continues in the terminal, nothing more to see on the form
        closeAndSaveVideo(page);

        var result = init.get(10, TimeUnit.MINUTES);
        assertEquals(0, result.exitCode(), "init failed");
        assertTrue(result.output().contains("Deployed successfully to @vm1"));
        workstation.awaitHttp("http://vm1/", "v1 browser"::equals, Duration.ofSeconds(30));
        page = context.newPage();
        page.navigate("http://vm1/");
        assertThat(page.locator("body")).hasText("v1 browser");
        pause();
        String conf = workstation.sh(app, "cat vmhosting.vm1.conf").output();
        // Left blank in the form: derived from the host
        assertTrue(conf.contains("DOMAIN=vm1\n"), conf);
        assertTrue(conf.contains("HTTPS=no\n"), conf);
    }

    @Test
    @Order(2)
    void inheritedHardwareSettingsCanBeCleared() throws Exception {
        workstation.sh(app, "sed -i"
                + " -e 's/^JVM_OPTS=.*/JVM_OPTS=--enable-native-access=ALL-UNNAMED -XX:+ExitOnOutOfMemoryError/'"
                + " -e 's/^USER_GROUPS=.*/USER_GROUPS=gpio,i2c,spi,dialout,bluetooth,video,render,plugdev,input,audio/'"
                + " vmhosting.vm1.conf");
        var init = workstation.initWithForm(app, "@vm2");
        openForm(init);

        assertThat(page.getByText("@vm2 (vmhosting.vm2.conf, new)")).isVisible();
        var jvmOpts = field("Extra JVM options");
        var groups = field("Extra groups for the app user");
        var piPreset = page.getByRole(AriaRole.CHECKBOX, new Page.GetByRoleOptions()
                .setName(Pattern.compile("^Raspberry Pi, everything")));
        var serialPreset = page.getByRole(AriaRole.CHECKBOX, new Page.GetByRoleOptions()
                .setName(Pattern.compile("^Serial ports")));
        assertThat(jvmOpts).hasValue("--enable-native-access=ALL-UNNAMED -XX:+ExitOnOutOfMemoryError");
        assertThat(piPreset).isChecked();
        assertThat(serialPreset).isChecked();
        // Another server's host is never suggested
        assertThat(field("Host")).hasValue("");
        piPreset.scrollIntoViewIfNeeded();
        pause();

        // Unticking one preset removes its group, and the preset that includes it unticks too
        serialPreset.uncheck();
        assertThat(groups).hasValue("gpio,i2c,spi,bluetooth,video,render,plugdev,input,audio");
        assertThat(piPreset).not().isChecked();
        pause();

        var clearButtons = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Clear"));
        clearButtons.nth(0).click();
        clearButtons.nth(1).click();
        assertThat(jvmOpts).hasValue("");
        assertThat(groups).hasValue("");
        pause();
        assertThat(page.locator("vaadin-checkbox-group")
                .getByRole(AriaRole.CHECKBOX, new Locator.GetByRoleOptions().setChecked(true))).hasCount(0);

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Cancel")).click();
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Init cancelled"))).isVisible();
        pause();

        var result = init.get(1, TimeUnit.MINUTES);
        assertEquals(1, result.exitCode(), "a cancelled init must fail");
        assertTrue(result.output().contains("Init cancelled, nothing was written"));
        assertEquals(1, workstation.sh(app, "test -e vmhosting.vm2.conf").exitCode(), "vmhosting.vm2.conf was written");
    }

    /** Videos are per page: the pages of a test are saved as testName-1.webm, testName-2.webm, ... */
    void closeAndSaveVideo(Page p) {
        if (p.isClosed()) return;
        p.close();
        if (VIDEO_DIR != null) {
            videoNumber++;
            p.video().saveAs(VIDEO_DIR.resolve(testName + "-" + videoNumber + ".webm"));
            p.video().delete();
        }
    }

    /** Give a video viewer a moment to see what happened; no-op otherwise. */
    void pause() {
        if (VIDEO_DIR != null) page.waitForTimeout(1500);
    }

    /** The input of the form field with exactly this label. */
    Locator field(String label) {
        return page.getByLabel(label, new Page.GetByLabelOptions().setExact(true));
    }

    /** Open the form as soon as Deploy serves it. */
    void openForm(CompletableFuture<Workstation.Result> init) {
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
