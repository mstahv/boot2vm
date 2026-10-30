# A stand-in for a fresh cloud VM: Ubuntu with systemd as PID 1 and sshd.
# The host keys are generated once at build time, so every container from
# this image presents the same key.
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends systemd systemd-sysv openssh-server sudo ca-certificates curl iproute2 \
 && rm -rf /var/lib/apt/lists/* \
 # Docker's Ubuntu image forbids starting services from package scripts; a VM does not
 && rm -f /usr/sbin/policy-rc.d \
 && mkdir -p /root/.ssh && chmod 700 /root/.ssh \
 && systemctl enable ssh
STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
