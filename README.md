# fess-ds-trello

A [Fess](https://github.com/codelibs/fess) Data Store plugin that crawls
Trello boards. Written because no such plugin exists anywhere — this repo's
sibling `wiki-fess` deployment researched the codelibs `fess-ds-*` org and the
wider GitHub code/repo index and found nothing for Trello, official or
community (see that repo's chat history for the research trail).

This was scaffolded from
[`fess-ds-example`](https://github.com/codelibs/fess-ds-example) (the
official copy-from template) and follows the HTTP-client shape of
[`fess-ds-slack`](https://github.com/codelibs/fess-ds-slack), the closest
existing analog (token-authenticated REST API, JSON responses, pagination).
**It has not been built or run against a live Fess instance yet** — treat it
as a working draft to compile, test, and adjust, not a finished plugin.

## What it crawls

For each configured board, every card: name, description, URL, due date,
last-activity timestamp, label names, its containing list's name, and
(optionally) its comments.

## Requirements

- Java 21+, Maven 3.x
- Fess 15.8.0 (pinned in `pom.xml` to match the `wiki-fess` deployment's
  `fess_image_tag`; bump both together if that changes)
- A Trello API key + token (see below)

## Configuration

Set these in the data store config's **Parameter** field
(`Admin > Crawler > Data Store > Create`, Handler Name
`org.codelibs.fess.ds.trello.TrelloDataStore`):

| Parameter | Required | Description |
|---|---|---|
| `key` | yes | Trello API key. Create a Power-Up at the [Trello Power-Up admin](https://trello.com/power-ups/admin) to get one — Trello requires this even for personal/read-only use. |
| `token` | yes | API token authorized for that key (generate via the manual token flow linked from the Power-Up's API key page). |
| `board_id` | yes | Comma-separated Trello board ids or shortLinks (the code at the end of a board URL, e.g. `https://trello.com/b/aBcD1234/my-board` → `aBcD1234`). |
| `include_comments` | no | `true` to fetch and join each card's comments into a `comments` source field (default `false`; costs one extra API call per card). |
| `include_closed_cards` | no | `true` to also crawl archived/closed cards (default `false`). |
| `readInterval` | no | Milliseconds to sleep between cards (default `0`). |

### Script (field mapping)

The plugin doesn't hard-code index fields — map source fields to them in the
**Script** field, same as every other Fess data store:

```
title=name
content=desc + (comments != null && comments != "" ? "\n\n" + comments : "")
url=url
last_modified=last_modified
```

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

This plugin is **not published to `maven.codelibs.org`**, so the `wiki-fess`
repo's usual `fess_plugins`/`FESS_PLUGINS` auto-fetch mechanism won't pick it
up: the official image's `run.sh` entrypoint only downloads plugins named
`fess-ds-*`/`fess-ingest-*`/etc. from
`https://maven.codelibs.org/release/org/codelibs/fess/<name>/<version>/`,
verifying a `.sha1` checksum it fetches alongside the jar — there's no
equivalent for a jar hosted elsewhere. Get the built JAR into the Fess
container yourself instead:

- `COPY target/fess-ds-trello-15.8.0.jar` to
  `/usr/share/fess/app/WEB-INF/plugin/` in a custom image layered on
  `ghcr.io/codelibs/fess:15.8.0` (that's the exact directory `run.sh` itself
  installs auto-fetched plugins into — confirmed from
  [`codelibs/docker-fess`](https://github.com/codelibs/docker-fess)'s
  `fess/15.8/run.sh`), or
- Push it to a private Maven repo and replicate `run.sh`'s download+`.sha1`
  logic yourself.

Then restart the `fess01` container and create the data store config as
described above (or via the admin REST API, following the pattern documented
in `wiki-fess`'s `docs/fess-datastore-config.md`).

## Status / TODO

- Not yet built or tested against a live Fess instance.
- No automated tests yet (see `fess-ds-example`'s `ExampleDataStoreTest` for
  the expected shape).
- `TrelloClient` is a minimal hand-rolled client; `fess-ds-slack` shows a
  fuller typed request/response API layer if this grows more endpoints.
- Permission/ACL mapping (per-board visibility restriction, the way
  `wiki-fess` restricts its Wikipedia data store) isn't wired up yet.
