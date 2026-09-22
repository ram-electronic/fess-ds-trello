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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure, network-free logic in {@link TrelloDataStore} —
 * board id parsing and card-to-source-record mapping. Doesn't cover
 * {@code storeData} itself, which needs a live Fess/Lasta Di container
 * (via {@code ComponentUtil}) that these tests intentionally avoid
 * bootstrapping.
 */
public class TrelloDataStoreTest {

    private final TrelloDataStore dataStore = new TrelloDataStore();

    @Test
    public void getBoardIds_singleId() {
        final DataStoreParams params = new DataStoreParams();
        params.put("board_id", "aBcD1234");

        assertEquals(Arrays.asList("aBcD1234"), dataStore.getBoardIds(params));
    }

    @Test
    public void getBoardIds_multipleIds_trimmedAndBlanksRemoved() {
        final DataStoreParams params = new DataStoreParams();
        params.put("board_id", " aBcD1234 ,, eFgH5678,");

        assertEquals(Arrays.asList("aBcD1234", "eFgH5678"), dataStore.getBoardIds(params));
    }

    @Test
    public void getBoardIds_blank_throws() {
        final DataStoreParams params = new DataStoreParams();
        params.put("board_id", "   ");

        final TrelloDataStoreException e = assertThrows(TrelloDataStoreException.class, () -> dataStore.getBoardIds(params));
        assertTrue(e.getMessage().contains("board_id"));
    }

    @Test
    public void getBoardIds_missing_throws() {
        final TrelloDataStoreException e = assertThrows(TrelloDataStoreException.class, () -> dataStore.getBoardIds(new DataStoreParams()));
        assertTrue(e.getMessage().contains("board_id"));
    }

    @Test
    public void createSourceRecord_mapsCardFields() {
        final Map<String, Object> card = new HashMap<>();
        card.put("id", "card1");
        card.put("name", "Card Title");
        card.put("desc", "Card body");
        card.put("shortUrl", "https://trello.com/c/card1");
        card.put("idList", "list1");
        card.put("due", "2026-01-01T00:00:00.000Z");
        card.put("dateLastActivity", "2026-01-02T00:00:00.000Z");
        card.put("labels", labelList("Bug", "P1"));

        final Map<String, String> listNames = new HashMap<>();
        listNames.put("list1", "To Do");

        final Map<String, Object> source = dataStore.createSourceRecord("board1", listNames, card, false, List.of());

        assertEquals("card1", source.get("id"));
        assertEquals("Card Title", source.get("name"));
        assertEquals("Card body", source.get("desc"));
        assertEquals("https://trello.com/c/card1", source.get("url"));
        assertEquals("https://trello.com/c/card1", source.get("card_url"));
        assertEquals("board1", source.get("board_id"));
        assertEquals("To Do", source.get("list"));
        assertEquals("2026-01-01T00:00:00.000Z", source.get("due"));
        assertEquals("2026-01-02T00:00:00.000Z", source.get("last_modified"));
        assertEquals("Bug, P1", source.get("labels"));
        assertFalse(source.containsKey("comments"), "comments should be absent when includeComments is false");
    }

    @Test
    public void createSourceRecord_fallsBackToUrl_whenShortUrlMissing() {
        final Map<String, Object> card = new HashMap<>();
        card.put("id", "card1");
        card.put("url", "https://trello.com/c/card1/full");

        final Map<String, Object> source = dataStore.createSourceRecord("board1", new HashMap<>(), card, false, List.of());

        assertEquals("https://trello.com/c/card1/full", source.get("url"));
    }

    @Test
    public void createSourceRecord_joinsComments_whenIncludeCommentsTrue() {
        final Map<String, Object> card = new HashMap<>();
        card.put("id", "card1");
        card.put("shortUrl", "https://trello.com/c/card1");
        final List<TrelloClient.Comment> comments = Arrays.asList(new TrelloClient.Comment("commentA", "first comment", null),
                new TrelloClient.Comment("commentB", "second comment", null));

        final Map<String, Object> source = dataStore.createSourceRecord("board1", new HashMap<>(), card, true, comments);

        assertEquals("first comment\n(https://trello.com/c/card1#comment-commentA)"
                + "\n\nsecond comment\n(https://trello.com/c/card1#comment-commentB)", source.get("comments"));
    }

