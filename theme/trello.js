// Trello markup for one search hit, called from search.js's buildResultCard()
// (see bootstrap.patch). Reads the trello_* fields described in README.md's
// "Search theme"; a hit without trello_type renders as stock.
//
// Every value goes in through textContent or a checked href, never innerHTML
// (the bootstrap theme's own XSS rule).

/** A field as a trimmed string; arrays (multi-valued fields) are joined. */
export function str(v) {
  if (Array.isArray(v)) return v.map(str).filter(Boolean).join(", ");
  return v == null ? "" : String(v).trim();
}

export function isTrelloDoc(d) {
  return d.site === "trello.com" && str(d.trello_type) !== "";
}

/** Label names: fess-ds-trello joins them with ", " into one string. */
export function trelloLabels(v) {
  return str(v).split(",").map(s => s.trim()).filter(Boolean);
}

/** The due date as yyyy-MM-dd in the viewer's time zone, or "". */
export function trelloDue(v) {
  const s = str(v);
  if (!s) return "";
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return "";
  const pad = x => String(x).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/**
 * The parent card's URL for an attachment, or "": a card's or comment's own
 * URL already opens the card. Only https://trello.com/ URLs are linked.
 */
export function trelloCardUrl(d) {
  const url = str(d.trello_card_url);
  if (str(d.trello_type) !== "attachment" || !url || url === str(d.url_link)) return "";
  return url.startsWith("https://trello.com/") ? url : "";
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text) node.textContent = text;
  return node;
}

function badge(variant, text) {
  return el("span", "badge rounded-pill " + variant, text);
}

function icon(className) {
  const i = el("i", className);
  i.setAttribute("aria-hidden", "true");
  return i;
}

/** The icon put in front of the title link. */
export function trelloIcon() {
  const i = icon("fab fa-trello text-primary");
  i.setAttribute("title", "Trello");
  return i;
}

/** The rows appended after the result's body: badges, and the card link. */
export function trelloRows(d) {
  const meta = el("div", "trello-meta text-truncate mb-1");
  meta.appendChild(badge("text-bg-info text-uppercase", str(d.trello_type)));
  const list = str(d.trello_list);
  if (list) meta.appendChild(badge("text-bg-secondary", list));
  for (const label of trelloLabels(d.trello_labels)) {
    meta.appendChild(badge("text-bg-light border", label));
  }
  const due = trelloDue(d.trello_due);
  if (due) {
    const b = badge("text-bg-warning");
    b.append(icon("far fa-clock"), " " + due);
    meta.appendChild(b);
  }
  const rows = [meta];

  const cardUrl = trelloCardUrl(d);
  if (cardUrl) {
    const row = el("div", "trello-card-link mb-1");
    const a = el("a", "small");
    a.setAttribute("href", cardUrl);
    // English only, like the JSP template was: a translation would need keys
    // in every i18n/messages.*.json.
    a.append(icon("fas fa-external-link-alt"), " Open Trello card");
    row.appendChild(a);
    rows.push(row);
  }
  return rows;
}
