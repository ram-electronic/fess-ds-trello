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
 * cards, and optionally fetching a card's comments and attachments.
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

    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private static final int READ_TIMEOUT_MS = 30_000;

    /** Retries on 429/5xx before giving up; a card-heavy board with
     *  {@code include_comments=true} does one API call per card, which can
     *  trip Trello's per-key/per-token rate limit long before a crawl
     *  finishes. */
    private static final int MAX_RETRIES = 5;

    private static final long INITIAL_BACKOFF_MS = 500L;

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
     * <p>
     * Attachments, when requested, are pulled in via Trello's
     * <a href="https://developer.atlassian.com/cloud/trello/guides/rest-api/nested-resources/">
     * nested resources</a> feature — embedded directly in each card object
     * returned by this same call — rather than one extra API call per card.
     * Use {@link #parseAttachments(Map)} to read them back out of the card map
     * handed to {@code consumer}.
     * </p>
     *
     * <p>
     * Comments are deliberately <b>not</b> embedded this way (unlike before -
     * see {@link #getComments(String)}): Trello enforces an undocumented cap
     * on how many cards can be requested with embedded {@code actions} in one
     * call, returning a 403 {@code API_TOO_MANY_CARDS_REQUESTED} once a board
     * has enough cards - confirmed in production, aborting the entire crawl
     * (the exception propagates out of this method before any card is
     * processed), not just costing more API calls. Attachments aren't
     * confirmed to hit the same cap (no reports found), so they stay embedded;
     * if that ever turns out to be wrong too, drop {@code attachments}/
     * {@code attachment_fields} here the same way {@code actions} was dropped.
     * </p>
     *
     * @param boardId The Trello board id or shortLink.
     * @param includeAttachments Whether to embed each card's attachments.
     * @param consumer Callback invoked once per card.
     */
    @SuppressWarnings("unchecked")
    public void getCards(final String boardId, final boolean includeAttachments, final Consumer<Map<String, Object>> consumer) {
        String beforeId = null;
        while (true) {
            final Map<String, String> params = new HashMap<>();
            params.put("limit", String.valueOf(PAGE_LIMIT));
            params.put("fields", "name,desc,shortUrl,url,idList,due,dateLastActivity,labels,idMembers,closed");
            if (includeAttachments) {
                params.put("attachments", "true");
                params.put("attachment_fields", "name,url,mimeType,bytes,isUpload,date");
            }
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
     * @return Each comment on the card, oldest first.
     */
    @SuppressWarnings("unchecked")
    public List<Comment> getComments(final String cardId) {
        final List<Map<String, Object>> actions = (List<Map<String, Object>>) get(API_BASE + "/cards/" + cardId + "/actions",
                Map.of("filter", "commentCard", "fields", "data,date"));
        final List<Comment> comments = new ArrayList<>();
        for (int i = actions.size() - 1; i >= 0; i--) {
            final Map<String, Object> action = actions.get(i);
            final Map<String, Object> data = (Map<String, Object>) action.get("data");
            if (data != null && data.get("text") instanceof final String text && action.get("id") instanceof final String id) {
                final String date = action.get("date") instanceof final String d ? d : null;
                comments.add(new Comment(id, text, date));
            }
        }
        return comments;
    }

    /**
     * One comment on a card.
     *
     * @param id The comment's Trello action id — the same id used in a
     * direct comment link, {@code <card-url>#comment-<id>} (confirmed by
     * copying a comment's own share link from the Trello web app).
     * @param text The comment's text.
     * @param date When the comment was posted, or {@code null} if Trello didn't report one.
     */
    public record Comment(String id, String text, String date) {
    }

    /**
     * Reads a card's attachments back out of its own map, as embedded by
     * {@link #getCards} when called with {@code includeAttachments} true —
     * no separate API call.
     *
     * @param card A card map, as handed to the {@code consumer} of {@link #getCards}.
     * @return Every attachment on the card, in the order Trello returns them
     * (or empty if attachments weren't embedded).
     */
    public static List<Attachment> parseAttachments(final Map<String, Object> card) {
        final Object rawAttachments = card.get("attachments");
        if (!(rawAttachments instanceof final List<?> attachments)) {
            return List.of();
        }
        final List<Attachment> result = new ArrayList<>();
        for (final Object rawAttachment : attachments) {
            if (!(rawAttachment instanceof final Map<?, ?> attachment)) {
                continue;
            }
            if (attachment.get("id") instanceof final String id && attachment.get("name") instanceof final String name
                    && attachment.get("url") instanceof final String url) {
                final String mimeType = attachment.get("mimeType") instanceof final String mt ? mt : null;
                final long bytes = attachment.get("bytes") instanceof final Number n ? n.longValue() : -1L;
                final boolean isUpload = Boolean.TRUE.equals(attachment.get("isUpload"));
                final String date = attachment.get("date") instanceof final String d ? d : null;
                result.add(new Attachment(id, name, url, mimeType, bytes, isUpload, date));
            }
        }
        return result;
    }

    /**
     * One attachment on a card.
     *
     * @param id The attachment's Trello id.
     * @param name The attachment's filename (as uploaded, or the link text for a non-upload).
     * @param url Where to download it from ({@link #downloadAttachment(String)} — for an
     * external-link attachment ({@code isUpload} false), this is just the link itself, not
     * necessarily a file at all.
     * @param mimeType Trello's own guess at the content type, or {@code null} if it didn't say.
     * @param bytes File size in bytes, or {@code -1} if Trello didn't report one.
     * @param isUpload {@code true} for a file actually stored on Trello's own storage;
     * {@code false} for an attachment that's just a link to an external URL, with nothing of
     * ours to download or extract.
     * @param date When the attachment was added, or {@code null} if Trello didn't report one.
     */
    public record Attachment(String id, String name, String url, String mimeType, long bytes, boolean isUpload, String date) {
    }

    /**
     * Downloads an attachment's raw file bytes.
     *
     * <p>
     * Authenticates via an {@code Authorization: OAuth ...} header, not the {@code key}/
     * {@code token} query parameters {@link #get} uses for every other endpoint — confirmed via
     * real-world reports (Atlassian Community: "trello REST API: getting 401 (unauthorized)
     * when downloading attachment") that Trello's own docs once recommended the query-parameter
     * form for attachment download URLs specifically, then later added a note that it no longer
     * works; the header form is the one that still does.
     * </p>
     *
     * @param url An attachment's own {@code url} (see {@link Attachment#url()}).
     * @return The raw file content.
     */
    public byte[] downloadAttachment(final String url) {
        Exception lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            final org.codelibs.curl.CurlRequest request = Curl.get(url)
                    .header("Authorization", "OAuth oauth_consumer_key=\"" + apiKey + "\", oauth_token=\"" + apiToken + "\"")
                    .timeout(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
            try (final CurlResponse response = request.execute()) {
                final int status = response.getHttpStatusCode();
                if (status == 200) {
                    return response.getContentAsStream().readAllBytes();
                }
                if (isRetryable(status) && attempt < MAX_RETRIES) {
                    sleepBeforeRetry(attempt, retryAfterMillis(response));
                    continue;
                }
                throw new TrelloDataStoreException("Trello attachment download returned " + status + " for " + url);
            } catch (final TrelloDataStoreException e) {
                throw e;
            } catch (final Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    sleepBeforeRetry(attempt, -1);
                    continue;
                }
                throw new TrelloDataStoreException("Failed to download Trello attachment: " + url, e);
            }
        }
        throw new TrelloDataStoreException("Failed to download Trello attachment after " + MAX_RETRIES + " retries: " + url, lastException);
    }

    /**
     * Issues an authenticated GET request and parses the JSON response body,
     * retrying on rate-limit (429) and server-error (5xx) responses with
     * exponential backoff (honoring Trello's {@code Retry-After} header when
     * present) up to {@link #MAX_RETRIES} times.
     *
     * @param url The request URL, without query parameters.
     * @param params Extra query parameters (key/token are added automatically).
     * @return The parsed JSON body (a {@code List} or {@code Map}, per Trello's response shape).
     */
    protected Object get(final String url, final Map<String, String> params) {
        Exception lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            final org.codelibs.curl.CurlRequest request =
                    Curl.get(url).param("key", apiKey).param("token", apiToken).timeout(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
            for (final Map.Entry<String, String> entry : params.entrySet()) {
                request.param(entry.getKey(), entry.getValue());
            }
            try (final CurlResponse response = request.execute()) {
                final int status = response.getHttpStatusCode();
                if (status == 200) {
                    return new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.getContentAsString(), Object.class);
                }
                if (isRetryable(status) && attempt < MAX_RETRIES) {
                    sleepBeforeRetry(attempt, retryAfterMillis(response));
                    continue;
                }
                throw new TrelloDataStoreException("Trello API returned " + status + " for " + url + ": " + response.getContentAsString());
            } catch (final TrelloDataStoreException e) {
                throw e;
            } catch (final Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    sleepBeforeRetry(attempt, -1);
                    continue;
                }
                throw new TrelloDataStoreException("Failed to call Trello API: " + url, e);
            }
        }
        // Unreachable: every loop iteration either returns or throws, but the
        // compiler can't see that from a for-loop condition alone.
        throw new TrelloDataStoreException("Failed to call Trello API after " + MAX_RETRIES + " retries: " + url, lastException);
    }

    /**
     * @param status An HTTP status code.
     * @return {@code true} for a rate-limit (429) or server-error (5xx) response — both are
     * expected to be transient, unlike a 4xx client error (bad key/token, missing board, ...).
     */
    private static boolean isRetryable(final int status) {
        return status == 429 || status >= 500;
    }

    /**
     * @param response A response carrying a (possibly absent) {@code Retry-After} header.
     * @return The header's value in milliseconds, or {@code -1} if absent/unparseable.
     */
    private static long retryAfterMillis(final CurlResponse response) {
        final String header = response.getHeaderValue("Retry-After");
        if (StringUtil.isNotBlank(header)) {
            try {
                return Long.parseLong(header.trim()) * 1000L;
            } catch (final NumberFormatException ignore) {
                // Not a delay-in-seconds value (e.g. an HTTP-date) — fall back to backoff below.
            }
        }
        return -1;
    }

    /**
     * @param attempt The retry attempt number so far (0-based).
     * @param retryAfterMillis A server-supplied delay in milliseconds, or {@code -1} to fall
     * back to exponential backoff ({@link #INITIAL_BACKOFF_MS} doubled per attempt).
     */
    private void sleepBeforeRetry(final int attempt, final long retryAfterMillis) {
        final long delay = retryAfterMillis >= 0 ? retryAfterMillis : INITIAL_BACKOFF_MS * (1L << attempt);
        logger.warn("Trello API call failed; retrying in {}ms (attempt {}/{})", delay, attempt + 1, MAX_RETRIES);
        try {
            Thread.sleep(delay);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrelloDataStoreException("Interrupted while waiting to retry a Trello API call", e);
        }
    }

    @Override
    public void close() {
        // no persistent resources to release; CurlResponse instances are closed per-call
    }
}
