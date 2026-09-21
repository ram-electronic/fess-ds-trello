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

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

/**
 * Renders Trello's Markdown-formatted card/comment text down to plain text.
 *
 * <p>
 * Trello's card {@code desc} and comment text are always raw Markdown as
 * typed (Trello's own API has no pre-rendered plain-text alternative) — left
 * as-is, it shows up as literal {@code **bold**}/{@code [text](url)}/etc. in
 * a Fess search snippet instead of readable text.
 * </p>
 *
 * <p>
 * Deliberately renders to plain text, not HTML: Fess's {@code content}/
 * {@code digest} fields are plain text highlighted by search, so HTML tags
 * would show up just as literally as the Markdown syntax they replaced.
 * </p>
 */
final class MarkdownPlainTextRenderer {

    private MarkdownPlainTextRenderer() {
    }

    /**
     * @param markdown Markdown source text (Trello's card {@code desc}, or a comment's text).
     * @return The same text with Markdown syntax stripped — link destinations and code
     * literals are kept, everything else collapses to plain text. Empty if {@code markdown}
     * is blank.
     */
    static String render(final String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        final Node document = Parser.builder().build().parse(markdown);
        final PlainTextVisitor visitor = new PlainTextVisitor();
        document.accept(visitor);
        return visitor.getText().strip();
    }

    /**
     * Walks a parsed Markdown document, appending each node's plain-text content. Any node type
     * not explicitly overridden here falls through to {@link AbstractVisitor}'s default of
     * visiting the node's children — which is exactly what strips the Markdown syntax itself
     * (e.g. {@code **bold**}'s asterisks are structural, not a child {@link Text} node, so
     * they're never appended; only the "bold" text inside is).
     */
    private static final class PlainTextVisitor extends AbstractVisitor {

        private final StringBuilder text = new StringBuilder();

        String getText() {
            return text.toString();
        }

        @Override
        public void visit(final Text textNode) {
            text.append(textNode.getLiteral());
        }

        @Override
        public void visit(final SoftLineBreak softLineBreak) {
            text.append('\n');
        }

        @Override
        public void visit(final HardLineBreak hardLineBreak) {
            text.append('\n');
        }

        @Override
        public void visit(final Code code) {
            text.append(code.getLiteral());
        }

        @Override
        public void visit(final FencedCodeBlock fencedCodeBlock) {
            text.append(fencedCodeBlock.getLiteral()).append("\n\n");
        }

        @Override
        public void visit(final IndentedCodeBlock indentedCodeBlock) {
            text.append(indentedCodeBlock.getLiteral()).append("\n\n");
        }

        // Keeps the destination alongside the link text ("text (url)") rather than
        // dropping it entirely, since a Markdown link's URL is often the actual
        // point of the text, not just decoration.
        @Override
        public void visit(final Link link) {
            visitChildren(link);
            text.append(" (").append(link.getDestination()).append(')');
        }

        @Override
        public void visit(final Paragraph paragraph) {
            visitChildren(paragraph);
            text.append("\n\n");
        }

        @Override
        public void visit(final Heading heading) {
            visitChildren(heading);
            text.append("\n\n");
        }

        // No override for ListItem: its content is itself wrapped in a Paragraph
        // node (structurally, even for a "tight" list with no blank lines in the
        // source), so that Paragraph override above already adds the separator
        // between items -- an extra one here would just double up the newlines.
    }
}
