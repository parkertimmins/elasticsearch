/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.logsdb.patternedtext;

import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;

public class PatternTextDocValuesTests extends ESTestCase {

    private static PatternedTextDocValues makeDocValueSparseArgs() throws IOException {
        var template = SimpleSortedSetDocValues.single(removePlaceholders("% dog", "cat", "% mouse %", "hat %"));
        var args = new SimpleSortedSetDocValues(Stream.of(args("1"), null, args("2", "3"), args("4")).toList());
        var offsets = SimpleSortedSetDocValues.single(ords(0), null, ords(0, 1), ords(0));
        var info = SimpleSortedSetDocValues.single(info(0), info(), info(0, 7), info(4));
        return new PatternedTextDocValues(template, args, info, offsets);
    }

    private static PatternedTextDocValues makeDocValuesDenseArgs() throws IOException {
        var template = SimpleSortedSetDocValues.single(removePlaceholders("% moose", "% goose %", "% mouse %", "% house"));
        var args = new SimpleSortedSetDocValues(Stream.of(args("1"), args("4", "5"), args("2", "3"), args("7")).toList());
        var offsets = SimpleSortedSetDocValues.single(ords(0), ords(0, 1), ords(0, 1), ords(0));
        var info = SimpleSortedSetDocValues.single(info(0), info(0, 7), info(0, 7), info(0));
        return new PatternedTextDocValues(template, args, info, offsets);
    }

    private static PatternedTextDocValues makeDocValueMissingValues() throws IOException {
        var template = SimpleSortedSetDocValues.single(removePlaceholders("% cheddar", "cat", null, "% cheese"));
        var args = new SimpleSortedSetDocValues(Stream.of(args("1"), null, null, args("4")).toList());
        var offsets = SimpleSortedSetDocValues.single(ords(0), null, null, ords(0));
        var info = SimpleSortedSetDocValues.single(info(0), info(), info(), info(0));
        return new PatternedTextDocValues(template, args, info, offsets);
    }

    public void testNextDoc() throws IOException {
        var docValues = randomBoolean() ? makeDocValueSparseArgs() : makeDocValuesDenseArgs();
        assertEquals(-1, docValues.docID());
        assertEquals(0, docValues.nextDoc());
        assertEquals(1, docValues.nextDoc());
        assertEquals(2, docValues.nextDoc());
        assertEquals(3, docValues.nextDoc());
        assertEquals(NO_MORE_DOCS, docValues.nextDoc());
    }

    public void testNextDocMissing() throws IOException {
        var docValues = makeDocValueMissingValues();
        assertEquals(-1, docValues.docID());
        assertEquals(0, docValues.nextDoc());
        assertEquals(1, docValues.nextDoc());
        assertEquals(3, docValues.nextDoc());
        assertEquals(NO_MORE_DOCS, docValues.nextDoc());
    }

    public void testAdvance1() throws IOException {
        var docValues = randomBoolean() ? makeDocValueSparseArgs() : makeDocValuesDenseArgs();
        assertEquals(-1, docValues.docID());
        assertEquals(0, docValues.nextDoc());
        assertEquals(1, docValues.advance(1));
        assertEquals(2, docValues.advance(2));
        assertEquals(3, docValues.advance(3));
        assertEquals(NO_MORE_DOCS, docValues.advance(4));
    }

    public void testAdvanceFarther() throws IOException {
        var docValues = randomBoolean() ? makeDocValueSparseArgs() : makeDocValuesDenseArgs();
        assertEquals(2, docValues.advance(2));
        // repeats says on value
        assertEquals(2, docValues.advance(2));
    }

    public void testAdvanceSkipsValuesIfMissing() throws IOException {
        var docValues = makeDocValueMissingValues();
        assertEquals(3, docValues.advance(2));
    }

    public void testAdvanceExactMissing() throws IOException {
        var docValues = makeDocValueMissingValues();
        assertTrue(docValues.advanceExact(1));
        assertFalse(docValues.advanceExact(2));
        assertEquals(3, docValues.docID());
    }

