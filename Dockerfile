# The rule editor's REST service as a container (PLAN-rule-editor §4.2, RE-Q6).
#
# Built FROM THE EXPLODED DISTRIBUTION, not from the sources: `mvn package` (or
# `mvn verify`) assembles target/cumba-oss-corej-rule-editor-rest-<version>/… — the
# cumba-oss-bootstrap launcher, lib/, config/ and the sidecar .conf — and this file
# only copies that tree in. So the image runs exactly the bundle the smoke test
# started (src/test/smoke.sh), and the build arguments name the version:
#
#     mvn -B clean package
#     docker build --build-arg VERSION=0.1.0-SNAPSHOT -t rule-editor-rest .
#
# With the SPA bundled (deployment shape 1 — one container serves the UI at /):
#
#     mvn -B -Pbundle-web clean package -Dweb.dist.dir=../cumba-oss-corej-rule-editor-web/dist
#
# Otherwise the jar is API-only (shape 2) and docker-compose.yml puts the SPA's nginx
# in front of it.
#
# ⚠ Not built in CI yet (the deployment host is an owner decision, PLAN-rule-editor
# §8.5), and the sandbox this was written in has no docker: the file is checked for
# shape, not by a build. Verify the first build by running the smoke test's steps
# against the container.
ARG JRE_IMAGE=eclipse-temurin:25-jre
FROM ${JRE_IMAGE}

ARG VERSION=0.1.0-SNAPSHOT
# The exploded `dir` assembly nests <baseDirectory> inside <finalName>, hence the doubled path.
COPY target/cumba-oss-corej-rule-editor-rest-${VERSION}/cumba-oss-corej-rule-editor-rest-${VERSION}/ /opt/rule-editor/

# git is NOT needed: every git operation is JGit, in-process. A non-root user owns the
# workspaces (one clone per forge user, §5.1.5) and nothing else.
RUN useradd --system --create-home --home-dir /var/lib/rule-editor rule-editor \
    && mkdir -p /var/lib/rule-editor/workspaces /var/lib/rule-editor/metadata-store \
    && chown -R rule-editor:rule-editor /var/lib/rule-editor \
    && chmod 755 /opt/rule-editor/run.sh \
    && mkdir -p /etc/rule-editor
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
# The workspaces survive a container replacement; the metadata store (a FILE seeded at
# the engine's store format 5, named by CDISC_METADATA_STORE) lives in a second volume —
# see docker-compose.yml. Every run is a child JVM started from /opt/rule-editor/lib/*.
VOLUME ["/var/lib/rule-editor/workspaces", "/var/lib/rule-editor/metadata-store"]
EXPOSE 8081

ENTRYPOINT ["/opt/rule-editor/run.sh"]
# Behind the compose's nginx (plain HTTP inside the network, TLS terminated outside) the
# session cookie must not be Secure-only; a TLS-fronted single container overrides this
# with --server.servlet.session.cookie.secure=true in its command.
CMD ["--editor.workspace-root=/var/lib/rule-editor/workspaces", "--server.servlet.session.cookie.secure=false"]
