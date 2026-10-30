///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 25+
//DEPS in.virit.ws:jbang-starter:25.2.1

package boot2vm;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.theme.aura.Aura;
import com.vaadin.open.Open;
import in.virit.widgetset.jbang.ViritinJBangAutoconfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.vaadin.firitin.form.FormBinder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Deploy {

    static String host, user, domain, sshKey, adminUser, proxy, appType, jdkProvider;
    static String httpsMode = "yes";
    static String jvmOpts = "", userGroups = "";

    /** A deploy target: one server config file. "default" is vmhosting.conf, others vmhosting.<name>.conf */
    record Target(String name, Path path) {
        static Target named(String name) {
            return "default".equals(name)
                    ? new Target("default", Path.of("vmhosting.conf"))
                    : new Target(name, Path.of("vmhosting." + name + ".conf"));
        }
    }

    /** Thrown when a local or remote command fails; main() turns it into the exit code. */
    static class CommandFailed extends RuntimeException {
        final int exitCode;
        CommandFailed(String message, int exitCode) {
            super(message);
            this.exitCode = exitCode;
        }
    }

    /** The target whose config is currently loaded into the static fields. */
    static Target currentTarget = Target.named("default");
    static boolean blueGreen;
    static boolean gracefulDrain;
    static boolean webService = true;
    static int port = 8080;
    static String slotCookie = "X-Slot", managementPort = "", notifyPath = "/actuator/new-version", activeUsersPath = "/actuator/active-users";
    static int drainTimeout = 300;

    static final String SETUP_SCRIPT = """
            #!/bin/bash
            set -euo pipefail
            # Never stop to ask (tzdata and friends would wait for an answer nobody gives)
            export DEBIAN_FRONTEND=noninteractive
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
            JDK_PROVIDER="${12:-temurin}"  # temurin, zulu or semeru (OpenJ9)
            PORT="${13:-8080}"             # app port; blue-green green slot uses PORT+1
            JVM_OPTS="${14:-}"             # extra JVM flags, exposed to the unit as JAVA_OPTS
            USER_GROUPS="${15:-}"          # comma-separated extra groups for the app user (gpio,i2c,...)
            AUTO_UPDATES="${16:-yes}"      # unattended security updates with a nightly reboot when needed

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

            # Install missing packages only. 'apt-get update' is slow on small boards, so it is
            # skipped when the package lists are less than a week old (unattended-upgrades keeps
            # them fresh daily) and run only for unknown packages (a repository was just added)
            # or when an install fails because the cached lists have gone stale.
            APT_UPDATED=""
            apt_update() {
                if [ -z "$APT_UPDATED" ]; then apt-get update; APT_UPDATED=1; fi
            }
            apt_install() {
                local missing=() p
                for p in "$@"; do
                    dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q "install ok installed" || missing+=("$p")
                done
                [ ${#missing[@]} -eq 0 ] && return 0
                if [ -z "$(find /var/lib/apt/lists -maxdepth 1 -type f -name '*Release' -mtime -7 2>/dev/null | head -n1)" ]; then
                    apt_update
                fi
                for p in "${missing[@]}"; do
                    apt-cache show "$p" >/dev/null 2>&1 || { APT_UPDATED=""; apt_update; break; }
                done
                apt-get install -y "${missing[@]}" || { APT_UPDATED=""; apt_update; apt-get install -y "${missing[@]}"; }
            }

            echo "=== Setting up server for user '$APP_USER' with domain '$DOMAIN' ==="

            # Wait for any background apt/dpkg process to release the lock
            echo "--- Waiting for package manager lock ---"
            while ! flock -n /var/lib/dpkg/lock-frontend true 2>/dev/null; do
                echo "  Package manager is busy, retrying in 10s ..."
                sleep 10
            done

            # deploy syncs the app with rsync, which minimal images leave out
            apt_install rsync

            # 1. Automatic security updates with nightly reboot if required
            if [ "$AUTO_UPDATES" = "yes" ]; then
                echo "--- Configuring unattended-upgrades ---"
                apt_install unattended-upgrades
                cat > /etc/apt/apt.conf.d/20auto-upgrades << 'CONF'
            APT::Periodic::Update-Package-Lists "1";
            APT::Periodic::Unattended-Upgrade "1";
            CONF
                cat > /etc/apt/apt.conf.d/50unattended-upgrades-local << 'CONF'
            Unattended-Upgrade::Automatic-Reboot "true";
            Unattended-Upgrade::Automatic-Reboot-Time "02:00";
            CONF
                systemctl enable --now unattended-upgrades
            else
                # Off, also when the image or an earlier init turned them on; the package
                # lists are still refreshed daily, so installs stay quick
                echo "--- Turning off unattended-upgrades ---"
                cat > /etc/apt/apt.conf.d/20auto-upgrades << 'CONF'
            APT::Periodic::Update-Package-Lists "1";
            APT::Periodic::Unattended-Upgrade "0";
            CONF
                rm -f /etc/apt/apt.conf.d/50unattended-upgrades-local
            fi

            # 2. Install JDK 25 (Eclipse Adoptium / Temurin, Azul Zulu or IBM Semeru / OpenJ9)
            JDK_PROVIDER="$(printf '%s' "$JDK_PROVIDER" | tr '[:upper:]' '[:lower:]')"
            echo "--- Installing JDK 25 (provider: $JDK_PROVIDER) ---"
            case "$JDK_PROVIDER" in
                zulu)
                    apt_install gnupg ca-certificates curl
                    curl -s https://repos.azul.com/azul-repo.key | gpg --dearmor -o /usr/share/keyrings/azul.gpg
                    chmod 644 /usr/share/keyrings/azul.gpg
                    echo "deb [signed-by=/usr/share/keyrings/azul.gpg] https://repos.azul.com/zulu/deb stable main" \\
                        > /etc/apt/sources.list.d/zulu.list
                    apt_install zulu25-jdk
                    ;;
                temurin)
                    apt_install wget apt-transport-https gpg
                    wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \\
                        | gpg --dearmor --yes -o /usr/share/keyrings/adoptium.gpg
                    echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" \\
                        > /etc/apt/sources.list.d/adoptium.list
                    apt_install temurin-25-jdk
                    ;;
                semeru)
                    # IBM Semeru (OpenJ9 VM) has no apt repository: install the latest GitHub release tarball
                    apt_install curl ca-certificates
                    case "$(dpkg --print-architecture)" in
                        amd64) SEMERU_ARCH=x64 ;;
                        arm64) SEMERU_ARCH=aarch64 ;;
                        ppc64el) SEMERU_ARCH=ppc64le ;;
                        s390x) SEMERU_ARCH=s390x ;;
                        *) echo "Semeru has no Linux build for $(dpkg --print-architecture); use temurin or zulu." >&2; exit 1 ;;
                    esac
                    SEMERU_URL="$(curl -fsSL https://api.github.com/repos/ibmruntimes/semeru25-binaries/releases/latest \\
                        | grep -o 'https://[^"]*' | grep "/ibm-semeru-open-jdk_${SEMERU_ARCH}_linux_[^/]*\\.tar\\.gz$" | head -n1)"
                    if [ -z "$SEMERU_URL" ]; then
                        echo "Could not find a Semeru 25 JDK download for $SEMERU_ARCH" >&2
                        exit 1
                    fi
                    if [ "$(cat /opt/semeru-25/.download-url 2>/dev/null)" = "$SEMERU_URL" ]; then
                        echo "Semeru is up to date: $SEMERU_URL"
                    else
                        # The ~250 MB tarball goes to disk: /tmp is a small RAM-backed tmpfs on e.g. Raspberry Pi OS
                        rm -f /tmp/semeru-jdk.tar.gz  # left behind by older versions of this script
                        SEMERU_TMP="$(mktemp -d /var/tmp/semeru.XXXXXX)"
                        trap 'rm -rf "$SEMERU_TMP"' EXIT
                        curl -fsSL -o "$SEMERU_TMP/jdk.tar.gz" "$SEMERU_URL"
                        echo "$(curl -fsSL "$SEMERU_URL.sha256.txt" | cut -d' ' -f1)  $SEMERU_TMP/jdk.tar.gz" | sha256sum -c -
                        rm -rf /opt/semeru-25 && mkdir -p /opt/semeru-25
                        tar -xzf "$SEMERU_TMP/jdk.tar.gz" -C /opt/semeru-25 --strip-components=1
                        echo "$SEMERU_URL" > /opt/semeru-25/.download-url
                        rm -rf "$SEMERU_TMP"
                        trap - EXIT
                    fi
                    for tool in java javac jar jcmd jps jstack; do
                        update-alternatives --install "/usr/bin/$tool" "$tool" "/opt/semeru-25/bin/$tool" 2500
                        update-alternatives --set "$tool" "/opt/semeru-25/bin/$tool"
                    done
                    ;;
                *)
                    echo "Unsupported JDK_PROVIDER: '$JDK_PROVIDER'. Supported values are: temurin, zulu, semeru." >&2
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
                apt_install debian-keyring debian-archive-keyring apt-transport-https curl
                curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \\
                    | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
                curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \\
                    > /etc/apt/sources.list.d/caddy-stable.list
                apt_install caddy
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
                systemctl reload-or-restart caddy
            else
                echo "--- Skipping reverse proxy installation ---"
            fi

            # 7. Configure firewall
            if [ "$FIREWALL" = "yes" ]; then
                echo "--- Configuring firewall (ufw) ---"
                apt_install ufw
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

    public static void main(String[] rawArgs) throws Exception {
        // Split "@name" target selectors (and "--from @name" for init) from the real arguments
        var selected = new ArrayList<String>();
        var rest = new ArrayList<String>();
        String from = null;
        // How init asks: "tui", "gui", or null to pick the form when a browser can be opened
        String ui = "1".equals(System.getenv("BOOT2VM_TUI")) || "true".equalsIgnoreCase(System.getenv("BOOT2VM_TUI")) ? "tui" : null;
        for (int i = 0; i < rawArgs.length; i++) {
            String arg = rawArgs[i];
            if ("--tui".equals(arg) || "--gui".equals(arg)) {
                ui = arg.substring(2);
            } else if ("--from".equals(arg)) {
                if (i + 1 >= rawArgs.length) throw new IllegalArgumentException("--from needs a target name");
                from = rawArgs[++i].replaceFirst("^@", "");
            } else if (arg.startsWith("@") && arg.length() > 1) {
                selected.add(arg.substring(1));
            } else {
                rest.add(arg);
            }
        }
        String[] args = rest.toArray(new String[0]);
        String command = args.length == 0 ? "deploy" : args[0];

        try {
            switch (command) {
                case "init" -> init(selected, from, ui);
                case "deploy" -> deployTargets(resolveTargets(selected, true));
                case "logs" -> { loadConfig(singleTarget(selected, "logs")); logs(args); }
                case "env" -> env(args, selected);
                case "add-key" -> addKey(args, resolveTargets(selected, true));
                case "clean" -> clean(selected);
                case "root-cert" -> { loadConfig(singleTarget(selected, "root-cert")); rootCert(args); }
                case "targets" -> listTargets();
                default -> {
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
                }
            }
        } catch (CommandFailed e) {
            System.err.println(e.getMessage());
            System.exit(e.exitCode);
        }
    }

    // -----------------------------------------------------------------------
    // targets – one app deployed to several servers, one config file each
    // -----------------------------------------------------------------------

    /** All targets found in the current directory: vmhosting.conf first, then vmhosting.<name>.conf sorted. */
    static List<Target> allTargets() throws IOException {
        var targets = new ArrayList<Target>();
        Target def = Target.named("default");
        if (Files.exists(def.path())) targets.add(def);
        var named = new ArrayList<Target>();
        try (var stream = Files.list(Path.of("."))) {
            stream.map(f -> f.getFileName().toString())
                    .filter(n -> n.matches("vmhosting\\.[A-Za-z0-9_-]+\\.conf"))
                    .sorted()
                    .forEach(n -> named.add(Target.named(n.substring("vmhosting.".length(), n.length() - ".conf".length()))));
        }
        targets.addAll(named);
        return targets;
    }

    /**
     * Targets to operate on: the ones selected with @name, or (when allowed) every
     * target in the directory. Fails when nothing matches so a typo never silently
     * hits the wrong server.
     */
    static List<Target> resolveTargets(List<String> selected, boolean allWhenUnselected) throws IOException {
        List<Target> all = allTargets();
        if (all.isEmpty()) {
            throw new CommandFailed("No vmhosting.conf (or vmhosting.<name>.conf) found in current directory. Run 'init' first.", 1);
        }
        if (selected.isEmpty()) {
            if (allWhenUnselected) return all;
            throw new CommandFailed("Select a target with @name. Available: " + targetNames(all), 1);
        }
        var result = new ArrayList<Target>();
        for (String name : selected) {
            Target t = Target.named(name);
            if (!Files.exists(t.path())) {
                throw new CommandFailed("Unknown target '@" + name + "' (" + t.path() + " not found). Available: " + targetNames(all), 1);
            }
            if (!result.contains(t)) result.add(t);
        }
        return result;
    }

    /** Commands like logs need exactly one target; with a single config it is implied. */
    static Target singleTarget(List<String> selected, String command) throws IOException {
        List<Target> all = allTargets();
        if (selected.isEmpty() && all.size() == 1) return all.get(0);
        if (selected.isEmpty()) {
            throw new CommandFailed("'" + command + "' needs one target, e.g. 'Deploy " + command + " @" + all.get(0).name()
                    + "'. Available: " + targetNames(all), 1);
        }
        if (selected.size() > 1) {
            throw new CommandFailed("'" + command + "' works on one target at a time", 1);
        }
        return resolveTargets(selected, false).get(0);
    }

    static String targetNames(List<Target> targets) {
        var names = new ArrayList<String>();
        for (Target t : targets) names.add("@" + t.name());
        return String.join(" ", names);
    }

    static void printTargetHeader(Target target) {
        System.out.println();
        System.out.println("=== " + target.name() + " (" + user + "@" + host + ") ===");
    }

    static void listTargets() throws Exception {
        List<Target> all = allTargets();
        if (all.isEmpty()) {
            System.out.println("No targets. Run 'Deploy init' (or 'Deploy init @name') first.");
            return;
        }
        System.out.printf("%-12s %-30s %-14s %-6s %s%n", "TARGET", "HOST", "USER", "PORT", "FILE");
        for (Target t : all) {
            loadConfig(t);
            String mode = blueGreen ? (gracefulDrain ? "blue-green+drain" : "blue-green") : "simple";
            System.out.printf("%-12s %-30s %-14s %-6d %s (%s, https=%s)%n",
                    "@" + t.name(), host, user, port, t.path(), mode, httpsMode);
        }
    }

    /**
     * Run an action for each target in order, loading its config first. Stops at
     * the first failure and lists the targets that were not reached.
     */
    interface TargetAction { void run(Target target) throws Exception; }

    static void forEachTarget(List<Target> targets, TargetAction action) throws Exception {
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            loadConfig(target);
            if (targets.size() > 1) printTargetHeader(target);
            try {
                action.run(target);
            } catch (CommandFailed e) {
                if (i + 1 < targets.size()) {
                    System.err.println("Failed on @" + target.name() + "; not attempted: "
                            + targetNames(targets.subList(i + 1, targets.size())));
                }
                throw e;
            }
        }
    }

    static void printUsage() {
        System.out.println("Usage: jbang Deploy.java <command> [@target ...]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  init           - Set up the server (run once); 'init @name [--from @other]' adds another server");
        System.out.println("                   Opens a form in the browser; --tui (or BOOT2VM_TUI=1) asks in the terminal,");
        System.out.println("                   --gui serves the form even when no browser can be opened here");
        System.out.println("  deploy         - Build once, then sync and restart on every target (or the given @targets)");
        System.out.println("  targets        - List the configured servers");
        System.out.println("  logs [n] [slot] - Tail the application logs (default: 200 lines)");
        System.out.println("                    slot: active (default), inactive, blue, green");
        System.out.println("  env            - List environment variables on the server");
        System.out.println("  env set K=V    - Set env var(s), then restart the service");
        System.out.println("  env remove K   - Remove env var(s), then restart the service");
        System.out.println("  env list       - List environment variables on the server");
        System.out.println("  add-key [file] - Add an SSH public key to the server");
        System.out.println("  clean          - Remove the app, service, and user from the server");
        System.out.println("  root-cert [file] - Export Caddy public root CA (default: caddy-root.crt)");
        System.out.println();
        System.out.println("Targets: vmhosting.conf is @default, vmhosting.<name>.conf is @<name>.");
        System.out.println("deploy, env and add-key run on all targets unless @targets are given;");
        System.out.println("logs, root-cert and clean work on one target.");
    }

    /**
     * True when every given (space-separated) address is something a public CA
     * cannot issue a certificate for: a .local/mDNS name, a bare hostname without
     * a dot, or an IP address. Used to default HTTPS to "no" for LAN devices.
     */
    static boolean isLocalAddress(String addresses) {
        for (String address : addresses.trim().split("\\s+")) {
            String a = address.toLowerCase(Locale.ROOT);
            int colon = a.indexOf(':');
            if (colon > 0 && a.indexOf(':', colon + 1) < 0) {
                a = a.substring(0, colon); // strip a :port suffix, but not an IPv6 literal
            }
            boolean local = a.endsWith(".local") || a.endsWith(".lan") || a.endsWith(".home.arpa")
                    || a.endsWith(".internal") || !a.contains(".")
                    || a.matches("\\d{1,3}(\\.\\d{1,3}){3}") || a.contains(":");
            if (!local) {
                return false;
            }
        }
        return true;
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

    static void loadConfig(Target target) throws IOException {
        Path config = target.path();
        if (!Files.exists(config)) {
            throw new CommandFailed(config + " not found in current directory. Run 'init' first.", 1);
        }
        currentTarget = target;
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
            throw new CommandFailed("HOST and USER must be set in " + config, 1);
        }
        if (host.contains(",") || host.matches(".*\\s.*")) {
            throw new CommandFailed("HOST in " + config + " must be a single hostname or IP (got: '" + host
                    + "'). Put additional domains in DOMAIN instead.", 1);
        }
    }

    // -----------------------------------------------------------------------
    // init – interactively collect config, write vmhosting.conf, set up server
    // -----------------------------------------------------------------------
    static void init(List<String> selected, String from, String ui) throws Exception {
        if (selected.size() > 1) {
            throw new CommandFailed("init sets up one target at a time", 1);
        }
        String targetName = selected.isEmpty() ? "default" : selected.get(0);
        if (!isValidTargetName(targetName)) {
            throw new CommandFailed("Target name may contain only letters, digits, '-' and '_': " + targetName, 1);
        }
        if (from != null && !Files.exists(Target.named(from).path())) {
            throw new CommandFailed("--from target '@" + from + "' not found (" + Target.named(from).path() + ")", 1);
        }
        // First server of a fresh project: offer a name right away, so that later
        // additions leave no anonymous "default" whose host nobody remembers.
        boolean askName = selected.isEmpty() && allTargets().isEmpty();

        String noGui = ui == null ? guiUnavailableReason() : null;
        if ("tui".equals(ui) || noGui != null) {
            if (noGui != null) System.out.println(noGui + ", using the terminal UI.");
            InitResult result = promptInit(targetName, askName, from);
            runInit(result.target(), result.config());
        } else {
            InitGui.run(targetName, askName, from);
        }
    }

    record InitResult(Target target, InitConfig config) {}

    static boolean isValidTargetName(String name) {
        return name.matches("[A-Za-z0-9_-]+");
    }

    /**
     * Where init takes its defaults from: --from @other, else the target's own file,
     * else the first existing target. That makes adding a server mostly a matter of
     * typing its host. Null when there is nothing to start from.
     */
    static Path defaultsSource(Target target, String from) throws IOException {
        if (from != null) return Target.named(from).path();
        if (Files.exists(target.path())) return target.path();
        List<Target> existing = allTargets();
        return existing.isEmpty() ? null : existing.get(0).path();
    }

    /** Init defaults for the target, read from the given config file (null for built-in defaults only). */
    static InitConfig initDefaults(Target target, Path source) throws IOException {
        InitConfig config = source == null ? new InitConfig() : InitConfig.load(source, source.equals(target.path()));
        config.fillDefaults();
        return config;
    }

    /** Write the target's config file, provision the server and run the first deploy. */
    static void runInit(Target target, InitConfig config) throws Exception {
        currentTarget = target;
        Path configPath = target.path();
        config.apply();
        Files.writeString(configPath, config.toConf());
        System.out.println("Wrote " + configPath);

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
                + " " + (config.isFirewall() ? "yes" : "no")
                + " " + (config.isExposeNodes() ? "yes" : "no")
                + " " + (webService ? "yes" : "no")
                + " " + jdkProvider
                + " " + port
                + " " + shellQuote(jvmOpts)
                + " " + shellQuote(userGroups)
                + " " + (config.isAutoUpdates() ? "yes" : "no"));

        Files.delete(tempScript);

        // Write env vars to server if any were provided
        List<String> envVars = config.envLines();
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

        // Automatically run first deploy, for this target only
        deployTargets(List.of(target));
    }

    /** The terminal UI for init: one prompt per setting, defaults in brackets. */
    static InitResult promptInit(String targetName, boolean askName, String from) throws IOException {
        var console = System.console();
        if (console == null) {
            throw new CommandFailed("No console available for interactive input", 1);
        }
        if (askName) {
            while (true) {
                targetName = prompt(console, "Name for this server target (used as @name; blank keeps plain vmhosting.conf)", "default");
                if (isValidTargetName(targetName)) break;
                System.err.println("  Use only letters, digits, '-' and '_'");
            }
        }
        Target target = Target.named(targetName);
        Path source = defaultsSource(target, from);
        if (source != null && !source.equals(target.path())) {
            System.out.println("New target @" + target.name() + " (" + target.path() + "), defaults taken from " + source);
        }
        InitConfig c = initDefaults(target, source);

        // HOST (required, single hostname — extra domains are asked separately)
        while (true) {
            c.setHost(prompt(console, "Host (SSH address, a single hostname or IP)", c.getHost()));
            if (c.getHost().isBlank()) {
                throw new CommandFailed("Host is required", 1);
            }
            if (c.getHost().contains(",") || c.getHost().matches(".*\\s.*")) {
                System.err.println("  Host must be a single hostname or IP — additional domains are entered at the 'Domain(s)' prompt below.");
                continue;
            }
            break;
        }

        c.setUser(prompt(console, "App user", c.getUser() != null ? c.getUser() : c.derivedUser()));
        c.setWebService(!"no".equalsIgnoreCase(prompt(console, "Does this service expose web endpoints (yes/no)",
                c.isWebService() ? "yes" : "no")));
        if (c.isWebService()) {
            c.setDomain(prompt(console, "Domain(s) (comma-separated for multiple)", c.getDomain() != null ? c.getDomain() : c.getHost()));
        }
        c.setSshKey(promptSshKey(console, c.getSshKey()));
        c.setAdminUser(prompt(console, "Admin SSH user", c.getAdminUser()));
        if (c.isWebService()) {
            c.setHttps(prompt(console, "HTTPS (yes/no/internal)", c.getHttps() != null ? c.getHttps() : c.derivedHttps()));
            c.setProxy(prompt(console, "Reverse proxy (caddy/none)", c.getProxy()));
            c.setPort(prompt(console, "App port (must be unique per app on the server; blue-green also uses port+1)", c.getPort()));
        }
        c.setAppType(prompt(console, "App type (spring-boot/quarkus/plain)", c.getAppType()));
        c.setJdkProvider(prompt(console, "JDK provider (temurin/zulu/semeru)", c.getJdkProvider()));
        promptJvmAndHardware(console, c);
        if (c.isWebService()) {
            c.setBlueGreen("yes".equalsIgnoreCase(prompt(console, "Blue-green deployment (yes/no) [not recommended for low-end servers]",
                    c.isBlueGreen() ? "yes" : "no")));
            if (c.isBlueGreen()) {
                c.setGracefulDrain("yes".equalsIgnoreCase(prompt(console, "Graceful drain mode (yes/no)",
                        c.isGracefulDrain() ? "yes" : "no")));
                if (c.isGracefulDrain()) {
                    c.setSlotCookie(prompt(console, "Slot cookie name", c.getSlotCookie()));
                    c.setDrainTimeout(prompt(console, "Drain timeout (seconds)", c.getDrainTimeout()));
                    c.setManagementPort(prompt(console, "Management port (blank = app port)", c.getManagementPort()));
                    c.setNotifyPath(prompt(console, "Notify path", c.getNotifyPath()));
                    c.setActiveUsersPath(prompt(console, "Active users path", c.getActiveUsersPath()));
                }
            }
        }

        c.setAutoUpdates("yes".equalsIgnoreCase(prompt(console, "Automatic security updates, rebooting at 02:00 when needed (yes/no)",
                c.isAutoUpdates() ? "yes" : "no")));
        c.setFirewall("yes".equalsIgnoreCase(prompt(console, "Configure firewall with ufw (yes/no)",
                c.isFirewall() ? "yes" : "no")));
        if (c.isFirewall() && c.isWebService()) {
            int p = c.getPort().matches("\\d+") ? Integer.parseInt(c.getPort()) : 8080;
            String exposePortsLabel = c.isBlueGreen() ? p + "/" + (p + 1) : String.valueOf(p);
            c.setExposeNodes("yes".equalsIgnoreCase(prompt(console, "Expose app server port(s) " + exposePortsLabel + " for direct access (yes/no)",
                    c.isExposeNodes() ? "yes" : "no")));
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
        c.setEnv(String.join("\n", envVars));

        c.fillHostDerived();
        c.normalize();
        var errors = c.validate();
        if (!errors.isEmpty()) {
            errors.values().forEach(e -> System.err.println("  " + e));
            throw new CommandFailed("Invalid settings, nothing was written", 1);
        }
        return new InitResult(target, c);
    }

    /**
     * Everything init asks, one property per vmhosting.conf key plus the app's
     * environment variables, which only ever go to the server. A plain bean so
     * that both the terminal UI and the browser form (via FormBinder) can edit it.
     * Null means "not set yet": fillDefaults() and fillHostDerived() fill those in.
     */
    public static class InitConfig {
        static final List<String> HTTPS_MODES = List.of("yes", "no", "internal");
        static final List<String> PROXIES = List.of("caddy", "none");
        static final List<String> APP_TYPES = List.of("spring-boot", "quarkus", "plain");
        static final List<String> JDK_PROVIDERS = List.of("temurin", "zulu", "semeru");

        private String host, user, domain, sshKey, adminUser, https, proxy, port, appType, jdkProvider;
        private String jvmOpts, userGroups;
        private String slotCookie, drainTimeout, managementPort, notifyPath, activeUsersPath;
        private String env;
        private boolean webService = true, blueGreen, gracefulDrain, firewall = true, exposeNodes, autoUpdates = true;

        /**
         * Read a config file. Another target's host and domains, and the HTTPS
         * mode that depends on them, are never taken over as defaults for a new target.
         */
        static InitConfig load(Path path, boolean ownConfig) throws IOException {
            var props = new Properties();
            try (var reader = Files.newBufferedReader(path)) {
                props.load(reader);
            }
            var c = new InitConfig();
            c.host = ownConfig ? props.getProperty("HOST") : null;
            c.domain = ownConfig ? props.getProperty("DOMAIN") : null;
            c.user = props.getProperty("USER");
            c.sshKey = props.getProperty("SSH_KEY");
            c.adminUser = props.getProperty("ADMIN_USER");
            c.https = ownConfig ? props.getProperty("HTTPS") : null;
            c.proxy = props.getProperty("PROXY");
            c.port = props.getProperty("PORT");
            c.appType = props.getProperty("APP_TYPE");
            c.jdkProvider = props.getProperty("JDK_PROVIDER");
            c.jvmOpts = props.getProperty("JVM_OPTS");
            c.userGroups = props.getProperty("USER_GROUPS");
            c.slotCookie = props.getProperty("SLOT_COOKIE");
            c.drainTimeout = props.getProperty("DRAIN_TIMEOUT");
            c.managementPort = props.getProperty("MANAGEMENT_PORT");
            c.notifyPath = props.getProperty("NOTIFY_PATH");
            c.activeUsersPath = props.getProperty("ACTIVE_USERS_PATH");
            c.webService = !"no".equalsIgnoreCase(props.getProperty("WEB_SERVICE", "yes"));
            c.blueGreen = "yes".equalsIgnoreCase(props.getProperty("BLUE_GREEN", "no"));
            c.gracefulDrain = "yes".equalsIgnoreCase(props.getProperty("BLUE_GREEN_GRACEFUL", "no"));
            c.firewall = "yes".equalsIgnoreCase(props.getProperty("FIREWALL", "yes"));
            c.autoUpdates = !"no".equalsIgnoreCase(props.getProperty("AUTO_UPDATES", "yes"));
            c.exposeNodes = "yes".equalsIgnoreCase(props.getProperty("EXPOSE_NODES", "no"));
            // Without graceful drain the port is written blank: suggest the usual one if it gets enabled
            if (!c.gracefulDrain && c.managementPort != null && c.managementPort.isBlank()) c.managementPort = null;
            return c;
        }

        /** Built-in defaults for everything that does not depend on the host. */
        void fillDefaults() throws IOException {
            if (sshKey == null) sshKey = defaultSshKey(listSshKeys(), null);
            else sshKey = defaultSshKey(List.of(), sshKey);
            if (adminUser == null) adminUser = "root";
            if (proxy == null) proxy = "caddy";
            if (port == null) port = "8080";
            if (appType == null) appType = detectAppType();
            if (jdkProvider == null) jdkProvider = "temurin";
            if (jvmOpts == null) jvmOpts = "";
            if (userGroups == null) userGroups = "";
            if (slotCookie == null) slotCookie = "X-Slot";
            if (drainTimeout == null) drainTimeout = "300";
            if (managementPort == null) managementPort = "8090";
            if (notifyPath == null) notifyPath = "/actuator/new-version";
            if (activeUsersPath == null) activeUsersPath = "/actuator/active-users";
        }

        /** The app user suggested for the current host: its first label. */
        String derivedUser() {
            if (host == null || host.isBlank()) return null;
            String h = host.trim();
            return h.contains(".") ? h.substring(0, h.indexOf('.')) : h;
        }

        /** HTTPS suggested for the current domains: off for LAN names a public CA cannot certify. */
        String derivedHttps() {
            String d = domain != null && !domain.isBlank() ? domain : host;
            if (d == null || d.isBlank()) return "yes";
            return isLocalAddress(d.replaceAll("\\s*,\\s*", " ")) ? "no" : "yes";
        }

        /** Fill the values left blank that are derived from the host. */
        void fillHostDerived() {
            if (host == null || host.isBlank()) return;
            host = host.trim();
            if (user == null || user.isBlank()) user = derivedUser();
            if (domain == null || domain.isBlank()) domain = host;
            if (https == null || https.isBlank()) https = derivedHttps();
        }

        /** Trim values and switch off settings that do not apply to the chosen mode. */
        void normalize() {
            if (domain != null) domain = domain.replaceAll("[\\s,]+", " ").trim();
            if (jvmOpts != null) jvmOpts = jvmOpts.trim().replaceAll("\\s+", " ");
            if (userGroups != null) userGroups = userGroups.replaceAll("[\\s,]+", ",").replaceAll("^,|,$", "");
            if (https != null) https = https.trim().toLowerCase(Locale.ROOT);
            if (!webService) {
                https = "no";
                proxy = "none";
                blueGreen = false;
            }
            if (!blueGreen) gracefulDrain = false;
            if (!gracefulDrain) managementPort = "";
            if (!firewall || !webService) exposeNodes = false;
        }

        /** Problems keyed by property name; empty when the config can be written. */
        Map<String, String> validate() {
            var errors = new LinkedHashMap<String, String>();
            if (host == null || host.isBlank()) errors.put("host", "Host is required");
            else if (host.contains(",") || host.matches(".*\\s.*"))
                errors.put("host", "Host must be a single hostname or IP; put additional domains in Domain(s)");
            // Blank user, domain and HTTPS get values derived from the host, see fillHostDerived()
            String u = user == null || user.isBlank() ? derivedUser() : user;
            if (u == null || !u.matches("[a-z_][a-z0-9_-]{0,31}"))
                errors.put("user", "App user must be a Linux user name: lowercase letters, digits, '-' and '_'");
            if (sshKey == null || sshKey.isBlank()) errors.put("sshKey", "SSH public key is required");
            if (adminUser == null || adminUser.isBlank()) errors.put("adminUser", "Admin SSH user is required");
            if (!APP_TYPES.contains(appType)) errors.put("appType", "App type must be one of " + APP_TYPES);
            if (!JDK_PROVIDERS.contains(jdkProvider)) errors.put("jdkProvider", "JDK provider must be one of " + JDK_PROVIDERS);
            if (webService) {
                String h = https == null || https.isBlank() ? derivedHttps() : https.trim().toLowerCase(Locale.ROOT);
                if (!HTTPS_MODES.contains(h)) errors.put("https", "HTTPS must be yes, no or internal");
                else if ("internal".equals(h) && !"caddy".equals(proxy))
                    errors.put("https", "HTTPS internal requires Caddy as the reverse proxy");
                if (!PROXIES.contains(proxy)) errors.put("proxy", "Reverse proxy must be caddy or none");
                if (!isPort(port)) errors.put("port", "App port must be a number between 1 and 65535");
            }
            if (gracefulDrain) {
                if (slotCookie == null || !slotCookie.matches("[A-Za-z0-9_-]+"))
                    errors.put("slotCookie", "Slot cookie name may contain only letters, digits, '-' and '_'");
                if (drainTimeout == null || !drainTimeout.matches("\\d+")) errors.put("drainTimeout", "Drain timeout must be a number of seconds");
                if (managementPort != null && !managementPort.isBlank() && !isPort(managementPort))
                    errors.put("managementPort", "Management port must be blank or a number between 1 and 65535");
            }
            if (jvmOpts != null && jvmOpts.contains("\""))
                errors.put("jvmOpts", "Double quotes are not supported in JVM options (the value is written into a quoted systemd Environment= line)");
            if (userGroups != null && !userGroups.matches("[a-z0-9_,\\s-]*"))
                errors.put("userGroups", "Groups must be comma-separated Linux group names");
            for (String line : envLines()) {
                if (!line.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
                    errors.put("env", "Invalid environment variable line, expected KEY=VALUE: " + line);
                    break;
                }
            }
            return errors;
        }

        static boolean isPort(String value) {
            if (value == null || !value.trim().matches("\\d{1,5}")) return false;
            int p = Integer.parseInt(value.trim());
            return p > 0 && p < 65536;
        }

        List<String> envLines() {
            if (env == null) return List.of();
            return env.lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
        }

        /** Copy into the static fields the rest of the tool works with. */
        void apply() {
            Deploy.host = host;
            Deploy.user = user;
            Deploy.domain = domain;
            Deploy.adminUser = adminUser;
            Deploy.httpsMode = https;
            Deploy.proxy = proxy;
            Deploy.webService = webService;
            Deploy.appType = appType;
            Deploy.jdkProvider = jdkProvider;
            Deploy.port = Integer.parseInt(port.trim());
            Deploy.blueGreen = blueGreen;
            Deploy.gracefulDrain = gracefulDrain;
            Deploy.slotCookie = slotCookie;
            Deploy.drainTimeout = Integer.parseInt(drainTimeout.trim());
            Deploy.managementPort = managementPort;
            Deploy.notifyPath = notifyPath;
            Deploy.activeUsersPath = activeUsersPath;
            Deploy.jvmOpts = jvmOpts;
            Deploy.userGroups = userGroups;
            validateHttpsMode();
            // Resolve the private key path for SSH connections (strip .pub if present)
            String key = sshKey.endsWith(".pub") ? sshKey.substring(0, sshKey.length() - 4) : sshKey;
            Deploy.sshKey = key.startsWith("~") ? System.getProperty("user.home") + key.substring(1) : key;
        }

        String toConf() {
            return "HOST=" + host + "\n"
                    + "USER=" + user + "\n"
                    + "DOMAIN=" + domain + "\n"
                    + "SSH_KEY=" + sshKey + "\n"
                    + "ADMIN_USER=" + adminUser + "\n"
                    + "HTTPS=" + https + "\n"
                    + "PROXY=" + proxy + "\n"
                    + "APP_TYPE=" + appType + "\n"
                    + "JDK_PROVIDER=" + jdkProvider + "\n"
                    + "PORT=" + port.trim() + "\n"
                    + "BLUE_GREEN=" + (blueGreen ? "yes" : "no") + "\n"
                    + "BLUE_GREEN_GRACEFUL=" + (gracefulDrain ? "yes" : "no") + "\n"
                    + "SLOT_COOKIE=" + slotCookie + "\n"
                    + "DRAIN_TIMEOUT=" + drainTimeout.trim() + "\n"
                    + "MANAGEMENT_PORT=" + managementPort + "\n"
                    + "NOTIFY_PATH=" + notifyPath + "\n"
                    + "ACTIVE_USERS_PATH=" + activeUsersPath + "\n"
                    + "AUTO_UPDATES=" + (autoUpdates ? "yes" : "no") + "\n"
                    + "FIREWALL=" + (firewall ? "yes" : "no") + "\n"
                    + "EXPOSE_NODES=" + (exposeNodes ? "yes" : "no") + "\n"
                    + "WEB_SERVICE=" + (webService ? "yes" : "no") + "\n"
                    + "JVM_OPTS=" + jvmOpts + "\n"
                    + "USER_GROUPS=" + userGroups + "\n";
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
        public String getSshKey() { return sshKey; }
        public void setSshKey(String sshKey) { this.sshKey = sshKey; }
        public String getAdminUser() { return adminUser; }
        public void setAdminUser(String adminUser) { this.adminUser = adminUser; }
        public String getHttps() { return https; }
        public void setHttps(String https) { this.https = https; }
        public String getProxy() { return proxy; }
        public void setProxy(String proxy) { this.proxy = proxy; }
        public String getPort() { return port; }
        public void setPort(String port) { this.port = port; }
        public String getAppType() { return appType; }
        public void setAppType(String appType) { this.appType = appType; }
        public String getJdkProvider() { return jdkProvider; }
        public void setJdkProvider(String jdkProvider) { this.jdkProvider = jdkProvider; }
        public String getJvmOpts() { return jvmOpts; }
        public void setJvmOpts(String jvmOpts) { this.jvmOpts = jvmOpts; }
        public String getUserGroups() { return userGroups; }
        public void setUserGroups(String userGroups) { this.userGroups = userGroups; }
        public String getSlotCookie() { return slotCookie; }
        public void setSlotCookie(String slotCookie) { this.slotCookie = slotCookie; }
        public String getDrainTimeout() { return drainTimeout; }
        public void setDrainTimeout(String drainTimeout) { this.drainTimeout = drainTimeout; }
        public String getManagementPort() { return managementPort; }
        public void setManagementPort(String managementPort) { this.managementPort = managementPort; }
        public String getNotifyPath() { return notifyPath; }
        public void setNotifyPath(String notifyPath) { this.notifyPath = notifyPath; }
        public String getActiveUsersPath() { return activeUsersPath; }
        public void setActiveUsersPath(String activeUsersPath) { this.activeUsersPath = activeUsersPath; }
        public String getEnv() { return env; }
        public void setEnv(String env) { this.env = env; }
        public boolean isWebService() { return webService; }
        public void setWebService(boolean webService) { this.webService = webService; }
        public boolean isBlueGreen() { return blueGreen; }
        public void setBlueGreen(boolean blueGreen) { this.blueGreen = blueGreen; }
        public boolean isGracefulDrain() { return gracefulDrain; }
        public void setGracefulDrain(boolean gracefulDrain) { this.gracefulDrain = gracefulDrain; }
        public boolean isAutoUpdates() { return autoUpdates; }
        public void setAutoUpdates(boolean autoUpdates) { this.autoUpdates = autoUpdates; }
        public boolean isFirewall() { return firewall; }
        public void setFirewall(boolean firewall) { this.firewall = firewall; }
        public boolean isExposeNodes() { return exposeNodes; }
        public void setExposeNodes(boolean exposeNodes) { this.exposeNodes = exposeNodes; }
    }

    // -----------------------------------------------------------------------
    // init GUI – the same settings as a form in the browser
    // -----------------------------------------------------------------------

    /** Why the browser form cannot be used here, or null when it can. */
    static String guiUnavailableReason() {
        if (System.getenv("SSH_CONNECTION") != null || System.getenv("SSH_TTY") != null) {
            return "Running over SSH";
        }
        boolean linux = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
        if (linux && System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null) {
            return "No graphical display found";
        }
        return null;
    }

    /**
     * Runs the init form as a local Spring Boot app on a random port and opens it
     * in the browser. Once the form is submitted, the setup runs as in the terminal
     * UI, and the window follows its progress until it is done.
     */
    @SpringBootApplication(exclude = ViritinJBangAutoconfiguration.class)
    public static class InitGui {
        static String initialTarget, from;
        static boolean askName;
        static final CompletableFuture<InitResult> result = new CompletableFuture<>();
        static final Progress progress = new Progress();
        static final Set<UI> windows = ConcurrentHashMap.newKeySet();
        /** Seconds between heartbeats of an open window, and silence after which init gives up. */
        static final int HEARTBEAT = 5, GIVE_UP = 30;

        static void run(String targetName, boolean askName, String from) throws Exception {
            InitGui.initialTarget = targetName;
            InitGui.askName = askName;
            InitGui.from = from;
            System.out.println("Opening the init form in your browser (add --tui to answer in the terminal instead) ...");
            var context = new SpringApplicationBuilder(InitGui.class).logStartupInfo(false)
                    // Only this machine may see the form; SERVER_ADDRESS and SERVER_PORT override for tests
                    .properties("server.address=127.0.0.1", "vaadin.heartbeatInterval=" + HEARTBEAT).run();
            // The browser doesn't say when its window closes, but its heartbeats stop
            var watchdog = Executors.newSingleThreadScheduledExecutor();
            watchdog.scheduleWithFixedDelay(() -> {
                long now = System.currentTimeMillis();
                if (!result.isDone() && !windows.isEmpty() && windows.stream().allMatch(ui ->
                        now - ui.getInternals().getLastHeartbeatTimestamp() > GIVE_UP * 1000L)) {
                    System.out.println("The init form window was closed");
                    result.cancel(false);
                }
            }, HEARTBEAT, HEARTBEAT, TimeUnit.SECONDS);
            try {
                InitResult init;
                try {
                    init = result.get();
                } catch (CancellationException e) {
                    throw new CommandFailed("Init cancelled, nothing was written", 1);
                }
                follow(() -> runInit(init.target(), init.config()));
            } finally {
                watchdog.shutdownNow();
                Thread.sleep(500); // let the last response reach the browser
                context.close();
            }
        }

        interface Setup { void run() throws Exception; }

        /** Run the setup, copying everything it prints to the progress shown in the window. */
        static void follow(Setup setup) throws Exception {
            PrintStream out = System.out, err = System.err;
            System.setOut(new PrintStream(progress.new Tee(out), true, out.charset()));
            System.setErr(new PrintStream(progress.new Tee(err), true, err.charset()));
            captureOutput = true;
            try {
                setup.run();
                progress.finish(null);
            } catch (Exception e) {
                progress.finish(e.getMessage() != null ? e.getMessage() : e.toString());
                throw e;
            } finally {
                captureOutput = false;
                System.setOut(out);
                System.setErr(err);
                // Give an open window its next poll to show how it ended
                progress.shown.await(3, TimeUnit.SECONDS);
            }
        }

        @EventListener
        void serverStarted(WebServerInitializedEvent event) {
            String url = "http://localhost:" + event.getWebServer().getPort();
            System.out.println("Init form at " + url + " (closing the window cancels init after " + GIVE_UP + " seconds)");
            try {
                Open.open(url);
            } catch (Exception e) {
                System.out.println("Could not open a browser, open the address above manually");
            }
        }

        @EventListener
        void serviceInit(ServiceInitEvent event) {
            event.getSource().addUIInitListener(uiInit -> {
                UI ui = uiInit.getUI();
                ui.getPage().addStyleSheet(Aura.STYLESHEET);
                ui.addClassNames("aura-accent-purple", "aura-surface");
                // The form is taller than the window: let the page background grow with it
                ui.getStyle().setHeight("auto").setMinHeight("100vh");
                windows.add(ui);
                ui.addDetachListener(detach -> windows.remove(ui));
            });
        }
    }

    /**
     * What the setup has printed so far: the last lines, the current step and a rough
     * share done, judged by the steps it announces.
     */
    static class Progress {
        static final int TAIL = 14;
        /** The steps the setup prints, with how far along each one starts. */
        static final Map<String, Double> STEPS = Map.ofEntries(
                Map.entry("Initializing server", 0.02),
                Map.entry("--- Configuring unattended-upgrades", 0.06),
                Map.entry("--- Turning off unattended-upgrades", 0.06),
                Map.entry("--- Installing JDK", 0.1),
                Map.entry("--- Creating user", 0.4),
                Map.entry("--- Installing systemd", 0.45),
                Map.entry("--- Installing Caddy", 0.5),
                Map.entry("--- Configuring firewall", 0.6),
                Map.entry("Writing environment variables", 0.65),
                Map.entry("Server initialized", 0.68),
                Map.entry("Building application", 0.7),
                Map.entry("Syncing to server", 0.85),
                Map.entry("Running blue-green", 0.92),
                Map.entry("Restarting service", 0.95));
        final Deque<String> tail = new ArrayDeque<>();
        final CountDownLatch shown = new CountDownLatch(1);
        String step = "Starting";
        double done;
        boolean finished;
        String failure;

        record Snapshot(String step, double done, String tail, boolean finished, String failure) {}

        synchronized Snapshot snapshot() {
            return new Snapshot(step, done, String.join("\n", tail), finished, failure);
        }

        synchronized void line(String line) {
            line = line.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "");
            tail.add(line);
            if (tail.size() > TAIL) tail.removeFirst();
            var banner = Pattern.compile("^(?:---|===) (.+?) (?:---|===)$").matcher(line);
            if (banner.matches()) step = banner.group(1);
            for (var s : STEPS.entrySet()) {
                if (line.startsWith(s.getKey())) {
                    if (!line.startsWith("-")) step = line.replaceFirst(" \\.\\.\\.$", "");
                    done = Math.max(done, s.getValue());
                }
            }
        }

        synchronized void finish(String failure) {
            finished = true;
            this.failure = failure;
            if (failure == null) done = 1;
        }

        /** Passes output on to the terminal, and its lines to the progress. */
        class Tee extends OutputStream {
            final OutputStream terminal;
            final ByteArrayOutputStream current = new ByteArrayOutputStream();
            boolean carriageReturn;

            Tee(OutputStream terminal) {
                this.terminal = terminal;
            }

            @Override
            public synchronized void write(int b) throws IOException {
                terminal.write(b);
                // A lone \r is a progress meter redrawing its line, \r\n just ends one (ssh does that)
                if (carriageReturn && b != '\n') current.reset();
                carriageReturn = b == '\r';
                if (b == '\n') {
                    line(current.toString(java.nio.charset.StandardCharsets.UTF_8));
                    current.reset();
                } else if (b != '\r') {
                    current.write(b);
                }
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) throws IOException {
                for (int i = off; i < off + len; i++) write(b[i]);
            }

            @Override
            public void flush() throws IOException {
                terminal.flush();
            }
        }
    }

    /** Replaces the form once it is submitted: follows the setup until it ends. */
    public static class SetupView extends VerticalLayout {
        final InitResult init;
        final H2 title;
        final Span step = new Span();
        final ProgressBar bar = new ProgressBar();
        final Output output = new Output();
        Registration polling;

        SetupView(InitResult init) {
            this.init = init;
            title = new H2("Setting up " + init.config().getHost());
            setPadding(false);
            bar.setWidthFull();
            add(title, new Paragraph("The settings went to " + init.target().path()
                    + ". Below is the latest output, the terminal has all of it."), step, bar, output);
            addAttachListener(e -> {
                e.getUI().setPollInterval(500);
                polling = e.getUI().addPollListener(p -> update());
                update();
            });
        }

        void update() {
            var p = InitGui.progress.snapshot();
            step.setText(p.step());
            bar.setValue(p.done());
            output.setText(p.tail());
            if (!p.finished()) return;

            polling.remove();
            getUI().ifPresent(ui -> {
                ui.setPollInterval(-1);
                ui.beforeClientResponse(this, ctx -> InitGui.progress.shown.countDown());
            });
            if (p.failure() == null) {
                title.setText("@" + init.target().name() + " is ready");
                step.setText("Server set up and app deployed. You can close this window.");
                String url = appUrl();
                if (url != null) add(new Paragraph(new Anchor(url, "Open " + url)));
            } else {
                title.setText("Setup failed");
                bar.setVisible(false);
                step.setText(p.failure());
                step.getStyle().setColor("var(--aura-red, #c62828)").setFontWeight(Style.FontWeight.BOLD);
                add(new Paragraph("Fix the problem and run init again, the form will start from the settings in "
                        + init.target().path() + "."));
            }
        }

        /** Where the deployed app answers, null if it is not a web service. */
        String appUrl() {
            InitConfig c = init.config();
            if (!c.isWebService()) return null;
            String scheme = "no".equals(c.getHttps()) ? "http" : "https";
            return scheme + "://" + c.getDomain().split(" ")[0] + "/";
        }

        static class Output extends Pre {
            Output() {
                setWidthFull();
                getStyle().setMinHeight(Progress.TAIL * 1.4 + "em")
                        .setMargin("0")
                        .setPadding("0.75em")
                        .setBoxSizing(Style.BoxSizing.BORDER_BOX)
                        .setBorderRadius("6px")
                        .setBackgroundColor("rgba(127, 127, 127, 0.12)")
                        .setFontSize("0.8em")
                        .setLineHeight("1.4")
                        .setWhiteSpace(Style.WhiteSpace.PRE_WRAP)
                        .setOverflow(Style.Overflow.HIDDEN);
            }
        }
    }

    @Route("")
    @PageTitle("boot2vm init")
    public static class InitView extends VerticalLayout {
        final TargetSection target = new TargetSection();
        final ServerSection server = new ServerSection();
        final AppSection app = new AppSection();
        final WebSection web = new WebSection();
        final HardwareSection hardware = new HardwareSection(this::config);
        final BlueGreenSection blueGreen = new BlueGreenSection();
        final SecuritySection security = new SecuritySection();
        final EnvSection env = new EnvSection();
        final Div errors = new Div();
        final FormBinder<InitConfig> binder = new FormBinder<>(InitConfig.class,
                server, app, web, hardware, blueGreen, security, env);
        boolean submitted;

        public InitView() throws IOException {
            setMaxWidth("52rem");
            getStyle().setMargin("0 auto");
            add(new H2("Set up a server for " + Path.of("").toAbsolutePath().getFileName()));
            add(target, server, app, web, hardware, blueGreen, security, env, errors, new Buttons());

            binder.setClassLevelViolationDisplay(errors);
            binder.addValueChangeListener(e -> refresh());
            app.jdkProvider.addValueChangeListener(e -> {
                if (e.isFromClient()) hardware.jdkChanged();
            });
            target.defaultsFrom.addValueChangeListener(e -> {
                if (e.isFromClient()) loadDefaults(e.getValue());
            });
            target.name.addValueChangeListener(e -> target.validateName());

            Target initial = Target.named(InitGui.initialTarget);
            Path source = defaultsSource(initial, InitGui.from);
            target.setTargets(initial, source);
            edit(initDefaults(initial, source));
        }

        InitConfig config() {
            return binder.getValue();
        }

        void edit(InitConfig config) {
            server.addKeyOption(config.getSshKey());
            binder.setValue(config);
            hardware.configChanged();
            refresh();
        }

        /** Switch the "start from" target, keeping what identifies this server. */
        void loadDefaults(Target source) {
            try {
                Target own = target.target();
                InitConfig current = config();
                InitConfig loaded = initDefaults(own, source == null ? null : source.path());
                if (source == null || !source.equals(own)) {
                    loaded.setHost(current.getHost());
                    loaded.setDomain(current.getDomain());
                    loaded.setHttps(current.getHttps());
                }
                loaded.setEnv(current.getEnv());
                edit(loaded);
            } catch (IOException ex) {
                Notification.show("Could not read " + source.path() + ": " + ex.getMessage());
            }
        }

        /** Show what applies to the current choices, and the values blank fields will get. */
        void refresh() {
            InitConfig c = config();
            server.update(c);
            app.update(c);
            web.update(c);
            hardware.update(c);
            blueGreen.update(c);
            security.update(c);
            if (submitted) binder.setRawConstraintViolations(c.validate());
        }

        void submit() {
            submitted = true;
            InitConfig c = config();
            c.fillHostDerived();
            c.normalize();
            binder.setValue(c);
            refresh();
            var problems = c.validate();
            binder.setRawConstraintViolations(problems);
            if (!problems.isEmpty() || !target.validateName()) {
                Notification.show("Check the highlighted fields");
                return;
            }
            var init = new InitResult(target.target(), c);
            removeAll();
            add(new SetupView(init));
            InitGui.result.complete(init);
        }

        class Buttons extends HorizontalLayout {
            Buttons() {
                var initialize = new Button("Initialize server", e -> submit());
                initialize.addThemeVariants(ButtonVariant.PRIMARY);
                add(initialize, new Button("Cancel", e -> {
                    InitGui.result.cancel(false);
                    InitView.this.removeAll();
                    InitView.this.add(new H2("Init cancelled"), new Paragraph("Nothing was written. You can close this window."));
                }));
            }
        }
    }

    /** A titled part of the init form. Subclasses declare fields named after InitConfig properties. */
    static class FormSection extends VerticalLayout {
        final FormLayout form = new FormLayout();

        FormSection(String title, String description) {
            setPadding(false);
            add(new H3(title));
            if (description != null) add(new Paragraph(description));
            add(form);
        }

        /** Show the parts that apply to the current values. */
        void update(InitConfig config) {
        }
    }

    static class TargetSection extends FormSection {
        final TextField name = new TextField("Target name");
        final Select<Target> defaultsFrom = new Select<>();
        Target fixed;

        TargetSection() {
            super("Target", null);
            name.setHelperText("Used as @name in later commands; 'default' writes plain vmhosting.conf");
            defaultsFrom.setLabel("Start from the settings of");
            defaultsFrom.setEmptySelectionAllowed(true);
            defaultsFrom.setEmptySelectionCaption("Built-in defaults");
            defaultsFrom.setItemLabelGenerator(t -> t == null ? "Built-in defaults" : "@" + t.name() + " (" + t.path() + ")");
            defaultsFrom.setHelperText("Host and domains are never taken from another server");
        }

        void setTargets(Target initial, Path source) throws IOException {
            List<Target> existing = allTargets();
            if (InitGui.askName) {
                name.setValue(initial.name());
                form.add(name);
            } else {
                fixed = initial;
                form.add(new Span("@" + initial.name() + " (" + initial.path()
                        + (Files.exists(initial.path()) ? ", re-initializing" : ", new") + ")"));
            }
            if (!existing.isEmpty()) {
                defaultsFrom.setItems(existing);
                defaultsFrom.setValue(existing.stream().filter(t -> t.path().equals(source)).findFirst().orElse(null));
                form.add(defaultsFrom);
            }
        }

        Target target() {
            return fixed != null ? fixed : Target.named(name.getValue().trim());
        }

        boolean validateName() {
            boolean valid = fixed != null || isValidTargetName(name.getValue().trim());
            name.setInvalid(!valid);
            name.setErrorMessage(valid ? null : "Use only letters, digits, '-' and '_'");
            return valid;
        }
    }

    static class ServerSection extends FormSection {
        final TextField host = new TextField("Host");
        final TextField adminUser = new TextField("Admin SSH user");
        final ComboBox<String> sshKey = new ComboBox<>("SSH public key");
        final List<String> keys = new ArrayList<>();

        ServerSection() throws IOException {
            super("Server", "How to reach the server over SSH.");
            host.setHelperText("SSH address: a single hostname or IP; more domains can be given below");
            adminUser.setHelperText("root, or a user with sudo");
            keys.addAll(listSshKeys());
            sshKey.setItems(keys);
            sshKey.setAllowCustomValue(true);
            sshKey.setHelperText("Installed for the app user too; pick one from ~/.ssh or type a path");
            sshKey.addCustomValueSetListener(e -> {
                addKeyOption(e.getDetail());
                sshKey.setValue(e.getDetail());
            });
            form.add(host, adminUser, sshKey);
        }

        void addKeyOption(String key) {
            if (key != null && !key.isBlank() && !keys.contains(key)) {
                keys.add(key);
                sshKey.setItems(keys);
            }
        }

        @Override
        void update(InitConfig config) {
            // A typed key path is set programmatically, which the binder ignores
            if (sshKey.getValue() != null && !sshKey.getValue().equals(config.getSshKey())) {
                config.setSshKey(sshKey.getValue());
            }
        }
    }

    static class AppSection extends FormSection {
        final TextField user = new TextField("App user");
        final Select<String> appType = new Select<>();
        final Select<String> jdkProvider = new Select<>();

        AppSection() {
            super("App", null);
            user.setHelperText("The Linux user the app runs as, also the name of its service");
            appType.setLabel("App type");
            appType.setItems(InitConfig.APP_TYPES);
            jdkProvider.setLabel("JDK");
            jdkProvider.setItems(InitConfig.JDK_PROVIDERS);
            jdkProvider.setItemLabelGenerator(jdk -> switch (jdk) {
                case "temurin" -> "Eclipse Temurin";
                case "zulu" -> "Azul Zulu";
                case "semeru" -> "IBM Semeru (OpenJ9 VM, smaller memory footprint)";
                default -> jdk;
            });
            form.add(user, appType, jdkProvider);
        }

        @Override
        void update(InitConfig config) {
            user.setPlaceholder(config.derivedUser());
        }
    }

    static class WebSection extends FormSection {
        final Checkbox webService = new Checkbox("Exposes web endpoints");
        final TextField domain = new TextField("Domain(s)");
        final Select<String> https = new Select<>();
        final Select<String> proxy = new Select<>();
        final TextField port = new TextField("App port");

        WebSection() {
            super("Web", null);
            webService.setTooltipText("Uncheck for headless services: no reverse proxy, no 80/443 firewall rules");
            domain.setHelperText("Comma-separated; Caddy serves them all");
            https.setLabel("HTTPS");
            https.setItems(InitConfig.HTTPS_MODES);
            https.setEmptySelectionAllowed(true);
            https.setItemLabelGenerator(mode -> mode == null ? "Automatic" : switch (mode) {
                case "yes" -> "Yes, with a public certificate";
                case "no" -> "No, plain HTTP";
                case "internal" -> "Internal CA (LAN devices, PWAs)";
                default -> mode;
            });
            proxy.setLabel("Reverse proxy");
            proxy.setItems(InitConfig.PROXIES);
            port.setHelperText("Unique per app on the server; blue-green also uses port + 1");
            form.add(webService, domain, https, proxy, port);
            form.setColspan(webService, 2);
        }

        @Override
        void update(InitConfig config) {
            boolean web = config.isWebService();
            domain.setVisible(web);
            https.setVisible(web);
            proxy.setVisible(web);
            port.setVisible(web);
            domain.setPlaceholder(config.getHost());
            https.setEmptySelectionCaption("Automatic: " + ("yes".equals(config.derivedHttps())
                    ? "public certificate" : "plain HTTP for a LAN address"));
        }
    }

    /** JVM flags and Linux groups, with presets that tick on when their values are present. */
    static class HardwareSection extends FormSection {
        final CheckboxGroup<Preset> presets = new CheckboxGroup<>("Presets");
        final TextField jvmOpts = new TextField("Extra JVM options");
        final TextField userGroups = new TextField("Extra groups for the app user");
        final Supplier<InitConfig> config;

        HardwareSection(Supplier<InitConfig> config) {
            super("Hardware access and JVM options",
                    "Groups are added to the app user, flags to the java command line.");
            this.config = config;
            presets.setItemLabelGenerator(Preset::label);
            presets.addValueChangeListener(e -> {
                if (e.isFromClient()) presetsPicked(e.getOldValue(), e.getValue());
            });
            jvmOpts.setHelperText("Space-separated");
            userGroups.setHelperText("Comma-separated");
            form.add(presets, 2);
            form.add(withClear(jvmOpts), withClear(userGroups));
        }

        HorizontalLayout withClear(TextField field) {
            var clear = new Button("Clear", e -> {
                field.clear();
                // Programmatic changes don't reach the bean through the binder
                if (field == jvmOpts) config.get().setJvmOpts("");
                else config.get().setUserGroups("");
                update(config.get());
            });
            clear.addThemeVariants(ButtonVariant.TERTIARY);
            var row = new HorizontalLayout(field, clear);
            row.setAlignItems(Alignment.BASELINE);
            row.setFlexGrow(1, field);
            return row;
        }

        void configChanged() {
            presets.setItems(presets(config.get().getJdkProvider()));
            update(config.get());
        }

        void jdkChanged() {
            InitConfig c = config.get();
            boolean openJ9 = isOpenJ9(c.getJdkProvider());
            var opts = tokens(c.getJvmOpts(), "\\s+");
            var dropped = dropOtherVmOptions(opts, openJ9);
            if (!dropped.isEmpty()) {
                c.setJvmOpts(String.join(" ", opts));
                jvmOpts.setValue(c.getJvmOpts());
                Notification.show("Dropped JVM options " + c.getJdkProvider() + " does not support: "
                        + String.join(" ", dropped) + ". Pick the presets again for equivalents.");
            }
            configChanged();
        }

        void presetsPicked(Set<Preset> before, Set<Preset> after) {
            InitConfig c = config.get();
            var opts = tokens(c.getJvmOpts(), "\\s+");
            var groups = tokens(c.getUserGroups(), "[\\s,]+");
            for (Preset p : after) {
                if (before.contains(p)) continue;
                opts.addAll(tokens(p.jvmOpts(), "\\s+"));
                groups.addAll(tokens(p.groups(), ","));
            }
            for (Preset p : before) {
                if (after.contains(p)) continue;
                // Unticking removes exactly what the preset adds; presets that needed some of it untick too
                opts.removeAll(tokens(p.jvmOpts(), "\\s+"));
                groups.removeAll(tokens(p.groups(), ","));
            }
            c.setJvmOpts(String.join(" ", opts));
            c.setUserGroups(String.join(",", groups));
            jvmOpts.setValue(c.getJvmOpts());
            userGroups.setValue(c.getUserGroups());
            update(c);
        }

        @Override
        void update(InitConfig c) {
            var opts = tokens(c.getJvmOpts(), "\\s+");
            var groups = tokens(c.getUserGroups(), "[\\s,]+");
            var ticked = presets.getListDataView().getItems().filter(p -> {
                var needOpts = tokens(p.jvmOpts(), "\\s+");
                var needGroups = tokens(p.groups(), ",");
                return !(needOpts.isEmpty() && needGroups.isEmpty())
                        && opts.containsAll(needOpts) && groups.containsAll(needGroups);
            }).collect(Collectors.toSet());
            if (!ticked.equals(presets.getValue())) presets.setValue(ticked);
        }

        static LinkedHashSet<String> tokens(String value, String separator) {
            var set = new LinkedHashSet<String>();
            if (value == null) return set;
            for (String token : value.trim().split(separator)) {
                if (!token.isBlank()) set.add(token.trim());
            }
            return set;
        }
    }

    static class BlueGreenSection extends FormSection {
        final Checkbox blueGreen = new Checkbox("Blue-green deployment");
        final Checkbox gracefulDrain = new Checkbox("Graceful drain");
        final TextField slotCookie = new TextField("Slot cookie name");
        final TextField drainTimeout = new TextField("Drain timeout (seconds)");
        final TextField managementPort = new TextField("Management port");
        final TextField notifyPath = new TextField("Notify path");
        final TextField activeUsersPath = new TextField("Active users path");

        BlueGreenSection() {
            super("Zero-downtime deployment", null);
            blueGreen.setTooltipText("Starts the new version next to the old one; needs memory for two JVMs, not recommended for low-end servers");
            gracefulDrain.setTooltipText("Keeps existing sessions on the old version until they end or the drain timeout passes");
            managementPort.setHelperText("Blank for the app port");
            form.add(blueGreen, gracefulDrain, slotCookie, drainTimeout, managementPort, notifyPath, activeUsersPath);
        }

        @Override
        void update(InitConfig c) {
            setVisible(c.isWebService());
            gracefulDrain.setVisible(c.isBlueGreen());
            for (var field : List.of(slotCookie, drainTimeout, managementPort, notifyPath, activeUsersPath)) {
                field.setVisible(c.isBlueGreen() && c.isGracefulDrain());
            }
        }
    }

    static class SecuritySection extends FormSection {
        final Checkbox autoUpdates = new Checkbox("Install security updates automatically");
        final Checkbox firewall = new Checkbox("Configure the firewall with ufw");
        final Checkbox exposeNodes = new Checkbox();

        SecuritySection() {
            super("Security", null);
            autoUpdates.setHelperText("unattended-upgrades, rebooting at 02:00 when an update needs it");
            form.add(autoUpdates, firewall, exposeNodes);
        }

        @Override
        void update(InitConfig c) {
            exposeNodes.setVisible(c.isFirewall() && c.isWebService());
            int p = InitConfig.isPort(c.getPort()) ? Integer.parseInt(c.getPort().trim()) : 8080;
            exposeNodes.setLabel("Open app port " + (c.isBlueGreen() ? p + "/" + (p + 1) : String.valueOf(p)) + " for direct access");
        }
    }

    static class EnvSection extends FormSection {
        final TextArea env = new TextArea("Environment variables");

        EnvSection() {
            super("Environment", "Stored on the server only, never in the local config file.");
            env.setPlaceholder("KEY=VALUE, one per line");
            env.setHelperText("Values with spaces must be quoted: KEY=\"value with spaces\"");
            env.setWidthFull();
            form.add(env, 2);
        }
    }

    static String prompt(Console console, String label, String defaultValue) {
        String p = defaultValue != null
                ? label + " [" + defaultValue + "]: "
                : label + ": ";
        String input = console.readLine(p).trim();
        return input.isEmpty() && defaultValue != null ? defaultValue : input;
    }

    /**
     * Like {@link #prompt}, but a non-empty default can be dropped by answering "-",
     * since a blank answer keeps the default.
     */
    static String promptClearable(Console console, String label, String defaultValue) {
        if (defaultValue == null || defaultValue.isEmpty()) return prompt(console, label, null);
        String input = prompt(console, label + " ('-' to clear)", defaultValue);
        return input.equals("-") ? "" : input;
    }

    /**
     * Ask for the SSH public key. Lists the *.pub files found in ~/.ssh so the
     * user can pick one by number instead of typing the full path. A path can
     * still be typed directly. The returned value keeps the ~ prefix so the
     * written vmhosting.conf stays portable between machines and users.
     */
    static String promptSshKey(Console console, String defaultKey) throws IOException {
        List<String> keys = listSshKeys();
        String def = defaultSshKey(keys, defaultKey);

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

    /** The *.pub files in ~/.ssh, as ~/.ssh/name.pub so the written config stays portable. */
    static List<String> listSshKeys() throws IOException {
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
        return keys;
    }

    /** Pick a default key: the configured one, then common key names, then the first key found. */
    static String defaultSshKey(List<String> keys, String configured) {
        String def = configured;
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
        return def;
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

    /**
     * Presets for the chosen JDK. Semeru runs the OpenJ9 VM, which silently ignores
     * HotSpot's GC and JIT tuning flags, so the small-device and startup presets use
     * OpenJ9's own equivalents there.
     */
    static List<Preset> presets(String jdkProvider) {
        boolean openJ9 = isOpenJ9(jdkProvider);
        String piBase = "--enable-native-access=ALL-UNNAMED -XX:+ExitOnOutOfMemoryError";
        String smallHeap = openJ9 ? "-XX:MaxRAMPercentage=60" : "-XX:+UseSerialGC -XX:MaxRAMPercentage=60";
        String fastStartup = openJ9 ? "-Xquickstart -Xshareclasses:name=app" : "-XX:TieredStopAtLevel=1";
        return List.of(
                new Preset("Raspberry Pi, everything: all hardware groups below, native access, restart on OOM",
                        PI_GROUPS, piBase, "pi"),
                new Preset("Raspberry Pi Zero / 512 MB class: as above plus "
                        + (openJ9 ? "60% heap, quick-start JIT, shared class cache" : "serial GC, 60% heap, C1 JIT only"),
                        PI_GROUPS, piBase + " " + smallHeap + " " + fastStartup, "pi-zero"),
                new Preset("Raspberry Pi GPIO/I2C/SPI/PWM (Pi4J, gpiod)", "gpio,i2c,spi", "--enable-native-access=ALL-UNNAMED"),
                new Preset("Serial ports (/dev/ttyAMA*, /dev/ttyUSB*)", "dialout", ""),
                new Preset("Bluetooth (BlueZ over D-Bus)", "bluetooth", ""),
                new Preset("Camera (libcamera/rpicam, V4L2)", "video,render", ""),
                new Preset("USB, HID and input devices", "plugdev,input", ""),
                new Preset("Audio (ALSA)", "audio", ""),
                new Preset("Native libraries (JNI/FFM: JNA, OpenCV, SQLite ...) without JDK 24+ warnings", "", "--enable-native-access=ALL-UNNAMED"),
                new Preset("Small device (<= 2 GB RAM): "
                        + (openJ9 ? "60% of RAM for the heap (OpenJ9 default is 25%)" : "serial GC, 60% of RAM for the heap"),
                        "", smallHeap),
                new Preset("Faster startup on a slow CPU ("
                        + (openJ9 ? "quick-start JIT and shared class/AOT cache" : "C1 JIT only")
                        + ", lower peak throughput)", "", fastStartup),
                new Preset("Exit on OutOfMemoryError so systemd restarts the app", "", "-XX:+ExitOnOutOfMemoryError"),
                new Preset("Headless AWT (image processing on a server)", "", "-Djava.awt.headless=true"));
    }

    static boolean isOpenJ9(String jdkProvider) {
        return "semeru".equalsIgnoreCase(jdkProvider);
    }

    /**
     * Whether the flag only works on the other VM. HotSpot refuses to start with
     * OpenJ9 -X options; OpenJ9 silently ignores HotSpot GC/JIT flags.
     */
    static boolean isForOtherVm(String flag, boolean openJ9) {
        boolean openJ9Only = flag.startsWith("-Xquickstart") || flag.startsWith("-Xshareclasses")
                || flag.startsWith("-Xgcpolicy") || flag.startsWith("-Xtune") || flag.startsWith("-Xjit")
                || flag.startsWith("-Xaot") || flag.startsWith("-Xscmx");
        boolean hotSpotOnly = flag.matches("-XX:[+-]Use\\w*GC") || flag.startsWith("-XX:TieredStopAtLevel");
        return openJ9 ? hotSpotOnly : openJ9Only;
    }

    /**
     * Ask for extra JVM options and extra Linux groups for the app user. Common
     * use cases (Raspberry Pi hardware access, small devices, ...) can be picked
     * from a list; the merged result is then shown for manual editing.
     */
    static void promptJvmAndHardware(Console console, InitConfig config) {
        String jdkProvider = config.getJdkProvider();
        String jvmOpts = config.getJvmOpts().trim();
        String userGroups = config.getUserGroups().trim();

        boolean openJ9 = isOpenJ9(jdkProvider);
        var opts = new LinkedHashSet<String>();
        if (!jvmOpts.isEmpty()) opts.addAll(List.of(jvmOpts.split("\\s+")));
        var dropped = dropOtherVmOptions(opts, openJ9);
        if (!dropped.isEmpty()) {
            jvmOpts = String.join(" ", opts);
            config.setJvmOpts(jvmOpts);
            System.out.println("  Dropped JVM options for " + (openJ9 ? "HotSpot" : "OpenJ9") + " that " + jdkProvider
                    + " does not support: " + String.join(" ", dropped) + " (pick the presets again for equivalents)");
        }

        if (!jvmOpts.isEmpty() || !userGroups.isEmpty()) {
            // Values inherited from this or another target: make dropping them as easy as keeping them
            System.out.println("Current hardware access / JVM options:");
            System.out.println("  JVM options: " + (jvmOpts.isEmpty() ? "(none)" : jvmOpts));
            System.out.println("  Extra groups: " + (userGroups.isEmpty() ? "(none)" : userGroups));
            while (true) {
                String choice = prompt(console, "Keep them, edit them, or clear and start from scratch (keep/edit/clear)", "keep").toLowerCase();
                if (choice.equals("keep")) return;
                if (choice.equals("edit")) break;
                if (choice.equals("clear")) {
                    userGroups = "";
                    opts.clear();
                    config.setJvmOpts("");
                    config.setUserGroups("");
                    String tune = prompt(console, "Configure hardware access / JVM options (yes/no)", "no");
                    if (!"yes".equalsIgnoreCase(tune)) return;
                    break;
                }
                System.err.println("  Answer keep, edit or clear");
            }
        } else {
            String tune = prompt(console, "Configure hardware access / JVM options (yes/no)", "no");
            if (!"yes".equalsIgnoreCase(tune)) {
                return;
            }
        }

        var presets = presets(jdkProvider);
        var groups = new LinkedHashSet<String>();
        if (!userGroups.isEmpty()) groups.addAll(List.of(userGroups.split("\\s*,\\s*")));

        System.out.println("Presets (groups are added to the app user, flags to the java command line):");
        for (int i = 0; i < presets.size(); i++) {
            Preset preset = presets.get(i);
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
                Preset preset = findPreset(presets, token);
                if (preset == null) {
                    System.err.println("  '" + token + "' is not a preset number between 1 and " + presets.size() + " or a preset name");
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
        config.setJvmOpts(promptClearable(console, "Extra JVM options", mergedOpts).trim());
        config.setUserGroups(promptClearable(console, "Extra groups for the app user (comma-separated)", mergedGroups)
                .replaceAll("\\s*,\\s*", ",").trim());
    }

    /** Remove the flags the chosen VM does not support from opts, returning the removed ones. */
    static List<String> dropOtherVmOptions(Collection<String> opts, boolean openJ9) {
        var dropped = opts.stream().filter(o -> isForOtherVm(o, openJ9)).toList();
        opts.removeAll(dropped);
        return dropped;
    }

    static Preset findPreset(List<Preset> presets, String token) {
        if (token.matches("\\d+")) {
            int idx = Integer.parseInt(token) - 1;
            return idx >= 0 && idx < presets.size() ? presets.get(idx) : null;
        }
        return presets.stream()
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
    record Build(String appType, boolean quarkus, boolean plain, boolean mavenw, boolean pom) {}

    /** Build once, then sync and restart on every given target in order. */
    static void deployTargets(List<Target> targets) throws Exception {
        // One app has one build: check every config before building or touching any server
        loadConfig(targets.get(0));
        String firstType = appType;
        for (Target target : targets) {
            loadConfig(target);
            if (!appType.equals(firstType)) {
                throw new CommandFailed("APP_TYPE differs between targets (" + firstType + " in " + targets.get(0).path()
                        + " vs " + appType + " in " + target.path() + "); one app must have the same type everywhere", 1);
            }
        }
        loadConfig(targets.get(0));
        Build build = build();
        forEachTarget(targets, target -> deploy(build));
    }

    static Build build() throws Exception {
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
            throw new CommandFailed("No Maven or Gradle project found in current directory", 1);
        }
        return new Build(appType, quarkus, plain, mavenw, pom);
    }

    /** Sync the already built app to the currently loaded target and restart it. */
    static void deploy(Build build) throws Exception {
        boolean quarkus = build.quarkus(), plain = build.plain(), mavenw = build.mavenw(), pom = build.pom();
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

        System.out.println("Deployed successfully to @" + currentTarget.name() + "!");
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
    static void addKey(String[] args, List<Target> targets) throws Exception {
        String pubKey = resolvePublicKey(args);
        forEachTarget(targets, target -> addKey(pubKey));
    }

    static String resolvePublicKey(String[] args) throws Exception {
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
        return pubKey;
    }

    static void addKey(String pubKey) throws Exception {
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

    static void clean(List<String> selected) throws Exception {
        Target target = singleTarget(selected, "clean");
        loadConfig(target);
        System.out.println("Cleaning up " + user + " from " + host + " (@" + target.name() + ") ...");

        Path tempScript = Files.createTempFile("clean-server", ".sh");
        Files.writeString(tempScript, CLEAN_SCRIPT);

        scp(tempScript.toString(), adminUser + "@" + host + ":/tmp/clean-server.sh");
        sshAsRoot("chmod +x /tmp/clean-server.sh");
        sshAsRoot("/tmp/clean-server.sh " + user + " " + proxy + " " + (blueGreen ? "yes" : "no"));

        Files.delete(tempScript);

        Files.deleteIfExists(target.path());
        System.out.println("Server cleaned and " + target.path() + " removed. Run 'init' to start fresh.");
    }

    // -----------------------------------------------------------------------
    // env – manage environment variables on the server
    // -----------------------------------------------------------------------
    static void env(String[] args, List<String> selected) throws Exception {
        String subcommand = args.length > 1 ? args[1] : "list";
        List<Target> targets = resolveTargets(selected, true);
        switch (subcommand) {
            case "list" -> forEachTarget(targets, target -> envList());
            case "set" -> forEachTarget(targets, target -> envSet(args));
            case "remove" -> forEachTarget(targets, target -> envRemove(args));
            default -> {
                System.err.println("Unknown env subcommand: " + subcommand);
                System.err.println("Usage: Deploy env [set|remove|list] [@target ...]");
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

    /** Set while the init form follows the setup: commands then write through System.out, which it copies. */
    static boolean captureOutput;

    static void run(String... cmd) throws Exception {
        System.out.println("  > " + String.join(" ", cmd));
        var builder = new ProcessBuilder(cmd);
        if (captureOutput) {
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT).redirectErrorStream(true);
        } else {
            builder.inheritIO();
        }
        var process = builder.start();
        if (captureOutput) process.getInputStream().transferTo(System.out);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new CommandFailed("Command failed with exit code " + exit + ": " + String.join(" ", cmd), exit);
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
            throw new CommandFailed("Remote command failed (exit " + exit + ")", exit);
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
            throw new CommandFailed("Remote command failed (exit " + exit + ")", exit);
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
