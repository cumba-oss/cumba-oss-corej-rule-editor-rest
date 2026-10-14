# syntax=docker/dockerfile:1
# The rule editor's REST service as a container (PLAN-rule-editor §4.2, RE-Q6).
#
# Built FROM THE SOURCES, in two stages, so `docker compose up --build` (or `docker build`)
# needs nothing built beforehand and can never package a stale target/:
#
#   build    Maven runs `clean package -DskipTests` on this repo, which assembles the exploded
#            distribution target/cumba-oss-corej-rule-editor-rest-<version>/… — the
#            cumba-oss-bootstrap launcher, lib/, config/ and the sidecar .conf, the bundle
#            src/test/smoke.sh starts;
#   runtime  a JRE image that copies only that tree in.
#
# ⚠ WHERE THE DEPENDENCIES COME FROM. With no settings, from Maven Central: every third-party
# dependency, and every RELEASED cumba-oss-* artifact. Central hosts no snapshots. So:
#
#   - a RELEASE of this repository (its dependency.cumba-oss-*.version properties flipped to
#     released versions, as a release commit does) builds with NO secret at all:
#
#         docker build -t rule-editor-rest .
#
#   - `main` between releases pins the cumba-oss-* DEVELOPMENT snapshots, which are published
#     to the project's own snapshot repository, not to Central. Built with no settings it stops
#     at the first of them ("Could not find artifact net.cumba:cumba-oss-bootstrap:jar:
#     <version>-SNAPSHOT"). It needs a Maven settings.xml naming a repository that serves those
#     snapshots (or a mirror of one), passed as the OPTIONAL build secret `maven_settings` —
#     mounted for the one RUN, never stored in a layer:
#
#         docker build --secret id=maven_settings,src=$HOME/.m2/settings.xml -t rule-editor-rest .
#         podman build --secret id=maven_settings,src=$HOME/.m2/settings.xml -t rule-editor-rest .
#
#     (docker-compose.yml: a docker-compose.override.yml adds the secret, see its header.)
#     Without such a repository: `mvn install` the cumba-oss-* chain bottom-up (commons,
#     formats, datatable, corej) and run this repo's distribution on the host instead
#     (README.md, "Running the distribution").
#
# docker/maven-global-settings.xml (passed as -gs) enables SNAPSHOT resolution on `central` —
# the build starts from an empty local repository, Maven's built-in central has snapshots off,
# and a `*` mirror in your settings inherits that. Without a secret it changes nothing (Central
# serves no snapshot either way). The local Maven repository is a build CACHE mount (pinned by
# -Dmaven.repo.local, whatever <localRepository> the settings name): the first build downloads
# everything, later ones only what changed. Only the bundle is built — no tests, no Spotless,
# no javadoc: those are this repo's CI gate.
#
# ⚠ UPSTREAM SNAPSHOTS (the cumba-oss-* engine, commons and datatable development lines). -U
# makes Maven re-check the snapshot repository whenever the step runs (without it the cache
# mount keeps a snapshot, or a "not found" marker, for 24 h). But the step only runs when THIS
# repo's sources changed: after a fix published only upstream, the build layer is CACHED and
# the image keeps the old engine. Rebuild that one stage without the layer cache:
#
#     docker compose build --no-cache rule-editor-rest && docker compose up -d
#
# With the SPA bundled (deployment shape 1 — one container serves the UI at /) build the bundle
# outside docker instead:
#
#     mvn -B -Pbundle-web clean package -Dweb.dist.dir=../cumba-oss-corej-rule-editor-web/dist
#
# Otherwise the jar is API-only (shape 2) and docker-compose.yml puts the SPA's nginx
# in front of it.
#
# This file is for LOCAL builds from the sources. CI does not build it: it publishes the image
# docker.io/p300/corej-rule-editor-rest from Dockerfile.runtime instead, which copies the release
# zip's bundle (PLAN-ci-snapshots-and-container-images; README § Container image). Keep the two
# runtime stages in step.
ARG MAVEN_IMAGE=maven:3.10.0-eclipse-temurin-25
ARG JRE_IMAGE=eclipse-temurin:25-jre

