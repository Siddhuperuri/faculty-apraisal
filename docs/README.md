# FAMS documentation

The Faculty Appraisal Management System (FAMS) is the digital version of the Sri Vasavi Engineering College
(Autonomous) *Faculty Self-Appraisal & Assessment Report*. Faculty fill in the form section by section and submit it to
their Head of the Department (HoD), who approves it and forwards it to the Principal or the Director Technical for the final decision. Every step is
audited, and an approved appraisal produces an official PDF that mirrors the six-page paper form.

It runs **inside the college network only**; nothing in it needs the internet once it is running.

## Start here

| I am... | Read |
|---|---|
| **Faculty, HoD, Principal or administrator** using the system | [User guide](user-guide.md) |
| **Installing or operating** it on the college network | [Deployment](deployment.md) (with Docker: [Docker deployment](docker-deployment.md)), then [Security](security.md) |
| **A developer** setting it up or changing it | [Developer guide](developer-guide.md), then [Architecture](architecture.md) |
| **Reviewing the design** or the rules behind it | [Requirements](requirements.md), [Workflow](workflow.md), [Scoring](scoring.md), [Decisions](decisions.md) |
| **Looking for a table or column** | [Database reference](database.md) |

## All documents

| Document | What it covers |
|---|---|
| [user-guide.md](user-guide.md) | Step-by-step use of the system for each role, and what the screens mean |
| [developer-guide.md](developer-guide.md) | Setting up, everyday commands, recipes for common changes, troubleshooting |
| [architecture.md](architecture.md) | Packages, the path of a request, where each kind of rule lives, what a change must pass |
| [database.md](database.md) | Every table, what it holds, the constraints and triggers, how the schema evolved |
| [workflow.md](workflow.md) | Appraisal states, who may do what, who may see what |
| [scoring.md](scoring.md) | Maximum marks per cadre, scoring components B1 to B5, policy versioning, automatic marks |
| [requirements.md](requirements.md) | What the official form says, assumptions made, questions still open for the college |
| [security.md](security.md) | Controls that are implemented and tested, and known gaps |
| [deployment.md](deployment.md) | Shape of an installation, every setting, backups and recovery |
| [docker-deployment.md](docker-deployment.md) | The Docker Compose installation, step by step: settings, storage, TLS, building, offline transfer, first administrator, backups |
| [decisions.md](decisions.md) | Architecture decisions and why |
| [codex-ui-polish-prompt.md](codex-ui-polish-prompt.md) | A prompt for a separate UI-polish pass (frontend only); not documentation of the system |

The top-level [README](../README.md) has the quick start and the full API table.

## The system in one page

**Roles.** `FACULTY`, `HOD`, `PRINCIPAL`, `DIRECTOR` (the Director Technical, at the Principal's level) and `ADMIN`; one role per account. The administrator manages configuration and
accounts and never reads appraisal content. (Dean and Vice Principal roles existed briefly and were withdrawn; see
[workflow.md](workflow.md#the-earlier-longer-chain).)

**The appraisal.** One per faculty member per academic year. Part A (general information), Part B items 1 to 10
(twenty sections, grouped into eleven form pages), the automatically scored score sheet over nine criteria
and the declaration.

**The chain.** `DRAFT -> SUBMITTED -> HOD_REVIEW -> HOD_APPROVED -> PRINCIPAL_REVIEW -> APPROVED`. Nothing moves
backwards: a submitted appraisal is never returned for correction.

**The stack.**

```
browser -> reverse proxy (TLS) -> Next.js 16 / React 19 (frontend/)
                             \--> Spring Boot 3.5 / Java 25 (backend/) -> MySQL 8
                                                                     \-> document storage directory
```

**What is deliberately not built:** completeness checks before submit, self-service password reset, e-mail delivery,
single sign-on, malware scanning of uploads, and any formula that turns activities into marks (the form does not
define one; marks are self-entered and validated against the cadre's maximum).
