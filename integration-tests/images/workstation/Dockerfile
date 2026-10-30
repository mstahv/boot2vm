# The developer's machine: what boot2vm needs locally, plus Maven for the test apps.
FROM eclipse-temurin:25-jdk
RUN apt-get update \
 && apt-get install -y --no-install-recommends rsync openssh-client maven curl unzip python3 \
 && rm -rf /var/lib/apt/lists/*
RUN curl -Ls https://sh.jbang.dev | bash -s - app setup \
 && ln -s /root/.jbang/bin/jbang /usr/local/bin/jbang
RUN ssh-keygen -q -t ed25519 -N '' -f /root/.ssh/id_ed25519
COPY drive.py /usr/local/bin/drive.py
WORKDIR /work
CMD ["sleep", "infinity"]
