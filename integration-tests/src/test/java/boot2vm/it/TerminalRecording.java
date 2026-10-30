package boot2vm.it;

import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * What a command showed in its terminal, recorded with script(1): the output and
 * how long after the previous chunk each chunk came. {@link #play} replays it in
 * xterm.js in a browser page, so that it can be recorded as a video like the
 * browser tests are.
 */
record TerminalRecording(String command, int exitCode, String output, byte[] typescript, String timing) {

    static final int COLUMNS = 120, ROWS = 36;

    static final String PLAYER = """
            <!doctype html>
            <html>
            <head>
            <meta charset="utf-8">
            <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@xterm/xterm@5.5.0/css/xterm.css">
            <script src="https://cdn.jsdelivr.net/npm/@xterm/xterm@5.5.0/lib/xterm.js"></script>
            <style>body { margin: 0; padding: 12px; background: #1e1e1e; }</style>
            </head>
            <body>
            <div id="terminal"></div>
            <script>
            const term = new Terminal({cols: %d, rows: %d, fontSize: 15, theme: {background: '#1e1e1e'}});
            term.open(document.getElementById('terminal'));
            const sleep = ms => new Promise(r => setTimeout(r, ms));
            const write = data => new Promise(r => term.write(data, r));
            window.play = async session => {
                await write(session.prompt);
                await sleep(800);
                for (const c of session.command) {
                    await write(c);
                    await sleep(60);
                }
                await sleep(500);
                await write('\\r\\n');
                for (const [delay, data] of session.chunks) {
                    await sleep(delay * 1000);
                    await write(Uint8Array.from(atob(data), c => c.charCodeAt(0)));
                }
                await sleep(3000);
                window.done = true;
            };
            </script>
            </body>
            </html>
            """.formatted(COLUMNS, ROWS);

    /** Replay in real time in the page, showing the prompt and typing the command first. */
    void play(Page page, String prompt, String typedCommand) {
        page.setContent(PLAYER);
        page.waitForFunction("typeof window.play === 'function'");
        page.evaluate("session => { window.play(session); }", Map.of(
                "prompt", prompt,
                "command", typedCommand,
                "chunks", chunks()));
        double seconds = chunks().stream().mapToDouble(c -> (Double) c.get(0)).sum();
        page.waitForFunction("window.done === true", null,
                new Page.WaitForFunctionOptions().setTimeout((seconds + 60) * 1000));
    }

    /** [delay in seconds, base64 bytes] pairs, from the classic timing format: "delay bytes" per line. */
    List<List<Object>> chunks() {
        // The typescript starts with a "Script started on ..." line that the timing doesn't count
        int offset = 0;
        while (typescript[offset] != '\n') offset++;
        offset++;
        var chunks = new ArrayList<List<Object>>();
        for (String line : timing.lines().toList()) {
            String[] parts = line.trim().split(" ");
            if (parts.length != 2) continue;
            int length = Math.min(Integer.parseInt(parts[1]), typescript.length - offset);
            byte[] bytes = new byte[length];
            System.arraycopy(typescript, offset, bytes, 0, length);
            offset += length;
            chunks.add(List.of(Double.parseDouble(parts[0]), Base64.getEncoder().encodeToString(bytes)));
        }
        return chunks;
    }
}
