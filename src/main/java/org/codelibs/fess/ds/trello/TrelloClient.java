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

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.curl.Curl;
import org.codelibs.curl.CurlResponse;

/**
 * Minimal client for the parts of the Trello REST API this data store needs:
 * listing a board's lists (for id-&gt;name lookup), paging through a board's
 * cards, and optionally fetching a card's comments.
 *
 * <p>
 * Trello authenticates via {@code key}/{@code token} query parameters (see
 * <a href="https://developer.atlassian.com/cloud/trello/guides/rest-api/authorization/">
 * Trello REST API authorization</a>) rather than a bearer header, and caps a
 * single {@code /cards} response at 1000 results. Boards with more than 1000
 * cards are paged using the {@code before} parameter set to the id of the
 * oldest card seen so far, since Trello card ids are (roughly) chronologically
 * ordered ObjectIds.
 * </p>
 */
public class TrelloClient implements Closeable {

    private static final Logger logger = LogManager.getLogger(TrelloClient.class);

    private static final String API_BASE = "https://api.trello.com/1";

    private static final int PAGE_LIMIT = 1000;

    protected final String apiKey;

    protected final String apiToken;

    /**
     * @param apiKey Trello API key (from a Power-Up, see the Trello developer portal).
     * @param apiToken Trello API token authorized for that key.
     */
    public TrelloClient(final String apiKey, final String apiToken) {
        if (StringUtil.isBlank(apiKey) || StringUtil.isBlank(apiToken)) {
            throw new TrelloDataStoreException("Trello \"key\" and \"token\" parameters are required.");
        }
        this.apiKey = apiKey;
        this.apiToken = apiToken;
    }

    /**
     * @param boardId The Trello board id or shortLink.
     * @return A map of list id to list name for the given board.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> getLists(final String boardId) {
        final List<Map<String, Object>> lists =
                (List<Map<String, Object>>) get(API_BASE + "/boards/" + boardId + "/lists", Map.of("fields", "name"));
        final Map<String, String> idToName = new HashMap<>();
        for (final Map<String, Object> list : lists) {
            idToName.put((String) list.get("id"), (String) list.get("name"));
        }
        return idToName;
    }

    /**
     * Iterates every card on a board, transparently paging past Trello's
     * 1000-result-per-call cap.
     *
     * @param boardId The Trello board id or shortLink.
     * @param consumer Callback invoked once per card.
     */
    @SuppressWarnings("unchecked")
    public void getCards(final String boardId, final Consumer<Map<String, Object>> consumer) {
        String beforeId = null;
        while (true) {
            final Map<String, String> params = new HashMap<>();
            params.put("limit", String.valueOf(PAGE_LIMIT));
            params.put("fields", "name,desc,shortUrl,url,idList,due,dateLastActivity,labels,idMembers,closed");
            if (beforeId != null) {
                params.put("before", beforeId);
            }
            final List<Map<String, Object>> cards = (List<Map<String, Object>>) get(API_BASE + "/boards/" + boardId + "/cards", params);
            if (cards.isEmpty()) {
                break;
            }
            for (final Map<String, Object> card : cards) {
                consumer.accept(card);
            }
            if (cards.size() < PAGE_LIMIT) {
                break;
            }
            beforeId = (String) cards.get(cards.size() - 1).get("id");
        }
    }

    /**
     * @param cardId The Trello card id.
     * @return The text of each comment on the card, oldest first.
     */
    @SuppressWarnings("unchecked")
    public List<String> getComments(final String cardId) {
        final List<Map<String, Object>> actions = (List<Map<String, Object>>) get(API_BASE + "/cards/" + cardId + "/actions",
                Map.of("filter", "commentCard", "fields", "data"));
        final List<String> comments = new ArrayList<>();
        for (int i = actions.size() - 1; i >= 0; i--) {
            final Map<String, Object> data = (Map<String, Object>) actions.get(i).get("data");
            if (data != null && data.get("text") instanceof final String text) {
                comments.add(text);
            }
        }
        return comments;
    }

    /**
     * Issues an authenticated GET request and parses the JSON response body.
     *
     * @param url The request URL, without query parameters.
     * @param params Extra query parameters (key/token are added automatically).
     * @return The parsed JSON body (a {@code List} or {@code Map}, per Trello's response shape).
     */
    protected Object get(final String url, final Map<String, String> params) {
        final org.codelibs.curl.CurlRequest request = Curl.get(url).param("key", apiKey).param("token", apiToken);
        for (final Map.Entry<String, String> entry : params.entrySet()) {
            request.param(entry.getKey(), entry.getValue());
        }
        try (final CurlResponse response = request.execute()) {
            if (response.getHttpStatusCode() != 200) {
                throw new TrelloDataStoreException(
                        "Trello API returned " + response.getHttpStatusCode() + " for " + url + ": " + response.getContentAsString());
            }
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.getContentAsString(), Object.class);
        } catch (final TrelloDataStoreException e) {
            throw e;
        } catch (final Exception e) {
            throw new TrelloDataStoreException("Failed to call Trello API: " + url, e);
        }
    }

    @Override
    public void close() {
        // no persistent resources to release; CurlResponse instances are closed per-call
    }
}
