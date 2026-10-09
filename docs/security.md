# Security

**Deployment context:** FAMS runs only inside the college network (`docs/deployment.md`). That removes the public-internet
threats but not the local ones: students share the network, so passwords still need TLS, accounts are still protected
against guessing, and the administrator and reviewer areas are still enforced on the server.

## Implemented and tested

- **Passwords:** BCrypt. The hash is stripped from the principal before it is stored in the session, and is never
  printed (`FamsUserPrincipal.toString`). Login rejects passwords over 72 bytes (BCrypt's limit) as bad credentials.
- **Sessions:** server-side session, `HttpOnly`, `SameSite=Lax`, `Secure` unless `FAMS_SECURE_COOKIES=false`, 30 minute
  idle timeout, session ID rotated at login (fixation), carried in a cookie only (never in a URL). A request that is
  refused for lack of a sign-in opens no session, so sessions cannot be piled up by someone who is not signed in.
  Verified over real HTTP in `RealServerSecurityTest`.
- **CSRF:** every state-changing request needs the `X-XSRF-TOKEN` header matching the `XSRF-TOKEN` cookie.
  Failure is 403 with a JSON body. A token issued before sign-in is retired by the sign-in (and by sign-out). The token
  counts in the header only, never as a request parameter (`HeaderOnlyCsrfTokenHandler`): the cookie could be planted by
  another site under the same parent domain, and a form from there can carry a parameter but not a header.
- **No account enumeration:** unknown user, wrong password and disabled account return the same 401 message, and take
  the same time: the account's state is checked after the password, and an unknown address costs a dummy hash check.
- **Brute force** (`SignInThrottle`): three limits over ten minutes, counting only attempts whose password was checked
  and wrong. Each returns the same 429.
  - 5 per account *and* client address: the everyday limit. Someone guessing at the Principal's account locks out
    only their own address.
  - 25 per account from any address: changing address (trivial on a LAN) or forging a forwarding header does not buy
    more guesses. The address the account last signed in from successfully is exempt, so the owner at their usual
    machine is not shut out by other people's guessing.
  - 100 per client address across all accounts: one machine cannot try a few common passwords against everyone.

  An attempt is counted before the password is checked and given back if it was right, so a burst of parallel requests
  cannot slip past the limit. The counters are in the database (`sign_in_attempts`, migration V10), not in the server's
  memory: every instance of the backend counts against the same limits and a restart forgets nothing, including the
  address each account last signed in from (`users.last_login_address`). Each change is one conditional statement, so
  the limit holds under parallel requests whichever instance they reach. Only a SHA-256 of each key is stored (no
  e-mail or client address), dead rows are purged, and the table is bounded (100 000 keys; beyond that it refuses
  rather than grows).
- **Client address:** taken from the connection. Behind a reverse proxy (`FAMS_FORWARD_HEADERS=native`) it is read from
  `X-Forwarded-For`, but only when the request comes from a trusted proxy (`FAMS_TRUSTED_PROXIES`, this machine by
  default) and from the right-hand end, so a value the client put there itself is never used. The setting that does
  believe the client (`framework`) stops the application from starting. The backend listens on this machine only unless
  `FAMS_BIND_ADDRESS` says otherwise, so the proxy cannot be bypassed from the network.
- **Request size:** every request body is limited to 1 MB (`fams.max-request-bytes`), whether or not it declares its
  length; over that is 413. The application accepts no file uploads.
- **Authorization:** URL rules per role area, plus object-level checks in `AppraisalService.loadVisible`
  (ownership, department assignment, workflow state). Unauthorized access to a record returns 404.
- **State rules** are backend-enforced; concurrent transitions are safe (guarded `UPDATE`).
- **Database as last defence:** enum, range and format CHECK constraints; review history, issued reports and the earlier wordings of edited HoD messages are append-only; audit entries cannot be edited.
- **Headers:** `X-Content-Type-Options`, `X-Frame-Options: DENY`, CSP `default-src 'none'`, `Referrer-Policy`,
  `Permissions-Policy`, `Cross-Origin-Resource-Policy` and `Cross-Origin-Opener-Policy: same-origin`. Cross-origin
  access (CORS) is off unless `FAMS_CORS_ORIGIN` names one origin.
- **Errors:** uniform `{"message"}` bodies, no stack traces, a request ID in logs, responses and 500 bodies.
- **Security events in the log:** sign-ins (user, role, address), failed sign-ins (the address tried, only if it is shaped
  like an e-mail address, so a password typed into the wrong box is never written), each limit when it starts refusing,
  wrong current passwords at a password change, sessions ended by the server and why, and a signed-in user refused an
  area. Passwords, tokens and session IDs are never logged.
- **Secrets:** the database password has no default and no value is committed; `.env` is git-ignored.
- **Actuator:** only `/actuator/health`, status only.

- **Section saves:** only the author can write, only while the appraisal is editable (checked under a row lock);
  record ids from another appraisal are rejected; strict field allow-list per section; control characters rejected;
  all values are bound parameters and all table/column names come from code.

- **Frontend:** the browser talks only to its own origin (proxy), sends the CSRF header on every write, shows the
  server's messages without HTML interpretation (React escapes text), and treats any 401 as an ended session.
  Security headers are set on pages too (nosniff, frame deny, referrer policy, same-origin isolation), and every page
  carries a Content-Security-Policy with a per-request nonce (`frontend/src/proxy.ts`): only the application's own
  scripts run, no inline event handlers or `javascript:` links, no `eval`, no requests or form posts to other origins,
  no frames. Pages are rendered per request (the nonce needs it) and are never cached.
- **Dev accounts:** created only under the `dev` profile, with the old password (see the note on it below).
  Never enable that profile in production.
- **Sessions follow the database:** `SessionGuardFilter` re-checks the account on every request. Disabling an account,
  changing its role, deleting it, or changing/resetting its password ends the affected sessions at once (401). A user
  whose password was issued by an administrator can do nothing but change it (403 `PASSWORD_CHANGE_REQUIRED`).
- **Passwords people choose:** at least 10 characters, a letter and a number, at most 72 bytes, no leading or trailing
  space, at least five different characters, not containing the e-mail name, and not a common word or the college's
  name dressed up with digits and symbols (`Password@2026`, `Welcome#12345`, `Svec@123456`). Changing needs the current
  password again, is rate-limited like sign-in (a stolen session cannot be used to guess it), is audited, and signs
  out the user's other sessions.
- **Administration:** `/api/admin/**` is ADMIN only twice over (URL rule and `@PreAuthorize`); tested against every
  other role and anonymous callers for every endpoint. Every new account, and every account an administrator resets, starts with the
  one standard password (`DefaultPassword`; `Srivasavi@123` unless `FAMS_DEFAULT_PASSWORD` is set), stored as BCrypt only
  and never logged, audited or listed. The account is flagged `must_change_password`, so nothing but changing it works
  until the person has, and the password rules refuse the standard password as a choice. An administrator cannot disable or reset
  their own account, cannot change a role, and cannot read appraisal content or the file names in audit entries about
  appraisals. The audit trail is paged with a hard size cap.
- **Document storage:** issued reports are kept in `FAMS_STORAGE_DIR`, which must be an absolute path
  on storage that is kept (there is no built-in location: the application refuses to start without a directory it can
  write to, rather than keep reports somewhere a redeployment would wipe). A file is forced to disk before it is moved
  into place and before the database mentions it. `scripts/backup.ps1` backs the directory up together with the
  database and verifies every file against the SHA-256 the database holds; `scripts/restore.ps1` puts both back and
  verifies again (`docs/deployment.md`).
- **Hierarchy:** the chain is faculty -> HoD -> Principal and is enforced on the server: the step is chosen from the
  caller's role, the Principal sees an appraisal only from the HoD's approval, and there is no return step (the
  endpoint does not exist and the database refuses the old statuses). The withdrawn Dean and Vice Principal roles have
  no area, cannot sign in and cannot be given to an active account (a database constraint as well as the application).
  Tested over HTTP (`HierarchyIntegrationTest`).
- **Consoles:** each is open to its own role only (URL rule and `@PreAuthorize`; tested for every role and anonymous
  callers). They apply the appraisal visibility rules to what they count: no draft is revealed to an HoD, the Principal sees
  no names before HoD approval, and an administrator's overview has no names or content at all.
- **Reports:** the official PDF is generated once after final approval, stored with its SHA-256 and immutable
  (database triggers); only people who may open the appraisal can download it. Every time it is served the stored file is
  checked against that SHA-256; a file altered or replaced on disk is refused (500, logged), never sent.
- **Dependencies:** Spring Boot 3.5.16 with Tomcat, Jackson and the Log4j bridge raised to their patched releases
  (`backend/pom.xml` says which and why). Hibernate and Spring Data JPA were removed (one query used them), which took
  the runtime libraries from 81 to 60. All 60 are checked against the OSV database by `scripts/audit-dependencies.mjs`
  (on 9 October 2026 the only advisories were the two Spring Framework ones accepted under "Known gaps"). `npm audit --omit=dev`
  reports none for what is shipped to browsers.

- **Image optimizer:** Next's `/_next/image` may read only the application's own logo (`images.localPatterns`). It
  fetches what it is given from this server and caches the result for everyone, so it must never reach `/api`.
- **Exact account match at sign-in:** the database compares addresses loosely (accents, letter width). An address is
  accepted only if it is, lower-cased, character for character the stored one, so look-alike spellings cannot be used
  to get fresh attempt counters for the same account.
- **Checked on every build:** compiler warnings are errors, Checkstyle, SpotBugs with Find Security Bugs, 300+ tests
  against a real MySQL, ESLint and the TypeScript compiler (`scripts/verify.ps1`, `docs/architecture.md`).

## Known gaps

- **An administrator can delete the audit trail** (one entry, every entry of one kind, or all; migration V11 removed the delete lock, and entries still cannot be edited). Each deletion leaves one `AUDIT_DELETED` entry naming the administrator and the number removed, but the deleted entries themselves are gone, so the trail proves less than before. Keep the number of administrators small, and rely on `scripts/backup.ps1` copies and the backend log (every sign-in and refusal is also logged) if a record that cannot be erased is needed.

- **Other sites under the same parent domain are "same site" to a browser.** A page on one of them can plant cookies
  for this host and have the browser send the session with a request it starts. The header-only CSRF token stops the
  form-based version of that. Giving the application a host name whose parent domain carries no untrusted sites, or a
  `__Host-` cookie prefix once HTTPS is in place, would remove the cookie planting itself.

- **Two Spring Framework advisories are accepted, not fixed.** `spring-webmvc 6.2.19` (the version Spring Boot 3.5.16 manages)
  is listed for GHSA-j9f9-w8pj-32f8 / CVE-2026-47890 (Server-Sent Events with view fragments) and GHSA-pc63-qcmh-9cmg /
  CVE-2026-47884 (`XsltView`), both rated critical. The 6.2 line has no fix; it comes in Spring Framework 7.0.9, that is Spring
  Boot 4. Neither feature is used here: the backend has only `@RestController` classes that return JSON, no view technology,
  no `SseEmitter`, no `text/event-stream` and no XSLT. They are recorded with that reason in `scripts/accepted-advisories.json`,
  which `scripts/audit-dependencies.mjs` honours only for that library version and only until the entry's `reviewBy` date
  (31 December 2026); after that, or when the version changes, the audit fails again until someone decides. The real fix is the
  Spring Boot 4 upgrade. Do not add an entry for an advisory in a feature the application does use.
- **Frontend dev dependencies** report 5 npm audit findings, all in the ESLint toolchain (`braces` via `eslint-config-next`); they are not shipped to browsers. Do not apply `npm audit fix --force`: it would downgrade Next.

- **The sign-in limits can be used to inconvenience people.** 25 wrong passwords from five or more addresses close an
  account to new addresses for up to ten minutes (the owner's usual machine still works), and 100 failures from one
  address stop sign-ins from that address. If all users share an address (a proxy without `FAMS_FORWARD_HEADERS=native`,
  or NAT), these act on everyone at once. That is the price of not allowing unlimited guessing; the log says when a limit
  starts refusing.
- **Next.js must not be the outermost server.** It passes an incoming `X-Forwarded-For` through unchanged, so with
  `FAMS_FORWARD_HEADERS=native` and browsers connecting to Next directly, a client could claim any address. The
  per-account limit still holds; the per-address ones would not. Put the reverse proxy in front (it is needed for TLS
  anyway) and bind Next to this machine (`next start -H 127.0.0.1`).
- **A shared old password is guessable until it is replaced.** Everyone's account starts with the same, widely
  known password, so until a person has signed in and replaced it anyone on the network who knows their e-mail
  address can sign in as them (and would then choose the password, locking the person out). Create accounts shortly
  before people are told to sign in, ask them to do so at once, and watch the *Has not chosen a password* filter on
  the Accounts page. The sign-in limits (5 wrong passwords, per account and per address) still apply.
- **An administrator can become anyone** by resetting their password (it is audited as `PASSWORD_RESET`, and the person
  is forced to choose a new one). Keep the number of administrators small and read the audit trail.
- **Generating a draft report is real work** (a PDF per request) and is not rate-limited for signed-in users.
- **Triggers need `log_bin_trust_function_creators=1` or `SUPER`** when the migration runs on a binlog-enabled server.
- **`TRUNCATE`/`DROP` bypass the append-only triggers.** The production application user must not hold those
  privileges. Use a separate migration user.
- **No self-service password reset, no e-mail delivery, no SSO.** An administrator resets a forgotten password and
  sets it back to the standard password; the identity questions in `docs/requirements.md` are still open.
- **PDF text is limited to the WinAnsi character set** (Latin letters, common punctuation). Other characters print as "?".
  Embed a Unicode font in `FormPdfBuilder` if names or titles in other scripts must appear.
- **The document store is one directory.** Several instances of the backend must all be given the same shared volume
  as `FAMS_STORAGE_DIR`; there is no object-storage implementation of `DocumentStorage`.
- **The application does not speak HTTPS itself.** Terminate TLS at a reverse proxy (an internal certificate is fine) and keep
  `FAMS_SECURE_COOKIES=true`. Even on the college network, plain HTTP lets anyone on the same segment read passwords. The Docker
  deployment (`docs/docker-deployment.md`) ships an Nginx configuration that does this, with the certificate mounted from the
  host; its `docker-compose.http-test.yml` option is plain HTTP and for a closed trial run only.
- **Docker hides client addresses on some hosts.** Docker Desktop (Windows, macOS) and rootless Docker put every connection behind
  a gateway address, so the sign-in limits would treat all users as one client. Run the production stack on a Linux host with
  the ordinary Docker Engine.
- **Behind a proxy, set `FAMS_FORWARD_HEADERS=native`.** The sign-in limit is per e-mail address and client address;
  without the real client address, one user's repeated wrong guesses could lock another user's account for ten minutes.
- **The database connection in `.env.example` is unencrypted** (`useSSL=false`), which is right only when MySQL is on the
  same machine. For a database on another host use `sslMode=VERIFY_CA` and remove `allowPublicKeyRetrieval`.
- Flyway 11.7 logs a warning that MySQL 8.4 is newer than it has tested. It works; production on 8.0 avoids the warning.
- The frontend and the API must be same-site (same registrable domain, ports ignored) because cookies are `SameSite=Lax`.
