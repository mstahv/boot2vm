///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17+

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Deploy {

    static String host, user, domain, sshKey, adminUser, proxy;
    static boolean https;

    static final String SETUP_SCRIPT = """
            #!/bin/bash
            set -euo pipefail
            APP_USER="$1"
            DOMAIN="$2"
            ADMIN_USER="${3:-root}"
            HTTPS="${4:-yes}"
            PROXY="${5:-caddy}"
            echo "=== Setting up server for user '$APP_USER' with domain '$DOMAIN' ==="

            # 1. Automatic security updates with nightly reboot if required
            echo "--- Configuring unattended-upgrades ---"
            apt-get update
            apt-get install -y unattended-upgrades
            cat > /etc/apt/apt.conf.d/20auto-upgrades << 'CONF'
            APT::Periodic::Update-Package-Lists "1";
            APT::Periodic::Unattended-Upgrade "1";
            CONF
            cat > /etc/apt/apt.conf.d/50unattended-upgrades-local << 'CONF'
            Unattended-Upgrade::Automatic-Reboot "true";
            Unattended-Upgrade::Automatic-Reboot-Time "02:00";
            CONF
            systemctl enable --now unattended-upgrades

            # 2. Install JDK 25 (Eclipse Adoptium / Temurin)
            echo "--- Installing JDK 25 ---"
            apt-get install -y wget apt-transport-https gpg
            wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \\
                | gpg --dearmor --yes -o /usr/share/keyrings/adoptium.gpg
            echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" \\
                > /etc/apt/sources.list.d/adoptium.list
            apt-get update
            apt-get install -y temurin-25-jdk

            # 3. Create application user and set up SSH access
            echo "--- Creating user '$APP_USER' ---"
            if ! id "$APP_USER" &>/dev/null; then
                useradd -m -s /bin/bash "$APP_USER"
            fi
            ADMIN_HOME=$(eval echo "~$ADMIN_USER")
            mkdir -p "/home/$APP_USER/.ssh"
            cp "$ADMIN_HOME/.ssh/authorized_keys" "/home/$APP_USER/.ssh/authorized_keys"
            chown -R "$APP_USER:$APP_USER" "/home/$APP_USER/.ssh"
            chmod 700 "/home/$APP_USER/.ssh"
            chmod 600 "/home/$APP_USER/.ssh/authorized_keys"

            # 4. Create application working directory
            echo "--- Creating app directory ---"
            mkdir -p "/home/$APP_USER/app"
            chown "$APP_USER:$APP_USER" "/home/$APP_USER/app"

            # 5. Systemd service for the Spring Boot application
            echo "--- Installing systemd service ---"
            cat > "/etc/systemd/system/$APP_USER.service" << UNIT
            [Unit]
            Description=Spring Boot Application ($APP_USER)
            After=network.target

            [Service]
            Type=simple
            User=$APP_USER
            WorkingDirectory=/home/$APP_USER/app
            ExecStart=/usr/bin/java -jar /home/$APP_USER/app/$APP_USER.jar
            Restart=on-failure
            RestartSec=10

            [Install]
            WantedBy=multi-user.target
            UNIT
            systemctl daemon-reload
            systemctl enable "$APP_USER"

            # 6. Install reverse proxy (if configured)
            if [ "$PROXY" = "caddy" ]; then
                echo "--- Installing Caddy ---"
                apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl
                curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \\
                    | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
                curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \\
                    > /etc/apt/sources.list.d/caddy-stable.list
                apt-get update
                apt-get install -y caddy
                if [ "$HTTPS" = "yes" ]; then
                    cat > /etc/caddy/Caddyfile << CADDY
            $DOMAIN {
                reverse_proxy localhost:8080
            }
            CADDY
                else
                    cat > /etc/caddy/Caddyfile << CADDY
            http://$DOMAIN {
                reverse_proxy localhost:8080
            }
            CADDY
                fi
                systemctl reload caddy
            else
                echo "--- Skipping reverse proxy installation ---"
            fi

            echo "=== Server setup complete! ==="
            """;

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "deploy" : args[0];

        switch (command) {
            case "init" -> init();
            case "deploy" -> { loadConfig(); deploy(); }
            case "logs" -> { loadConfig(); logs(args); }
            case "add-key" -> { loadConfig(); addKey(args); }
            default -> {
                System.err.println("Unknown command: " + command);
                printUsage();
                System.exit(1);
            }
        }
    }

    static void printUsage() {
        System.out.println("Usage: jbang Deploy.java <command>");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  init           - Set up the server (run once)");
        System.out.println("  deploy         - Build, sync, and restart the app");
        System.out.println("  logs [n]       - Tail the application logs (default: 200 lines)");
        System.out.println("  add-key [file] - Add an SSH public key to the server");
    }

    static void loadConfig() throws IOException {
        Path config = Path.of("vmhosting.conf");
        if (!Files.exists(config)) {
            System.err.println("vmhosting.conf not found in current directory. Run 'init' first.");
            System.exit(1);
        }
        var props = new Properties();
        try (var reader = Files.newBufferedReader(config)) {
            props.load(reader);
        }
        host = props.getProperty("HOST");
        user = props.getProperty("USER");
        domain = props.getProperty("DOMAIN", host);
        sshKey = props.getProperty("SSH_KEY", "~/.ssh/id_rsa.pub");
        adminUser = props.getProperty("ADMIN_USER", "root");
        https = !"no".equalsIgnoreCase(props.getProperty("HTTPS", "yes"));
        proxy = props.getProperty("PROXY", "caddy");

        if (sshKey.endsWith(".pub")) {
            sshKey = sshKey.substring(0, sshKey.length() - 4);
        }
        if (sshKey.startsWith("~")) {
            sshKey = System.getProperty("user.home") + sshKey.substring(1);
        }

        if (host == null || user == null) {
            System.err.println("HOST and USER must be set in vmhosting.conf");
            System.exit(1);
        }
    }

    // -----------------------------------------------------------------------
    // init – interactively collect config, write vmhosting.conf, set up server
    // -----------------------------------------------------------------------
    static void init() throws Exception {
        var console = System.console();
        if (console == null) {
            System.err.println("No console available for interactive input");
            System.exit(1);
        }

        // Load existing config as defaults if available
        Path configPath = Path.of("vmhosting.conf");
        String defaultHost = null, defaultUser = null, defaultDomain = null,
                defaultKey = null, defaultAdmin = null, defaultHttps = null, defaultProxy = null;
        if (Files.exists(configPath)) {
            var props = new Properties();
            try (var reader = Files.newBufferedReader(configPath)) {
                props.load(reader);
            }
            defaultHost = props.getProperty("HOST");
            defaultUser = props.getProperty("USER");
            defaultDomain = props.getProperty("DOMAIN");
            defaultKey = props.getProperty("SSH_KEY");
            defaultAdmin = props.getProperty("ADMIN_USER");
            defaultHttps = props.getProperty("HTTPS");
            defaultProxy = props.getProperty("PROXY");
        }

        // HOST (required)
        host = prompt(console, "Host", defaultHost);
        if (host.isBlank()) {
            System.err.println("Host is required");
            System.exit(1);
        }

        // Derive smart defaults from host
        String derivedUser = host.contains(".") ? host.substring(0, host.indexOf('.')) : host;

        // USER, DOMAIN, SSH_KEY, ADMIN_USER – with defaults
        user = prompt(console, "App user", defaultUser != null ? defaultUser : derivedUser);
        domain = prompt(console, "Domain", defaultDomain != null ? defaultDomain : host);
        String sshKeyRaw = prompt(console, "SSH public key",
                defaultKey != null ? defaultKey : "~/.ssh/id_rsa.pub");
        adminUser = prompt(console, "Admin SSH user",
                defaultAdmin != null ? defaultAdmin : "root");
        String httpsStr = prompt(console, "HTTPS",
                defaultHttps != null ? defaultHttps : "yes");
        https = httpsStr.equalsIgnoreCase("yes");
        proxy = prompt(console, "Reverse proxy (caddy/none)",
                defaultProxy != null ? defaultProxy : "caddy");

        // Write vmhosting.conf
        Files.writeString(configPath,
                "HOST=" + host + "\n"
                + "USER=" + user + "\n"
                + "DOMAIN=" + domain + "\n"
                + "SSH_KEY=" + sshKeyRaw + "\n"
                + "ADMIN_USER=" + adminUser + "\n"
                + "HTTPS=" + (https ? "yes" : "no") + "\n"
                + "PROXY=" + proxy + "\n");
        System.out.println("Wrote vmhosting.conf");

        // Resolve the private key path for SSH connections (strip .pub if present)
        sshKey = sshKeyRaw;
        if (sshKey.endsWith(".pub")) {
            sshKey = sshKey.substring(0, sshKey.length() - 4);
        }
        if (sshKey.startsWith("~")) {
            sshKey = System.getProperty("user.home") + sshKey.substring(1);
        }

        // Upload and execute setup script
        System.out.println("Initializing server " + host + " ...");

        Path tempScript = Files.createTempFile("setup-server", ".sh");
        Files.writeString(tempScript, SETUP_SCRIPT);

        scp(tempScript.toString(), adminUser + "@" + host + ":/tmp/setup-server.sh");
        sshAsRoot("chmod +x /tmp/setup-server.sh");
        sshAsRoot("/tmp/setup-server.sh "
                + user + " " + domain + " " + adminUser + " " + (https ? "yes" : "no") + " " + proxy);

        Files.delete(tempScript);
        System.out.println("Server initialized successfully.\n");

        // Automatically run first deploy
        deploy();
    }

    static String prompt(Console console, String label, String defaultValue) {
        String p = defaultValue != null
                ? label + " [" + defaultValue + "]: "
                : label + ": ";
        String input = console.readLine(p).trim();
        return input.isEmpty() && defaultValue != null ? defaultValue : input;
    }

    // -----------------------------------------------------------------------
    // deploy – build, extract, rsync, restart
    // -----------------------------------------------------------------------
    static void deploy() throws Exception {
        // 1. Build
        System.out.println("Building application ...");
        boolean mavenw = Files.exists(Path.of("mvnw"));
        boolean gradlew = Files.exists(Path.of("gradlew"));
        boolean pom = Files.exists(Path.of("pom.xml"));
        boolean gradle = Files.exists(Path.of("build.gradle")) || Files.exists(Path.of("build.gradle.kts"));

        if (mavenw) {
            run("./mvnw", "-DskipTests", "package");
        } else if (gradlew) {
            run("./gradlew", "-x", "test", "bootJar");
        } else if (pom) {
            run("mvn", "-DskipTests", "package");
        } else if (gradle) {
            run("gradle", "-x", "test", "bootJar");
        } else {
            System.err.println("No Maven or Gradle project found in current directory");
            System.exit(1);
        }

        // 2. Locate the built fat jar
        Path jarDir = (mavenw || pom) ? Path.of("target") : Path.of("build", "libs");
        Path jar = findJar(jarDir);
        System.out.println("Found jar: " + jar);

        // 3. Extract for efficient rsync (lib/ changes rarely)
        Path extracted = Path.of("target", "extracted");
        if (Files.exists(extracted)) {
            deleteRecursively(extracted);
        }
        run("java", "-Djarmode=tools", "-jar", jar.toString(),
                "extract", "--destination", extracted.toString());

        // The extract command may place files directly in extracted/ or in a
        // subdirectory. Find the directory that contains lib/.
        Path extractRoot = findExtractedRoot(extracted);

        // 4. Rename the application jar to $USER.jar
        Path extractedJar = findJar(extractRoot);
        Path renamedJar = extractRoot.resolve(user + ".jar");
        if (!extractedJar.getFileName().toString().equals(user + ".jar")) {
            Files.move(extractedJar, renamedJar, StandardCopyOption.REPLACE_EXISTING);
        }

        // 5. Rsync to server – only changed bytes are transferred
        System.out.println("Syncing to server ...");
        run("rsync", "-az", "--delete", "--stats",
                "-e", "ssh -i " + sshKey + " -o StrictHostKeyChecking=accept-new",
                extractRoot + "/",
                user + "@" + host + ":/home/" + user + "/app/");

        // 6. Restart the systemd service
        System.out.println("Restarting service ...");
        sshAsRoot("systemctl restart " + user);

        System.out.println("Deployed successfully!");
    }

    // -----------------------------------------------------------------------
    // logs – tail journalctl
    // -----------------------------------------------------------------------
    static void logs(String[] args) throws Exception {
        String lines = args.length > 1 ? args[1] : "200";
        String cmd = "journalctl -u " + user + " -n " + lines + " -f";
        if (!"root".equals(adminUser)) {
            cmd = "sudo " + cmd;
        }
        new ProcessBuilder("ssh", "-i", sshKey,
                "-o", "StrictHostKeyChecking=accept-new",
                adminUser + "@" + host,
                cmd)
                .inheritIO()
                .start()
                .waitFor();
    }

    // -----------------------------------------------------------------------
    // add-key – register an additional SSH public key for the app user
    // -----------------------------------------------------------------------
    static void addKey(String[] args) throws Exception {
        String pubKey;
        if (args.length > 1) {
            pubKey = readPublicKey(args[1]);
        } else {
            var console = System.console();
            if (console == null) {
                System.err.println("No console available. Pass the key file as argument.");
                System.exit(1);
            }
            String input = console.readLine("Paste the public key: ").trim();
            pubKey = looksLikeKeyContent(input) ? input : readPublicKey(input);
        }
        if (!looksLikeKeyContent(pubKey)) {
            System.err.println("Does not look like a valid SSH public key");
            System.exit(1);
        }

        System.out.println("Adding key to " + user + "@" + host + " ...");
        String escaped = pubKey.replace("\"", "\\\"");
        sshAsRoot("grep -qF \"" + escaped + "\" /home/" + user + "/.ssh/authorized_keys 2>/dev/null"
                + " && echo 'Key already present' "
                + " || (echo \"" + escaped + "\" >> /home/" + user + "/.ssh/authorized_keys"
                + " && echo 'Key added successfully')");
    }

    static String readPublicKey(String path) throws IOException {
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        Path keyFile = Path.of(path);
        if (!Files.exists(keyFile)) {
            System.err.println("File not found: " + path);
            System.exit(1);
        }
        return Files.readString(keyFile).trim();
    }

    static boolean looksLikeKeyContent(String s) {
        return s.startsWith("ssh-rsa ") || s.startsWith("ssh-ed25519 ")
                || s.startsWith("ecdsa-sha2-") || s.startsWith("sk-ssh-");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static Path findJar(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files
                    .filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.toString().endsWith("-plain.jar"))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No jar found in " + dir));
        }
    }

    static Path findExtractedRoot(Path extracted) throws IOException {
        if (Files.isDirectory(extracted.resolve("lib"))) {
            return extracted;
        }
        try (var dirs = Files.list(extracted)) {
            return dirs
                    .filter(Files::isDirectory)
                    .filter(d -> Files.isDirectory(d.resolve("lib")))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(
                            "Could not find extracted app layout in " + extracted));
        }
    }

    static void run(String... cmd) throws Exception {
        System.out.println("  > " + String.join(" ", cmd));
        int exit = new ProcessBuilder(cmd)
                .inheritIO()
                .start()
                .waitFor();
        if (exit != 0) {
            System.err.println("Command failed with exit code " + exit);
            System.exit(exit);
        }
    }

    static void ssh(String asUser, String command) throws Exception {
        run("ssh", "-i", sshKey, "-o", "StrictHostKeyChecking=accept-new",
                asUser + "@" + host, command);
    }

    static void sshAsRoot(String command) throws Exception {
        if (!"root".equals(adminUser)) {
            command = "sudo " + command;
        }
        ssh(adminUser, command);
    }

    static void scp(String local, String remote) throws Exception {
        run("scp", "-i", sshKey, "-o", "StrictHostKeyChecking=accept-new",
                local, remote);
    }

    static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
