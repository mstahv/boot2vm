///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Cuts the recordings of GracefulDrainIT into one demo video: title cards, captions,
 * the waits sped up, the deploy window next to the users' windows in sync, and
 * stills of the ways users are told about a new version.
 * Runs ffmpeg in Docker.
 *
 * <pre>
 * mvn verify -Dit.test=GracefulDrainIT -Dit.video=target/videos -Dit.slowMo=300
 * jbang promo/Promo.java     # writes target/promo/boot2vm-graceful-deploy.mp4
 * </pre>
 */
public class Promo {

    static final Path VIDEOS = Path.of("target/videos").toAbsolutePath();
    static final Path OUT = Path.of("target/promo").toAbsolutePath();
    static final String FFMPEG = "linuxserver/ffmpeg";
    static final int W = 1280, H = 800, STRIP = 110; // picture area and the caption strip below it
    static final String BG = "0x1e1e2e";
    static final String FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    static final String BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf";

    /** A recorded window, named as in the timeline. */
    record Window(String name, String title, String subtitle) {
        String video() {
            return "/v/" + name + ".webm";
        }
    }

    static final Window DEPLOY = new Window("2-deploy-window", "The developer", "jbang Deploy.java");
    static final Window USER_A = new Window("3-user-main", "User A, main view", "stays until they update");
    static final Window USER_B = new Window("4-user-info", "User B, info view", "@AutoUpgrade: moved right away");
    static final Window USER_C = new Window("5-user-form", "User C, form", "moved once nothing is unsaved");
    static final Window DEPLOY_MOVE = new Window("6-deploy-window", "The developer", "jbang Deploy.java");
    static final Window USER_D = new Window("7-user-moved", "User D, main view", "doesn't get around to updating");

    final Map<String, Long> timeline = new HashMap<>();
    final List<String> segments = new ArrayList<>();
    int texts;

    public static void main(String[] args) throws Exception {
        new Promo().run();
    }

    void run() throws Exception {
        Files.createDirectories(OUT);
        for (String line : Files.readAllLines(VIDEOS.resolve("timeline.txt"))) {
            String[] parts = line.split(" ", 2);
            timeline.putIfAbsent(parts[1], Long.parseLong(parts[0]));
        }

        card("boot2vm", "Zero-downtime deploys of Vaadin apps to your own server\n(raw material from the integration tests)", 4);

        card("1. Set up the server", "One form: host, app user, blue-green deployment with graceful drain", 3);
        clip("1-init.webm", 0, 7, 1, 0, null,
                caption("Fill in the host and the app user, turn on graceful blue-green deployment"));
        clip("1-init.webm", 7, 51, 5, 0, "5× speed",
                caption("The server gets a JDK, Caddy, a firewall and the app.\nThe window follows the progress."));
        clip("1-init.webm", 51, 53.1, 1, 2.5, null, caption("Ready: the app is running"));

        double announced = seconds(DEPLOY, "new version announced"), saved = seconds(DEPLOY, "form saved"),
                clicked = seconds(DEPLOY, "update clicked"), done = seconds(DEPLOY, "deploy done");
        var deployAndUsers = List.of(DEPLOY, USER_A, USER_B, USER_C);
        card("2. Deploy a new version", "Three users are in the app while the developer deploys", 3);
        double shown = seconds(DEPLOY, "deploy window shown");
        hold(deployAndUsers, shown, 6,
                caption("Three users on version 1: A in a view that keeps them on their version,\n"
                        + "B in an @AutoUpgrade view, C with unsaved notes in a form"));
        grid(deployAndUsers, shown, announced - 3, 2, "2× speed",
                caption("The developer starts the deploy: build, upload,\nversion 2 starts next to version 1"));
        double t = announced - 3; // the next part starts here
        grid(deployAndUsers, t, saved + 3, 1, null,
                caption("Version 2 is up: the users are told about it", 0, announced - t),
                caption("B is moved at once. A gets a notification.\nC has unsaved notes: the form asks to save them first.",
                        announced - t, saved - t),
                caption("C saves. With nothing left to lose, the form moves C to version 2.", saved - t, 99));
        grid(deployAndUsers, saved + 3, clicked - 3, 2, "2× speed",
                caption("The deploy waits for A, who still works on version 1"));
        t = clicked - 3;
        grid(deployAndUsers, t, done + 2.5, 1, null,
                caption("A clicks \"Update now\" when it suits them.\nNo one is left on version 1: the deploy stops it.",
                        clicked - t - 0.5, 99));

        if (timeline.containsKey(DEPLOY_MOVE.name())) {
            double drain = seconds(DEPLOY_MOVE, "move: drain shown"), move = seconds(DEPLOY_MOVE, "move: clicked"),
                    moved = seconds(DEPLOY_MOVE, "move: user moved");
            card("3. No time to wait?", "The developer can move the remaining users right away", 3);
            grid(List.of(DEPLOY_MOVE, USER_D), seconds(DEPLOY_MOVE, "move: deploy window shown"), drain - 0.5, 2, "2× speed",
                    caption("Version 3 is on its way"));
            grid(List.of(DEPLOY_MOVE, USER_D), drain - 0.5, moved + 3.5, 1, null,
                    caption("D has been told about the new version, but stays on the old one", 0, move - drain - 0.5),
                    caption("The developer clicks \"Move them now\": the old version stops", move - drain - 0.5, moved - drain - 0.5),
                    caption("D's page reloads on the new version, with an apology", moved - drain - 0.5, 99));
        }

        if (Files.exists(VIDEOS.resolve("notifier-notification.png"))) {
            stills(List.of("notifier-notification.png", "notifier-badge.png"),
                    List.of("A notification (the default)", "A badge in the drawer"), 6,
                    caption("Users are told with a notification or a badge in the drawer,\nor however the app wants to"));
        }

        card("boot2vm", "Init with a form, deploy with one command,\nusers move to the new version when it suits them", 4);

        Files.writeString(OUT.resolve("list.txt"), segments.stream().map(s -> "file '/f/" + s + "'\n").collect(Collectors.joining()));
        docker("-f", "concat", "-safe", "0", "-i", "/f/list.txt", "-c", "copy", "-movflags", "+faststart",
                "/f/boot2vm-graceful-deploy.mp4");
        System.out.println("Wrote " + OUT.resolve("boot2vm-graceful-deploy.mp4"));
    }

