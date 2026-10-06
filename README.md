# cumba-oss-corej-rule-editor-rest

The REST half of the coreJ **rule editor** — a rule review and test tool for a coreJ rule corpus
kept in git (the public one is [cumba-oss-corej-rules](https://github.com/cumba-oss/cumba-oss-corej-rules)).
The SPA is [cumba-oss-corej-rule-editor-web](https://github.com/cumba-oss/cumba-oss-corej-rule-editor-web);
the engine behind it is [cumba-oss-corej](https://github.com/cumba-oss/cumba-oss-corej).
Part of the [cumba-oss](https://github.com/cumba-oss) set, licensed under the AGPL-3.0 (`LICENSE`).
An instance runs against **whatever repository it is configured for**, on Gitea or GitHub.

⛔ **Nothing here is published to Maven Central** — this is an application, delivered as the
runnable bundle attached to the forge release (excluded via `excludeArtifacts` in the pom's
`deploy-central` profile).

**What it does:** login against the forge, one git workspace per user, branches / files /
commit / push under the server-side rules, the Explorer tree and the search (free text + the
semantic filters); and, through the engine's authoring API (`cumba-oss-corej-authoring`,
`cumba-oss-corej-ruletest`, `PLAN-rule-authoring-api`), the analysis of a rule buffer with its
diagnostics, derived fields and scope universe, the completion and tooltip catalogues, the
scenario list, related rules, the scenario grid over the engine's text-faithful `.cdt` document,
and the run of one rule buffer against one scenario buffer in a child JVM worker — the corpus
gate's own code, so the editor's verdict **is** the gate's. Packaged as the `cumba-oss-bootstrap`
distribution bundle with the opt-in bundled SPA, the CORS allow-list, the docker files and the CI
workflow with its smoke test.

## What it is bound to

One instance serves **one git repository** (`editor.repository.url`) on **one forge**
(`editor.forge.type` = `gitea` | `github`, `editor.forge.api-url`). There is no user management:
at login the user supplies a login name and a personal access token for that forge; the token is
probed (`GET /user`, `GET /repos/{owner}/{repo}`, and the git `receive-pack` advertisement) and
decides the mode:

| probe result | mode |
|---|---|
| cannot read the repository (Gitea answers **404**, never 401) | login refused (`401 FORGE_REFUSED`) |
| can read, cannot push — by role **or by token** | `READ_ONLY`: open, edit buffers, switch branches; save / commit / push answer `403 READ_ONLY` |
| can read, role may push **and** the token passes the receive-pack probe | `EDIT` |

Every authenticated user gets **their own clone** under `<editor.workspace-root>/<forge user id>/`,
fetched with their own token, kept across sessions (the next session resumes the branch). A
workspace untouched for `editor.workspace.retention-days` and holding nothing uncommitted or
unpushed is deleted by the retention sweep.

**Anonymous read** (`editor.anonymous.enabled`, default off) is an opt-in for a **public**
repository only: a server-owned bare clone of the default branch is served read-only to requests
without a session, from a commit snapshot; every refresh re-probes visibility and the mode switches
itself off when the probe fails.

## Server-side rules (PLAN-rule-editor §5.8) — each has a test

- **Authorization** — mode comes from the session's probe; the workspace comes only from the
  principal, never from a parameter. Read-only ⇒ `403 READ_ONLY` on every mutating endpoint.
- **Path containment** — file paths are repository-relative and on the allow-list
  `rules-src/checks/**`, `rules-src/docs/**`,
  `src/test/resources/net/cumba/corej/core/ruletestsuites/**` (sidecars included). Absolute
  paths, `..`, any `.git` segment and any hidden segment answer `400 BAD_PATH`; the resolved path
  must stay inside the clone with symlinks not followed, and no symlink is ever read or listed.
- **No hooks, no symlinks in clones** — every clone has `core.symlinks=false` and
  `core.hooksPath` pointing at the empty, server-owned `editor.workspace.hooks-dir` (default
  `<workspace-root>/.hooks-disabled/`; the container image's is root-owned and read-only). ⚠ That
  setting is the control: commits also pass `--no-verify`, which skips only `pre-commit` /
  `commit-msg` — JGit still runs `post-commit` from the hooks directory (the test plants it).
- **The default branch is read-only** — file writes, commits, pushes and updates on it answer
  `409 DEFAULT_BRANCH` (compared case-insensitively); changes reach it through a pull request.
  ⚠ The editor's check is a convenience, frozen at clone: the **forge's branch protection** on
  the default branch is the real barrier — keep it on.
- **Branch names are ref names** — every name a request carries (create, switch, delete,
  update) is checked as a valid ref name not starting with `-` before JGit sees it
  (`400 BAD_REQUEST`).
- **Clean tree for branch switch and update-from-\*** — `409 DIRTY_TREE` names the files.
- **Discard is commit's inverse** — `POST /api/discard` acts on exactly the set a commit would
  stage (the uncommitted changes under the allow-list): a path HEAD holds is restored from HEAD
  (index and working tree), a path HEAD lacks is unstaged and deleted, and the directories it
  alone filled are removed up to the allow-list root. HEAD, other branches, the push ledger,
  changes outside the allow-list (never produced through the API — they keep the tree dirty, as
  they keep a commit from cleaning it) and git-ignored files are never touched. The request names
  the files the user confirmed; they must equal the current set, else `409 STALE` with the
  current `paths` and nothing is discarded. The file system is acted on only for paths `git status`
  reported, never for a client-supplied path, and every one passes the containment check (no
  symlink element) before the first change — one refused path leaves the tree as it was.
- **Optimistic concurrency** — every mutating request carries the client's `branch` and `head`
  (and `blob` for a file write); a mismatch is `409 STALE` with the current `branch` / `head`,
  and the UI reloads. Every access to a workspace runs under its lock, reads included — waited
  for at most `editor.limits.workspace-lock-wait`, then `503 WORKSPACE_BUSY`; even the commit
  identity and the last-use marker a request records are written under it (a push saves the same
  `.git/config`). Nothing slow holds
  it: a run exports under it and runs outside it, the search index is prepared under it and built
  outside it, from the HEAD snapshot plus the changed files read under the lock.
- **Push is never forced** — `409 NON_FAST_FORWARD` offers *update from remote branch*.
- **A conflicting merge is aborted** by resetting to the pre-merge HEAD, `409 MERGE_CONFLICT`
  naming the paths.
- **Push status** — a branch is *unpushed* when HEAD is not reachable from its remote-tracking
  ref, or there is no tracking ref and HEAD is not the SHA this server last pushed (kept in
  `.git/config`, never in the tree). A branch the forge squash-merged and deleted therefore stops
  counting as unpushed after a fetch with prune, and *delete branch* removes it.
- **Transports time out** — every clone, fetch, push and ls-remote has
  `editor.workspace.git-timeout` (60 s).
- **Retention deletes under the lock** — the check "untouched, clean, nothing unpushed" and the
  deletion happen in one lock hold; a workspace whose lock a request holds is kept.
- **Request limits** — a request body above `editor.limits.max-request-bytes` (default four
  buffers) answers `413 TOO_LARGE` before anyone reads it; logins are limited to
  `editor.limits.logins-per-minute` per client (`429 RATE_LIMITED`); anonymous searches to
  `editor.anonymous.searches-per-minute` with at most `max-search-results` hits. A *client* is the
  TCP peer, or — behind one of `editor.trusted-proxies` — the right-most untrusted
  `X-Forwarded-For` hop; IPv6 counts by its `/64`. Trust only the proxies' own addresses: a
  client inside a trusted block cannot be told from a proxy.
- **No server path in an answer** — an I/O failure answers a generic text, a 5xx message has the
  workspace root replaced by `<workspaces>`, the store's reason names its file only.

## Session and CSRF

Session cookie `HttpOnly; Secure; SameSite=Lax`, never persisted, 8 h timeout (a session nobody
has logged in to: `editor.limits.pre-login-session-timeout`, 15 min); the token lives only in the
server-side session and is handed to JGit in memory. CSRF is on for every mutating request, **the
login included**: `GET /api/session/csrf` → send the token as `X-CSRF-TOKEN`. The login rotates
both the session id and the CSRF token: re-fetch it after login and after logout. Unauthenticated
API calls answer `401` as a problem, never a redirect — the read endpoints, the buffer-in POSTs
included, are decided by the security chain before a body is read.

⚠ **HTTPS.** The forge token lives in the session, so the cookie stays `Secure`: serve the editor
through a TLS-terminating proxy (which also sets HSTS). Plain HTTP to anything but `localhost` is
**not supported** — Chrome and Firefox send a `Secure` cookie to `http://localhost`, so a local
developer run works as it is. Trial opt-out ONLY, for a throw-away instance:
`--server.servlet.session.cookie.secure=false` (compose: `COOKIE_SECURE=false`).

## The API

Errors are RFC 9457 problems (`application/problem+json`) with a stable `code` property; extra
properties as noted. The full contract is the committed [`openapi.snapshot.json`](openapi.snapshot.json)
(served live at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`); `OpenApiSnapshotTest` reds when
the served document drifts from the file — regenerate with
`mvn -Dopenapi.snapshot.write=true test -Dtest=OpenApiSnapshotTest` and commit it with the change.

| method, path | request | response | errors |
|---|---|---|---|
| `GET /api/info` | — | `{service, version, commit, repository{url, owner, name, forge}, anonymousRead}` | — |
| `GET /api/session/csrf` | — | `{headerName, parameterName, token}` | — |
| `POST /api/session/login` | `{login, token}` | `SessionInfo {id, login, name, email, mode, defaultBranch}` | 400 `BAD_REQUEST`, 401 `FORGE_REFUSED`, 403 `CSRF`, 502 `FORGE_UNAVAILABLE` |
| `GET /api/session` | — | `SessionInfo` | 401 `UNAUTHENTICATED` |
| `POST /api/session/logout` | — | 204 | — |
| `GET /api/status` | — | `{branch, head, defaultBranch, readOnly, uncommitted[{path, kind}], ignored[…], unpushed{unpushed, hasTrackingRef, aheadCount, behindCount, commits[{sha, message, author}]}}` | 401 |
| `GET /api/branches?fetch=` | `fetch=true` fetches + prunes first | `{current, defaultBranch, head, branches[{name, kind: LOCAL\|REMOTE, head, current, isDefault, unpushed?}]}` | 401, 502 |
| `POST /api/branches` | `{name, branch, head}` — from the remote default branch, checked out | `Branches` | 400, 403 `READ_ONLY`, 409 `STALE` / `DIRTY_TREE` / `BRANCH_EXISTS` / `DEFAULT_BRANCH` |
| `POST /api/branches/switch` | `{name, branch, head}` — a local or a remote branch | `Branches` | 404, 409 `STALE` / `DIRTY_TREE` |
| `POST /api/branches/delete` | `{name}` — a local branch holding nothing unpushed | `Branches` | 404, 409 `UNPUSHED` / `CURRENT_BRANCH` |
| `POST /api/branches/update-from-default` | `{branch, head}` | `{merge{ref, status: ALREADY_UP_TO_DATE\|FAST_FORWARD\|MERGED, head}, branch, head}` | 403, 409 `STALE` / `DIRTY_TREE` / `DEFAULT_BRANCH` / `MERGE_CONFLICT{ref, paths, head}` |
| `POST /api/branches/update-from-remote` | `{branch, head}` — `origin/<current>` | as above | as above, 404 when the remote branch is gone |
| `POST /api/commit` | `{message, branch, head}` — stages every saved change under the allow-list | `{commit{sha, message, author}, branch, head}` | 400, 403, 409 `STALE` / `DEFAULT_BRANCH` / `NOTHING_TO_COMMIT` |
| `POST /api/discard` | `{paths, branch, head}` — `paths` = the confirmed `status.uncommitted` paths | `{discarded[{path, kind}], branch, head}` | 400 (no `paths`), 400 `BAD_PATH` (a planted symlink), 403 `READ_ONLY`, 409 `STALE{branch, head[, paths]}` |
| `POST /api/push` | `{branch, head}` | `{push{branch, head, status: OK\|UP_TO_DATE, created}, pullRequestUrl}` | 403, 409 `STALE` / `DEFAULT_BRANCH` / `NON_FAST_FORWARD`, 502 |
| `GET /api/files?path=` | a directory on the allow-list | `{path, entries[{name, directory, size}], branch, head}` | 400 `BAD_PATH{path}`, 401 |
| `GET /api/files/content?path=` | a file on the allow-list | `{path, content, blob, branch, head}` | 400, 401, 404 |
| `PUT /api/files/content` | `{path, content, branch, head, blob}` — `blob` null when creating | `{path, blob, branch, head}` | 400, 403 `READ_ONLY`, 409 `STALE{branch, head}` / `DEFAULT_BRANCH` |
| `GET /api/explorer` | — | `{branch, head, anonymous, families[{name, rules[{id, path, docsPath?, marker: CLEAN\|MODIFIED\|NEW}]}]}` | 401 |
| `GET /api/search?q=&limit=` + `variable=`, `scope=`, `function=`, `status=`, `standard=`, `version=`, `noScenarios=` | words ANDed, quotes make a phrase; the semantic filters are ANDed with the text: *mentions variable* (`AETERM` also finds a `--TERM` rule whose scope reaches AE, through its domains or — via the store — its class; case-insensitive), *scoped to* a domain or class (explicit scope only, never `ALL`), *uses function*, status, standard + version, *has no scenarios* | `{branch, head, query, hits[{id, family, path, docsPath?, description, snippets[{file: check\|docs, line, text}]}], total, truncated}` | 401 |
| `POST /api/analyse` | `{path, content, docs?}` — the check buffer; the docs buffer when the client holds one, else the branch's sidecar | `{branch, head, analysis{coreId, loaded, parked, diagnostics[{code, severity, message, path?, range?{startLine, startColumn, endLine, endColumn} (1-based, inclusive), document: CHECK\|DOCS}], derived?{sensitivity, sensitivityConfidence, sensitivityRationale, outputVariables{effective, authored, excluded, derived}, authorities?, evaluationDomain?, wildcardNames}, scope{products, missingProducts, datasets[{product, name, datasetClass?, dataStructure?, inScope, mismatch?}], variables[{name, label?, type?, core?, role?, codelists, datasets, products, classLevel, concrete}]}, referencedVariables[{name, where, quoted}], bindings, storeAvailable}}` | 400 `BAD_PATH`, 401, 413 `TOO_LARGE` |
| `GET /api/engine/functions` | — | `{functions[{name, kind, signature, parameters[{name, type, required, collector, description?}], returnType?, summary?, sections}]}` — the registry joined with the branch's `rule-guide/reference/functions.md` | 401 |
| `GET /api/engine/schema` | — | the branch's `rule-check.schema.json` (`documentation/` or `schema/`) | 401, 404 `NO_SCHEMA` |
| `GET /api/engine/ct?codelist=&term=` | a C-code or submission value; `term` narrows to one term | `{query, hits[{ctPackage, conceptId, submissionValue, name, preferredTerm, definition, extensible, termCount, terms[{conceptId, submissionValue, preferredTerm, definition}]}]}` — newest package first, empty without a store | — |
| `GET /api/scenarios?rule=` | a rule id | `{branch, head, rule, family, suite{directory, scenarios[{name, path, coreId?, expect?, domain?, expectViolationCount?, expectViolationAtCount, datasets, sidecars, parseError?, size}], otherFiles}}` | 400, 401 |
| `GET /api/related?rule=` | a rule id | `{branch, head, rule, related[{coreId, relation, via, family, path, exists}]}` — shared authority references across families; the equivalence / relation classes and minted parentage where the branch carries the registers (§5.5) | 400, 401 |
| `POST /api/cdt/parse` | `{content}` | `{datasets[{name, label?, attrs, fence, columns[{name, type, label?, length?, format?, codelist?, attrs}], rows[][]}], directives[{line, name, text}], error?}` — cells in the parser's field encoding: an unquoted missing sentinel (`.`, `._`, `.A`…`.Z`) bare, a literal that looks like one backslash-escaped, text otherwise | 401, 413 |
| `POST /api/cdt/splice` | `{content, dataset, index?}` — one block rewritten by the grid, addressed by its position in `parse`'s `datasets` (else the first block of `dataset.name`) | `{content}` — only that block's `col` lines and rows change (comments between its rows are dropped) | 400 `CDT_UNREPRESENTABLE` (the engine's message), 401, 413 |
| `POST /api/cdt/expectations` | `{content, lines, expect?}` — the confirmed `#expectViolation*` lines (required; `[]` clears them) and, for a run's proposal, its `expect` (`violation` \| `noViolation`) | `{content}` — the old lines replaced at the canonical position; with `expect`, the `#test` line's `expect=` set to it (and `#expect-execution-error` / `#expect-abort`, which only another verdict admits, dropped) | 400, 401, 413 |
| `POST /api/run` | `{path, content, docs?, scenarioPath, scenarioContent}` — the rule buffer and the scenario buffer (saved or not) | `{branch, head, path, scenarioPath, report{outcome: PASS\|FAIL\|ABORTED, code, detail, scopeGate, evaluation?, skip?, verdict?, locations?, results[], findings[{ruleId, dataset?, row, usubjid?, seq?, keys, values, level?, unit, groupKey, namedColumns, message?}] (maps in the engine's order; `dataset` is the primary dataset the rule ran on, `#test domain=`, as the v2 report names it), reportJson? (the v2 report document), expectations?{count, ats, lines, expect, testLine}, ruleDiagnostics, durationMs, worker: child\|inline}}` — `path` / `scenarioPath` name what ran, so *take actual as expected* binds to the run's scenario; `expectations` proposes `expect=violation` with the lines when the rule fired, `expect=noViolation` with none when nothing fired on a scenario that expected a violation, and `testLine` is the rewritten `#test` line; codes beyond the engine's: `RULE_ERRORS`, `PARKED`, `PARSE`, `TIMEOUT`, `WORKER_FAILED`, `TOO_LARGE` (the report exceeded `editor.run.max-report-bytes`) | 400 `BAD_PATH`, 401, 409 `RUN_IN_PROGRESS` (one run at a time per user / anonymous client), 413 `TOO_LARGE`, 429 `RATE_LIMITED` (anonymous), 503 `RUN_BUSY`, 503 `WORKSPACE_BUSY` |

The read endpoints (`files`, `explorer`, `search`, `scenarios`, `related`, `engine/*`, and the
buffer-in / answer-out POSTs `analyse`, `cdt/*`, `run`) serve the session's working tree, else
the anonymous snapshot when it is on, else `401 UNAUTHENTICATED` — decided by the security chain
before the body is read. Every problem carries a `code`, Spring MVC's own included (`BAD_REQUEST`
for an unreadable body or a mistyped parameter, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, …; `INTERNAL`
and `IO_FAILED` with a generic text — the log has the details); `GET /api/engine/schema` answers
`422 SCHEMA_INVALID` when the branch's schema file is not JSON. Content is UTF-8 text; `blob`
is the git blob id of the content. `GET /api/info` carries `engine{version, languageLevel,
storeAvailable, storeFormat, storeReason, runTimeoutSeconds, maxBufferBytes}` and
`GET /api/explorer` the branch's `rules-src/LANGUAGE-LEVEL` (`languageLevel`, null when the
branch declares none): the SPA's engine-version banner compares the two (O13).

## The engine behind it (phase 3)

- **One implementation.** `AuthoredRuleAssembler.assemble` reads the buffer and the docs sidecar
  with the package standards of the branch's `rules-src/package-standards.json` (the first
  `Standards` entry names the package, as the corpus generator files the rule); `RuleInsight`
  derives the fields and the scope universe over the metadata store; `ScenarioRunner.runText`
  judges a run with the confined `SidecarResolver`; `ScenarioReports` + `JsonReportWriter`
  write the report; `ViolationLocationCheck.toExpectations` proposes the expectations, rendered
  by `RuleTestCdt`'s own writer; `CdtDocument` splices the grid. Nothing here re-implements a
  verdict. ⚠ The engine derives `Sensitivity` before it knows a rule is parked, so a parked
  rule can carry a `SENSITIVITY_UNDERIVABLE` the gate never raises: the editor drops it
  (`RuleAnalyser`, PLAN-rule-editor §12) until the one-line engine fix lands. A parked rule is
  never loaded, yet its derived fields, references and scope universe come from the same check
  read without its `Executability` (the engine's reading), its `Authorities` from the parked
  canonical node. A class variable (`--ENDTC`) lists the concrete ones (`AEENDTC`) whose prefix
  is an in-scope dataset.
- **The metadata store** (`editor.metadata-store`, else `CDISC_METADATA_STORE`, else
  `-Dcdisc.metadata.store`) is opened once at startup and shared read-only; the run workers get
  its path. ⛔ CDISC Library content comes only from it — the editor never seeds, never reads the
  pickle or the web API. A store of another format is refused by the engine and the instance runs
  without one (`engine.storeAvailable=false`: scope completion empty, library tooltips absent,
  a scenario needing the Library fails with the engine's reason).
- **Runs are child JVM workers** (§5.8.5): `RunWorker` reads the job on stdin and writes the
  report on stdout; `-Xmx editor.run.max-heap`, `-XX:+ExitOnOutOfMemoryError`, killed past
  `editor.run.timeout` (`ABORTED TIMEOUT`); at most `editor.run.max-concurrent` at once and
  `editor.run.max-queued` waiting up to `editor.run.queue-wait` (`503 RUN_BUSY`); one run at a
  time per user or anonymous client (`409 RUN_IN_PROGRESS`); anonymous clients
  `editor.run.anonymous-runs-per-minute` (`429 RATE_LIMITED`) and a pool of their own
  (`anonymous-max-concurrent` 1, `anonymous-max-queued` 2, `anonymous-timeout` 20 s) — never an
  author's worker; a report above `editor.run.max-report-bytes` kills the worker (`ABORTED
  TOO_LARGE`); analysed and run buffers above `editor.limits.max-buffer-bytes` answer
  `413 TOO_LARGE`. The job is fed from a thread of its own, so the timeout counts from the
  worker's start; the worker inherits only `PATH`, the temp, locale and time-zone variables, and
  halts itself when the server that started it is gone or it outlived the timeout by 15 s. ⚠ It
  runs under the server's uid (a separate uid would isolate more; not done). The worker's class path is
  `editor.run.classpath`, else the bundle's `lib/*` under the launcher (`bootstrap.dir`), else
  this JVM's. A run never touches the workspace: the scenario's suite directory (its sidecars)
  is exported into a scratch tree under `<workspace-root>/.runs/`, the buffer is written where
  the file is or would be, the resolver is rooted at the exported suites tree — a `../`,
  absolute or symlinked sidecar has nothing to reach and the engine refuses it (§5.8.6, tested
  end to end). A read-only user and an anonymous reader run exactly like a writer (O12).
- **The semantic search index** assembles every check of the view once (3 461 rules in
  1.7–3.2 s measured 2026-10-06), cached per HEAD + the content hash of the uncommitted check,
  docs and scenario files; the related-rules `CorpusIndex` comes from the same build. One build
  per view however many requests ask (single-flight), OUTSIDE the workspace lock (the HEAD
  snapshot with the changed files laid over it), and incremental: with an index of the same HEAD
  cached, only the rules whose files changed are assembled again.
- **Jackson:** the engine speaks Jackson 2, Spring Boot 4 speaks Jackson 3; nodes cross by text
  (`engine/Json`), never by reference.

## Building and running

Shaped like `cumba-oss-corej-rest`: Spring Boot BOM 4.1.1 (no parent), Java 25, the full gate stack,
`ban-oss-twins`. A client leaf — never in `build_order.txt`, nothing consumes it:

```bash
mvn -o verify                                            # tests, PMD/SpotBugs reports, javadoc gate
mvn -o verify -Dspotbugs.failOnError=true -Dpmd.failOnViolation=true -Dspotless.check=true
```

Tests run against local bare repositories and a fake forge; no test reaches a real forge or the
real user home (`user.home` is redirected to `target/test-home`, guarded by
`HomeStaysCleanExtension`). There is no Mockito.

A developer run straight from the build output, after `mvn package`:

```bash
java -cp "target/classes:target/libs/*" net.cumba.corej.editor.RuleEditorApplication \
    --editor.repository.url=https://github.com/cumba-oss/cumba-oss-corej-rules.git \
    --editor.workspace-root=./workspaces
```

(Browse it at `http://localhost:…`: the `Secure` cookie travels to `localhost` over plain HTTP in
Chrome and Firefox. A forge without TLS needs `--editor.repository.allow-http=true`.)

Configuration keys and their meaning: [`src/main/resources/application.yaml`](src/main/resources/application.yaml).

## Running the distribution

`mvn package` also assembles the runnable bundle, `target/cumba-oss-corej-rule-editor-rest-<version>.zip`
(and the same tree exploded under `target/…/`), shaped like `cumba-oss-corej-rest`'s:

```
cumba-oss-corej-rule-editor-rest-<version>/
├── cumba-oss-corej-rule-editor-rest.jar    the cumba-oss-bootstrap launcher (java -jar this)
├── cumba-oss-corej-rule-editor-rest.conf   its sidecar: JVM + launcher config, the classpath
├── run.sh   run.bat
├── config/application.yaml             the EXTERNAL Spring config — the repository, forge,
│                                       workspace root, port, cookie flags, CORS (README there)
└── lib/                                this jar + every runtime dependency
```

`./run.sh --editor.repository.url=…` (arguments go to Spring; `COREJ_CONFIG_DIR` points the
external config elsewhere). The plain jar in `lib/` has no `Main-Class` and is not runnable on its
own. No corpus directory is shipped: the rules come from git, per user.

### The three deployment shapes (PLAN-rule-editor §4.2)

1. **Bundled** — the SPA inside this jar, served at `/` on the same origin:
   `mvn -Pbundle-web clean package -Dweb.dist.dir=../cumba-oss-corej-rule-editor-web/dist`
   (build the SPA first: `npm ci && npm run build` there). The default build is **API-only**.
2. **Two apps behind a reverse proxy** — the SPA served as static files, `/api`, `/v3/api-docs`
   and `/swagger-ui*` forwarded here; the browser still sees one origin. The web repo's
   `docker/nginx.conf` is the reference. Sibling subdomains of one site also work: list the SPA's
   origin in `editor.cors.allowed-origins` and set `server.servlet.session.cookie.domain`.
   ⛔ Cross-site is not supported (`SameSite=Lax` cookie, session-bound CSRF token).
3. **`docker-compose.yml`** — `rule-editor-rest` (this `Dockerfile`, built from the exploded
   bundle; `workspaces` and `metadata-store` volumes) + `rule-editor-web` (the web repo's nginx).
   ⚠ Written without docker available: shape-checked, not run. The metadata store is a FILE
   seeded separately at the engine's store format (5), placed in the `metadata-store` volume as
   `metadata-store.zip` — see the compose file's header; the run workers' memory is
   `EDITOR_RUN_MAX_HEAP` × `EDITOR_RUN_MAX_CONCURRENT` on top of `JAVA_OPTS`. Secure by default
   (security review M3): the nginx port is published on `127.0.0.1` only and the cookie is
   `Secure` — put a TLS-terminating proxy in front to serve other machines; `COOKIE_SECURE=false`
   is a trial opt-out for a throw-away instance only. `EDITOR_TRUSTED_PROXIES` (by default exactly the
   compose network's pinned subnet, `172.28.40.0/24` — change both together) makes the per-client
   limits count clients, not nginx. ⚠ A client inside a trusted block cannot be told from a proxy
   (it could forge `X-Forwarded-For` for a fresh limit key per request), so never trust a wider
   range; the network's gateway is inside the block, so the TLS proxy on the host must append
   the real client to `X-Forwarded-For`. The SPA's nginx must accept bodies up to `editor.limits.max-request-bytes` (4 MiB by
   default).

### The smoke test

`src/test/smoke.sh <exploded bundle>` starts the bundle from an unrelated directory, against
`src/test/smoke-forge.py` — a python fake Gitea whose git half is `git http-backend` as CGI — and
does what the SPA does: login (expects `EDIT`, which proves the identity, repository and
receive-pack probes and the clone over HTTP), explorer, new branch, open, save with the stamp, read
back, commit, push — then reads the commit out of the fixture's bare repository. CI runs it after
`install`. Since phase 3 it also analyses the edited rule and runs it against the seeded
scenario in a child worker started from the bundle's `lib/*` — with no metadata store
configured (the analysis reports `storeAvailable=false`).
