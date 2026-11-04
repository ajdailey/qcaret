# Build image for QCaret: JDK 21, git, and Xvfb with GTK 3 for the SWT tests.
# The same ./mvnw runs inside as on a host. compose.yaml mounts the working
# tree at run time; nothing from it is baked in.

FROM eclipse-temurin:21.0.12_8-jdk-noble

# git: Spotless reads .gitattributes for line endings.
# unzip: without it ./mvnw fetches the .tar.gz Maven distribution, which fails
# the wrapper's SHA-256 pinned for the .zip.
# xvfb, xauth: xvfb-run for `check`. libgtk-3-0t64, fonts: SWT on GTK 3.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      git unzip xvfb xauth libgtk-3-0t64 fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*

# World-writable so the container can run as the caller's uid. MAVEN_USER_HOME
# holds the wrapper's Maven, the repository and the JDK cache; compose.yaml
# backs it with a named volume.
ENV HOME=/opt/qcaret \
    MAVEN_USER_HOME=/opt/qcaret/.m2

RUN mkdir -p /opt/qcaret/.m2 \
 && chmod -R a+rwX /opt/qcaret

WORKDIR /work