    /** Seconds from the start of a window's recording to an event. */
    double seconds(Window window, String event) {
        return (timeline.get(event) - timeline.get(window.name())) / 1000.0;
    }

    void card(String title, String subtitle, double seconds) throws Exception {
        String vf = String.join(",",
                "drawtext=fontfile=" + BOLD + ":textfile=" + text(title) + ":fontcolor=white:fontsize=48:line_spacing=14"
                        + ":x=(w-text_w)/2:y=(h-text_h)/2-60",
                "drawtext=fontfile=" + FONT + ":textfile=" + text(subtitle) + ":fontcolor=0xbac2de:fontsize=28:line_spacing=12"
                        + ":x=(w-text_w)/2:y=h/2+40");
        encode(List.of("-f", "lavfi", "-i", "color=c=" + BG + ":s=" + W + "x" + (H + STRIP) + ":d=" + seconds + ":r=30",
                "-vf", vf));
    }

    /** A piece of one recording, sped up, optionally frozen at the end, with captions timed in the output. */
    void clip(String video, double start, double end, double speed, double freeze, String badge,
              String... captions) throws Exception {
        var filters = new ArrayList<>(List.of("setpts=(PTS-STARTPTS)/" + speed, "fps=30",
                "pad=" + W + ":" + (H + STRIP) + ":0:0:color=" + BG));
        if (freeze > 0) filters.add("tpad=stop_mode=clone:stop_duration=" + freeze);
        if (badge != null) filters.add(badge(badge));
        filters.addAll(List.of(captions));
        encode(List.of("-ss", String.valueOf(start), "-to", String.valueOf(end), "-i", "/v/" + video,
                "-vf", String.join(",", filters)));
    }

    /** Where the windows go: two side by side, or four in a grid, each 636x398. */
    static final int CELL_W = 636, CELL_H = 398;

    static int[] cell(int index, int count) {
        int x = index % 2 == 0 ? 4 : W - CELL_W - 4;
        int y = count <= 2 ? (H - CELL_H) / 2 : (index < 2 ? 0 : H - CELL_H);
        return new int[] {x, y};
    }

    /**
     * The deploy window and the users' windows, all at the same moment, labelled in their bottom
     * right corner. Start and end are seconds into the deploy window's recording.
     */
    void grid(List<Window> windows, double start, double end, double speed, String badge, String... captions)
            throws Exception {
        grid(windows, start, end, speed, badge, 0, captions);
    }

    /** The windows still at the given moment, for the given seconds. */
    void hold(List<Window> windows, double at, double seconds, String... captions) throws Exception {
        grid(windows, at, at + 0.1, 1, null, seconds, captions);
    }

    void grid(List<Window> windows, double start, double end, double speed, String badge, double hold,
              String... captions) throws Exception {
        Window deploy = windows.getFirst();
        var inputs = new ArrayList<String>();
        var graph = new ArrayList<String>();
        var labels = new ArrayList<String>();
        graph.add("color=c=" + BG + ":s=" + W + "x" + (H + STRIP) + ":r=30[o0]");
        for (int i = 0; i < windows.size(); i++) {
            Window window = windows.get(i);
            double offset = (timeline.get(window.name()) - timeline.get(deploy.name())) / 1000.0;
            inputs.addAll(List.of("-ss", String.valueOf(Math.max(0, start - offset)), "-to", String.valueOf(end - offset),
                    "-i", window.video()));
            int[] at = cell(i, windows.size());
            graph.add("[" + i + ":v]setpts=(PTS-STARTPTS)/" + speed + ",fps=30,scale=" + CELL_W + ":" + CELL_H + "[w" + i + "]");
            graph.add("[o" + i + "][w" + i + "]overlay=x=" + at[0] + ":y=" + at[1] + ":shortest=1[o" + (i + 1) + "]");
            labels.add(boxed(window.title() + ": " + window.subtitle(), at[0] + CELL_W - 10, at[1] + CELL_H - 34));
        }
        var overlays = new ArrayList<>(labels);
        // Between the windows, where it covers nothing that matters
        if (badge != null) overlays.add(badge(badge, "(w-text_w)/2", windows.size() <= 2 ? "150" : "(" + H + "-text_h)/2"));
        overlays.addAll(List.of(captions));
        if (hold > 0) overlays.addFirst("tpad=stop_mode=clone:stop_duration=" + hold);
        graph.add("[o" + windows.size() + "]" + String.join(",", overlays) + "[out]");
        var args = new ArrayList<>(inputs);
        args.addAll(List.of("-filter_complex", String.join(";", graph), "-map", "[out]"));
        encode(args);
    }

