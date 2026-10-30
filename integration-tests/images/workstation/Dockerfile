# The developer's machine: what boot2vm needs locally, plus Maven for the test apps.
FROM eclipse-temurin:25-jdk
RUN apt-get update \
 && apt-get install -y --no-install-recommends rsync openssh-client maven curl unzip \
 && rm -rf /var/lib/apt/lists/*
RUN curl -Ls https://sh.jbang.dev | bash -s - app setup \
 && ln -s /root/.jbang/bin/jbang /usr/local/bin/jbang
RUN ssh-keygen -q -t ed25519 -N '' -f /root/.ssh/id_ed25519
COPY Drive.java /usr/local/bin/Drive.java
# ssh passes the locale on, and the servers only have C.UTF-8: no setlocale warnings in the output
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 LANGUAGE=
WORKDIR /work
CMD ["sleep", "infinity"]
