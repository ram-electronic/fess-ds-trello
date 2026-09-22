/*
 * Copyright 2012-2025 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.ds.trello;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.Constants;
import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.crawler.exception.CrawlingAccessException;
import org.codelibs.fess.crawler.exception.MultipleCrawlingAccessException;
import org.codelibs.fess.crawler.extractor.ExtractorFactory;
import org.codelibs.fess.ds.AbstractDataStore;
import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.exception.DataStoreCrawlingException;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsAction;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsKeyObject;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.util.ComponentUtil;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;

/**
 * Data store crawler for Trello boards.
 *
 * <p>
 * Crawls every card on one or more Trello boards and feeds each card through
 * the standard Fess data store pipeline (see {@code fess-ds-example} for the
 * general pattern this follows). {@code desc} and each comment are rendered
 * from Trello's Markdown source down to plain text ({@link MarkdownPlainTextRenderer})
 * before being made available to the script map below — Trello's API has no
 * pre-rendered plain-text alternative, so left as Markdown, raw syntax like
 * {@code **bold**} would otherwise show up literally in a Fess search snippet.
 * </p>
 *
 * <p>
 * Configuration parameters:
 * </p>
 * <ul>
 * <li>{@code key} (required) - Trello API key, from a Power-Up in the
 * <a href="https://trello.com/power-ups/admin">Trello Power-Up admin</a>.</li>
 * <li>{@code token} (required) - Trello API token authorized for that key.</li>
 * <li>{@code board_id} (required) - Comma-separated list of Trello board ids
 * or shortLinks to crawl.</li>
 * <li>{@code include_comments} (optional) - {@code true} to both (a) append each
 * card's comments to the source record's {@code comments} field, each
 * followed by a direct link to that comment ({@code <card-url>#comment-<id>},
 * the same format Trello's own "copy link to comment" feature produces), so
 * the card's own document stays a single consolidated body a topic spread
 * across several comments can rank well on as a whole, and (b) index each
 * comment as its own separate document (see {@link #storeComments}) — its
 * {@code desc} the card's description plus that one comment, its {@code url}
 * a direct link to that specific comment — so a single comment can also be
 * found and linked to on its own (default {@code false}; costs one extra
 * Trello API call per card — see {@link TrelloClient#getComments}. Not
 * embedded via Trello's nested resources the way {@code include_attachments}
 * is below: Trello enforces an undocumented cap on how many cards can be
 * requested with embedded comment actions in one call, which aborted the
 * entire crawl with a 403 {@code API_TOO_MANY_CARDS_REQUESTED} once a board
 * had enough cards — confirmed in production).</li>
 * <li>{@code include_closed_cards} (optional) - {@code true} to also crawl
 * archived cards (default {@code false}).</li>
 * <li>{@code include_attachments} (optional) - {@code true} to also index each
 * card's file attachments (uploaded files only — a link-only attachment has
 * nothing to fetch) as their own separate documents, one per attachment, using
 * the same field names as a card's own source record ({@code id}, {@code name},
 * {@code desc} — the extracted file text — {@code url}, {@code board_id},
 * {@code last_modified}), so an existing script map keeps working unchanged.
 * Each attachment's {@code url} is its own direct download link; its
 * {@code card_url} is the parent card's link, so a script map can surface
 * both — one to open the attachment directly, another to open the card it's
 * attached to. A card's own source record also has {@code card_url} (equal
 * to its {@code url}), so a script map can reference {@code card_url}
 * unconditionally on every document regardless of type.
 * Only attachments with a recognized text-shaped extension ({@code .txt},
 * {@code .md}, {@code .pdf}, {@code .doc(x)}) up to
 * {@value #MAX_ATTACHMENT_BYTES} bytes are extracted; everything else
 * (images, video, oversized files, ...) is skipped. Text is pulled out via
 * Fess's own {@link ExtractorFactory} (the same Tika-backed extraction the
 * web crawler and other data stores use). Attachment metadata is embedded
 * via Trello's nested resources in the same per-board card-listing call
 * (unlike {@code include_comments} above — no confirmed report of the same
 * per-request cap applying to attachments), so only the file download itself
 * costs an extra request, one per qualifying attachment (default
 * {@code false}).</li>
 * <li>{@code skip_unmodified} (optional) - {@code true} to skip a card entirely
 * (no script evaluation, no attachment work, no index write) when its Trello
 * {@code dateLastActivity} exactly matches the {@code last_modified} value
 * already indexed for it from a previous crawl. Lets the Scheduler job run
 * frequently (every few minutes) without redoing work for cards nobody
 * touched since the last run. Looked up once per crawl via a single query
 * against Fess's own index for this data config's existing documents (see
 * {@link #loadIndexedLastModified}) - no extra Trello API calls. Assumes the
 * configured {@code handler_script} maps {@code last_modified} straight
 * through from the source record's own {@code last_modified} field with no
 * transformation; if it doesn't, cards simply stop being skippable (safe -
 * every card gets reprocessed as if this were {@code false}), never the
 * other way around. Only the first {@value #MAX_INDEXED_DOCS_TO_CHECK}
 * already-indexed documents for this data config are considered; a board
 * with more previously-indexed documents than that falls back to
 * reprocessing every card beyond that count on every run (default
 * {@code false}).</li>
 * <li>{@code readInterval} - Interval in milliseconds to wait between cards
 * (default: 0).</li>
 * </ul>
 *
 * <p>
 * Example script map (Admin &gt; Crawler &gt; Data Store &gt; Script):
 * </p>
 * <pre>
 * title=name
 * content=desc + "\n\n" + comments
 * url=url
 * last_modified=last_modified
 * </pre>
 */
