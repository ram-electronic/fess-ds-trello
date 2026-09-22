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
- Fess 15.8.0 (pinned via `pom.xml`'s `<parent>` version; bump this if you
  rebuild against a newer Fess). This plugin's own version (`pom.xml`'s
  top-level `<version>`, and its release tags) is independent semver, not
  tied to Fess's version — unlike the official `codelibs/fess-ds-*` plugins,
  it isn't published to `maven.codelibs.org`, so nothing resolves it by
  matching Fess's version the way `FESS_PLUGINS` does for those.
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
| `include_comments` | no | `true` to both (a) fetch each card's comments into a `comments` source field, each followed by a direct link to that comment (`<card-url>#comment-<id>`, the same format Trello's own "copy link to comment" feature produces), and (b) index each comment as its own separate document (`desc` = card description + that one comment, `url` = a direct link to that comment) — see [Comment documents](#comment-documents) below (default `false`). Embedded in the same per-board `/cards` call via Trello's [nested resources](https://developer.atlassian.com/cloud/trello/guides/rest-api/nested-resources/) — no extra API call per card. |
| `include_closed_cards` | no | `true` to also crawl archived/closed cards (default `false`). |
| `include_attachments` | no | `true` to also index each card's uploaded file attachments (txt, md, pdf, doc, docx) as separate documents, using Fess's built-in text extractor (default `false`; still costs one download per qualifying attachment, but attachment metadata itself is embedded in the same per-board `/cards` call, not a separate one per card). Attachments over 20MB, non-uploads (e.g. linked URLs), and unrecognized extensions are skipped. |
| `skip_unmodified` | no | `true` to skip a card entirely (no script evaluation, no attachment work, no index write) when its Trello `dateLastActivity` exactly matches what's already indexed for it from a previous crawl (default `false`). Looked up once per crawl via a single query against Fess's own index — no extra Trello API calls. Lets the Scheduler job run frequently (e.g. every 15 minutes) without redoing work for untouched cards. Assumes `last_modified=last_modified` (a straight passthrough, as in the example script below) — a script that transforms it just stops being skippable, safely, rather than skipping incorrectly. |
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

Source fields available: `id`, `name`, `desc`, `url`, `card_url`, `board_id`,
`list`, `due`, `last_modified`, `labels`, and `comments` (only present when
`include_comments=true`).

Attachment documents (when `include_attachments=true`) reuse the same field
names as a card (`id`, `name`, `desc`, `url`, `card_url`, `board_id`,
`last_modified`, `comments`) so the same script config indexes both without
changes — `desc` holds the extracted attachment text instead of the card
description, and `list`/`due`/`labels` aren't set (map to nothing/empty).
`url` is the attachment's own direct download link; `card_url` is the link
to the Trello card it's attached to (for a card document, `card_url` is
just the card's own `url`). Map both to separate index fields if you want
search results to offer opening the attachment directly *or* jumping to its
card, e.g. add a custom field:

```
url=url
card_url=card_url
```

### Comment documents

`include_comments=true` indexes each comment twice, deliberately, for two
different goals:

- **The card's own document** folds every comment into its `comments`
  field (used above in `content`/`digest`) so a topic discussed across
  several comments — plus the card's own description — accumulates enough
  combined term frequency to make the card itself rank well as a whole.
- **A separate document per comment** (same field names as a card again:
  `id`, `name`, `desc`, `url`, `board_id`, `last_modified`, `labels`,
  `comments`) lets one specific comment be found and linked to directly —
  something the card's own document can't do, since its `url` is always the
  plain card link with no comment anchor. `name` is the card's own title
  (not a synthesized "— comment" label); `desc` is the card's description
  plus that one comment's text, so a short, low-context comment (e.g.
  "sounds good") still carries the card's own topic keywords instead of
  matching on almost nothing; `url` is `<card-url>#comment-<id>`, so
  clicking this specific search result jumps straight to that comment.

## Search result template

Fess renders every search result with one generic template
(`WEB-INF/view/searchResults.jsp`), so a Trello card, comment, or attachment
looks like a plain web page by default. `design/` ships an optional
Trello-aware template:

- [`design/searchResults.jsp`](design/searchResults.jsp) — Fess's own
  **15.8.0** stock file, with its per-hit `<li>` block wrapped in a
  `<c:choose>`: `<c:otherwise>` keeps that block's original markup
  verbatim, and a new `<c:when test="${doc.site == 'trello.com' &&
  !empty doc.trello_type}">` splices in the Trello branch via
  `<%@ include file="/WEB-INF/view/trelloResult.jspf" %>`. Everything
  outside that one `<li>` block is untouched. Diff this against your own
  `WEB-INF/view/searchResults.jsp` before using it if you're on a
  different Fess version.
- [`design/trelloResult.jspf`](design/trelloResult.jspf) — the actual
  Trello markup (icon, a Card/Comment/Attachment badge, list, label chips,
  due date, and a link back to an attachment's parent card). Isolated here
  so upgrading Fess never touches this file.

It expects these `handler_script` fields, in addition to the ones in
[Script (field mapping)](#script-field-mapping) above:

```groovy
trello_type=list?.toString()?.trim() ? "card" : (url.contains("#comment-") ? "comment" : "attachment")
trello_list=list
trello_due=due
trello_labels=labels
trello_card_url=card_url
```

`card_url` requires `v1.4.0` or later (added in #28) — on an older release,
**drop `trello_card_url=card_url`**, or Groovy throws
`MissingPropertyException` on a field that doesn't exist yet (same gotcha
as `comments` above). Every `trello_*` field is otherwise optional: absent
means the generic Fess branch renders instead.

**Applying it:** this repo only ships the two files above, not an installer.
Both are attached to [Releases](../../releases) alongside the jar (and
attestation-signed the same way — see [Install](#install) above), so pin a
tag instead of a raw commit if you want one. `COPY` both into
`/usr/share/fess/app/WEB-INF/view/` in your Fess image build, alongside the
plugin jar. Fess's own Page Design admin screen (`/admin/design`) can edit
`searchResults.jsp` at runtime without a rebuild, but only for filenames
Fess already ships — it can't add a new file like `trelloResult.jspf`, so
that one always needs an image rebuild.

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
  tag (`vX.Y.Z`, this plugin's own version — see [Requirements](#requirements)
  for why it's independent of the Fess version below) gets a
  `fess-ds-trello-<version>.jar` asset attached,
  built by this repo's own `.github/workflows/release.yml`. No local Java/
  Maven toolchain needed, just `curl`/`wget` the asset. This is the easiest
  path if you don't need to modify the plugin itself. Each jar is also
  signed with a [GitHub Artifact
  Attestation](https://docs.github.com/en/actions/security-guides/using-artifact-attestations-to-establish-provenance-for-builds)
  (keyless, via Sigstore/OIDC — proves it was built by this repo's own
  workflow, from a specific commit) — verify with the [`gh`
  CLI](https://cli.github.com/):
  ```
  gh attestation verify fess-ds-trello-1.4.0.jar -R ram-electronic/fess-ds-trello
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
