package boot2vm.it;

import com.github.dockerjava.api.model.Bind;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * A container that plays the developer's machine: JBang, ssh, rsync and Maven,
 * with the Deploy.java under test in /work and the test apps next to it. The
 * Maven, JBang, Node and npm caches live in Docker volumes, so only the first
 * run downloads the dependencies.
 */
public class Workstation extends GenericContainer<Workstation> {

    /** The boot2vm checkout, i.e. where Deploy.java is. */
    static final Path ROOT = Path.of(System.getProperty("boot2vm.root", "..")).toAbsolutePath().normalize();

    /** The port the init form is served on, see {@link #withInitForm()}. */
    static final int FORM_PORT = 8888;

    public record Result(int exitCode, String output) {
    }

    public Workstation(Network network) {
        super(new ImageFromDockerfile("boot2vm-it-workstation", false)
                .withFileFromPath(".", Path.of("images/workstation")));
        withNetwork(network);
        withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("Deploy.java")), "/work/Deploy.java");
        withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withBinds(
                Bind.parse("boot2vm-it-m2:/root/.m2"),
                Bind.parse("boot2vm-it-jbang:/root/.jbang/cache"),
                // Node and npm packages for Vaadin's frontend build
                Bind.parse("boot2vm-it-vaadin:/root/.vaadin"),
                Bind.parse("boot2vm-it-npm:/root/.npm")));
    }

    /** Expose the init form port to the test JVM, for a browser to open it. */
    Workstation withInitForm() {
        withExposedPorts(FORM_PORT);
        // Nothing listens on the port until a test starts init
        waitingFor(Wait.forSuccessfulCommand("true"));
        return this;
    }

    /** Where the browser reaches the init form, once {@link #initWithForm} runs. */
    String formUrl() {
        return "http://" + getHost() + ":" + getMappedPort(FORM_PORT) + "/";
    }

    /**
     * Start init with the browser form in the background. The form listens on all
     * interfaces of the container, on {@link #FORM_PORT}. The result completes
     * when Deploy exits.
     */
    CompletableFuture<Result> initWithForm(String dir, String... args) {
        return CompletableFuture.supplyAsync(() -> sh(dir,
                "SERVER_ADDRESS=0.0.0.0 SERVER_PORT=" + FORM_PORT + " jbang /work/Deploy.java init --gui "
                        + String.join(" ", args)));
    }

    /** Copy a project from apps/ to /work/{name} and return that directory. */
    String copyApp(String name) {
        copyFileToContainer(MountableFile.forHostPath(Path.of("apps", name)), "/work/" + name);
        return "/work/" + name;
    }

    /**
     * Copy a project of the boot2vm checkout to /work/{name} and return that directory.
     * Only the files git knows are copied (as they are in the working tree), no build output.
     */
    String copyProject(String name) {
        try {
            var git = new ProcessBuilder("git", "-c", "safe.directory=*", "-C", ROOT.toString(), "ls-files", "--", name)
                    .redirectErrorStream(true).start();
            List<String> files = new String(git.getInputStream().readAllBytes()).lines().toList();
            if (git.waitFor() != 0 || files.isEmpty()) {
                throw new IllegalStateException("Could not list the files of " + name + ": " + files);
            }
            for (String file : files) {
                Path source = ROOT.resolve(file);
                if (Files.isRegularFile(source)) copyFileToContainer(MountableFile.forHostPath(source), "/work/" + file);
            }
            return "/work/" + name;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Copy an artifact directory from the local Maven repository of this machine, if it is there.
     * For snapshots that are not published anywhere yet.
     */
    void copyFromLocalRepository(String path) {
        Path dir = Path.of(System.getProperty("user.home"), ".m2", "repository", path);
        if (Files.isDirectory(dir)) copyFileToContainer(MountableFile.forHostPath(dir), "/root/.m2/repository/" + path);
    }

    /**
     * Run a command in a terminal of 120x36 characters, recording what it shows with
     * script(1), for {@link TerminalRecording#play}. The result completes when it exits.
     */
    CompletableFuture<TerminalRecording> recordTerminal(String dir, String command) {
        return CompletableFuture.supplyAsync(() -> {
            var result = sh(dir, "script -q -e -m classic -T /tmp/term.timing -O /tmp/term.out -c '"
                    + "stty cols " + TerminalRecording.COLUMNS + " rows " + TerminalRecording.ROWS + "; " + command + "'");
            return new TerminalRecording(command, result.exitCode(), result.output(),
                    copyFileFromContainer("/tmp/term.out", InputStream::readAllBytes),
                    copyFileFromContainer("/tmp/term.timing", in -> new String(in.readAllBytes())));
        });
    }

    String publicKey() {
        return sh("/work", "cat /root/.ssh/id_ed25519.pub").output();
    }

    /** Run Deploy.java with the given arguments in a project directory. */
    Result deploy(String dir, String... args) {
        return sh(dir, "jbang /work/Deploy.java " + String.join(" ", args));
    }

    /** Run Deploy.java interactively, answering its prompts as the script says. */
    Result deployInteractively(String dir, Prompts prompts, String... args) {
        copyFileToContainer(Transferable.of(prompts.toScript()), "/tmp/prompts.txt");
        return sh(dir, "jbang /usr/local/bin/Drive.java /tmp/prompts.txt -- jbang /work/Deploy.java "
                + String.join(" ", args));
    }

    /** Fetch a URL until the body satisfies the condition, failing after the timeout. */
    String awaitHttp(String url, Predicate<String> condition, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        String body;
        while (true) {
            body = sh("/work", "curl -s -m 5 " + url + " || true").output();
            if (condition.test(body)) return body;
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(url + " did not answer as expected within " + timeout + ", last body: '" + body + "'");
            }
            Vm.sleep(1000);
        }
    }

    /** Run a shell command in a directory; the output is echoed so failures can be followed in the test log. */
    Result sh(String dir, String command) {
        try {
            var result = execInContainer("sh", "-c", "cd " + dir + " && " + command + " 2>&1");
            System.out.println("$ " + command + "\n" + result.getStdout());
            return new Result(result.getExitCode(), result.getStdout());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