    /** Screenshots side by side, with a title above each, held for the given seconds. */
    void stills(List<String> images, List<String> titles, double seconds, String caption) throws Exception {
        var inputs = new ArrayList<String>();
        var graph = new ArrayList<String>();
        var overlays = new ArrayList<String>();
        graph.add("color=c=" + BG + ":s=" + W + "x" + (H + STRIP) + ":r=30:d=" + seconds + "[o0]");
        for (int i = 0; i < images.size(); i++) {
            inputs.addAll(List.of("-loop", "1", "-t", String.valueOf(seconds), "-i", "/v/" + images.get(i)));
            int[] at = cell(i, images.size());
            graph.add("[" + i + ":v]scale=" + CELL_W + ":" + CELL_H + "[w" + i + "]");
            graph.add("[o" + i + "][w" + i + "]overlay=x=" + at[0] + ":y=" + at[1] + ":shortest=1[o" + (i + 1) + "]");
            overlays.add(label(titles.get(i), at[0], at[1] - 44, 26, "white", false));
        }
        overlays.add(caption);
        graph.add("[o" + images.size() + "]" + String.join(",", overlays) + "[out]");
        var args = new ArrayList<>(inputs);
        args.addAll(List.of("-filter_complex", String.join(";", graph), "-map", "[out]"));
        encode(args);
    }

    /** A label on a dark box, its bottom right corner at x, y. */
    String boxed(String s, int right, int bottom) throws IOException {
        return "drawtext=fontfile=" + FONT + ":textfile=" + text(s) + ":fontcolor=white:fontsize=16"
                + ":x=" + right + "-text_w:y=" + bottom + "-text_h:box=1:boxcolor=black@0.65:boxborderw=7";
    }

    String caption(String s) throws IOException {
        return caption(s, -1, -1);
    }

    /** A caption in the strip below the picture, shown between the given seconds of the segment (always if negative). */
    String caption(String s, double from, double to) throws IOException {
        String enable = from >= 0 ? ":enable='between(t," + from + "," + to + ")'" : "";
        return "drawtext=fontfile=" + BOLD + ":textfile=" + text(s) + ":fontcolor=white:fontsize=30:line_spacing=10"
                + ":x=(w-text_w)/2:y=" + H + "+(" + STRIP + "-text_h)/2" + enable;
    }

    /** A label at x, y; a negative x aligns it to the right edge. */
    String label(String s, int x, int y, int size, String color, boolean boxed) throws IOException {
        return "drawtext=fontfile=" + FONT + ":textfile=" + text(s) + ":fontcolor=" + color + ":fontsize=" + size
                + ":x=" + (x < 0 ? "w-text_w-18" : x) + ":y=" + y
                + (boxed ? ":box=1:boxcolor=black@0.65:boxborderw=8" : "");
    }

    String badge(String s) throws IOException {
        return badge(s, String.valueOf(W / 2 - 160), "24");
    }

    /** A speed-up note at x, y (ffmpeg expressions). */
    String badge(String s, String x, String y) throws IOException {
        return "drawtext=fontfile=" + BOLD + ":textfile=" + text(s) + ":fontcolor=black:fontsize=22"
                + ":box=1:boxcolor=0xf9e2af:boxborderw=10:x=" + x + ":y=" + y;
    }

    /** Texts go through files, so they need no escaping in the filters. */
    String text(String s) throws IOException {
        String name = "t" + (++texts) + ".txt";
        Files.writeString(OUT.resolve(name), s);
        return "/f/" + name;
    }

    void encode(List<String> input) throws Exception {
        String out = "s%02d.mp4".formatted(segments.size());
        var args = new ArrayList<>(input);
        args.addAll(List.of("-r", "30", "-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p", "-an", "/f/" + out));
        docker(args.toArray(String[]::new));
        segments.add(out);
    }

    void docker(String... ffmpegArgs) throws Exception {
        var command = new ArrayList<>(List.of("docker", "run", "--rm", "-v", OUT + ":/f", "-v", VIDEOS + ":/v",
                FFMPEG, "-loglevel", "error", "-y"));
        command.addAll(List.of(ffmpegArgs));
        if (new ProcessBuilder(command).inheritIO().start().waitFor() != 0) {
            throw new IllegalStateException("ffmpeg failed: " + command);
        }
    }
}
