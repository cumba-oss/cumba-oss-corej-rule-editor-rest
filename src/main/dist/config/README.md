# `config/` — external Spring configuration

`application.yaml` here overrides the copy packaged inside the application jar. The launcher
wires it up in `cumba-oss-corej-rule-editor-rest.conf` as

```ini
spring.config.additional-location = ${env:COREJ_CONFIG_DIR:-${sys:corej.config.dir:-${bootstrap.dir}/config}}/
```

`additional-location` (not `location`) means the packaged defaults still apply and this file
only overrides the keys it sets. `COREJ_CONFIG_DIR` points the whole mechanism somewhere else.

⚠ The trailing slash in that value is required, and it is appended by the `.conf` — Spring Boot
reads a value without one as a *file* path and refuses to start (*"If the location is meant to
reference a directory, it must end in '/'"*). Set `COREJ_CONFIG_DIR` (or `-Dcorej.config.dir`)
to a directory **without** one.

⛔ `-Dspring.config.additional-location=…` on the command line does **not** work: the `.conf`
applies its `[properties]` with an unconditional `System.setProperty` and overwrites it.

What belongs here: the bound repository and forge (`editor.repository.url`, `editor.forge.*`),
the workspace root, the port, the session cookie's `domain` (sibling subdomains), the CORS
allow-list (`editor.cors.allowed-origins`) and the reverse proxies whose `X-Forwarded-For` the
per-client limits believe (`editor.trusted-proxies`). The packaged `application.yaml` documents
every key.

⚠ The session cookie is `Secure` and stays so: serve HTTPS (a TLS-terminating proxy). Plain HTTP
to anything but `localhost` is not supported; `server.servlet.session.cookie.secure: false` is a
trial opt-out for a throw-away instance only — the forge token lives in that session.
