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
 * the waits sped up and the two users side by side, in sync. Runs ffmpeg in Docker.
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
    /** The terminal replay types the command before the recorded output starts. */
    static final double TYPING = 2.35;

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
            timeline.put(parts[1], Long.parseLong(parts[0]));
        }
        double announced = mainT("new version announced"), clicked = mainT("update clicked"), done = mainT("deploy done");
        double termAnnounced = termT("new version announced"), termDone = termT("deploy done");

        card("boot2vm", "Zero-downtime deploys of Vaadin apps to your own server\n(raw material from the integration tests)", 4);

        card("1. Set up the server", "One form: host, app user, blue-green deployment with graceful drain", 3);
        clip("1-init.webm", 0, 7, 1, 0, 0, null,
                caption("Fill in the host and the app user, turn on graceful blue-green deployment"));
        clip("1-init.webm", 7, 51, 5, 0, 0, "5× speed",
                caption("The server gets a JDK, Caddy, a firewall and the app.\nThe window follows the progress."));
        clip("1-init.webm", 51, 53.1, 1, 2.5, 0, null, caption("Ready: the app is running"));

        card("2. Deploy a new version", "Two users are in the app while the developer deploys", 3);
        sideBySide(1, announced - 1, 3, caption("Both users work on version 1 (green slot)"));
        clip("2-deploy-terminal.webm", 0, termAnnounced + 0.8, 1.5, 0, 40, "1.5× speed",
                caption("The developer runs the deploy: build, upload,\nstart version 2 next to version 1, health check"));
        sideBySide(announced - 1, done + 1.5, 1,
                caption("Version 2 is up: the users are told about it", 0, 1.2),
                caption("A sees a countdown and keeps working, B is already on version 2", 1.2, clicked - announced + 1),
                caption("A clicks \"Update now!\" when it suits them", clicked - announced + 1, done - announced + 2.5));
        clip("2-deploy-terminal.webm", termAnnounced, termDone + 2, 2, 2, 40, "2× speed",
                caption("The deploy waits until no one is left on version 1,\nthen stops it. No one lost their work."));

        card("boot2vm", "Init with a form, deploy with one command,\nusers move to the new version when it suits them", 4);

        Files.writeString(OUT.resolve("list.txt"), segments.stream().map(s -> "file '/f/" + s + "'\n").collect(Collectors.joining()));
        docker("-f", "concat", "-safe", "0", "-i", "/f/list.txt", "-c", "copy", "-movflags", "+faststart",
                "/f/boot2vm-graceful-deploy.mp4");
        System.out.println("Wrote " + OUT.resolve("boot2vm-graceful-deploy.mp4"));
    }

    /** Seconds into the main user's video, which started first of the two users. */
    double mainT(String event) {
        return (timeline.get(event) - timeline.get("3-user-main")) / 1000.0;
    }

    /** Seconds into the terminal replay. */
    double termT(String event) {
        return TYPING + (timeline.get(event) - timeline.get("2-deploy-terminal")) / 1000.0;
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
    void clip(String video, double start, double end, double speed, double freeze, int top, String badge,
              String... captions) throws Exception {
        var filters = new ArrayList<>(List.of("setpts=(PTS-STARTPTS)/" + speed, "fps=30",
                "pad=" + W + ":" + (H + STRIP) + ":0:" + top + ":color=" + BG));
        if (freeze > 0) filters.add("tpad=stop_mode=clone:stop_duration=" + freeze);
        if (badge != null) {
            filters.add("drawtext=fontfile=" + BOLD + ":textfile=" + text(badge) + ":fontcolor=black:fontsize=22"
                    + ":box=1:boxcolor=0xf9e2af:boxborderw=10:x=w-text_w-24:y=20");
        }
        filters.addAll(List.of(captions));
        encode(List.of("-ss", String.valueOf(start), "-to", String.valueOf(end), "-i", "/v/" + video,
                "-vf", String.join(",", filters)));
    }

    /** Both users at the same moment: start and end are seconds into the main user's video. */
    void sideBySide(double start, double end, double speed, String... captions) throws Exception {
        int w = 626, h = 391, y = 252;
        double infoLag = (timeline.get("4-user-info") - timeline.get("3-user-main")) / 1000.0;
        var overlays = new ArrayList<>(List.of(
                label("User A, in the main view", 9, 157, 26, "white"),
                label("Kept on the version they started with,\nuntil they choose to update", 9, 194, 18, "0xcdd6f4"),
                label("User B, in the info view", W - w - 9, 157, 26, "white"),
                label("@AutoUpgrade: nothing to lose here,\nmoved to a new version right away", W - w - 9, 194, 18, "0xcdd6f4")));
        overlays.addAll(List.of(captions));
        String graph = String.join(";",
                "[0:v]setpts=(PTS-STARTPTS)/" + speed + ",fps=30,scale=" + w + ":" + h + "[a]",
                "[1:v]setpts=(PTS-STARTPTS)/" + speed + ",fps=30,scale=" + w + ":" + h + "[b]",
                "color=c=" + BG + ":s=" + W + "x" + (H + STRIP) + ":r=30[bg]",
                "[bg][a]overlay=x=9:y=" + y + ":shortest=1[l]",
                "[l][b]overlay=x=" + (W - w - 9) + ":y=" + y + ":shortest=1," + String.join(",", overlays) + "[out]");
        encode(List.of("-ss", String.valueOf(start), "-to", String.valueOf(end), "-i", "/v/3-user-main.webm",
                "-ss", String.valueOf(start - infoLag), "-to", String.valueOf(end - infoLag), "-i", "/v/4-user-info.webm",
                "-filter_complex", graph, "-map", "[out]"));
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

    String label(String s, int x, int y, int size, String color) throws IOException {
        return "drawtext=fontfile=" + FONT + ":textfile=" + text(s) + ":fontcolor=" + color + ":fontsize=" + size
                + ":x=" + x + ":y=" + y;
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
