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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link TrelloClient}'s constructor validation. Its actual API
 * methods ({@code getLists}, {@code getCards}, {@code getComments}) issue
 * real HTTP calls to {@code api.trello.com} and aren't covered here — see
 * {@link TrelloDataStoreTest} for how {@code getComments} is stubbed out
 * for the one place its result is consumed.
 */
public class TrelloClientTest {

    @Test
    public void constructor_blankKey_throws() {
        final TrelloDataStoreException e = assertThrows(TrelloDataStoreException.class, () -> new TrelloClient(" ", "token"));
        assertTrue(e.getMessage().contains("key"));
    }

    @Test
    public void constructor_blankToken_throws() {
        final TrelloDataStoreException e = assertThrows(TrelloDataStoreException.class, () -> new TrelloClient("key", ""));
        assertTrue(e.getMessage().contains("token"));
    }

    @Test
    public void constructor_validCredentials_succeeds() {
        // Just needs to not throw.
        new TrelloClient("key", "token").close();
    }
}