public class TrelloDataStore extends AbstractDataStore {

    private static final Logger logger = LogManager.getLogger(TrelloDataStore.class);

    protected static final String KEY_PARAM = "key";

    protected static final String TOKEN_PARAM = "token";

    protected static final String BOARD_ID_PARAM = "board_id";

    protected static final String INCLUDE_COMMENTS_PARAM = "include_comments";

    protected static final String INCLUDE_CLOSED_CARDS_PARAM = "include_closed_cards";

    protected static final String INCLUDE_ATTACHMENTS_PARAM = "include_attachments";

    protected static final String SKIP_UNMODIFIED_PARAM = "skip_unmodified";

    /** Caps how many already-indexed documents {@link #loadIndexedLastModified} considers per
     *  crawl (a single, non-scrolled query) - matches OpenSearch's own default
     *  {@code index.max_result_window}, past which a plain query silently can't page further
     *  anyway without switching to scroll/search-after. */
    private static final int MAX_INDEXED_DOCS_TO_CHECK = 10_000;

    /** Attachment file extensions (lowercase, no dot) worth extracting text
     *  from, each mapped to the MIME type its document is indexed with —
     *  deliberately conservative: anything else (images, video, archives, ...)
     *  is skipped rather than handed to Tika speculatively. The MIME type comes
     *  from the extension rather than Trello's own {@code mimeType}, which can be
     *  blank or a generic {@code application/octet-stream} for an upload;
     *  it's what Fess's {@code index.filetype} maps to the {@code filetype}
     *  field (so e.g. {@code filetype:pdf} matches a PDF attachment). */
    private static final Map<String, String> EXTRACTABLE_ATTACHMENT_MIMETYPES = Map.of( //
            "txt", "text/plain", //
            "md", "text/markdown", //
            "pdf", "application/pdf", //
            "doc", "application/msword", //
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    /** Skips extracting an attachment larger than this rather than
     *  downloading+parsing an arbitrarily large file (Trello attachments
     *  aren't limited to small documents — an uploaded video or disk image
     *  would otherwise be fetched in full just to be handed to Tika). 20 MB,
     *  generous for the {@link #EXTRACTABLE_ATTACHMENT_MIMETYPES} this
     *  applies to. */
    private static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;

    public TrelloDataStore() {
        super();
    }

    @Override
    protected String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    protected void storeData(final DataConfig dataConfig, final IndexUpdateCallback callback, final DataStoreParams paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap) {
        final CrawlerStatsHelper crawlerStatsHelper = ComponentUtil.getCrawlerStatsHelper();

        final long readInterval = getReadInterval(paramMap);
        final String scriptType = getScriptType(paramMap);
        final boolean includeComments = paramMap.getAsString(INCLUDE_COMMENTS_PARAM, "false").equalsIgnoreCase("true");
        final boolean includeClosedCards = paramMap.getAsString(INCLUDE_CLOSED_CARDS_PARAM, "false").equalsIgnoreCase("true");
        final boolean includeAttachments = paramMap.getAsString(INCLUDE_ATTACHMENTS_PARAM, "false").equalsIgnoreCase("true");
        final boolean skipUnmodified = paramMap.getAsString(SKIP_UNMODIFIED_PARAM, "false").equalsIgnoreCase("true");

        final List<String> boardIds = getBoardIds(paramMap);
        final Map<String, String> indexedLastModified = skipUnmodified ? loadIndexedLastModified(dataConfig) : Map.of();

        try (final TrelloClient client = createClient(paramMap)) {
            boolean running = true;
            for (final String boardId : boardIds) {
                if (!running) {
                    break;
                }
                final Map<String, String> listNames = client.getLists(boardId);
                final boolean[] runningRef = { running };
                client.getCards(boardId, includeAttachments, card -> {
                    if (!runningRef[0]) {
                        return;
                    }
                    if (!includeClosedCards && Boolean.TRUE.equals(card.get("closed"))) {
                        return;
                    }
                    if (skipUnmodified && isAlreadyIndexedUnmodified(card, indexedLastModified)) {
                        return;
                    }

                    final String cardId = (String) card.get("id");
                    final StatsKeyObject statsKey = new StatsKeyObject(dataConfig.getId() + "#" + cardId);
                    paramMap.put(Constants.CRAWLER_STATS_KEY, statsKey);
                    final Map<String, Object> dataMap = new HashMap<>(defaultDataMap);
                    try {
                        crawlerStatsHelper.begin(statsKey);

                        final List<TrelloClient.Comment> comments = includeComments ? client.getComments(cardId) : List.of();
                        final Map<String, Object> source = createSourceRecord(boardId, listNames, card, includeComments, comments);

                        final Map<String, Object> resultMap = new LinkedHashMap<>(paramMap.asMap());
                        resultMap.putAll(source);

                        crawlerStatsHelper.record(statsKey, StatsAction.PREPARED);

                        for (final Map.Entry<String, String> entry : scriptMap.entrySet()) {
                            final Object convertValue = convertValue(scriptType, entry.getValue(), resultMap);
                            if (convertValue != null) {
                                dataMap.put(entry.getKey(), convertValue);
                            }
                        }

                        crawlerStatsHelper.record(statsKey, StatsAction.EVALUATED);

                        if (dataMap.get("url") instanceof final String statsUrl) {
                            statsKey.setUrl(statsUrl);
                        }

                        callback.store(paramMap, dataMap);
                        crawlerStatsHelper.record(statsKey, StatsAction.FINISHED);

                        if (includeAttachments) {
                            storeAttachments(dataConfig, callback, paramMap, scriptMap, defaultDataMap, scriptType, crawlerStatsHelper,
                                    client, boardId, cardId, (String) source.get("url"), TrelloClient.parseAttachments(card));
                        }

                        if (includeComments) {
                            storeComments(dataConfig, callback, paramMap, scriptMap, defaultDataMap, scriptType, crawlerStatsHelper,
                                    boardId, cardId, card, comments);
                        }
                    } catch (final CrawlingAccessException e) {
                        logger.warn("Crawling Access Exception at : {}", dataMap, e);

                        Throwable target = e;
                        if (target instanceof final MultipleCrawlingAccessException ex) {
                            final Throwable[] causes = ex.getCauses();
                            if (causes.length > 0) {
                                target = causes[causes.length - 1];
                            }
                        }

                        String errorName;
                        final Throwable cause = target.getCause();
                        if (cause != null) {
                            errorName = cause.getClass().getCanonicalName();
                        } else {
                            errorName = target.getClass().getCanonicalName();
                        }

                        String url;
                        if (target instanceof final DataStoreCrawlingException dce) {
                            url = dce.getUrl();
                            if (dce.aborted()) {
                                runningRef[0] = false;
                            }
                        } else {
                            url = "trello:card:" + cardId;
                        }
                        final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
                        failureUrlService.store(dataConfig, errorName, url, target);
                        crawlerStatsHelper.record(statsKey, StatsAction.ACCESS_EXCEPTION);
                    } catch (final Throwable t) {
                        logger.warn("Crawling Access Exception at : {}", dataMap, t);
                        final String url = "trello:card:" + cardId;
                        final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
                        failureUrlService.store(dataConfig, t.getClass().getCanonicalName(), url, t);
                        crawlerStatsHelper.record(statsKey, StatsAction.EXCEPTION);
                    } finally {
                        crawlerStatsHelper.done(statsKey);
                    }

                    if (readInterval > 0 && runningRef[0]) {
                        sleep(readInterval);
                    }
                });
                running = runningRef[0];
            }
        }
    }

    /**
     * @param paramMap The data store parameters.
     * @return The configured board ids, in order, with blanks removed.
     */
    protected List<String> getBoardIds(final DataStoreParams paramMap) {
        final String value = paramMap.getAsString(BOARD_ID_PARAM);
        if (StringUtil.isBlank(value)) {
            throw new TrelloDataStoreException("The \"" + BOARD_ID_PARAM + "\" parameter is required.");
        }
        final List<String> boardIds = new ArrayList<>();
        for (final String id : value.split(",")) {
            final String trimmed = id.trim();
            if (StringUtil.isNotBlank(trimmed)) {
                boardIds.add(trimmed);
            }
        }
        return boardIds;
    }

    /**
     * @param paramMap The data store parameters.
     * @return A Trello API client built from the configured {@code key}/{@code token}.
     */
    protected TrelloClient createClient(final DataStoreParams paramMap) {
        return new TrelloClient(paramMap.getAsString(KEY_PARAM), paramMap.getAsString(TOKEN_PARAM));
    }

    /**
     * @param card The raw card fields, as returned by the Trello API.
     * @param indexedLastModified Already-indexed {@code url -> last_modified} pairs for this
     * data config, as loaded by {@link #loadIndexedLastModified}.
     * @return {@code true} if this card's {@code shortUrl}/{@code url} is already indexed with
     * a {@code last_modified} exactly matching its current {@code dateLastActivity} - i.e.
     * nothing about the card has changed since it was last successfully indexed.
     */
    protected boolean isAlreadyIndexedUnmodified(final Map<String, Object> card, final Map<String, String> indexedLastModified) {
        final String cardUrl = getCardUrl(card);
        final Object dateLastActivity = card.get("dateLastActivity");
        return cardUrl != null && dateLastActivity != null && dateLastActivity.equals(indexedLastModified.get(cardUrl));
    }

    /**
     * Queries Fess's own already-indexed documents for this data config, to find each one's
     * currently-indexed {@code last_modified} value - used by {@code skip_unmodified} to avoid
     * reprocessing a card whose Trello {@code dateLastActivity} hasn't moved since the last
     * crawl. One query per crawl run, not per card or per board; no Trello API cost.
     *
     * @param dataConfig The data store config being crawled.
     * @return Already-indexed {@code url -> last_modified} pairs for this data config, up to
     * {@value #MAX_INDEXED_DOCS_TO_CHECK} documents.
     */
    protected Map<String, String> loadIndexedLastModified(final DataConfig dataConfig) {
        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final String urlField = fessConfig.getIndexFieldUrl();
        final String lastModifiedField = fessConfig.getIndexFieldLastModified();
        final QueryBuilder query = QueryBuilders.termQuery(fessConfig.getIndexFieldConfigId(), dataConfig.getId());

        final List<Map<String, Object>> docs =
                ComponentUtil.getSearchEngineClient().getDocumentList(fessConfig.getIndexDocumentUpdateIndex(), builder -> {
                    builder.setQuery(query).setSize(MAX_INDEXED_DOCS_TO_CHECK);
                    builder.setFetchSource(new String[] { urlField, lastModifiedField }, null);
                    return true;
                });

        final Map<String, String> result = new HashMap<>();
        for (final Map<String, Object> doc : docs) {
            if (doc.get(urlField) instanceof final String url && doc.get(lastModifiedField) instanceof final String lastModified) {
                result.put(url, lastModified);
            }
        }
        return result;
    }

    /**
     * Builds the source record for one Trello card, available to the
     * admin-configured scriptMap under the field names used below.
     *
     * @param boardId The id of the board the card belongs to.
     * @param listNames A map of list id to list name for the card's board.
     * @param card The raw card fields, as returned by the Trello API.
     * @param includeComments Whether to join {@code comments} into the source record.
     * @param comments The card's comments (see {@link TrelloClient#getComments}), or empty if
     * {@code includeComments} is {@code false}.
     * @return The source record: {@code id}, {@code name}, {@code desc}, {@code url},
     * {@code card_url} (the card's own URL, same as {@code url} — present so a script map
     * can reference {@code card_url} unconditionally on both card and attachment documents;
     * see {@link #createAttachmentSourceRecord} for the attachment case, where it differs),
     * {@code board_id}, {@code list}, {@code due}, {@code last_modified}, {@code labels},
     * and, when requested, {@code comments}.
     */
    protected Map<String, Object> createSourceRecord(final String boardId, final Map<String, String> listNames,
            final Map<String, Object> card, final boolean includeComments, final List<TrelloClient.Comment> comments) {
        final Map<String, Object> source = new HashMap<>();
        final String cardId = (String) card.get("id");
        final String cardUrl = getCardUrl(card);
        source.put("id", cardId);
        source.put("name", card.get("name"));
        source.put("desc", MarkdownPlainTextRenderer.render((String) card.get("desc")));
        source.put("url", cardUrl);
        source.put("card_url", cardUrl);
        source.put("board_id", boardId);
        source.put("list", listNames.get(card.get("idList")));
        source.put("due", card.get("due"));
        source.put("last_modified", card.get("dateLastActivity"));
        source.put("labels", joinLabelNames(card.get("labels")));

        if (includeComments) {
            source.put("comments", joinComments(comments, cardUrl));
        }
        return source;
    }

    /**
     * @param comments The card's comments, oldest first.
     * @param cardUrl The card's own URL (its {@code shortUrl}, if the card has one).
     * @return Each comment's text (Markdown rendered to plain text) followed by a direct
     * link to that comment ({@code <cardUrl>#comment-<id>} — the same link format Trello's
     * own "copy link to comment" feature in the web app produces), one comment per paragraph.
     */
    private String joinComments(final List<TrelloClient.Comment> comments, final String cardUrl) {
        final List<String> paragraphs = new ArrayList<>();
        for (final TrelloClient.Comment comment : comments) {
            paragraphs.add(MarkdownPlainTextRenderer.render(comment.text()) + "\n(" + cardUrl + "#comment-" + comment.id() + ")");
        }
        return String.join("\n\n", paragraphs);
    }

    /**
     * @param card The raw card fields, as returned by the Trello API.
     * @return The card's {@code shortUrl}, falling back to its full {@code url} if it has no
     * short one.
     */
    private String getCardUrl(final Map<String, Object> card) {
        return card.get("shortUrl") != null ? (String) card.get("shortUrl") : (String) card.get("url");
    }

    @SuppressWarnings("unchecked")
    private String joinLabelNames(final Object labels) {
        if (!(labels instanceof final List<?> labelList)) {
            return StringUtil.EMPTY;
        }
        final List<String> names = new ArrayList<>();
        for (final Object label : labelList) {
            if (label instanceof final Map<?, ?> labelMap && labelMap.get("name") instanceof final String name
                    && StringUtil.isNotBlank(name)) {
                names.add(name);
            }
        }
        return String.join(", ", names);
    }

    /**
     * Indexes each of a card's qualifying attachments as its own separate Fess document —
     * not appended into the card's own {@code content}, so an attachment stays independently
     * findable/rankable with its own title and URL. A failure on one attachment (a bad
     * download, an unparseable file, ...) is logged and recorded as a failure URL, same as a
     * card-level failure, but doesn't stop the rest of the card's attachments (or the crawl)
     * from proceeding.
     *
     * @param dataConfig The data store config being crawled.
     * @param callback Where each attachment document is stored.
     * @param paramMap The data store parameters.
     * @param scriptMap The admin-configured field mapping — reused as-is from the card's own,
     * since attachment source records use the same field names ({@code name}, {@code desc},
     * {@code url}, {@code board_id}, {@code last_modified}) as a card's.
     * @param defaultDataMap Default field values to seed each attachment's document with.
     * @param scriptType The script language {@code scriptMap}'s values are written in.
     * @param crawlerStatsHelper Where per-document crawl stats are recorded.
     * @param client The Trello API client (used only to download an attachment's bytes).
     * @param boardId The id of the board the card belongs to.
     * @param cardId The card whose attachments to index.
     * @param cardUrl The card's own URL, so the attachment's source record can link back to it.
     * @param attachments The card's attachments, as embedded by {@link TrelloClient#getCards}
     * (see {@link TrelloClient#parseAttachments}) — no separate API call to list them.
     */
    private void storeAttachments(final DataConfig dataConfig, final IndexUpdateCallback callback, final DataStoreParams paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap, final String scriptType,
            final CrawlerStatsHelper crawlerStatsHelper, final TrelloClient client, final String boardId, final String cardId,
            final String cardUrl, final List<TrelloClient.Attachment> attachments) {
        for (final TrelloClient.Attachment attachment : attachments) {
            if (!isExtractable(attachment)) {
                continue;
            }

            final StatsKeyObject statsKey = new StatsKeyObject(dataConfig.getId() + "#" + cardId + "#" + attachment.id());
            final Map<String, Object> dataMap = new HashMap<>(defaultDataMap);
            try {
                crawlerStatsHelper.begin(statsKey);

                final String content = extractText(client, attachment);
                final Map<String, Object> source = createAttachmentSourceRecord(boardId, attachment, cardUrl, content);

                // Replaces the "application/datastore" (filetype "others") every data store
                // document gets by default, before the script map runs so it can still override.
                final String mimeType = getAttachmentMimeType(attachment);
                final FessConfig fessConfig = ComponentUtil.getFessConfig();
                dataMap.put(fessConfig.getIndexFieldMimetype(), mimeType);
                dataMap.put(fessConfig.getIndexFieldFiletype(), ComponentUtil.getFileTypeHelper().get(mimeType));

                final Map<String, Object> resultMap = new LinkedHashMap<>(paramMap.asMap());
                resultMap.putAll(source);

                crawlerStatsHelper.record(statsKey, StatsAction.PREPARED);

                for (final Map.Entry<String, String> entry : scriptMap.entrySet()) {
                    final Object convertValue = convertValue(scriptType, entry.getValue(), resultMap);
                    if (convertValue != null) {
                        dataMap.put(entry.getKey(), convertValue);
                    }
                }

                crawlerStatsHelper.record(statsKey, StatsAction.EVALUATED);

                if (dataMap.get("url") instanceof final String statsUrl) {
                    statsKey.setUrl(statsUrl);
                }

                callback.store(paramMap, dataMap);
                crawlerStatsHelper.record(statsKey, StatsAction.FINISHED);
            } catch (final Exception e) {
                logger.warn("Failed to index Trello attachment {} on card {}", attachment.name(), cardId, e);
                final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
                failureUrlService.store(dataConfig, e.getClass().getCanonicalName(), attachment.url(), e);
                crawlerStatsHelper.record(statsKey, StatsAction.EXCEPTION);
            } finally {
                crawlerStatsHelper.done(statsKey);
            }
        }
    }

    /**
     * Indexes each of a card's comments as its own separate Fess document, in addition to
     * (not instead of) folding all of them into the card's own {@code comments}/{@code content}
     * field via {@link #createSourceRecord} — the two serve different goals: the card's own
     * consolidated document lets a topic discussed across several comments (plus the card's
     * description) accumulate enough combined term frequency to rank the card well as a whole;
     * a separate per-comment document lets a single specific comment be found and linked to
     * directly, which the card's own document can't do since its {@code url} is always the
     * plain card link with no comment anchor. Each comment document's {@code desc} is the card's
     * own description plus that one comment (not the comment alone), so a short, low-context
     * comment (e.g. "sounds good") still carries the card's own topic keywords rather than
     * matching on almost nothing. Its {@code name} is deliberately the card's own title, not a
     * synthesized "<title> — comment" label, so it reads the same as the card in search results.
     *
     * @param dataConfig The data store config being crawled.
     * @param callback Where each comment document is stored.
     * @param paramMap The data store parameters.
     * @param scriptMap The admin-configured field mapping — reused as-is from the card's own,
     * since comment source records use the same field names as a card's.
     * @param defaultDataMap Default field values to seed each comment's document with.
     * @param scriptType The script language {@code scriptMap}'s values are written in.
     * @param crawlerStatsHelper Where per-document crawl stats are recorded.
     * @param boardId The id of the board the card belongs to.
     * @param cardId The card whose comments to index.
     * @param card The raw card fields, as returned by the Trello API.
     * @param comments The card's comments (see {@link TrelloClient#getComments}) — already
     * fetched once for {@link #createSourceRecord}, reused here rather than fetched again.
     */
    private void storeComments(final DataConfig dataConfig, final IndexUpdateCallback callback, final DataStoreParams paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap, final String scriptType,
            final CrawlerStatsHelper crawlerStatsHelper, final String boardId, final String cardId, final Map<String, Object> card,
            final List<TrelloClient.Comment> comments) {
        for (final TrelloClient.Comment comment : comments) {
            final StatsKeyObject statsKey = new StatsKeyObject(dataConfig.getId() + "#" + cardId + "#comment#" + comment.id());
            final Map<String, Object> dataMap = new HashMap<>(defaultDataMap);
            try {
                crawlerStatsHelper.begin(statsKey);

                final Map<String, Object> source = createCommentSourceRecord(boardId, card, comment);

                final Map<String, Object> resultMap = new LinkedHashMap<>(paramMap.asMap());
                resultMap.putAll(source);

                crawlerStatsHelper.record(statsKey, StatsAction.PREPARED);

                for (final Map.Entry<String, String> entry : scriptMap.entrySet()) {
                    final Object convertValue = convertValue(scriptType, entry.getValue(), resultMap);
                    if (convertValue != null) {
                        dataMap.put(entry.getKey(), convertValue);
                    }
                }

                crawlerStatsHelper.record(statsKey, StatsAction.EVALUATED);

                if (dataMap.get("url") instanceof final String statsUrl) {
                    statsKey.setUrl(statsUrl);
                }

                callback.store(paramMap, dataMap);
                crawlerStatsHelper.record(statsKey, StatsAction.FINISHED);
            } catch (final Exception e) {
                final String commentUrl = getCardUrl(card) + "#comment-" + comment.id();
                logger.warn("Failed to index Trello comment {} on card {}", comment.id(), cardId, e);
                final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
                failureUrlService.store(dataConfig, e.getClass().getCanonicalName(), commentUrl, e);
                crawlerStatsHelper.record(statsKey, StatsAction.EXCEPTION);
            } finally {
                crawlerStatsHelper.done(statsKey);
            }
        }
    }

    /**
     * Builds the source record for one Trello comment, available to the admin-configured
     * scriptMap under the same field names {@link #createSourceRecord} uses for a card.
     *
     * @param boardId The id of the board the comment's card belongs to.
     * @param card The raw card fields the comment belongs to, as returned by the Trello API.
     * @param comment The comment being indexed.
     * @return The source record: {@code id} (the comment's own action id), {@code name} (the
     * card's own title, unchanged), {@code desc} (the card's description followed by this one
     * comment's text, both Markdown-rendered), {@code url} (a direct link to this comment,
     * {@code <card-url>#comment-<id>}), {@code card_url} (the parent card's link),
     * {@code board_id}, {@code last_modified} (the comment's own post date, falling back to the
     * card's {@code dateLastActivity} if Trello didn't report one), {@code labels} (the card's
     * own), and an empty {@code comments}, {@code list} and {@code due} (so a script map
     * referencing any of those card fields doesn't fail with {@code MissingPropertyException}
     * on a comment document; {@code list} stays empty rather than the card's own list so a
     * script can still tell a card from a comment or attachment by it).
     */
    protected Map<String, Object> createCommentSourceRecord(final String boardId, final Map<String, Object> card,
            final TrelloClient.Comment comment) {
        final String cardDesc = MarkdownPlainTextRenderer.render((String) card.get("desc"));
        final String commentText = MarkdownPlainTextRenderer.render(comment.text());

        final Map<String, Object> source = new HashMap<>();
        source.put("id", comment.id());
        source.put("name", card.get("name"));
        source.put("desc", StringUtil.isNotBlank(cardDesc) ? cardDesc + "\n\n" + commentText : commentText);
        source.put("url", getCardUrl(card) + "#comment-" + comment.id());
        source.put("card_url", getCardUrl(card));
        source.put("board_id", boardId);
        source.put("last_modified", StringUtil.isNotBlank(comment.date()) ? comment.date() : card.get("dateLastActivity"));
        source.put("labels", joinLabelNames(card.get("labels")));
        source.put("comments", "");
        source.put("list", "");
        source.put("due", "");
        return source;
    }

    /**
     * @param attachment An attachment on a card.
     * @return {@code true} for an uploaded file (not a link-only attachment — nothing of ours
     * to download for those), no larger than {@link #MAX_ATTACHMENT_BYTES}, whose filename
     * extension is in {@link #EXTRACTABLE_ATTACHMENT_MIMETYPES}.
     */
    protected boolean isExtractable(final TrelloClient.Attachment attachment) {
        if (!attachment.isUpload()) {
            return false;
        }
        if (attachment.bytes() >= 0 && attachment.bytes() > MAX_ATTACHMENT_BYTES) {
            logger.info("Skipping oversized Trello attachment {} ({} bytes)", attachment.name(), attachment.bytes());
            return false;
        }
        return getAttachmentMimeType(attachment) != null;
    }

    /**
     * @param attachment An attachment on a card.
     * @return The MIME type its document is indexed with, from its filename extension (see
     * {@link #EXTRACTABLE_ATTACHMENT_MIMETYPES}), or {@code null} for an extension that isn't
     * extracted at all.
     */
    protected String getAttachmentMimeType(final TrelloClient.Attachment attachment) {
        final String name = attachment.name();
        final int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return EXTRACTABLE_ATTACHMENT_MIMETYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Downloads and extracts an attachment's text, via Fess's own {@link ExtractorFactory}
     * (the same Tika-backed extraction the web crawler and other data stores use) rather than
     * bundling a separate extraction library.
     *
     * @param client The Trello API client.
     * @param attachment The attachment to download and extract.
     * @return The extracted plain text.
     */
    private String extractText(final TrelloClient client, final TrelloClient.Attachment attachment) {
        final byte[] bytes = client.downloadAttachment(attachment.url());
        final ExtractorFactory extractorFactory = ComponentUtil.getExtractorFactory();
        try (final ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return extractorFactory.builder(in, new HashMap<>()).filename(attachment.name()).extract().getContent();
        } catch (final java.io.IOException e) {
            throw new TrelloDataStoreException("Failed to extract text from Trello attachment: " + attachment.name(), e);
        }
    }

    /**
     * Builds the source record for one Trello attachment, available to the admin-configured
     * scriptMap under the same field names {@link #createSourceRecord} uses for a card, so an
     * existing script map indexes attachments without needing separate rules for them.
     *
     * @param boardId The id of the board the attachment's card belongs to.
     * @param attachment The attachment being indexed.
     * @param cardUrl The URL of the card this attachment belongs to — distinct from {@code url}
     * (the attachment's own download link), so a script map can surface both: one link to open
     * the attachment directly, another to open the card it's attached to.
     * @param content The attachment's extracted text.
     * @return The source record: {@code id}, {@code name}, {@code desc} (the extracted text),
     * {@code url} (the attachment's own download link), {@code card_url} (the parent card's
     * link), {@code board_id}, {@code last_modified}, and an empty {@code comments},
     * {@code labels}, {@code list} and {@code due} (so a script map referencing any of those
     * card fields doesn't fail with {@code MissingPropertyException} on an attachment
     * document).
     */
    protected Map<String, Object> createAttachmentSourceRecord(final String boardId, final TrelloClient.Attachment attachment,
            final String cardUrl, final String content) {
        final Map<String, Object> source = new HashMap<>();
        source.put("id", attachment.id());
        source.put("name", attachment.name());
        source.put("desc", content);
        source.put("url", attachment.url());
        source.put("card_url", cardUrl);
        source.put("board_id", boardId);
        source.put("last_modified", attachment.date());
        source.put("comments", "");
        source.put("labels", "");
        source.put("list", "");
        source.put("due", "");
        return source;
    }
}
