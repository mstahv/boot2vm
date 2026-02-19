# boot2vm

Raw virtual machines are very cheap and efficient way to deploy apps — no containers, no orchestrators, no cloud vendor lock-in.

**boot2vm** is a single-file [JBang](https://www.jbang.dev/) tool that sets up a fresh Ubuntu VM and deploys Spring Boot apps to it. Just SSH, rsync, systemd, and Caddy (as reverse proxxy). That's it.

## Installation

```bash
jbang app install https://github.com/mstahv/boot2vm/blob/main/Deploy.java
```

This adds `Deploy` to your PATH. Alternatively, run directly without installing:

```bash
jbang https://github.com/mstahv/boot2vm/blob/main/Deploy.java <command>
```

## Prerequisites

 * A VM running Ubuntu with a DNS name pointing to it
 * SSH access to the server with private key authentication (root or a user with sudo)

## Usage

Run commands from your Spring Boot project directory:

```bash
# First time: interactively configure and set up the server
Deploy init

# Deploy (and redeploy) the app
Deploy deploy

# Check logs
Deploy logs
```

### `Deploy init`

Interactive one-time setup. Prompts for connection details, writes a `vmhosting.conf` in the current directory, then provisions the server:

```
Host: myapp.example.com
App user [myapp]:
Domain [myapp.example.com]:
SSH public key [~/.ssh/id_rsa.pub]:
Admin SSH user [root]:
```

Only the host is required — sensible defaults are derived for the rest. The server setup:

 1. Configures **unattended-upgrades** for automatic nightly security updates with automatic reboot when required
 2. Installs **JDK 25** (Eclipse Adoptium / Temurin)
 3. Creates the app user with SSH authorized key copied from the admin user
 4. Creates the working directory `/home/$USER/app`
 5. Installs a **systemd service** that runs the app on boot and restarts on failure
 6. Installs **Caddy** as a reverse proxy with automatic HTTPS

### `Deploy deploy`

Builds and deploys the app:

 1. Runs `./mvnw -DskipTests package` (or `./gradlew -x test bootJar`, auto-detected)
 2. Extracts the fat jar locally for [efficient deployment](https://docs.spring.io/spring-boot/reference/packaging/efficient.html)
 3. Rsyncs to the server — only changed files are transferred (dependency jars in `lib/` rarely change)
 4. Restarts the systemd service

### `Deploy logs`

Tails the application journal output via SSH.

## Configuration: `vmhosting.conf`

Created by `Deploy init`. Placed in the Spring Boot project root, simple key=value format:

```
HOST=myapp.example.com
USER=myapp
DOMAIN=myapp.example.com
SSH_KEY=~/.ssh/id_rsa.pub
ADMIN_USER=root
```

 * `HOST` – VM hostname or IP for SSH/rsync connections
 * `USER` – Linux user that will own and run the app (created during init)
 * `DOMAIN` – Domain name for Caddy HTTPS (defaults to HOST)
 * `SSH_KEY` – Path to SSH public key (private key is derived automatically)
 * `ADMIN_USER` – SSH user for server admin commands (uses sudo if not root)

## Demo: from zero to production in 60 seconds

Create a Vaadin web app, initialize a VM, and deploy — all in one go:

```bash
# Install boot2vm
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

 * Support Quarkus apps
 * Blue-green deployment for zero-downtime restarts
 * Sticky sessions allowing old users to stay on the previous version during rollout
