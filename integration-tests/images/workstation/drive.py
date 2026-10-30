#!/usr/bin/env python3
"""Answer the prompts of an interactive command, like expect.

Usage: drive.py SCRIPT -- COMMAND...

SCRIPT is a JSON list of {"expect": regex, "send": text} steps. For each step
the output is read until the regex matches it, then text and a newline are
typed. A step without "send" only waits. After the last step the command runs
to completion. All output is echoed, and the exit code is the command's own,
or 124 when a step times out.
"""
import json, os, pty, re, select, sys, time

STEP_TIMEOUT = float(os.environ.get("DRIVE_STEP_TIMEOUT", "60"))
TOTAL_TIMEOUT = float(os.environ.get("DRIVE_TOTAL_TIMEOUT", "1200"))

steps = json.load(open(sys.argv[1]))
command = sys.argv[sys.argv.index("--") + 1:]

pid, fd = pty.fork()
if pid == 0:
    os.execvp(command[0], command)

buffer = ""
started = time.time()

def read(timeout):
    """Read what is available within timeout; None once the command has exited."""
    global buffer
    ready, _, _ = select.select([fd], [], [], timeout)
    if not ready:
        return ""
    try:
        data = os.read(fd, 65536).decode(errors="replace")
    except OSError:
        return None
    if not data:
        return None
    sys.stdout.write(data)
    sys.stdout.flush()
    buffer += data
    return data

def fail(message):
    tail = buffer[-2000:]
    sys.stdout.write(f"\n[drive] {message}\n[drive] last output:\n{tail}\n")
    os.kill(pid, 9)
    sys.exit(124)

for number, step in enumerate(steps, 1):
    pattern = re.compile(step["expect"])
    deadline = time.time() + step.get("timeout", STEP_TIMEOUT)
    while True:
        match = pattern.search(buffer)
        if match:
            buffer = buffer[match.end():]
            break
        if time.time() > deadline:
            fail(f"step {number}: no match for {step['expect']!r}")
        if read(0.5) is None:
            fail(f"step {number}: command exited before {step['expect']!r}")
    if "send" in step:
        os.write(fd, (step["send"] + "\n").encode())

while read(1) is not None:
    if time.time() - started > TOTAL_TIMEOUT:
        fail("command did not finish")
_, status = os.waitpid(pid, 0)
sys.exit(os.waitstatus_to_exitcode(status))
