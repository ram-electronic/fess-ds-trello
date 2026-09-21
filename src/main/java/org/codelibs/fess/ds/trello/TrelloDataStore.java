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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.Constants;
import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.crawler.exception.CrawlingAccessException;
import org.codelibs.fess.crawler.exception.MultipleCrawlingAccessException;
import org.codelibs.fess.ds.AbstractDataStore;
import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.exception.DataStoreCrawlingException;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsAction;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsKeyObject;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.util.ComponentUtil;

/**
 * Data store crawler for Trello boards.
 *
 * <p>
 * Crawls every card on one or more Trello boards and feeds each card through
 * the standard Fess data store pipeline (see {@code fess-ds-example} for the
 * general pattern this follows).
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
 * <li>{@code include_comments} (optional) - {@code true} to append each
 * card's comments to the source record's {@code comments} field, each
 * followed by a direct link to that comment ({@code <card-url>#comment-<id>},
 * the same format Trello's own "copy link to comment" feature produces)
 * (default {@code false}; adds one extra API call per card).</li>
 * <li>{@code include_closed_cards} (optional) - {@code true} to also crawl
 * archived cards (default {@code false}).</li>
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

        final List<String> boardIds = getBoardIds(paramMap);

        try (final TrelloClient client = createClient(paramMap)) {
            boolean running = true;
            for (final String boardId : boardIds) {
                if (!running) {
                    break;
                }
                final Map<String, String> listNames = client.getLists(boardId);
                final boolean[] runningRef = { running };
                client.getCards(boardId, card -> {
                    if (!runningRef[0]) {
                        return;
                    }
                    if (!includeClosedCards && Boolean.TRUE.equals(card.get("closed"))) {
                        return;
                    }

                    final String cardId = (String) card.get("id");
                    final StatsKeyObject statsKey = new StatsKeyObject(dataConfig.getId() + "#" + cardId);
                    paramMap.put(Constants.CRAWLER_STATS_KEY, statsKey);
                    final Map<String, Object> dataMap = new HashMap<>(defaultDataMap);
                    try {
                        crawlerStatsHelper.begin(statsKey);

                        final Map<String, Object> source = createSourceRecord(client, boardId, listNames, card, includeComments);

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
     * Builds the source record for one Trello card, available to the
     * admin-configured scriptMap under the field names used below.
     *
     * @param client The Trello API client (used to fetch comments, if requested).
     * @param boardId The id of the board the card belongs to.
     * @param listNames A map of list id to list name for the card's board.
     * @param card The raw card fields, as returned by the Trello API.
     * @param includeComments Whether to fetch and join the card's comments.
     * @return The source record: {@code id}, {@code name}, {@code desc}, {@code url},
     * {@code board_id}, {@code list}, {@code due}, {@code last_modified}, {@code labels},
     * and, when requested, {@code comments}.
     */
    protected Map<String, Object> createSourceRecord(final TrelloClient client, final String boardId, final Map<String, String> listNames,
            final Map<String, Object> card, final boolean includeComments) {
        final Map<String, Object> source = new HashMap<>();
        final String cardId = (String) card.get("id");
        final String cardUrl = card.get("shortUrl") != null ? (String) card.get("shortUrl") : (String) card.get("url");
        source.put("id", cardId);
        source.put("name", card.get("name"));
        source.put("desc", card.get("desc"));
        source.put("url", cardUrl);
        source.put("board_id", boardId);
        source.put("list", listNames.get(card.get("idList")));
        source.put("due", card.get("due"));
        source.put("last_modified", card.get("dateLastActivity"));
        source.put("labels", joinLabelNames(card.get("labels")));

        if (includeComments) {
            source.put("comments", joinComments(client.getComments(cardId), cardUrl));
        }
        return source;
    }

    /**
     * @param comments The card's comments, oldest first.
     * @param cardUrl The card's own URL (its {@code shortUrl}, if the card has one).
     * @return Each comment's text followed by a direct link to that comment
     * ({@code <cardUrl>#comment-<id>} — the same link format Trello's own "copy link
     * to comment" feature in the web app produces), one comment per paragraph.
     */
    private String joinComments(final List<TrelloClient.Comment> comments, final String cardUrl) {
        final List<String> paragraphs = new ArrayList<>();
        for (final TrelloClient.Comment comment : comments) {
            paragraphs.add(comment.text() + "\n(" + cardUrl + "#comment-" + comment.id() + ")");
        }
        return String.join("\n\n", paragraphs);
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
}
