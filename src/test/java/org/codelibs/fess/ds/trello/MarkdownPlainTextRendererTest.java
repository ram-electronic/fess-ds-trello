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

import org.junit.jupiter.api.Test;

public class MarkdownPlainTextRendererTest {

    @Test
    public void render_null_returnsEmpty() {
        assertEquals("", MarkdownPlainTextRenderer.render(null));
    }

    @Test
    public void render_blank_returnsEmpty() {
        assertEquals("", MarkdownPlainTextRenderer.render(""));
    }

    @Test
    public void render_plainText_unchanged() {
        assertEquals("Just plain text.", MarkdownPlainTextRenderer.render("Just plain text."));
    }

    @Test
    public void render_boldAndItalic_stripsMarkers() {
        assertEquals("bold and italic", MarkdownPlainTextRenderer.render("**bold** and _italic_"));
    }

    @Test
    public void render_heading_stripsHashes() {
        assertEquals("Heading", MarkdownPlainTextRenderer.render("# Heading"));
    }

    @Test
    public void render_link_keepsTextAndDestination() {
        assertEquals("Example (https://example.com)", MarkdownPlainTextRenderer.render("[Example](https://example.com)"));
    }

    @Test
    public void render_inlineCode_keepsLiteral() {
        assertEquals("run `mvn package`".replace("`", ""), MarkdownPlainTextRenderer.render("run `mvn package`"));
    }

    @Test
    public void render_bulletList_oneParagraphPerItem() {
        assertEquals("one\n\ntwo", MarkdownPlainTextRenderer.render("- one\n- two"));
    }

    @Test
    public void render_multipleParagraphs_separatedByBlankLine() {
        assertEquals("first paragraph\n\nsecond paragraph", MarkdownPlainTextRenderer.render("first paragraph\n\nsecond paragraph"));
    }
}
