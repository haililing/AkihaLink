FROM ubuntu:24.04@sha256:4fbb8e6a8395de5a7550b33509421a2bafbc0aab6c06ba2cef9ebffbc7092d90

ARG DEBIAN_FRONTEND=noninteractive
ARG GO_ARCHIVE=go1.26.7.linux-amd64.tar.gz
ARG GO_SHA256=ffb5f8de10c62550dfddab66b36b57030721e0a44a3218e9e1181d7b59f121ca
ARG ANDROID_TOOLS=commandlinetools-linux-15859902_latest.zip
ARG ANDROID_TOOLS_SHA1=040d3996a65543d22ec4bf73e4c37aa37a8d4af4
ARG POWERSHELL_ARCHIVE=powershell-7.5.3-linux-x64.tar.gz
ARG POWERSHELL_SHA256=5c74e0bbafd8be59e72267f05700fd6615344d9cf4d30460d9b6d12cd4a88a8c

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl git jq libicu74 libssl3 openjdk-21-jdk-headless unzip xz-utils zip \
    && rm -rf /var/lib/apt/lists/*

RUN curl --fail --location --output /tmp/go.tgz "https://go.dev/dl/${GO_ARCHIVE}" \
    && echo "${GO_SHA256}  /tmp/go.tgz" | sha256sum --check - \
    && tar -C /usr/local -xzf /tmp/go.tgz \
    && rm /tmp/go.tgz

RUN mkdir -p /opt/microsoft/powershell/7 \
    && curl --fail --location --output /tmp/powershell.tgz \
        "https://github.com/PowerShell/PowerShell/releases/download/v7.5.3/${POWERSHELL_ARCHIVE}" \
    && echo "${POWERSHELL_SHA256}  /tmp/powershell.tgz" | sha256sum --check - \
    && tar -C /opt/microsoft/powershell/7 -xzf /tmp/powershell.tgz \
    && chmod 0755 /opt/microsoft/powershell/7/pwsh \
    && ln -s /opt/microsoft/powershell/7/pwsh /usr/local/bin/pwsh \
    && rm /tmp/powershell.tgz

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH=/usr/local/go/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl --fail --location --output /tmp/android-tools.zip \
        "https://dl.google.com/android/repository/${ANDROID_TOOLS}" \
    && echo "${ANDROID_TOOLS_SHA1}  /tmp/android-tools.zip" | sha1sum --check - \
    && unzip -q /tmp/android-tools.zip -d /tmp/android-tools \
    && mv /tmp/android-tools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -rf /tmp/android-tools /tmp/android-tools.zip \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager \
        "platform-tools" \
        "platforms;android-36" \
        "build-tools;36.0.0" \
        "ndk;29.0.14206865" \
    && rm -rf /root/.android/cache

ENV ANDROID_NDK_HOME=/opt/android-sdk/ndk/29.0.14206865
ENV TZ=UTC
ENV LANG=C.UTF-8

RUN java -version \
    && go version \
    && pwsh --version \
    && test -x "${ANDROID_HOME}/build-tools/36.0.0/apksigner" \
    && test -x "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
