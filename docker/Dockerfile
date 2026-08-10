# Headless Android build environment — no Android Studio, no local SDK install.
#
#   docker compose -f docker/docker-compose.yml build
#   ./build.sh apk
#
# The image contains only the JDK and the command line SDK; Gradle itself comes from the
# wrapper, so the build is pinned by the repository, not by the image.
FROM docker.io/library/eclipse-temurin:21-jdk-noble

ARG ANDROID_CMDLINE_TOOLS_VERSION=13114758
ARG ANDROID_PLATFORM=android-36
ARG ANDROID_BUILD_TOOLS=36.0.0

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle-home \
    DEBIAN_FRONTEND=noninteractive

RUN apt-get update && \
    apt-get install --no-install-recommends -y \
        unzip curl git ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Android SDK — installed as root, world-readable so any runtime UID can use it.
RUN mkdir -p "$ANDROID_HOME" "$GRADLE_USER_HOME" && \
    chmod 777 "$GRADLE_USER_HOME"

# Command line tools -> $ANDROID_HOME/cmdline-tools/latest (the layout sdkmanager expects).
RUN curl -fsSL -o /tmp/cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" && \
    unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools && \
    mkdir -p "$ANDROID_HOME/cmdline-tools" && \
    mv /tmp/cmdline-tools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest" && \
    rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools

ENV PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

RUN yes | sdkmanager --licenses > /dev/null && \
    sdkmanager --install \
        "platform-tools" \
        "platforms;${ANDROID_PLATFORM}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" \
        "build-tools;35.0.0" > /dev/null

# No USER directive — docker-compose.yml uses `userns_mode: keep-id` (podman) to run
# as the host user, giving write access to the bind-mounted workspace.
WORKDIR /workspace
