# boot2vm

Raw virtual machines are very cheap and efficient way to deploy apps — no containers, no orchestrators, no cloud vendor lock-in.

**boot2vm** is a single-file [JBang](https://www.jbang.dev/) tool that configures a fresh server for simple hosting setup and deploys Spring Boot and Quarkus apps to it. Just SSH, rsync, systemd, and Caddy (as reverse proxy). That's it.

While the name says "VM", the tool is not technically tied to virtual machines or Ubuntu — any Debian-based server with `apt` should work. Tested successfully against Raspberry Pi OS as well.

### Pros

 * No OCI containers or Kubernetes — zero container overhead, no image registry, no need to think about how to build, ship, or compose images.
 * Builds run on your CI server or workstation, not on the deployment target (unlike some self-hosted PaaS solutions running on the same machine).
 * Very fast redeployments — only your own code gets rsynced, dependency jars stay untouched, then the JVM restarts. Works great with the exploded-jar format of Spring Boot and Quarkus.
 * Easy to inspect and debug — the app is just a JVM process; SSH in and use familiar Linux tools (`ps`, `top`, `journalctl`) directly, with no container abstraction layer in the way.
 * Minimal moving parts.

### Cons

 * No container isolation — slightly reduced security boundary, though this setup assumes a dedicated VM per service anyway.
 * One server per service.
 * No horizontal scaling.
 * No versioned rollback — there is no previous image to revert to; rolling back requires rebuilding an older artifact from source.
 * No resource limits — unlike containers, there is no built-in CPU or memory cap per service; a runaway process can starve the whole machine.

## Installation

```bash
jbang app install https://github.com/mstahv/boot2vm/blob/main/Deploy.java
```

This adds `Deploy` to your PATH. Alternatively, fork the project, customize it to your needs and install. Or run directly without installing:

```bash
jbang https://github.com/mstahv/boot2vm/blob/main/Deploy.java <command>
```

*TODO, publish to jbang store...*


## Prerequisites

 * A Debian-based server (Ubuntu, Raspberry Pi OS, etc.) with a DNS name or IP
 * SSH access to the server with private key authentication (root or a user with sudo)

## Usage

Run commands from your project directory:

```bash
# First time: interactively configure and set up the server
Deploy init

# Deploy (and redeploy) the app
Deploy

# Check logs
Deploy logs

# Grant deploy access to a colleague or CI server
Deploy add-key ~/.ssh/colleague_id_rsa.pub
```

### `Deploy init`

Interactive one-time setup. Prompts for connection details, writes a `vmhosting.conf` in the current directory, then provisions the server:

```
Host: myapp.example.com
App user [myapp]:
Domain [myapp.example.com]:
SSH public key [~/.ssh/id_rsa.pub]:
Admin SSH user [root]:
HTTPS [yes]:
Reverse proxy (caddy/none) [caddy]:
App type (spring-boot/quarkus) [spring-boot]:
Only the host is required — sensible defaults are derived for the rest. The server setup:

 1. Configures **unattended-upgrades** for automatic nightly security updates with automatic reboot when required
 2. Installs **JDK 25** (Eclipse Adoptium / Temurin)
 3. Creates the app user with SSH authorized key copied from the admin user
 4. Creates the working directory `/home/$USER/app`
 5. Installs a **systemd service** that runs the app on boot and restarts on failure
 6. Installs **Caddy** as a reverse proxy with automatic HTTPS

### `Deploy deploy` (default)

Builds and deploys the app. This is the default command — running `Deploy` (with no arguments is equivalent to `Deploy deploy`).

 1. Runs the build (`./mvnw package`, `./gradlew bootJar` or `quarkusBuild`, auto-detected)
 2. **Spring Boot:** extracts the fat jar for [efficient rsync](https://docs.spring.io/spring-boot/reference/packaging/efficient.html); **Quarkus:** uses the already-exploded `target/quarkus-app` directly
 3. Rsyncs to the server — only changed files are transferred (dependency jars rarely change)

### `Deploy logs`

Tails the application journal output via SSH.

### `Deploy add-key [file]`

Adds an SSH public key to the app user's `authorized_keys` on the server, granting deploy access to a colleague or CI server. Pass a key file path as argument, or run without arguments to paste a key directly. Duplicate keys are detected and skipped.

### `Deploy clean`

Removes the deployed application from the server: stops and removes the systemd service, resets the Caddy config (if used), and deletes the app user and its home directory. JDK, Caddy, and other system packages are left installed. Useful for testing or starting fresh — run `Deploy init` again afterwards to re-provision.

ADMIN_USER=root
PROXY=caddy
APP_TYPE=spring-boot
 * `SSH_KEY` – Path to SSH public key (private key is derived automatically)
 * `ADMIN_USER` – SSH user for server admin commands (uses sudo if not root)
 * `PROXY` – Reverse proxy to install: `caddy` (default) or `none`
 * `APP_TYPE` – Application type: `spring-boot` (default) or `quarkus` (auto-detected from build files)
jbang app install https://github.com/mstahv/boot2vm/blob/main/Deploy.java

# Scaffold a new Vaadin + Spring Boot app
mvn -B archetype:generate \
  -DarchetypeGroupId=com.vaadin \
  -DarchetypeArtifactId=vaadin-archetype-spring-application \
  -DarchetypeVersion=LATEST \
  -DgroupId=org.example \
  -DartifactId=my-webapp \
  -Dversion=1.0-SNAPSHOT
cd my-webapp

# Set up the server and deploy (init automatically runs first deploy)
Deploy init       # enter: somehost.somewhere.com (then Enter through the defaults)
                  # ... builds, syncs, starts — open https://somehost.somewhere.com
Deploy logs       # watch it run
```

## For later

 * Nginx as an alternative reverse proxy option
 * Blue-green deployment for zero-downtime restarts
 * Sticky sessions allowing old users to stay on the previous version during rollout
