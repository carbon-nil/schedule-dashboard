FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

ARG TARGETARCH
ARG SBT_VERSION=1.13.0
ARG SCALAFMT_VERSION=3.11.5

# python3: the base image only has python3-minimal, which lacks the stdlib the Jev hook needs (shlex)
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 sqlite3 unzip \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
    | tar -xz -C /opt \
    && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt

RUN case "${TARGETARCH}" in arm64) arch=aarch64 ;; *) arch=x86_64 ;; esac \
    && curl -fsSL -o /tmp/scalafmt.zip \
        "https://github.com/scalameta/scalafmt/releases/download/v${SCALAFMT_VERSION}/scalafmt-${arch}-pc-linux.zip" \
    && unzip -q /tmp/scalafmt.zip -d /usr/local/bin \
    && chmod +x /usr/local/bin/scalafmt \
    && rm /tmp/scalafmt.zip
