import java.nio.file.*;
import java.util.*;

/** Run with javac -d /tmp/boot2vm-tests Deploy.java tests/LocalHttpsTest.java,
 * then java -cp /tmp/boot2vm-tests LocalHttpsTest. No SSH or provisioning. */
public class LocalHttpsTest {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("boot2vm-tls-test-");
        try {
            require(Deploy.parseHttpsMode(" INTERNAL ").equals("internal"), "normalize mode");
            try { Deploy.parseHttpsMode("typo"); throw new AssertionError("invalid mode accepted"); }
            catch (IllegalArgumentException expected) { }
            Deploy.httpsMode = "internal";
            Deploy.webService = true;
            Deploy.proxy = "none";
            try { Deploy.validateHttpsMode(); throw new AssertionError("missing proxy accepted"); }
            catch (IllegalArgumentException expected) { }
            Deploy.proxy = "caddy";
            Deploy.validateHttpsMode();
            int configurations = 0;
            for (String script : List.of(Deploy.SETUP_SCRIPT, Deploy.BLUE_GREEN_SWAP_SCRIPT,
                    Deploy.BLUE_GREEN_GRACEFUL_SCRIPT)) {
                Path shell = temp.resolve("check.sh");
                Files.writeString(shell, script);
                require(new ProcessBuilder("bash", "-n", shell.toString()).inheritIO().start().waitFor() == 0,
                        "generated bash syntax");
                String address = script.substring(script.indexOf("# Build Caddy site address"));
                address = address.substring(0, address.indexOf("\nfi") + 3);
                int from = 0;
                while ((from = script.indexOf("$SITE_ADDR {", from)) >= 0) {
                    int end = script.indexOf("\nCADDY", from);
                    String body = script.substring(from, end);
                    for (String mode : List.of("yes", "no", "internal")) {
                        String render = "HTTPS=" + mode + "\nDOMAIN='one.local two.local'\nPORT=8080\n"
                                + "INACTIVE_PORT=8081\nACTIVE_PORT=8080\nSLOT_COOKIE=X-Slot\nACTIVE=blue\n"
                                + address + "\ncat <<CADDY\n" + body + "\nCADDY\n";
                        Files.writeString(shell, render);
                        var process = new ProcessBuilder("bash", shell.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                        String config = new String(process.getInputStream().readAllBytes());
                        require(process.waitFor() == 0, "render failed");
                        require(config.contains("tls internal") == mode.equals("internal"), "TLS mode lost: " + mode);
                        require(config.startsWith(mode.equals("no") ? "http://one.local http://two.local {" : "one.local two.local {"), "address mode");
                        require(config.contains("reverse_proxy localhost:"), "proxy missing");
                        Files.writeString(temp.resolve("site-" + configurations + "-" + mode + ".caddy"), config);
                    }
                    configurations++;
                    from = end;
                }
            }
            require(configurations == 4, "cover setup, swap, drain and final cutover");
            Path cert = temp.resolve("root.crt");
            require(new ProcessBuilder("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                    "-keyout", temp.resolve("root.key").toString(), "-out", cert.toString(), "-days", "1",
                    "-subj", "/CN=boot2vm test CA", "-addext", "basicConstraints=critical,CA:TRUE")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0, "generate test CA");
            Path exported = temp.resolve("export.crt");
            Deploy.writeRootCertificate(exported, Files.readString(cert));
            require(!Files.readString(exported).contains("PRIVATE"), "private material exported");
            try { Deploy.writeRootCertificate(exported, Files.readString(cert)); throw new AssertionError("overwrite allowed"); }
            catch (FileAlreadyExistsException expected) { }
            try { Deploy.writeRootCertificate(temp.resolve("bad.crt"), "not a certificate"); throw new AssertionError("invalid certificate accepted"); }
            catch (java.security.cert.CertificateException expected) { }
            require(!Files.exists(temp.resolve("bad.crt")), "invalid export created");
            System.out.println("PASS: 12 Caddy configurations, shell syntax, mode validation and root certificate export");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