FROM ${MAVEN_IMAGE} AS build
WORKDIR /src
# The context is the repo minus target/ and .git (.dockerignore). Without .git the
# git-commit-id plugin writes no commit (failOnNoGitDirectory=false) — the build stays green.
COPY . .
# The exploded `dir` assembly nests <baseDirectory> inside <finalName>, hence the doubled
# directory; exactly one must exist, whatever the version, so the runtime stage needs no
# VERSION argument. The settings secret is optional (required=false): absent or empty, Maven
# runs on its defaults (Maven Central).
RUN --mount=type=secret,id=maven_settings,required=false \
    --mount=type=cache,target=/root/.m2/repository \
    set -- \
    && if [ -s /run/secrets/maven_settings ]; then set -- -s /run/secrets/maven_settings; fi \
    && mvn -B -U -gs docker/maven-global-settings.xml "$@" \
           -Dmaven.repo.local=/root/.m2/repository \
           clean package -DskipTests -Dspotless.apply.skip=true -Dmaven.javadoc.skip=true \
    && set -- target/cumba-oss-corej-rule-editor-rest-*/cumba-oss-corej-rule-editor-rest-*/ \
    && if [ "$#" -ne 1 ] || [ ! -d "$1" ]; then \
        echo "rule-editor-rest build: expected ONE exploded bundle, found: $*" >&2; exit 1; \
    fi \
    && mkdir /bundle && cp -a "$1". /bundle/

FROM ${JRE_IMAGE}

COPY --from=build /bundle/ /opt/rule-editor/

# git is NOT needed: every git operation is JGit, in-process. A non-root user owns the
# workspaces (one clone per forge user, §5.1.5) and nothing else.
# The hooks directory every clone's core.hooksPath names (§5.8.3) is part of the IMAGE,
# owned by root and read-only (security review L4): the service user cannot plant a hook
# in it, and the server refuses to start if it is not empty.
# ⚠ The run workers are child JVMs of the SAME uid as the service (a separate uid would be
# stronger isolation; not done): their limits are the heap cap, the timeout, a minimal
# environment and the confined export, not the operating system's permissions.
RUN useradd --system --create-home --home-dir /var/lib/rule-editor rule-editor \
    && mkdir -p /var/lib/rule-editor/workspaces /var/lib/rule-editor/metadata-store \
    && chown -R rule-editor:rule-editor /var/lib/rule-editor \
    && chmod 755 /opt/rule-editor/run.sh \
    && mkdir -p /etc/rule-editor \
    && mkdir -p /opt/rule-editor/hooks-disabled \
    && chown root:root /opt/rule-editor/hooks-disabled \
    && chmod 0555 /opt/rule-editor/hooks-disabled
USER rule-editor
WORKDIR /var/lib/rule-editor

# The bound repository, the forge and the workspace root come from the EXTERNAL
# config directory (config/README.md): mount one at /etc/rule-editor, or pass
# --editor.repository.url=… etc. as arguments (run.sh forwards them to Spring).
# ⚠ The directory must EXIST even when nothing is mounted: the launcher passes it as
# spring.config.additional-location without `optional:`, and Spring refuses to start on a
# missing location ("Config data location '/etc/rule-editor/' does not exist"). Empty is
# fine — the packaged defaults and the environment then apply.
ENV COREJ_CONFIG_DIR=/etc/rule-editor
# The workspaces survive a container replacement; the metadata store (a FILE at the
# engine's store format 5, named by CDISC_METADATA_STORE — the service seeds it at
# start-up when the volume holds none) lives in a second volume — see docker-compose.yml. Every run is a child JVM started from /opt/rule-editor/lib/*.
VOLUME ["/var/lib/rule-editor/workspaces", "/var/lib/rule-editor/metadata-store"]
EXPOSE 8081

ENTRYPOINT ["/opt/rule-editor/run.sh"]
# ⚠ THE SESSION COOKIE STAYS Secure (security review M3, the packaged default): the forge
# token lives in the session, so the cookie must never cross plain HTTP. Serve the editor
# through a TLS-terminating proxy (which also sets HSTS); plain HTTP to anything but
# localhost is NOT supported. Chrome and Firefox send a Secure cookie to http://localhost,
# so a trial on the same machine works as it is.
# Trial opt-out ONLY (a throw-away instance on a trusted network, a browser that refuses
# Secure cookies on http://localhost): pass --server.servlet.session.cookie.secure=false,
# or set SERVER_SERVLET_SESSION_COOKIE_SECURE=false (docker-compose.yml: COOKIE_SECURE).
CMD ["--editor.workspace-root=/var/lib/rule-editor/workspaces", "--editor.workspace.hooks-dir=/opt/rule-editor/hooks-disabled"]
