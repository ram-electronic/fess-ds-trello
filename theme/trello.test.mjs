// node --test theme/ (the DOM builders need a browser; these cover the field
// handling they rely on).
import assert from "node:assert/strict";
import test from "node:test";
import { isTrelloDoc, str, trelloCardUrl, trelloDue, trelloLabels } from "./trello.js";

test("str joins arrays and trims", () => {
  assert.equal(str(null), "");
  assert.equal(str(" card "), "card");
  assert.equal(str(["a", "", " b "]), "a, b");
});

test("isTrelloDoc needs the site and a trello_type", () => {
  assert.equal(isTrelloDoc({ site: "trello.com", trello_type: "card" }), true);
  assert.equal(isTrelloDoc({ site: "trello.com" }), false);
  assert.equal(isTrelloDoc({ site: "trello.com", trello_type: " " }), false);
  assert.equal(isTrelloDoc({ site: "wiki.rz.ram24.net", trello_type: "card" }), false);
});

test("trelloLabels splits fess-ds-trello's joined string", () => {
  assert.deepEqual(trelloLabels("Bug, Kunde A,, "), ["Bug", "Kunde A"]);
  assert.deepEqual(trelloLabels(["Bug", "Kunde A"]), ["Bug", "Kunde A"]);
  assert.deepEqual(trelloLabels(null), []);
});

test("trelloDue formats a date and drops junk", () => {
  assert.equal(trelloDue("2026-10-20T12:00:00.000"), "2026-10-20");
  assert.equal(trelloDue(""), "");
  assert.equal(trelloDue("not a date"), "");
});

test("trelloCardUrl only links an attachment's parent card on trello.com", () => {
  const card = "https://trello.com/c/aBcD1234/1-card";
  const att = { trello_type: "attachment", trello_card_url: card, url_link: "https://trello.com/1/cards/x/attachments/y" };
  assert.equal(trelloCardUrl(att), card);
  assert.equal(trelloCardUrl({ ...att, trello_type: "card" }), "");
  assert.equal(trelloCardUrl({ ...att, url_link: card }), "");
  assert.equal(trelloCardUrl({ ...att, trello_card_url: "javascript:alert(1)" }), "");
});
