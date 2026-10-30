///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Answers the prompts of an interactive command, like expect.
 *
 * <p>Usage: {@code jbang Drive.java STEPS -- COMMAND...}
 *
 * <p>STEPS is a text file with one step per line: a regex, a tab and the text to
 * type. For each step the output is read until the regex matches it, then the
 * text and Enter are typed. After the last step the command runs to completion.
 * All output is echoed. The exit code is the command's own, or 124 when a step
 * times out.
 *
 * <p>The command gets a terminal from util-linux {@code script}, as Deploy needs
 * a console for its prompts.
 */
public class Drive {

    static final long STEP_TIMEOUT = Long.getLong("drive.stepTimeout", 60) * 1000;
    static final long TOTAL_TIMEOUT = Long.getLong("drive.totalTimeout", 1200) * 1000;

    record Step(Pattern expect, String send) {
    }

    /** What the command has printed, minus what earlier steps already matched. */
    static final StringBuilder output = new StringBuilder();

    public static void main(String[] args) throws Exception {
        var steps = new ArrayList<Step>();
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            steps.add(new Step(Pattern.compile(line.substring(0, tab)), line.substring(tab + 1)));
        }
        List<String> command = List.of(args).subList(List.of(args).indexOf("--") + 1, args.length);

        var process = new ProcessBuilder("script", "-qefc", String.join(" ", command), "/dev/null")
                .redirectErrorStream(true)
                .start();
        Thread.ofVirtual().start(() -> echo(process.getInputStream()));
        var input = process.getOutputStream();
        long started = System.currentTimeMillis();

        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            long deadline = System.currentTimeMillis() + STEP_TIMEOUT;
            while (!consume(step.expect())) {
                if (System.currentTimeMillis() > deadline) {
                    fail(process, "step " + (i + 1) + ": no match for " + step.expect());
                }
                if (!process.isAlive()) {
                    fail(process, "step " + (i + 1) + ": command exited before " + step.expect());
                }
                Thread.sleep(100);
            }
            input.write((step.send() + "\n").getBytes(StandardCharsets.UTF_8));
            input.flush();
        }

        long left = TOTAL_TIMEOUT - (System.currentTimeMillis() - started);
        if (!process.waitFor(Math.max(left, 0), TimeUnit.MILLISECONDS)) {
            fail(process, "command did not finish");
        }
        Thread.sleep(200); // let the last output through
        System.exit(process.exitValue());
    }

    static void echo(InputStream in) {
        byte[] buffer = new byte[8192];
        try {
            for (int n; (n = in.read(buffer)) > 0; ) {
                String text = new String(buffer, 0, n, StandardCharsets.UTF_8);
                System.out.print(text);
                System.out.flush();
                synchronized (output) {
                    output.append(text);
                }
            }
        } catch (IOException e) {
            // the command has exited
        }
    }

    /** Whether the pattern matches the output so far; on a match, drop the output up to its end. */
    static boolean consume(Pattern pattern) {
        synchronized (output) {
            var matcher = pattern.matcher(output);
            if (!matcher.find()) return false;
            output.delete(0, matcher.end());
            return true;
        }
    }

    static void fail(Process process, String message) {
        String tail;
        synchronized (output) {
            tail = output.substring(Math.max(0, output.length() - 2000));
        }
        System.out.println("\n[drive] " + message + "\n[drive] last output:\n" + tail);
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        System.exit(124);
    }
}
