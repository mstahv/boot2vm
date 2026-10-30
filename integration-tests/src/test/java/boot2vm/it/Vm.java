package boot2vm.it;

import com.github.dockerjava.api.model.Bind;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * A container that stands in for a fresh server: Ubuntu with systemd as PID 1
 * and sshd, reachable from the {@link Workstation} by its network alias.
 * Privileged, as systemd, ufw and Caddy expect a whole machine.
 */
public class Vm extends GenericContainer<Vm> {

    final String name;

    public Vm(Network network, String name) {
        super(new ImageFromDockerfile("boot2vm-it-vm", false)
                .withFileFromPath(".", Path.of("images/vm")));
        this.name = name;
        withNetwork(network);
        withNetworkAliases(name);
        withPrivilegedMode(true);
        withTmpFs(Map.of("/run", "rw", "/run/lock", "rw"));
        withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                .withCgroupnsMode("host")
                .withBinds(Bind.parse("/sys/fs/cgroup:/sys/fs/cgroup:rw")));
    }

    @Override
    public void start() {
        super.start();
        awaitBoot();
    }

    /** Wait until systemd has started everything, sshd included. */
    void awaitBoot() {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        while (true) {
            String state = sh("systemctl is-system-running || true").trim();
            if (state.equals("running") || state.equals("degraded")) return;
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(name + " did not boot, systemd state: " + state);
            }
            sleep(500);
        }
    }

    /** Let the given public key log in as root, like a cloud provider does for a new VM. */
    void authorize(String publicKey) {
        sh("printf '%s\\n' '" + publicKey.trim() + "' >> /root/.ssh/authorized_keys"
                + " && chmod 600 /root/.ssh/authorized_keys");
    }

    /** Run a shell command as root on the server, failing on a non-zero exit. */
    String sh(String command) {
        try {
            var result = execInContainer("sh", "-c", command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("'" + command + "' failed on " + name + " (exit " + result.getExitCode()
                        + "):\n" + result.getStdout() + result.getStderr());
            }
            return result.getStdout();
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
