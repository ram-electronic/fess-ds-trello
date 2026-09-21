# fess-ds-trello

A [Fess](https://github.com/codelibs/fess) Data Store plugin that crawls
Trello boards. Written because no such plugin exists anywhere — a search of
the codelibs `fess-ds-*` org and the wider GitHub code/repo index turned up
nothing for Trello, official or community.

This was scaffolded from
[`fess-ds-example`](https://github.com/codelibs/fess-ds-example) (the
official copy-from template) and follows the HTTP-client shape of
[`fess-ds-slack`](https://github.com/codelibs/fess-ds-slack), the closest
existing analog (token-authenticated REST API, JSON responses, pagination).
Confirmed to build cleanly and register as a selectable Data Store type
(`TrelloDataStore`) in Fess Admin, against Fess 15.8.0 / OpenSearch 3.6.0 —
see [Status / TODO](#status--todo) below for what's still outstanding (mainly:
unit-tested but not yet run against a real Trello board end-to-end, so treat
it as tested-by-hand rather than production-hardened).

## What it crawls

For each configured board, every card: name, description, URL, due date,
last-activity timestamp, label names, its containing list's name, and
(optionally) its comments.

## Requirements

- Java 21+, Maven 3.x
- Fess 15.8.0 (pinned via `pom.xml`'s parent version; bump this alongside
  whatever Fess version you're installing the built jar into)
- A Trello API key + token (see below)

## Configuration

Set these in the data store config's **Parameter** field
(`Admin > Crawler > Data Store > Create`, Handler Name
`org.codelibs.fess.ds.trello.TrelloDataStore`):

| Parameter | Required | Description |
|---|---|---|
| `key` | yes | Trello API key. Create a Power-Up at the [Trello Power-Up admin](https://trello.com/power-ups/admin) to get one — Trello requires this even for personal/read-only use. |
| `token` | yes | API token authorized for that key (generate via the manual token flow linked from the Power-Up's API key page — **not** the "Secret" shown on that same page, which is for OAuth 1.0a; this plugin uses Trello's simpler key+token auth, a different mechanism entirely). |
| `board_id` | yes | Comma-separated Trello board ids or shortLinks (the code at the end of a board URL, e.g. `https://trello.com/b/aBcD1234/my-board` → `aBcD1234`). |
| `include_comments` | no | `true` to fetch each card's comments into a `comments` source field, each followed by a direct link to that comment (`<card-url>#comment-<id>`, the same format Trello's own "copy link to comment" feature produces) (default `false`; costs one extra API call per card). |
| `include_closed_cards` | no | `true` to also crawl archived/closed cards (default `false`). |
| `readInterval` | no | Milliseconds to sleep between cards (default `0`). |

### Script (field mapping)

The plugin doesn't hard-code index fields — map source fields to them in the
**Script** field, same as every other Fess data store:

```
title=name
content=desc + (comments != null && comments != "" ? "\n\n" + comments : "")
digest=desc + (comments != null && comments != "" ? "\n\n" + comments : "")
url=url
last_modified=last_modified
```

**Each line is evaluated independently against the raw source record — never
against another line's output**, so `digest=content` silently resolves to
nothing. Repeat the whole expression instead (as above) if you want `digest`
to match `content`.

Source fields available: `id`, `name`, `desc`, `url`, `board_id`, `list`,
`due`, `last_modified`, `labels`, and `comments` (only present when
`include_comments=true`).

## Pagination note

Trello caps a single `/boards/{id}/cards` response at 1000 results.
`TrelloClient.getCards` pages past that automatically using the `before`
parameter, keyed on the last card id seen — see the class Javadoc for why
that works (Trello ids are roughly chronologically ordered).

## Build

```
mvn clean package
```

## Install

This plugin is **not published to `maven.codelibs.org`**, so Fess's usual
`fess_plugins`/`FESS_PLUGINS` auto-fetch mechanism won't pick it up: the
official image's `run.sh` entrypoint only downloads plugins named
`fess-ds-*`/`fess-ingest-*`/etc. from
`https://maven.codelibs.org/release/org/codelibs/fess/<name>/<version>/`,
verifying a `.sha1` checksum it fetches alongside the jar — there's no
equivalent for a jar hosted elsewhere. Get the built JAR into the Fess
container yourself instead, either:

- **Download a built jar from [Releases](../../releases)** — every version
  tag (`vX.Y.Z`) gets a `fess-ds-trello-<fess-version>.jar` asset attached,
  built by this repo's own `.github/workflows/release.yml`. No local Java/
  Maven toolchain needed, just `curl`/`wget` the asset. This is the easiest
  path if you don't need to modify the plugin itself. Each jar is also
  signed with a [GitHub Artifact
  Attestation](https://docs.github.com/en/actions/security-guides/using-artifact-attestations-to-establish-provenance-for-builds)
  (keyless, via Sigstore/OIDC — proves it was built by this repo's own
  workflow, from a specific commit) — verify with the [`gh`
  CLI](https://cli.github.com/):
  ```
  gh attestation verify fess-ds-trello-15.8.0.jar -R ram-electronic/fess-ds-trello
  ```
  or
- **Build it yourself** with `mvn clean package` (see [Build](#build)
  above).

Either way, `COPY` the resulting jar to
`/usr/share/fess/app/WEB-INF/plugin/` in a custom image layered on
`ghcr.io/codelibs/fess:15.8.0` — that's the exact directory `run.sh` itself
installs auto-fetched plugins into (confirmed from
[`codelibs/docker-fess`](https://github.com/codelibs/docker-fess)'s
`fess/15.8/run.sh`). Alternatively, push it to a private Maven repo and
replicate `run.sh`'s download+`.sha1` logic yourself.

Then restart the `fess01` container and create the data store config as
described above (or via Fess's own admin REST API).

## Status / TODO

- Built and confirmed to register correctly in Fess Admin (Fess 15.8.0 /
  OpenSearch 3.6.0), but not yet run against a real Trello board / verified
  end-to-end (indexing actual cards, permission mapping, etc.) — checked by
  hand so far, not automated.
- Unit tests cover the pure logic (board id parsing, card-to-source-record
  field mapping) — see `TrelloDataStoreTest`/`TrelloClientTest`. `storeData`
  itself (the actual crawl loop) isn't covered, since it needs a live
  Fess/Lasta Di container to test against.
- `TrelloClient` is a minimal hand-rolled client; `fess-ds-slack` shows a
  fuller typed request/response API layer if this grows more endpoints.
- Permission/ACL mapping (per-board visibility restriction via the data
  store config's own Permission field, the way other Fess data stores
  restrict who sees their results) isn't wired up yet.