    @Test
    public void createSourceRecord_labels_emptyWhenNotAList() {
        final Map<String, Object> card = new HashMap<>();
        card.put("id", "card1");
        card.put("labels", "not-a-list");

        final Map<String, Object> source = dataStore.createSourceRecord("board1", new HashMap<>(), card, false, List.of());

        assertEquals("", source.get("labels"));
    }

    @Test
    public void isExtractable_uploadedTextFile_true() {
        assertTrue(dataStore.isExtractable(
                new TrelloClient.Attachment("a1", "notes.txt", "https://trello.com/1/notes.txt", "text/plain", 100, true, null)));
    }

    @Test
    public void isExtractable_notAnUpload_false() {
        assertFalse(dataStore.isExtractable(
                new TrelloClient.Attachment("a1", "notes.txt", "https://example.com/notes.txt", "text/plain", 100, false, null)));
    }

    @Test
    public void isExtractable_unrecognizedExtension_false() {
        assertFalse(dataStore.isExtractable(
                new TrelloClient.Attachment("a1", "photo.png", "https://trello.com/1/photo.png", "image/png", 100, true, null)));
    }

    @Test
    public void isExtractable_noExtension_false() {
        assertFalse(
                dataStore.isExtractable(new TrelloClient.Attachment("a1", "README", "https://trello.com/1/README", null, 100, true, null)));
    }

    @Test
    public void isExtractable_oversized_false() {
        assertFalse(dataStore.isExtractable(new TrelloClient.Attachment("a1", "big.pdf", "https://trello.com/1/big.pdf", "application/pdf",
                21L * 1024 * 1024, true, null)));
    }

    @Test
    public void isExtractable_unknownSize_stillExtractable() {
        // Trello omits `bytes` for some attachments -- -1 shouldn't be treated as "oversized".
        assertTrue(dataStore.isExtractable(
                new TrelloClient.Attachment("a1", "notes.pdf", "https://trello.com/1/notes.pdf", "application/pdf", -1, true, null)));
    }

    @Test
    public void createAttachmentSourceRecord_mapsFields() {
        final TrelloClient.Attachment attachment = new TrelloClient.Attachment("a1", "notes.pdf", "https://trello.com/1/notes.pdf",
                "application/pdf", 100, true, "2026-01-03T00:00:00.000Z");

        final Map<String, Object> source =
                dataStore.createAttachmentSourceRecord("board1", attachment, "https://trello.com/c/card1", "extracted text");

        assertEquals("a1", source.get("id"));
        assertEquals("notes.pdf", source.get("name"));
        assertEquals("extracted text", source.get("desc"));
        assertEquals("https://trello.com/1/notes.pdf", source.get("url"));
        assertEquals("https://trello.com/c/card1", source.get("card_url"));
        assertEquals("board1", source.get("board_id"));
        assertEquals("2026-01-03T00:00:00.000Z", source.get("last_modified"));
        assertEquals("", source.get("comments"));
        assertEquals("", source.get("labels"));
        assertEquals("", source.get("list"));
        assertEquals("", source.get("due"));
    }

    @Test
    public void isAlreadyIndexedUnmodified_matchingLastModified_true() {
        final Map<String, Object> card = new HashMap<>();
        card.put("shortUrl", "https://trello.com/c/card1");
        card.put("dateLastActivity", "2026-01-02T00:00:00.000Z");

        final Map<String, String> indexed = Map.of("https://trello.com/c/card1", "2026-01-02T00:00:00.000Z");

        assertTrue(dataStore.isAlreadyIndexedUnmodified(card, indexed));
    }

