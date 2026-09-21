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

        final Map<String, Object> source = dataStore.createSourceRecord(null, "board1", listNames, card, false);

        assertEquals("card1", source.get("id"));
        assertEquals("Card Title", source.get("name"));
        assertEquals("Card body", source.get("desc"));
        assertEquals("https://trello.com/c/card1", source.get("url"));
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

        final Map<String, Object> source = dataStore.createSourceRecord(null, "board1", new HashMap<>(), card, false);

        assertEquals("https://trello.com/c/card1/full", source.get("url"));
    }

    @Test
    public void createSourceRecord_joinsComments_whenIncludeCommentsTrue() {
        final Map<String, Object> card = new HashMap<>();
        card.put("id", "card1");
        card.put("shortUrl", "https://trello.com/c/card1");

        final TrelloClient client = new TrelloClient("key", "token") {
            @Override
            public List<Comment> getComments(final String cardId) {
                assertEquals("card1", cardId);
                return Arrays.asList(new Comment("commentA", "first comment"), new Comment("commentB", "second comment"));
            }
        };

        final Map<String, Object> source = dataStore.createSourceRecord(client, "board1", new HashMap<>(), card, true);

        assertEquals("first comment\n(https://trello.com/c/card1#comment-commentA)"
                + "\n\nsecond comment\n(https://trello.com/c/card1#comment-commentB)", source.get("comments"));
    }

    @Test
    public void createSourceRecord_labels_emptyWhenNotAList() {
        final Map<String, Object> card = new HashMap<>();
        card.put("id", "card1");
        card.put("labels", "not-a-list");

        final Map<String, Object> source = dataStore.createSourceRecord(null, "board1", new HashMap<>(), card, false);

        assertEquals("", source.get("labels"));
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