    public void testValueAll() throws IOException {
        var docValues = makeDocValuesDenseArgs();
        assertEquals(0, docValues.nextDoc());
        assertEquals("1 moose", docValues.binaryValue().utf8ToString());
        assertEquals(1, docValues.nextDoc());
        assertEquals("4 goose 5", docValues.binaryValue().utf8ToString());
        assertEquals(2, docValues.nextDoc());
        assertEquals("2 mouse 3", docValues.binaryValue().utf8ToString());
        assertEquals(3, docValues.nextDoc());
        assertEquals("7 house", docValues.binaryValue().utf8ToString());
    }

    public void testValueMissing() throws IOException {
        var docValues = makeDocValueMissingValues();
        assertEquals(0, docValues.nextDoc());
        assertEquals("1 cheddar", docValues.binaryValue().utf8ToString());
        assertEquals(1, docValues.nextDoc());
        assertEquals("cat", docValues.binaryValue().utf8ToString());
        assertEquals(3, docValues.nextDoc());
        assertEquals("4 cheese", docValues.binaryValue().utf8ToString());
    }

    static class SimpleSortedSetDocValues extends SortedSetDocValues {

        private final List<List<BytesRef>> docIdToValues;
        private int currDoc = -1;
        private int currOrd = -1;

        SimpleSortedSetDocValues(List<List<BytesRef>> docIdToValues) {
            this.docIdToValues = docIdToValues;
        }

        // Single value for each docId, null if no value for a docId
        static SimpleSortedSetDocValues single(BytesRef... docIdToSingleValue) {
            var values = Arrays.stream(docIdToSingleValue)
                .map(item -> item == null ? null : Stream.of(item).toList()).toList();
            return new SimpleSortedSetDocValues(values);
        }

        @Override
        public long nextOrd() {
            return currOrd++;
        }

        @Override
        public int docValueCount() {
            return docIdToValues.get(currDoc).size();
        }

        @Override
        public BytesRef lookupOrd(long ord) {
            return docIdToValues.get(currDoc).get((int) ord);
        }

        @Override
        public long getValueCount() {
            return docIdToValues.stream().mapToInt(List::size).sum();
        }

        @Override
        public boolean advanceExact(int target) {
            return advance(target) == target;
        }

        @Override
        public int docID() {
            return currDoc >= docIdToValues.size() ? NO_MORE_DOCS : currDoc;
        }

        @Override
        public int nextDoc() throws IOException {
            return advance(currDoc + 1);
        }

        @Override
        public int advance(int target) {
            for (currDoc = target; currDoc < docIdToValues.size(); currDoc++) {
                if (docIdToValues.get(currDoc) != null) {
                    currOrd = 0;
                    return currDoc;
                }
            }
            return NO_MORE_DOCS;
        }

        @Override
        public long cost() {
            return 1;
        }
    }

    private static BytesRef info(int... offsets) throws IOException {
        List<Arg.Info> argsInfo = new ArrayList<>();
        for (var offset : offsets) {
            argsInfo.add(new Arg.Info(Arg.Type.GENERIC, offset));
        }
        return new BytesRef(Arg.encodeInfo(argsInfo));
    }

    private static BytesRef ords(int... ords) throws IOException {
        return PatternedTextFieldMapper.encodeOffsetToOrd(ords);
    }

    private static List<BytesRef> args(String... arg) throws IOException {
        return Arrays.stream(arg).map(BytesRef::new).toList();
    }

    // Placeholders are only included here to help in testing
    private static BytesRef[] removePlaceholders(String... values) {
        var x = Arrays.stream(values)
            .map(s -> s == null ? null : s.replace("%", ""))
            .map(s -> s == null ? null : new BytesRef(s))
            .toList().toArray(BytesRef[]::new);
        int y = 3;
        return x;
    }
}