    @Test
    public void isAlreadyIndexedUnmodified_differentLastModified_false() {
        final Map<String, Object> card = new HashMap<>();
        card.put("shortUrl", "https://trello.com/c/card1");
        card.put("dateLastActivity", "2026-01-03T00:00:00.000Z");

        final Map<String, String> indexed = Map.of("https://trello.com/c/card1", "2026-01-02T00:00:00.000Z");

        assertFalse(dataStore.isAlreadyIndexedUnmodified(card, indexed));
    }

    @Test
    public void isAlreadyIndexedUnmodified_notYetIndexed_false() {
        final Map<String, Object> card = new HashMap<>();
        card.put("shortUrl", "https://trello.com/c/card1");
        card.put("dateLastActivity", "2026-01-02T00:00:00.000Z");

        assertFalse(dataStore.isAlreadyIndexedUnmodified(card, Map.of()));
    }

    @Test
    public void isAlreadyIndexedUnmodified_fallsBackToUrl_whenShortUrlMissing() {
        final Map<String, Object> card = new HashMap<>();
        card.put("url", "https://trello.com/c/card1/full");
        card.put("dateLastActivity", "2026-01-02T00:00:00.000Z");

        final Map<String, String> indexed = Map.of("https://trello.com/c/card1/full", "2026-01-02T00:00:00.000Z");

        assertTrue(dataStore.isAlreadyIndexedUnmodified(card, indexed));
    }

    @Test
    public void createCommentSourceRecord_combinesCardDescAndComment() {
        final Map<String, Object> card = new HashMap<>();
        card.put("name", "Card Title");
        card.put("desc", "Card body");
        card.put("shortUrl", "https://trello.com/c/card1");
        card.put("labels", labelList("Bug", "P1"));
        card.put("dateLastActivity", "2026-01-02T00:00:00.000Z");
        final TrelloClient.Comment comment = new TrelloClient.Comment("commentA", "the comment text", "2026-01-05T00:00:00.000Z");

        final Map<String, Object> source = dataStore.createCommentSourceRecord("board1", card, comment);

        assertEquals("commentA", source.get("id"));
        assertEquals("Card Title", source.get("name"));
        assertEquals("Card body\n\nthe comment text", source.get("desc"));
        assertEquals("https://trello.com/c/card1#comment-commentA", source.get("url"));
        assertEquals("https://trello.com/c/card1", source.get("card_url"));
        assertEquals("board1", source.get("board_id"));
        assertEquals("2026-01-05T00:00:00.000Z", source.get("last_modified"));
        assertEquals("Bug, P1", source.get("labels"));
        assertEquals("", source.get("comments"));
        assertEquals("", source.get("list"));
        assertEquals("", source.get("due"));
    }

    @Test
    public void createCommentSourceRecord_omitsDescSeparator_whenCardDescBlank() {
        final Map<String, Object> card = new HashMap<>();
        card.put("name", "Card Title");
        card.put("shortUrl", "https://trello.com/c/card1");
        final TrelloClient.Comment comment = new TrelloClient.Comment("commentA", "the comment text", null);

        final Map<String, Object> source = dataStore.createCommentSourceRecord("board1", card, comment);

        assertEquals("the comment text", source.get("desc"));
    }

    @Test
    public void createCommentSourceRecord_fallsBackToCardLastModified_whenCommentDateMissing() {
        final Map<String, Object> card = new HashMap<>();
        card.put("name", "Card Title");
        card.put("shortUrl", "https://trello.com/c/card1");
        card.put("dateLastActivity", "2026-01-02T00:00:00.000Z");
        final TrelloClient.Comment comment = new TrelloClient.Comment("commentA", "the comment text", null);

        final Map<String, Object> source = dataStore.createCommentSourceRecord("board1", card, comment);

        assertEquals("2026-01-02T00:00:00.000Z", source.get("last_modified"));
    }

    private static List<Map<String, Object>> labelList(final String... names) {
        final List<Map<String, Object>> labels = new ArrayList<>();
        for (final String name : names) {
            final Map<String, Object> label = new HashMap<>();
            label.put("name", name);
            labels.add(label);
        }
        return labels;
    }
}
