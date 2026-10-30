package boot2vm.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.Network;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The basic flow: init a fresh server with the terminal UI, then redeploy a
 * changed app. The app is a plain jar, so no framework is downloaded.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlainAppIT {

    static Network network;
    static Vm vm;
    static Workstation workstation;
    static String app;

    @BeforeAll
    static void start() {
        network = Network.newNetwork();
        vm = new Vm(network, "vm1");
        workstation = new Workstation(network);
        vm.start();
        workstation.start();
        vm.authorize(workstation.publicKey());
        app = workstation.copyApp("hello-plain");
    }

    @AfterAll
    static void stop() {
        if (workstation != null) workstation.stop();
        if (vm != null) vm.stop();
        if (network != null) network.close();
    }

    @Test
    @Order(1)
    void initSetsUpServerAndDeploys() {
        var prompts = new Prompts()
                .answer("Name for this server target", "vm1")
                .answer("Host", "vm1")
                .answer("App user", "hello")
                .accept("Does this service expose web endpoints", "yes")
                .accept("Domain(s)", "vm1")
                .accept("SSH public key", "~/.ssh/id_ed25519.pub")
                .accept("Admin SSH user", "root")
                // A bare host name can't get a public certificate
                .accept("HTTPS", "no")
                .accept("Reverse proxy", "caddy")
                .accept("App port", "8080")
                .accept("App type", "plain")
                .accept("JDK provider", "temurin")
                .accept("Configure hardware access", "no")
                .accept("Blue-green deployment", "no")
                .accept("Configure firewall with ufw", "yes")
                .accept("Expose app server port(s) 8080", "no")
                .answer("Environment variable", "GREETING=world")
                .answer("Environment variable", "");

        var result = workstation.deployInteractively(app, prompts, "init", "--tui");

        assertEquals(0, result.exitCode(), "init failed");
        assertTrue(result.output().contains("Deployed successfully to @vm1"));
        workstation.awaitHttp("http://vm1/", "v1 world"::equals, Duration.ofSeconds(30));
        String conf = workstation.sh(app, "cat vmhosting.vm1.conf").output();
        assertTrue(conf.contains("HOST=vm1\n"), conf);
        assertTrue(conf.contains("APP_TYPE=plain\n"), conf);
        assertTrue(!conf.contains("GREETING"), "env vars must stay on the server: " + conf);
        assertEquals("active", vm.sh("systemctl is-active hello").trim());
        assertTrue(vm.sh("ufw status").contains("Status: active"));
    }

    @Test
    @Order(2)
    void redeployServesTheNewVersion() {
        workstation.sh(app, "sed -i 's/\"v1\"/\"v2\"/' src/main/java/hello/Main.java");

        var result = workstation.deploy(app);

        assertEquals(0, result.exitCode(), "deploy failed");
        workstation.awaitHttp("http://vm1/", "v2 world"::equals, Duration.ofSeconds(30));
    }
}
