///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17+

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Deploy {

    static String host, user, domain, sshKey, adminUser, proxy, appType, jdkProvider;
    static String httpsMode = "yes";
    static String jvmOpts = "", userGroups = "";
    static boolean blueGreen;
    static boolean gracefulDrain;
    static boolean webService = true;
    static int port = 8080;
    static String slotCookie = "X-Slot", managementPort = "", notifyPath = "/actuator/new-version", activeUsersPath = "/actuator/active-users";
    static int drainTimeout = 300;

    static final String SETUP_SCRIPT = """
            #!/bin/bash
            set -euo pipefail
            APP_USER="$1"
            DOMAIN="$2"
            ADMIN_USER="${3:-root}"
            HTTPS="${4:-yes}"
            PROXY="${5:-caddy}"
            APP_TYPE="${6:-spring-boot}"
            BLUE_GREEN="${7:-no}"
            MANAGEMENT_PORT_BLUE="${8:-0}"
            FIREWALL="${9:-yes}"
            EXPOSE_NODES="${10:-no}"
            WEB_SERVICE="${11:-yes}"
            JDK_PROVIDER="${12:-temurin}"  # temurin or zulu
            PORT="${13:-8080}"             # app port; blue-green green slot uses PORT+1
            JVM_OPTS="${14:-}"             # extra JVM flags, exposed to the unit as JAVA_OPTS
            USER_GROUPS="${15:-}"          # comma-separated extra groups for the app user (gpio,i2c,...)

            # Build Caddy site address (supports multiple domains)
            TLS_DIRECTIVE=""
            if [ "$HTTPS" = "internal" ]; then TLS_DIRECTIVE="tls internal"; fi
            if [ "$HTTPS" != "no" ]; then
                SITE_ADDR="$DOMAIN"
            else
                SITE_ADDR=""
                for d in $DOMAIN; do SITE_ADDR="$SITE_ADDR http://$d"; done
                SITE_ADDR="${SITE_ADDR# }"
            fi

            echo "=== Setting up server for user '$APP_USER' with domain '$DOMAIN' ==="

            # Wait for any background apt/dpkg process to release the lock
            echo "--- Waiting for package manager lock ---"
            while ! flock -n /var/lib/dpkg/lock-frontend true 2>/dev/null; do
                echo "  Package manager is busy, retrying in 10s ..."
                sleep 10
            done

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

            # 2. Install JDK 25 (Eclipse Adoptium / Temurin or Azul Zulu)
            JDK_PROVIDER="$(printf '%s' "$JDK_PROVIDER" | tr '[:upper:]' '[:lower:]')"
            echo "--- Installing JDK 25 (provider: $JDK_PROVIDER) ---"
            case "$JDK_PROVIDER" in
                zulu)
                    apt-get install -y gnupg ca-certificates curl
                    curl -s https://repos.azul.com/azul-repo.key | gpg --dearmor -o /usr/share/keyrings/azul.gpg
                    chmod 644 /usr/share/keyrings/azul.gpg
                    echo "deb [signed-by=/usr/share/keyrings/azul.gpg] https://repos.azul.com/zulu/deb stable main" \\
                        > /etc/apt/sources.list.d/zulu.list
                    apt-get update
                    apt-get install -y zulu25-jdk
                    ;;
                temurin)
                    apt-get install -y wget apt-transport-https gpg
                    wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \\
                        | gpg --dearmor --yes -o /usr/share/keyrings/adoptium.gpg
                    echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" \\
                        > /etc/apt/sources.list.d/adoptium.list
                    apt-get update
                    apt-get install -y temurin-25-jdk
                    ;;
                *)
                    echo "Unsupported JDK_PROVIDER: '$JDK_PROVIDER'. Supported values are: temurin, zulu." >&2
                    exit 1
                    ;;
            esac

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

            # Extra groups for hardware access (gpio, i2c, spi, dialout, ...). Added to the
            # user itself rather than via SupplementaryGroups= in the unit, so the same
            # access works when logging in as the app user to test things by hand.
            if [ -n "$USER_GROUPS" ]; then
                for GRP in ${USER_GROUPS//,/ }; do
                    if getent group "$GRP" >/dev/null; then
                        usermod -aG "$GRP" "$APP_USER"
                        echo "Added $APP_USER to group $GRP"
                    else
                        echo "WARNING: group '$GRP' does not exist on this server, skipping"
                    fi
                done
            fi

            # 4. Create application working directory/directories
            echo "--- Creating app directory/directories ---"
            if [ "$BLUE_GREEN" = "yes" ]; then
                mkdir -p "/home/$APP_USER/app-blue" "/home/$APP_USER/app-green"
                chown "$APP_USER:$APP_USER" "/home/$APP_USER/app-blue" "/home/$APP_USER/app-green"
                echo "blue" > "/home/$APP_USER/active"
                chown "$APP_USER:$APP_USER" "/home/$APP_USER/active"
            else
                mkdir -p "/home/$APP_USER/app"
                chown "$APP_USER:$APP_USER" "/home/$APP_USER/app"
            fi

            # 5. Systemd service(s) for the application
            echo "--- Installing systemd service(s) ---"
            # Behind a reverse proxy the app would otherwise see every connection as
            # coming from localhost — tell the framework to trust X-Forwarded-* headers
            # so real client IPs and the https scheme are visible to the app.
            FWD_ENV_LINES=""
            if [ "$WEB_SERVICE" = "yes" ] && [ "$PROXY" != "none" ]; then
                case "$APP_TYPE" in
                    spring-boot)
                        FWD_ENV_LINES="Environment=SERVER_FORWARDED_HEADERS_STRATEGY=native"
                        ;;
                    quarkus)
                        FWD_ENV_LINES=$'Environment=QUARKUS_HTTP_PROXY_PROXY_ADDRESS_FORWARDING=true\\nEnvironment=QUARKUS_HTTP_PROXY_ALLOW_X_FORWARDED=true'
                        ;;
                esac
            fi
            if [ "$BLUE_GREEN" = "yes" ]; then
                for SLOT in blue green; do
                    if [ "$SLOT" = "blue" ]; then SLOT_PORT=$PORT; else SLOT_PORT=$((PORT + 1)); fi
                    if [ "$MANAGEMENT_PORT_BLUE" != "0" ]; then
                        if [ "$SLOT" = "blue" ]; then
                            MGMT_ENV_LINE="Environment=MANAGEMENT_SERVER_PORT=$MANAGEMENT_PORT_BLUE"
                        else
                            MGMT_ENV_LINE="Environment=MANAGEMENT_SERVER_PORT=$((MANAGEMENT_PORT_BLUE + 1))"
                        fi
                    else
                        MGMT_ENV_LINE=""
                    fi
                    if [ "$APP_TYPE" = "quarkus" ]; then
                        EXEC_START="/usr/bin/java \\$JAVA_OPTS -jar /home/$APP_USER/app-$SLOT/quarkus-app/quarkus-run.jar"
                    else
                        EXEC_START="/usr/bin/java \\$JAVA_OPTS -jar /home/$APP_USER/app-$SLOT/$APP_USER.jar"
                    fi
                    cat > "/etc/systemd/system/$APP_USER-$SLOT.service" << UNIT
            [Unit]
            Description=Java Application ($APP_USER/$SLOT)
            After=network.target

            [Service]
            Type=simple
            User=$APP_USER
            WorkingDirectory=/home/$APP_USER/app-$SLOT
            Environment=SERVER_PORT=$SLOT_PORT
            Environment=QUARKUS_HTTP_PORT=$SLOT_PORT
            Environment=APP_SLOT=$SLOT
            $MGMT_ENV_LINE
            $FWD_ENV_LINES
            Environment="JAVA_OPTS=$JVM_OPTS"
            EnvironmentFile=-/home/$APP_USER/.env
            ExecStart=$EXEC_START
            Restart=on-failure
            RestartSec=10

            [Install]
            WantedBy=multi-user.target
            UNIT
                done
                systemctl daemon-reload
                systemctl enable "$APP_USER-blue"
            else
                if [ "$APP_TYPE" = "quarkus" ]; then
                    EXEC_START="/usr/bin/java \\$JAVA_OPTS -jar /home/$APP_USER/app/quarkus-app/quarkus-run.jar"
                else
                    EXEC_START="/usr/bin/java \\$JAVA_OPTS -jar /home/$APP_USER/app/$APP_USER.jar"
                fi
                PORT_ENV_LINES=""
                if [ "$WEB_SERVICE" = "yes" ]; then
                    PORT_ENV_LINES=$'Environment=SERVER_PORT='"$PORT"$'\\nEnvironment=QUARKUS_HTTP_PORT='"$PORT"
                fi
                cat > "/etc/systemd/system/$APP_USER.service" << UNIT
            [Unit]
            Description=Java Application ($APP_USER)
            After=network.target

            [Service]
            Type=simple
            User=$APP_USER
            WorkingDirectory=/home/$APP_USER/app
            $PORT_ENV_LINES
            $FWD_ENV_LINES
            Environment="JAVA_OPTS=$JVM_OPTS"
            EnvironmentFile=-/home/$APP_USER/.env
            ExecStart=$EXEC_START
            Restart=on-failure
            RestartSec=10

            [Install]
            WantedBy=multi-user.target
            UNIT
                systemctl daemon-reload
                systemctl enable "$APP_USER"
            fi

            # 6. Install reverse proxy (if configured and this is a web service)
            if [ "$WEB_SERVICE" != "yes" ]; then
                echo "--- Not a web service; skipping reverse proxy ---"
            elif [ "$PROXY" = "caddy" ]; then
                echo "--- Installing Caddy ---"
                apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl
                curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \\
                    | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
                curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \\
                    > /etc/apt/sources.list.d/caddy-stable.list
                apt-get update
                apt-get install -y caddy
                # Per-app site files allow multiple apps behind the same Caddy instance.
                # The main Caddyfile only imports them; each app owns /etc/caddy/sites/<user>.caddy.
                mkdir -p /etc/caddy/sites
                if ! grep -qF 'import /etc/caddy/sites' /etc/caddy/Caddyfile 2>/dev/null; then
                    echo 'import /etc/caddy/sites/*.caddy' > /etc/caddy/Caddyfile
                fi
                cat > "/etc/caddy/sites/$APP_USER.caddy" << CADDY
            $SITE_ADDR {
                $TLS_DIRECTIVE
                reverse_proxy localhost:$PORT
            }
            CADDY
                systemctl reload caddy
            else
                echo "--- Skipping reverse proxy installation ---"
            fi

            # 7. Configure firewall
            if [ "$FIREWALL" = "yes" ]; then
                echo "--- Configuring firewall (ufw) ---"
                apt-get install -y ufw
                # No 'ufw reset' here — rules are added idempotently so that
                # setting up a second app on the same server keeps existing rules.
                ufw default deny incoming
                ufw default allow outgoing
                ufw allow ssh
                if [ "$WEB_SERVICE" = "yes" ]; then
                    ufw allow 80/tcp
                    ufw allow 443/tcp
                    # Open any custom ports used in site addresses (e.g. example.com:8443)
                    for d in $DOMAIN; do
                        hp="${d#*://}"
                        case "$hp" in
                            *:*) ufw allow "${hp##*:}/tcp" ;;
                        esac
                    done
                    if [ "$EXPOSE_NODES" = "yes" ]; then
                        ufw allow "$PORT/tcp"
                        if [ "$BLUE_GREEN" = "yes" ]; then
                            ufw allow "$((PORT + 1))/tcp"
                        fi
                    fi
                fi
                ufw --force enable
                if [ "$WEB_SERVICE" != "yes" ]; then
                    echo "Firewall enabled: SSH allowed inbound; all else blocked (non-web service)"
                elif [ "$EXPOSE_NODES" = "yes" ]; then
                    echo "Firewall enabled: SSH, 80/tcp, 443/tcp and app port(s) allowed inbound"
                else
                    echo "Firewall enabled: SSH, 80/tcp, 443/tcp allowed inbound; all else blocked"
                fi
            else
                echo "--- Skipping firewall configuration ---"
            fi

            echo "=== Server setup complete! ==="
            """;

    static final String BLUE_GREEN_SWAP_SCRIPT = """
            #!/bin/bash
            set -euo pipefail
            APP_USER="$1"
            PROXY="${2:-caddy}"
            HTTPS="${3:-yes}"
            DOMAIN="$4"
            MANAGEMENT_PORT_BLUE="${5:-0}"
            PORT="${6:-8080}"

            # Build Caddy site address (supports multiple domains)
            TLS_DIRECTIVE=""
            if [ "$HTTPS" = "internal" ]; then TLS_DIRECTIVE="tls internal"; fi
            if [ "$HTTPS" != "no" ]; then
                SITE_ADDR="$DOMAIN"
            else
                SITE_ADDR=""
                for d in $DOMAIN; do SITE_ADDR="$SITE_ADDR http://$d"; done
                SITE_ADDR="${SITE_ADDR# }"
            fi

            LOCK_DIR="/home/$APP_USER/deploy.lock"
            ACTIVE_FILE="/home/$APP_USER/active"

            # Acquire lock atomically — mkdir is atomic on local filesystems
            if ! mkdir "$LOCK_DIR" 2>/dev/null; then
                echo "ERROR: Another deploy is in progress. Remove $LOCK_DIR to force-unlock." >&2
                exit 1
            fi
            trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT

            # Read current active slot (default to blue if file missing)
            ACTIVE=$(cat "$ACTIVE_FILE" 2>/dev/null || echo blue)
            if [ "$ACTIVE" = "blue" ]; then
                INACTIVE="green"
                ACTIVE_PORT=$PORT
                INACTIVE_PORT=$((PORT + 1))
            else
                INACTIVE="blue"
                ACTIVE_PORT=$((PORT + 1))
                INACTIVE_PORT=$PORT
            fi

            # Resolve management port for the new (inactive) slot
            if [ "$MANAGEMENT_PORT_BLUE" != "0" ]; then
                if [ "$INACTIVE" = "blue" ]; then
                    INACTIVE_MGMT_PORT=$MANAGEMENT_PORT_BLUE
                else
                    INACTIVE_MGMT_PORT=$((MANAGEMENT_PORT_BLUE + 1))
                fi
                HEALTH_URL="http://localhost:$INACTIVE_MGMT_PORT/actuator/health"
            else
                HEALTH_URL="http://localhost:$INACTIVE_PORT/"
            fi

            ACTIVE_LABEL="$ACTIVE(:$ACTIVE_PORT)"
            INACTIVE_LABEL="$INACTIVE(:$INACTIVE_PORT)"

            echo "Active slot: $ACTIVE_LABEL, deploying to: $INACTIVE_LABEL"

            INACTIVE_SERVICE="$APP_USER-$INACTIVE"
            ACTIVE_SERVICE="$APP_USER-$ACTIVE"

            # Stop inactive service in case it is lingering from a failed previous deploy
            systemctl stop "$INACTIVE_SERVICE" 2>/dev/null || true

            # Start the new version
            echo "--- Starting $INACTIVE_LABEL ($INACTIVE_SERVICE) ---"
            systemctl start "$INACTIVE_SERVICE"

            # Health check: wait up to 60 seconds for the new slot to become ready
            echo "--- Health check $INACTIVE_LABEL ($HEALTH_URL, up to 60s) ---"
            HEALTHY=0
            for i in $(seq 1 30); do
                if ! systemctl is-active --quiet "$INACTIVE_SERVICE"; then
                    echo "  ERROR: $INACTIVE_LABEL stopped unexpectedly — aborting health check" >&2
                    break
                fi
                if curl -s --max-time 3 -o /dev/null "$HEALTH_URL" 2>/dev/null; then
                    HEALTHY=1
                    echo "  $INACTIVE_LABEL healthy after $((i * 2))s"
                    break
                fi
                echo "  Waiting for $INACTIVE_LABEL ... ($((i * 2))s / 60s)"
                sleep 2
            done

            if [ "$HEALTHY" = "0" ]; then
                echo "ERROR: Health check failed — rolling back (stopping $INACTIVE_LABEL)" >&2
                systemctl kill "$INACTIVE_SERVICE" 2>/dev/null || true
                systemctl stop "$INACTIVE_SERVICE" 2>/dev/null || true
                echo "Hint: run 'Deploy logs inactive' to see why $INACTIVE_LABEL failed to start" >&2
                exit 1
            fi

            # Swap traffic at the reverse proxy
            if [ "$PROXY" = "caddy" ]; then
                echo "--- Swapping Caddy to $INACTIVE_LABEL ---"
                mkdir -p /etc/caddy/sites
                # Migrate legacy single-app Caddyfile to the import-based layout
                if ! grep -qF 'import /etc/caddy/sites' /etc/caddy/Caddyfile 2>/dev/null; then
                    echo 'import /etc/caddy/sites/*.caddy' > /etc/caddy/Caddyfile
                fi
                cat > "/etc/caddy/sites/$APP_USER.caddy" << CADDY
            $SITE_ADDR {
                $TLS_DIRECTIVE
                reverse_proxy localhost:$INACTIVE_PORT
            }
            CADDY
                systemctl reload caddy
            fi

            # Stop old service, enable new active slot for boot, disable old
            echo "--- Stopping $ACTIVE_LABEL ($ACTIVE_SERVICE) ---"
            systemctl stop "$ACTIVE_SERVICE" || true
            systemctl enable "$INACTIVE_SERVICE"
            systemctl disable "$ACTIVE_SERVICE" || true

            # Write new active marker
            echo "$INACTIVE" > "$ACTIVE_FILE"

            echo "=== Blue-green deploy complete! Active slot: $INACTIVE_LABEL ==="
            """;

    static final String BLUE_GREEN_GRACEFUL_SCRIPT = """
            #!/bin/bash
            set -euo pipefail
            APP_USER="$1"
            PROXY="${2:-caddy}"
            HTTPS="${3:-yes}"
            DOMAIN="$4"
            SLOT_COOKIE="${5:-X-Slot}"
            DRAIN_TIMEOUT="${6:-300}"
            NOTIFY_PATH="${7:-/actuator/new-version}"
            ACTIVE_USERS_PATH="${8:-/actuator/active-users}"
            MANAGEMENT_PORT_BLUE="${9:-0}"
            PORT="${10:-8080}"

            # Build Caddy site address (supports multiple domains)
            TLS_DIRECTIVE=""
            if [ "$HTTPS" = "internal" ]; then TLS_DIRECTIVE="tls internal"; fi
            if [ "$HTTPS" != "no" ]; then
                SITE_ADDR="$DOMAIN"
            else
                SITE_ADDR=""
                for d in $DOMAIN; do SITE_ADDR="$SITE_ADDR http://$d"; done
                SITE_ADDR="${SITE_ADDR# }"
            fi

            LOCK_DIR="/home/$APP_USER/deploy.lock"
            ACTIVE_FILE="/home/$APP_USER/active"

            # Acquire lock atomically — mkdir is atomic on local filesystems
            if ! mkdir "$LOCK_DIR" 2>/dev/null; then
                echo "ERROR: Another deploy is in progress. Remove $LOCK_DIR to force-unlock." >&2
                exit 1
            fi
            trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT

            # Read current active slot (default to blue if file missing)
            ACTIVE=$(cat "$ACTIVE_FILE" 2>/dev/null || echo blue)
            if [ "$ACTIVE" = "blue" ]; then
                INACTIVE="green"
                INACTIVE_PORT=$((PORT + 1))
                ACTIVE_PORT=$PORT
            else
                INACTIVE="blue"
                INACTIVE_PORT=$PORT
                ACTIVE_PORT=$((PORT + 1))
            fi

            # Resolve management ports (Spring Boot management.server.port) for each slot
            if [ "$MANAGEMENT_PORT_BLUE" != "0" ]; then
                if [ "$INACTIVE" = "blue" ]; then
                    INACTIVE_MGMT_PORT=$MANAGEMENT_PORT_BLUE
                    ACTIVE_MGMT_PORT=$((MANAGEMENT_PORT_BLUE + 1))
                else
                    INACTIVE_MGMT_PORT=$((MANAGEMENT_PORT_BLUE + 1))
                    ACTIVE_MGMT_PORT=$MANAGEMENT_PORT_BLUE
                fi
                HEALTH_URL="http://localhost:$INACTIVE_MGMT_PORT/actuator/health"
            else
                INACTIVE_MGMT_PORT=$INACTIVE_PORT
                ACTIVE_MGMT_PORT=$ACTIVE_PORT
                HEALTH_URL="http://localhost:$INACTIVE_PORT/"
            fi

            ACTIVE_LABEL="$ACTIVE(:$ACTIVE_PORT)"
            INACTIVE_LABEL="$INACTIVE(:$INACTIVE_PORT)"

            echo "Active slot: $ACTIVE_LABEL, deploying to: $INACTIVE_LABEL"

            INACTIVE_SERVICE="$APP_USER-$INACTIVE"
            ACTIVE_SERVICE="$APP_USER-$ACTIVE"

            # Stop inactive service in case it is lingering from a failed previous deploy
            systemctl stop "$INACTIVE_SERVICE" 2>/dev/null || true

            # Start the new version
            echo "--- Starting $INACTIVE_LABEL ($INACTIVE_SERVICE) ---"
            systemctl start "$INACTIVE_SERVICE"

            # Health check: wait up to 60 seconds for the new slot to become ready
            echo "--- Health check $INACTIVE_LABEL ($HEALTH_URL, up to 60s) ---"
            HEALTHY=0
            for i in $(seq 1 30); do
                if ! systemctl is-active --quiet "$INACTIVE_SERVICE"; then
                    echo "  ERROR: $INACTIVE_LABEL stopped unexpectedly — aborting health check" >&2
                    break
                fi
                if curl -s --max-time 3 -o /dev/null "$HEALTH_URL" 2>/dev/null; then
                    HEALTHY=1
                    echo "  $INACTIVE_LABEL healthy after $((i * 2))s"
                    break
                fi
                echo "  Waiting for $INACTIVE_LABEL ... ($((i * 2))s / 60s)"
                sleep 2
            done

            if [ "$HEALTHY" = "0" ]; then
                echo "ERROR: Health check failed — rolling back (stopping $INACTIVE_LABEL)" >&2
                systemctl kill "$INACTIVE_SERVICE" 2>/dev/null || true
                systemctl stop "$INACTIVE_SERVICE" 2>/dev/null || true
                echo "Hint: run 'Deploy logs inactive' to see why $INACTIVE_LABEL failed to start" >&2
                exit 1
            fi

            # Write split-traffic Caddyfile (cookie-pinned users stay on old slot)
            if [ "$PROXY" = "caddy" ]; then
                echo "--- Writing drain-mode Caddy site config (old=$ACTIVE_LABEL new=$INACTIVE_LABEL, cookie $SLOT_COOKIE=$ACTIVE) ---"
                mkdir -p /etc/caddy/sites
                # Migrate legacy single-app Caddyfile to the import-based layout
                if ! grep -qF 'import /etc/caddy/sites' /etc/caddy/Caddyfile 2>/dev/null; then
                    echo 'import /etc/caddy/sites/*.caddy' > /etc/caddy/Caddyfile
                fi
                cat > "/etc/caddy/sites/$APP_USER.caddy" << CADDY
            $SITE_ADDR {
                $TLS_DIRECTIVE
                @old_slot {
                    header Cookie *$SLOT_COOKIE=$ACTIVE*
                }
                route @old_slot {
                    reverse_proxy localhost:$ACTIVE_PORT
                }
                reverse_proxy localhost:$INACTIVE_PORT
            }
            CADDY
                systemctl reload caddy
            fi

            # Notify old server that a new version is available
            DEADLINE=$(date -u --date="+${DRAIN_TIMEOUT} seconds" +%Y-%m-%dT%H:%M:%SZ)
            echo "--- Notifying old server (POST http://localhost:$ACTIVE_MGMT_PORT$NOTIFY_PATH, deadline: $DEADLINE) ---"
            if ! curl -s --max-time 5 -X POST \\
                    -H "Content-Type: application/json" \\
                    -d "{\\\"deadline\\\":\\\"$DEADLINE\\\"}" \\
                    "http://localhost:$ACTIVE_MGMT_PORT$NOTIFY_PATH" 2>/dev/null; then
                echo "WARNING: Notify POST failed (non-fatal)"
            fi

            # Poll active-users endpoint until drained or timeout expires
            echo "--- Draining users from $ACTIVE_LABEL ($ACTIVE_SERVICE) (timeout: ${DRAIN_TIMEOUT}s) ---"
            ELAPSED=0
            COUNT="1"
            while [ "$ELAPSED" -lt "$DRAIN_TIMEOUT" ]; do
                RESPONSE=$(curl -s --max-time 5 "http://localhost:$ACTIVE_MGMT_PORT$ACTIVE_USERS_PATH" 2>/dev/null || echo "")
                COUNT=$(echo "$RESPONSE" | grep -oP '"count"\\s*:\\s*\\K[0-9]+' 2>/dev/null || echo "")
                if [ -z "$COUNT" ]; then
                    echo "INFO: active-users endpoint unreachable or returned no count — assuming drained"
                    COUNT="0"
                fi
                if [ "$COUNT" = "0" ]; then
                    echo "All users drained after ${ELAPSED}s"
                    break
                fi
                REMAINING=$((DRAIN_TIMEOUT - ELAPSED))
                echo "$COUNT users remaining (${ELAPSED}s elapsed, ${REMAINING}s until forced upgrade) — press D to force-upgrade now"
                key=""
                read -t 10 -r -s -n 1 key 2>/dev/null || true
                if [ "${key}" = "d" ] || [ "${key}" = "D" ]; then
                    echo "Operator requested force-cutover — remaining users will be dropped to new slot"
                    break
                fi
                ELAPSED=$((ELAPSED + 10))
            done

            if [ "$COUNT" != "0" ]; then
                echo "Forcing cutover after ${DRAIN_TIMEOUT}s"
            fi

            # Switch Caddy to serve only the new backend
            if [ "$PROXY" = "caddy" ]; then
                echo "--- Switching Caddy to $INACTIVE_LABEL only ---"
                cat > "/etc/caddy/sites/$APP_USER.caddy" << CADDY
            $SITE_ADDR {
                $TLS_DIRECTIVE
                reverse_proxy localhost:$INACTIVE_PORT
            }
            CADDY
                systemctl reload caddy
            fi

            # Stop old service, enable new active slot for boot, disable old
            echo "--- Stopping $ACTIVE_LABEL ($ACTIVE_SERVICE) ---"
            systemctl stop "$ACTIVE_SERVICE" || true
            systemctl enable "$INACTIVE_SERVICE"
            systemctl disable "$ACTIVE_SERVICE" || true

            # Write new active marker
            echo "$INACTIVE" > "$ACTIVE_FILE"

            echo "=== Graceful blue-green deploy complete! Active slot: $INACTIVE_LABEL ==="
            """;

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "deploy" : args[0];

        switch (command) {
            case "init" -> init();
            case "deploy" -> { loadConfig(); deploy(); }
            case "logs" -> { loadConfig(); logs(args); }
            case "env" -> { loadConfig(); env(args); }
            case "add-key" -> { loadConfig(); addKey(args); }
            case "clean" -> { loadConfig(); clean(); }
            case "root-cert" -> { loadConfig(); rootCert(args); }
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
        System.out.println("  logs [n] [slot] - Tail the application logs (default: 200 lines)");
        System.out.println("                    slot: active (default), inactive, blue, green");
        System.out.println("  env            - List environment variables on the server");
        System.out.println("  env set K=V    - Set env var(s), then restart the service");
        System.out.println("  env remove K   - Remove env var(s), then restart the service");
        System.out.println("  env list       - List environment variables on the server");
        System.out.println("  add-key [file] - Add an SSH public key to the server");
        System.out.println("  clean          - Remove the app, service, and user from the server");
        System.out.println("  root-cert [file] - Export Caddy public root CA (default: caddy-root.crt)");
    }

    static String parseHttpsMode(String value) {
        String mode = value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("yes", "no", "internal").contains(mode)) {
            throw new IllegalArgumentException("HTTPS must be yes, no or internal: " + value);
        }
        return mode;
    }

    static void validateHttpsMode() {
        if ("internal".equals(httpsMode) && (!webService || !"caddy".equals(proxy))) {
            throw new IllegalArgumentException("HTTPS=internal requires WEB_SERVICE=yes and PROXY=caddy");
        }
    }

    /** Export only the public CA certificate, through authenticated SSH. */
    static void rootCert(String[] args) throws Exception {
        if (args.length > 2) throw new IllegalArgumentException("Usage: Deploy root-cert [file]");
        if (!"internal".equals(httpsMode)) {
            throw new IllegalArgumentException("root-cert requires HTTPS=internal; run init with that mode first");
        }
        Path output = Path.of(args.length == 2 ? args[1] : "caddy-root.crt");
        if (Files.exists(output)) throw new FileAlreadyExistsException(output.toString());
        // boot2vm installs the standard Debian Caddy systemd service/data directory.
        String pem = sshOutputAsRoot("cat /var/lib/caddy/.local/share/caddy/pki/authorities/local/root.crt");
        writeRootCertificate(output, pem);
        System.out.println("Install this public root certificate on each client device, then enable TLS trust.");
        System.out.println("Keep Caddy's data directory: replacing its CA requires trusting the new root on all devices.");
    }

    static void writeRootCertificate(Path output, String pem) throws Exception {
        var factory = java.security.cert.CertificateFactory.getInstance("X.509");
        var cert = (java.security.cert.X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        cert.checkValidity();
        if (cert.getBasicConstraints() < 0) throw new IllegalArgumentException("Not a CA certificate");
        cert.verify(cert.getPublicKey());
        byte[] der = cert.getEncoded();
        // Re-encode the parsed certificate so no other SSH output can enter the export.
        String publicPem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
                + "\n-----END CERTIFICATE-----\n";
        Files.writeString(output, publicPem, StandardOpenOption.CREATE_NEW);
        String fingerprint = HexFormat.ofDelimiter(":").withUpperCase().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(der));
        System.out.println("Saved public root certificate to " + output);
        System.out.println("SHA-256: " + fingerprint);
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
        // Accept comma-separated domains in hand-edited configs too — the scripts expect space-separated
        domain = props.getProperty("DOMAIN", host);
        if (domain != null) {
            domain = domain.replaceAll("\\s*,\\s*", " ").trim();
        }
        sshKey = props.getProperty("SSH_KEY", "~/.ssh/id_rsa.pub");
        adminUser = props.getProperty("ADMIN_USER", "root");
        httpsMode = parseHttpsMode(props.getProperty("HTTPS", "yes"));
        proxy = props.getProperty("PROXY", "caddy");
        webService = !"no".equalsIgnoreCase(props.getProperty("WEB_SERVICE", "yes"));
        validateHttpsMode();
        appType = props.getProperty("APP_TYPE", "spring-boot");
        jdkProvider = props.getProperty("JDK_PROVIDER", "temurin");
        port = Integer.parseInt(props.getProperty("PORT", "8080"));
        blueGreen = "yes".equalsIgnoreCase(props.getProperty("BLUE_GREEN", "no"));
        gracefulDrain = "yes".equalsIgnoreCase(props.getProperty("BLUE_GREEN_GRACEFUL", "no"));
        slotCookie = props.getProperty("SLOT_COOKIE", "X-Slot");
        drainTimeout = Integer.parseInt(props.getProperty("DRAIN_TIMEOUT", "300"));
        managementPort = props.getProperty("MANAGEMENT_PORT", "");
        notifyPath = props.getProperty("NOTIFY_PATH", "/actuator/new-version");
        activeUsersPath = props.getProperty("ACTIVE_USERS_PATH", "/actuator/active-users");
        jvmOpts = props.getProperty("JVM_OPTS", "");
        userGroups = props.getProperty("USER_GROUPS", "");

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
        if (host.contains(",") || host.matches(".*\\s.*")) {
            System.err.println("HOST in vmhosting.conf must be a single hostname or IP (got: '" + host
                    + "'). Put additional domains in DOMAIN instead.");
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
                defaultKey = null, defaultAdmin = null, defaultHttps = null,
                defaultProxy = null, defaultAppType = null, defaultJdkProvider = null,
                defaultBlueGreen = null, defaultGracefulDrain = null, defaultSlotCookie = null,
                defaultDrainTimeout = null, defaultManagementPort = null, defaultNotifyPath = null,
                defaultActiveUsersPath = null, defaultFirewall = null, defaultExposeNodes = null,
                defaultWebService = null, defaultPort = null,
                defaultJvmOpts = null, defaultUserGroups = null;
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
            defaultAppType = props.getProperty("APP_TYPE");
            defaultJdkProvider = props.getProperty("JDK_PROVIDER");
            defaultBlueGreen = props.getProperty("BLUE_GREEN");
            defaultGracefulDrain = props.getProperty("BLUE_GREEN_GRACEFUL");
            defaultSlotCookie = props.getProperty("SLOT_COOKIE");
            defaultDrainTimeout = props.getProperty("DRAIN_TIMEOUT");
            defaultManagementPort = props.getProperty("MANAGEMENT_PORT");
            defaultNotifyPath = props.getProperty("NOTIFY_PATH");
            defaultActiveUsersPath = props.getProperty("ACTIVE_USERS_PATH");
            defaultFirewall = props.getProperty("FIREWALL");
            defaultExposeNodes = props.getProperty("EXPOSE_NODES");
            defaultWebService = props.getProperty("WEB_SERVICE");
            defaultPort = props.getProperty("PORT");
            defaultJvmOpts = props.getProperty("JVM_OPTS");
            defaultUserGroups = props.getProperty("USER_GROUPS");
        }

        // HOST (required, single hostname — extra domains are asked separately)
        while (true) {
            host = prompt(console, "Host (SSH address, a single hostname or IP)", defaultHost);
            if (host.isBlank()) {
                System.err.println("Host is required");
                System.exit(1);
            }
            if (host.contains(",") || host.matches(".*\\s.*")) {
                System.err.println("  Host must be a single hostname or IP — additional domains are entered at the 'Domain(s)' prompt below.");
                continue;
            }
            break;
        }

        // Derive smart defaults from host
        String derivedUser = host.contains(".") ? host.substring(0, host.indexOf('.')) : host;

        // USER, WEB_SERVICE, DOMAIN, SSH_KEY, ADMIN_USER – with defaults
        user = prompt(console, "App user", defaultUser != null ? defaultUser : derivedUser);
        String webServiceStr = prompt(console, "Does this service expose web endpoints (yes/no)",
                defaultWebService != null ? defaultWebService : "yes");
        webService = !"no".equalsIgnoreCase(webServiceStr);

        String domainRaw;
        if (webService) {
            domainRaw = prompt(console, "Domain(s) (comma-separated for multiple)", defaultDomain != null ? defaultDomain : host);
        } else {
            domainRaw = defaultDomain != null ? defaultDomain : host;
        }
        domain = domainRaw.replaceAll("\\s*,\\s*", " ").trim();
        String sshKeyRaw = promptSshKey(console, defaultKey);
        adminUser = prompt(console, "Admin SSH user",
                defaultAdmin != null ? defaultAdmin : "root");
        if (webService) {
            String httpsStr = prompt(console, "HTTPS (yes/no/internal)",
                    defaultHttps != null ? defaultHttps : "yes");
            httpsMode = parseHttpsMode(httpsStr);
            proxy = prompt(console, "Reverse proxy (caddy/none)",
                    defaultProxy != null ? defaultProxy : "caddy");
            String portStr = prompt(console, "App port (must be unique per app on the server; blue-green also uses port+1)",
                    defaultPort != null ? defaultPort : "8080");
            port = Integer.parseInt(portStr);
        } else {
            httpsMode = "no";
            proxy = "none";
        }
        appType = prompt(console, "App type (spring-boot/quarkus/plain)",
                defaultAppType != null ? defaultAppType : detectAppType());
        jdkProvider = prompt(console, "JDK provider (temurin/zulu)",
                defaultJdkProvider != null ? defaultJdkProvider : "temurin");
        promptJvmAndHardware(console, defaultJvmOpts, defaultUserGroups);
        if (webService) {
            String blueGreenStr = prompt(console, "Blue-green deployment (yes/no) [not recommended for low-end servers]",
                    defaultBlueGreen != null ? defaultBlueGreen : "no");
            blueGreen = "yes".equalsIgnoreCase(blueGreenStr);

            if (blueGreen) {
                String gracefulDrainStr = prompt(console, "Graceful drain mode (yes/no)",
                        defaultGracefulDrain != null ? defaultGracefulDrain : "no");
                gracefulDrain = "yes".equalsIgnoreCase(gracefulDrainStr);
                if (gracefulDrain) {
                    slotCookie = prompt(console, "Slot cookie name",
                            defaultSlotCookie != null ? defaultSlotCookie : "X-Slot");
                    String drainTimeoutStr = prompt(console, "Drain timeout (seconds)",
                            defaultDrainTimeout != null ? defaultDrainTimeout : "300");
                    drainTimeout = Integer.parseInt(drainTimeoutStr);
                    managementPort = prompt(console, "Management port (blank = app port)",
                            defaultManagementPort != null ? defaultManagementPort : "8090");
                    notifyPath = prompt(console, "Notify path",
                            defaultNotifyPath != null ? defaultNotifyPath : "/actuator/new-version");
                    activeUsersPath = prompt(console, "Active users path",
                            defaultActiveUsersPath != null ? defaultActiveUsersPath : "/actuator/active-users");
                }
            }
        } else {
            blueGreen = false;
            gracefulDrain = false;
        }

        String firewallStr = prompt(console, "Configure firewall with ufw (yes/no)",
                defaultFirewall != null ? defaultFirewall : "yes");
        boolean firewall = "yes".equalsIgnoreCase(firewallStr);
        boolean exposeNodes = false;
        if (firewall && webService) {
            String exposePortsLabel = blueGreen ? port + "/" + (port + 1) : String.valueOf(port);
            String exposeNodesStr = prompt(console, "Expose app server port(s) " + exposePortsLabel + " for direct access (yes/no)",
                    defaultExposeNodes != null ? defaultExposeNodes : "no");
            exposeNodes = "yes".equalsIgnoreCase(exposeNodesStr);
        }

        // Collect environment variables
        System.out.println("Enter environment variables for the app (stored on server only, never locally).");
        System.out.println("Format: KEY=VALUE (values with spaces must be quoted: KEY=\"value with spaces\")");
        var envVars = new ArrayList<String>();
        while (true) {
            String entry = console.readLine("Environment variable (KEY=VALUE, blank to finish): ").trim();
            if (entry.isEmpty()) break;
            if (!entry.contains("=")) {
                System.err.println("  Invalid format — expected KEY=VALUE");
                continue;
            }
            envVars.add(entry);
        }

        validateHttpsMode();

        // Write vmhosting.conf
        Files.writeString(configPath,
                "HOST=" + host + "\n"
                + "USER=" + user + "\n"
                + "DOMAIN=" + domain + "\n"
                + "SSH_KEY=" + sshKeyRaw + "\n"
                + "ADMIN_USER=" + adminUser + "\n"
                + "HTTPS=" + httpsMode + "\n"
                + "PROXY=" + proxy + "\n"
                + "APP_TYPE=" + appType + "\n"
                + "JDK_PROVIDER=" + jdkProvider + "\n"
                + "PORT=" + port + "\n"
                + "BLUE_GREEN=" + (blueGreen ? "yes" : "no") + "\n"
                + "BLUE_GREEN_GRACEFUL=" + (gracefulDrain ? "yes" : "no") + "\n"
                + "SLOT_COOKIE=" + slotCookie + "\n"
                + "DRAIN_TIMEOUT=" + drainTimeout + "\n"
                + "MANAGEMENT_PORT=" + managementPort + "\n"
                + "NOTIFY_PATH=" + notifyPath + "\n"
                + "ACTIVE_USERS_PATH=" + activeUsersPath + "\n"
                + "FIREWALL=" + (firewall ? "yes" : "no") + "\n"
                + "EXPOSE_NODES=" + (exposeNodes ? "yes" : "no") + "\n"
                + "WEB_SERVICE=" + (webService ? "yes" : "no") + "\n"
                + "JVM_OPTS=" + jvmOpts + "\n"
                + "USER_GROUPS=" + userGroups + "\n");
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
                + user + " '" + domain + "' " + adminUser + " " + httpsMode
                + " " + proxy + " " + appType + " " + (blueGreen ? "yes" : "no")
                + " " + (managementPort != null && !managementPort.isBlank() ? managementPort : "0")
                + " " + (firewall ? "yes" : "no")
                + " " + (exposeNodes ? "yes" : "no")
                + " " + (webService ? "yes" : "no")
                + " " + jdkProvider
                + " " + port
                + " " + shellQuote(jvmOpts)
                + " " + shellQuote(userGroups));

        Files.delete(tempScript);

        // Write env vars to server if any were provided
        if (!envVars.isEmpty()) {
            System.out.println("Writing environment variables to server ...");
            var envContent = new StringBuilder();
            for (String entry : envVars) {
                envContent.append(entry).append("\n");
            }
            String escaped = envContent.toString().replace("'", "'\\''");
            sshAsRoot("printf '%s' '" + escaped + "' > /home/" + user + "/.env");
            sshAsRoot("chown " + user + ":" + user + " /home/" + user + "/.env");
            sshAsRoot("chmod 600 /home/" + user + "/.env");
        }

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

    /**
     * Ask for the SSH public key. Lists the *.pub files found in ~/.ssh so the
     * user can pick one by number instead of typing the full path. A path can
     * still be typed directly. The returned value keeps the ~ prefix so the
     * written vmhosting.conf stays portable between machines and users.
     */
    static String promptSshKey(Console console, String defaultKey) throws IOException {
        Path sshDir = Path.of(System.getProperty("user.home"), ".ssh");
        List<String> keys = new ArrayList<>();
        if (Files.isDirectory(sshDir)) {
            try (var stream = Files.list(sshDir)) {
                stream.filter(f -> f.getFileName().toString().endsWith(".pub"))
                        .filter(Files::isRegularFile)
                        .map(f -> "~/.ssh/" + f.getFileName())
                        .sorted()
                        .forEach(keys::add);
            }
        }

        // Pick a default: previous config value, then common key names, then the first key found
        String def = defaultKey;
        String home = System.getProperty("user.home");
        if (def != null && def.startsWith(home + "/")) {
            def = "~" + def.substring(home.length());
        }
        if (def == null) {
            for (String candidate : List.of("~/.ssh/id_ed25519.pub", "~/.ssh/id_rsa.pub", "~/.ssh/id_ecdsa.pub")) {
                if (keys.contains(candidate)) {
                    def = candidate;
                    break;
                }
            }
        }
        if (def == null) {
            def = keys.isEmpty() ? "~/.ssh/id_rsa.pub" : keys.get(0);
        }

        if (keys.isEmpty()) {
            return prompt(console, "SSH public key", def);
        }

        System.out.println("SSH public keys found in ~/.ssh:");
        for (int i = 0; i < keys.size(); i++) {
            String marker = keys.get(i).equals(def) ? " (default)" : "";
            System.out.printf("  %d) %s%s%n", i + 1, keys.get(i), marker);
        }
        while (true) {
            String input = prompt(console, "SSH public key (number or path)", def);
            if (input.matches("\\d+")) {
                int idx = Integer.parseInt(input) - 1;
                if (idx >= 0 && idx < keys.size()) {
                    return keys.get(idx);
                }
                System.err.println("  Pick a number between 1 and " + keys.size() + ", or type a path.");
                continue;
            }
            return input;
        }
    }

    /**
     * A preset bundles the groups and JVM flags a typical use case needs. Bundles
     * with an alias can also be picked by name instead of number.
     */
    record Preset(String label, String groups, String jvmOpts, String alias) {
        Preset(String label, String groups, String jvmOpts) {
            this(label, groups, jvmOpts, null);
        }
    }

    static final String PI_GROUPS = "gpio,i2c,spi,dialout,bluetooth,video,render,plugdev,input,audio";

    static final List<Preset> PRESETS = List.of(
            new Preset("Raspberry Pi, everything: all hardware groups below, native access, restart on OOM",
                    PI_GROUPS, "--enable-native-access=ALL-UNNAMED -XX:+ExitOnOutOfMemoryError", "pi"),
            new Preset("Raspberry Pi Zero / 512 MB class: as above plus serial GC, 60% heap, C1 JIT only",
                    PI_GROUPS, "--enable-native-access=ALL-UNNAMED -XX:+ExitOnOutOfMemoryError"
                            + " -XX:+UseSerialGC -XX:MaxRAMPercentage=60 -XX:TieredStopAtLevel=1", "pi-zero"),
            new Preset("Raspberry Pi GPIO/I2C/SPI/PWM (Pi4J, gpiod)", "gpio,i2c,spi", "--enable-native-access=ALL-UNNAMED"),
            new Preset("Serial ports (/dev/ttyAMA*, /dev/ttyUSB*)", "dialout", ""),
            new Preset("Bluetooth (BlueZ over D-Bus)", "bluetooth", ""),
            new Preset("Camera (libcamera/rpicam, V4L2)", "video,render", ""),
            new Preset("USB, HID and input devices", "plugdev,input", ""),
            new Preset("Audio (ALSA)", "audio", ""),
            new Preset("Native libraries (JNI/FFM: JNA, OpenCV, SQLite ...) without JDK 24+ warnings", "", "--enable-native-access=ALL-UNNAMED"),
            new Preset("Small device (<= 2 GB RAM): serial GC, 60% of RAM for the heap", "", "-XX:+UseSerialGC -XX:MaxRAMPercentage=60"),
            new Preset("Faster startup on a slow CPU (C1 JIT only, lower peak throughput)", "", "-XX:TieredStopAtLevel=1"),
            new Preset("Exit on OutOfMemoryError so systemd restarts the app", "", "-XX:+ExitOnOutOfMemoryError"),
            new Preset("Headless AWT (image processing on a server)", "", "-Djava.awt.headless=true"));

    /**
     * Ask for extra JVM options and extra Linux groups for the app user. Common
     * use cases (Raspberry Pi hardware access, small devices, ...) can be picked
     * from a list; the merged result is then shown for manual editing.
     */
    static void promptJvmAndHardware(Console console, String defaultJvmOpts, String defaultUserGroups) {
        boolean hasPrevious = (defaultJvmOpts != null && !defaultJvmOpts.isBlank())
                || (defaultUserGroups != null && !defaultUserGroups.isBlank());
        jvmOpts = defaultJvmOpts != null ? defaultJvmOpts.trim() : "";
        userGroups = defaultUserGroups != null ? defaultUserGroups.trim() : "";

        String tune = prompt(console, "Configure hardware access / JVM options (yes/no)", hasPrevious ? "yes" : "no");
        if (!"yes".equalsIgnoreCase(tune)) {
            return;
        }

        var opts = new LinkedHashSet<String>();
        if (!jvmOpts.isEmpty()) opts.addAll(List.of(jvmOpts.split("\\s+")));
        var groups = new LinkedHashSet<String>();
        if (!userGroups.isEmpty()) groups.addAll(List.of(userGroups.split("\\s*,\\s*")));

        System.out.println("Presets (groups are added to the app user, flags to the java command line):");
        for (int i = 0; i < PRESETS.size(); i++) {
            Preset preset = PRESETS.get(i);
            var details = new ArrayList<String>();
            if (!preset.groups().isEmpty()) details.add("groups: " + preset.groups());
            if (!preset.jvmOpts().isEmpty()) details.add(preset.jvmOpts());
            String number = preset.alias() != null ? (i + 1) + " / " + preset.alias() : String.valueOf(i + 1);
            System.out.printf("  %12s) %s%n%16s%s%n", number, preset.label(), "", String.join("; ", details));
        }
        while (true) {
            String picked = prompt(console, "Presets to apply (comma-separated numbers or names, blank for none)", null);
            if (picked.isEmpty()) break;
            boolean ok = true;
            for (String token : picked.split("\\s*[,\\s]\\s*")) {
                if (token.isEmpty()) continue;
                Preset preset = findPreset(token);
                if (preset == null) {
                    System.err.println("  '" + token + "' is not a preset number between 1 and " + PRESETS.size() + " or a preset name");
                    ok = false;
                    break;
                }
                if (!preset.groups().isEmpty()) groups.addAll(List.of(preset.groups().split(",")));
                if (!preset.jvmOpts().isEmpty()) opts.addAll(List.of(preset.jvmOpts().split(" ")));
            }
            if (ok) break;
        }

        String mergedOpts = String.join(" ", opts);
        String mergedGroups = String.join(",", groups);
        jvmOpts = prompt(console, "Extra JVM options", mergedOpts.isEmpty() ? null : mergedOpts).trim();
        if (jvmOpts.contains("\"")) {
            System.err.println("  Double quotes are not supported in JVM options (the value is written into a quoted systemd Environment= line)");
            System.exit(1);
        }
        userGroups = prompt(console, "Extra groups for the app user (comma-separated)", mergedGroups.isEmpty() ? null : mergedGroups)
                .replaceAll("\\s*,\\s*", ",").trim();
    }

    static Preset findPreset(String token) {
        if (token.matches("\\d+")) {
            int idx = Integer.parseInt(token) - 1;
            return idx >= 0 && idx < PRESETS.size() ? PRESETS.get(idx) : null;
        }
        return PRESETS.stream()
                .filter(p -> token.equalsIgnoreCase(p.alias()))
                .findFirst().orElse(null);
    }

    /** Quote a value for use as a single argument in a remote sh command line. */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    // -----------------------------------------------------------------------
    // deploy – build, sync, restart
    // -----------------------------------------------------------------------
    static void deploy() throws Exception {
        // 1. Build
        System.out.println("Building application ...");
        boolean mavenw = Files.exists(Path.of("mvnw"));
        boolean gradlew = Files.exists(Path.of("gradlew"));
        boolean pom = Files.exists(Path.of("pom.xml"));
        boolean gradle = Files.exists(Path.of("build.gradle")) || Files.exists(Path.of("build.gradle.kts"));
        boolean quarkus = "quarkus".equals(appType);
        boolean plain = "plain".equals(appType);
        String gradleTask = quarkus ? "quarkusBuild" : (plain ? "build" : "bootJar");

        if (mavenw) {
            run("./mvnw", "-DskipTests", "package");
        } else if (gradlew) {
            run("./gradlew", "-x", "test", gradleTask);
        } else if (pom) {
            run("mvn", "-DskipTests", "package");
        } else if (gradle) {
            run("gradle", "-x", "test", gradleTask);
        } else {
            System.err.println("No Maven or Gradle project found in current directory");
            System.exit(1);
        }

        if (blueGreen) {
            deployBlueGreen(quarkus, plain, mavenw, pom);
            return;
        }

        // 2. Prepare and sync to server
        System.out.println("Syncing to server ...");
        String syncSource;

        if (quarkus) {
            // Quarkus builds an already-exploded app in target/quarkus-app
            syncSource = "target/quarkus-app";
            run("rsync", "-az", "--delete", "--stats",
                    "-e", "ssh -i " + sshKey + " -o StrictHostKeyChecking=accept-new",
                    syncSource,
                    user + "@" + host + ":/home/" + user + "/app/");
        } else if (plain) {
            Path staging = stagePlainJar(mavenw, pom);
            run("rsync", "-az", "--delete", "--stats",
                    "-e", "ssh -i " + sshKey + " -o StrictHostKeyChecking=accept-new",
                    staging + "/",
                    user + "@" + host + ":/home/" + user + "/app/");
        } else {
            // Spring Boot: extract fat jar for efficient rsync (lib/ changes rarely)
            Path jarDir = (mavenw || pom) ? Path.of("target") : Path.of("build", "libs");
            Path jar = findJar(jarDir);
            System.out.println("Found jar: " + jar);

            Path extracted = Path.of("target", "extracted");
            if (Files.exists(extracted)) {
                deleteRecursively(extracted);
            }
            run("java", "-Djarmode=tools", "-jar", jar.toString(),
                    "extract", "--destination", extracted.toString());

            Path extractRoot = findExtractedRoot(extracted);

            Path extractedJar = findJar(extractRoot);
            Path renamedJar = extractRoot.resolve(user + ".jar");
            if (!extractedJar.getFileName().toString().equals(user + ".jar")) {
                Files.move(extractedJar, renamedJar, StandardCopyOption.REPLACE_EXISTING);
            }

            run("rsync", "-az", "--delete", "--stats",
                    "-e", "ssh -i " + sshKey + " -o StrictHostKeyChecking=accept-new",
                    extractRoot + "/",
                    user + "@" + host + ":/home/" + user + "/app/");
        }

        // 3. Restart the systemd service
        System.out.println("Restarting service ...");
        sshAsRoot("systemctl restart " + user);

        System.out.println("Deployed successfully!");
    }

    // -----------------------------------------------------------------------
    // deployBlueGreen – zero-downtime blue/green deploy
    // -----------------------------------------------------------------------
    static void deployBlueGreen(boolean quarkus, boolean plain, boolean mavenw, boolean pom) throws Exception {
        // Read the current active slot to determine where to rsync
        System.out.println("Reading active slot ...");
        String activeRaw = sshOutputAsRoot("cat /home/" + user + "/active 2>/dev/null || echo blue").trim();
        String active = activeRaw.isBlank() ? "blue" : activeRaw;
        String inactive = "blue".equals(active) ? "green" : "blue";
        System.out.println("Active slot: " + active + ", deploying to: " + inactive);

        String mgmtPortBlue = (managementPort != null && !managementPort.isBlank()) ? managementPort : "0";

        // Sync build artifacts to the inactive slot directory
        System.out.println("Syncing to server (slot: " + inactive + ") ...");
        String remoteDir = user + "@" + host + ":/home/" + user + "/app-" + inactive + "/";

        if (quarkus) {
            run("rsync", "-az", "--delete", "--stats",
                    "-e", "ssh -i " + sshKey + " -o StrictHostKeyChecking=accept-new",
                    "target/quarkus-app",
                    remoteDir);
        } else if (plain) {
            Path staging = stagePlainJar(mavenw, pom);
            run("rsync", "-az", "--delete", "--stats",
                    "-e", "ssh -i " + sshKey + " -o StrictHostKeyChecking=accept-new",
                    staging + "/",
                    remoteDir);
        } else {
            Path jarDir = (mavenw || pom) ? Path.of("target") : Path.of("build", "libs");
            Path jar = findJar(jarDir);
            System.out.println("Found jar: " + jar);

            Path extracted = Path.of("target", "extracted");
            if (Files.exists(extracted)) {
                deleteRecursively(extracted);
            }
            run("java", "-Djarmode=tools", "-jar", jar.toString(),
                    "extract", "--destination", extracted.toString());

            Path extractRoot = findExtractedRoot(extracted);
            Path extractedJar = findJar(extractRoot);
            Path renamedJar = extractRoot.resolve(user + ".jar");
            if (!extractedJar.getFileName().toString().equals(user + ".jar")) {
                Files.move(extractedJar, renamedJar, StandardCopyOption.REPLACE_EXISTING);
            }

            run("rsync", "-az", "--delete", "--stats",
                    "-e", "ssh -i " + sshKey + " -o StrictHostKeyChecking=accept-new",
                    extractRoot + "/",
                    remoteDir);
        }

        // Upload and run the swap (or graceful drain) script on the server
        if (gracefulDrain) {
            System.out.println("Running graceful blue-green drain ...");
            Path tempScript = Files.createTempFile("bg-graceful", ".sh");
            Files.writeString(tempScript, BLUE_GREEN_GRACEFUL_SCRIPT);
            scp(tempScript.toString(), adminUser + "@" + host + ":/tmp/bg-graceful.sh");
            Files.delete(tempScript);
            sshAsRootInteractive("bash /tmp/bg-graceful.sh " + user + " " + proxy + " " + httpsMode + " '" + domain + "'"
                    + " " + slotCookie + " " + drainTimeout + " " + notifyPath + " " + activeUsersPath
                    + " " + mgmtPortBlue + " " + port);
        } else {
            System.out.println("Running blue-green swap ...");
            Path tempScript = Files.createTempFile("bg-swap", ".sh");
            Files.writeString(tempScript, BLUE_GREEN_SWAP_SCRIPT);
            scp(tempScript.toString(), adminUser + "@" + host + ":/tmp/bg-swap.sh");
            Files.delete(tempScript);
            sshAsRoot("bash /tmp/bg-swap.sh " + user + " " + proxy + " " + httpsMode + " '" + domain + "'"
                    + " " + mgmtPortBlue + " " + port);
        }

        System.out.println("Deployed successfully! Active slot is now: " + inactive);
    }

    // -----------------------------------------------------------------------
    // logs – tail journalctl
    // -----------------------------------------------------------------------
    static void logs(String[] args) throws Exception {
        String lines = "200";
        String slotArg = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.matches("[0-9]+")) lines = arg;
            else slotArg = arg;
        }
        String unitName = user;
        if (blueGreen) {
            String active = sshOutputAsRoot("cat /home/" + user + "/active 2>/dev/null || echo blue").trim();
            if (active.isBlank()) active = "blue";
            String inactive = "blue".equals(active) ? "green" : "blue";
            String slot = switch (slotArg == null ? "active" : slotArg) {
                case "inactive"       -> inactive;
                case "blue", "green"  -> slotArg;
                case "active"         -> active;
                default -> {
                    System.err.println("Unknown slot '" + slotArg + "'. Use: blue, green, active, inactive");
                    System.exit(1);
                    yield active;
                }
            };
            unitName = user + "-" + slot;
        }
        String cmd = "journalctl -u " + unitName + " -n " + lines + " -f";
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
    // clean – remove app, service, proxy config, and user from the server
    // -----------------------------------------------------------------------
    static final String CLEAN_SCRIPT = """
            #!/bin/bash
            APP_USER="$1"
            PROXY="$2"
            BLUE_GREEN="${3:-no}"

            echo "--- Stopping and removing service(s) ---"
            if [ "$BLUE_GREEN" = "yes" ]; then
                systemctl stop "$APP_USER-blue" 2>/dev/null || true
                systemctl stop "$APP_USER-green" 2>/dev/null || true
                systemctl disable "$APP_USER-blue" 2>/dev/null || true
                systemctl disable "$APP_USER-green" 2>/dev/null || true
                rm -f "/etc/systemd/system/$APP_USER-blue.service"
                rm -f "/etc/systemd/system/$APP_USER-green.service"
            else
                systemctl stop "$APP_USER" 2>/dev/null || true
                systemctl disable "$APP_USER" 2>/dev/null || true
                rm -f "/etc/systemd/system/$APP_USER.service"
            fi
            systemctl daemon-reload

            if [ "$PROXY" = "caddy" ]; then
                echo "--- Removing Caddy site config for $APP_USER ---"
                rm -f "/etc/caddy/sites/$APP_USER.caddy"
                systemctl reload caddy 2>/dev/null || true
            fi

            echo "--- Removing user and home directory ---"
            userdel -r "$APP_USER" 2>/dev/null || true

            echo "=== Clean complete! ==="
            """;

    static void clean() throws Exception {
        System.out.println("Cleaning up " + user + " from " + host + " ...");

        Path tempScript = Files.createTempFile("clean-server", ".sh");
        Files.writeString(tempScript, CLEAN_SCRIPT);

        scp(tempScript.toString(), adminUser + "@" + host + ":/tmp/clean-server.sh");
        sshAsRoot("chmod +x /tmp/clean-server.sh");
        sshAsRoot("/tmp/clean-server.sh " + user + " " + proxy + " " + (blueGreen ? "yes" : "no"));

        Files.delete(tempScript);

        Files.deleteIfExists(Path.of("vmhosting.conf"));
        System.out.println("Server cleaned and vmhosting.conf removed. Run 'init' to start fresh.");
    }

    // -----------------------------------------------------------------------
    // env – manage environment variables on the server
    // -----------------------------------------------------------------------
    static void env(String[] args) throws Exception {
        String subcommand = args.length > 1 ? args[1] : "list";
        switch (subcommand) {
            case "list" -> envList();
            case "set" -> envSet(args);
            case "remove" -> envRemove(args);
            default -> {
                System.err.println("Unknown env subcommand: " + subcommand);
                System.err.println("Usage: Deploy env [set|remove|list]");
                System.exit(1);
            }
        }
    }

    static void envList() throws Exception {
        String output = sshOutputAsRoot("cat /home/" + user + "/.env 2>/dev/null || true").trim();
        if (output.isEmpty()) {
            System.out.println("No environment variables set on the server.");
            System.out.println("Use 'Deploy env set KEY=VALUE' to add variables.");
        } else {
            System.out.println(output);
        }
    }

    static void envSet(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: Deploy env set KEY=VALUE [KEY2=VALUE2 ...]");
            System.exit(1);
        }
        String envFile = "/home/" + user + "/.env";
        for (int i = 2; i < args.length; i++) {
            String entry = args[i];
            if (!entry.contains("=")) {
                System.err.println("Invalid format: " + entry + " — expected KEY=VALUE");
                System.exit(1);
            }
            String key = entry.substring(0, entry.indexOf('='));
            String escaped = entry.replace("'", "'\\''");
            // Create file if missing and ensure correct ownership/permissions
            sshAsRoot("touch " + envFile);
            sshAsRoot("chown " + user + ":" + user + " " + envFile);
            sshAsRoot("chmod 600 " + envFile);
            sshAsRoot("sed -i '/^" + key + "=/d' " + envFile);
            sshAsRoot("sh -c " + "'echo \"" + escaped + "\" >> " + envFile + "'");
            System.out.println("  " + key + " set");
        }
        restartService();
    }

    static void envRemove(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: Deploy env remove KEY [KEY2 ...]");
            System.exit(1);
        }
        String envFile = "/home/" + user + "/.env";
        // Ensure file exists and has correct ownership/permissions
        sshAsRoot("touch " + envFile);
        sshAsRoot("chown " + user + ":" + user + " " + envFile);
        sshAsRoot("chmod 600 " + envFile);
        for (int i = 2; i < args.length; i++) {
            String key = args[i];
            sshAsRoot("sed -i '/^" + key + "=/d' " + envFile);
            System.out.println("  " + key + " removed");
        }
        restartService();
    }

    /** Restart the service after env var changes — blue-green uses a slot swap for zero downtime. */
    static void restartService() throws Exception {
        if (blueGreen) {
            System.out.println("Performing blue-green swap for zero-downtime env change ...");
            String activeRaw = sshOutputAsRoot("cat /home/" + user + "/active 2>/dev/null || echo blue").trim();
            String active = activeRaw.isBlank() ? "blue" : activeRaw;
            String inactive = "blue".equals(active) ? "green" : "blue";
            String mgmtPortBlue = (managementPort != null && !managementPort.isBlank()) ? managementPort : "0";

            // Rsync active slot to inactive so both run the same app code
            sshAsRoot("rsync -a --delete /home/" + user + "/app-" + active + "/ /home/" + user + "/app-" + inactive + "/");

            if (gracefulDrain) {
                Path tempScript = Files.createTempFile("bg-graceful", ".sh");
                Files.writeString(tempScript, BLUE_GREEN_GRACEFUL_SCRIPT);
                scp(tempScript.toString(), adminUser + "@" + host + ":/tmp/bg-graceful.sh");
                Files.delete(tempScript);
                sshAsRootInteractive("bash /tmp/bg-graceful.sh " + user + " " + proxy + " " + httpsMode + " '" + domain + "'"
                        + " " + slotCookie + " " + drainTimeout + " " + notifyPath + " " + activeUsersPath
                        + " " + mgmtPortBlue + " " + port);
            } else {
                Path tempScript = Files.createTempFile("bg-swap", ".sh");
                Files.writeString(tempScript, BLUE_GREEN_SWAP_SCRIPT);
                scp(tempScript.toString(), adminUser + "@" + host + ":/tmp/bg-swap.sh");
                Files.delete(tempScript);
                sshAsRoot("bash /tmp/bg-swap.sh " + user + " " + proxy + " " + httpsMode + " '" + domain + "'"
                        + " " + mgmtPortBlue + " " + port);
            }
            System.out.println("Service restarted (blue-green swap complete).");
        } else {
            System.out.println("Restarting service ...");
            sshAsRoot("systemctl restart " + user);
            System.out.println("Service restarted.");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static String detectAppType() {
        try {
            boolean hasBuildFile = false;
            boolean mentionsSpringBoot = false;
            for (String buildFile : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
                Path path = Path.of(buildFile);
                if (!Files.exists(path)) continue;
                hasBuildFile = true;
                String content = Files.readString(path);
                if (content.contains("quarkus")) return "quarkus";
                if (content.contains("spring-boot")) mentionsSpringBoot = true;
            }
            if (hasBuildFile && !mentionsSpringBoot) return "plain";
        } catch (IOException e) {
            // fall through to default
        }
        return "spring-boot";
    }

    /** Stage the built fat jar as $user.jar in a clean dir suitable for rsync --delete. */
    static Path stagePlainJar(boolean mavenw, boolean pom) throws IOException {
        Path jarDir = (mavenw || pom) ? Path.of("target") : Path.of("build", "libs");
        Path jar = findJar(jarDir);
        System.out.println("Found jar: " + jar);
        Path staging = jar.getParent().resolve("plain-staging");
        if (Files.exists(staging)) {
            deleteRecursively(staging);
        }
        Files.createDirectories(staging);
        Files.copy(jar, staging.resolve(user + ".jar"));
        return staging;
    }

    static Path findJar(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files
                    .filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.toString().endsWith("-plain.jar"))
                    .filter(p -> !p.getFileName().toString().startsWith("original-"))
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

    /** Like sshAsRoot but allocates a pseudo-terminal (-t) so remote interactive reads work. */
    static void sshAsRootInteractive(String command) throws Exception {
        if (!"root".equals(adminUser)) {
            command = "sudo " + command;
        }
        int exit = new ProcessBuilder("ssh", "-t", "-i", sshKey, "-o", "StrictHostKeyChecking=accept-new",
                adminUser + "@" + host, command)
                .inheritIO()
                .start()
                .waitFor();
        if (exit != 0) {
            System.err.println("Remote command failed (exit " + exit + ")");
            System.exit(exit);
        }
    }

    static String sshOutputAsRoot(String command) throws Exception {
        if (!"root".equals(adminUser)) {
            command = "sudo " + command;
        }
        var pb = new ProcessBuilder("ssh", "-i", sshKey, "-o", "StrictHostKeyChecking=accept-new",
                adminUser + "@" + host, command);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        var process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            System.err.println("Remote command failed (exit " + exit + ")");
            System.exit(exit);
        }
        return output;
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
