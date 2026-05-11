/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.tsdb.insertionorder;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.TestUtil;
import org.elasticsearch.index.codec.tsdb.es95.ES95TSDBDocValuesFormat;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Roundtrip test for the insertion-order numeric prototype: index a field with the wrapper,
 * close, reopen, and assert the values come back in the exact order they were written.
 */
public class InsertionOrderNumericRoundtripTests extends ESTestCase {

    private final Codec codec = TestUtil.alwaysDocValuesFormat(new ES95TSDBDocValuesFormat());

    public void testSingleDocSingleValue() throws IOException {
        roundtrip(List.of(new long[] { 42L }));
    }

    public void testSingleDocMultiValueInsertionOrder() throws IOException {
        roundtrip(List.of(new long[] { 7L, 3L, 9L, 1L, 5L }));
    }

    public void testDuplicatesPreserved() throws IOException {
        roundtrip(List.of(new long[] { 5L, 5L, 5L, 2L, 5L, 2L }));
    }

    public void testNegativeAndPositive() throws IOException {
        roundtrip(List.of(new long[] { -1L, 0L, 1L, Long.MIN_VALUE, Long.MAX_VALUE, -100L, 100L }));
    }

    public void testManyDocsVaryingCounts() throws IOException {
        int numDocs = 50;
        List<long[]> docs = new ArrayList<>(numDocs);
        for (int i = 0; i < numDocs; i++) {
            int count = 1 + random().nextInt(8);
            long[] values = new long[count];
            for (int j = 0; j < count; j++) {
                values[j] = random().nextLong();
            }
            docs.add(values);
        }
        roundtrip(docs);
    }

    public void testForceMergeRoundtripPreservesPerDocOrder() throws IOException {
        // Each doc's first value is a unique marker so we can identify it after merge.
        // Lucene's merge does not guarantee that doc IDs in the merged segment match the
        // original add order across segments — what we care about is that each doc's
        // *values* are in their original insertion order.
        Map<Long, long[]> byMarker = new HashMap<>();
        byMarker.put(100L, new long[] { 100L, 1L, 2L, 3L });
        byMarker.put(200L, new long[] { 200L, 10L });
        byMarker.put(300L, new long[] { 300L, -5L, -5L, 0L });
        byMarker.put(400L, new long[] { 400L, 999L, 1L, 999L, 1L });

        try (Directory dir = newDirectory()) {
            IndexWriterConfig conf = new IndexWriterConfig();
            conf.setCodec(codec);
            conf.setMergePolicy(NoMergePolicy.INSTANCE);
            try (IndexWriter writer = new IndexWriter(dir, conf)) {
                for (long[] values : byMarker.values()) {
                    Document d = new Document();
                    InsertionOrderNumericDocValuesField f = new InsertionOrderNumericDocValuesField("ion");
                    for (long v : values) {
                        f.addValue(v);
                    }
                    d.add(f);
                    writer.addDocument(d);
                    writer.flush();
                }
            }
            try (IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig().setCodec(codec))) {
                writer.forceMerge(1);
            }

            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                int seen = 0;
                for (LeafReaderContext leaf : reader.leaves()) {
                    BinaryDocValues bdv = leaf.reader().getBinaryDocValues("ion");
                    InsertionOrderNumericDocValues view = InsertionOrderNumericDocValues.wrap(bdv);
                    for (int doc = view.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = view.nextDoc()) {
                        long[] actual = new long[view.docValueCount()];
                        for (int i = 0; i < actual.length; i++) {
                            actual[i] = view.nextValue();
                        }
                        long marker = actual[0];
                        long[] expected = byMarker.remove(marker);
                        assertNotNull("unknown marker " + marker, expected);
                        assertArrayEquals("insertion order mismatch for marker " + marker, expected, actual);
                        seen++;
                    }
                }
                assertEquals("all docs accounted for", 4, seen);
                assertTrue("all markers consumed: " + byMarker.keySet(), byMarker.isEmpty());
            }
        }
    }

    private void roundtrip(List<long[]> docs) throws IOException {
        try (Directory dir = newDirectory()) {
            IndexWriterConfig conf = new IndexWriterConfig();
            conf.setCodec(codec);
            try (IndexWriter writer = new IndexWriter(dir, conf)) {
                for (long[] values : docs) {
                    Document d = new Document();
                    InsertionOrderNumericDocValuesField f = new InsertionOrderNumericDocValuesField("ion");
                    for (long v : values) {
                        f.addValue(v);
                    }
                    d.add(f);
                    writer.addDocument(d);
                }
            }
            assertRoundtrip(dir, docs);
        }
    }

    private void assertRoundtrip(Directory dir, List<long[]> expectedDocs) throws IOException {
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            int totalDocs = 0;
            for (LeafReaderContext leaf : reader.leaves()) {
                BinaryDocValues bdv = leaf.reader().getBinaryDocValues("ion");
                assertNotNull("expected the field on leaf " + leaf, bdv);
                InsertionOrderNumericDocValues view = InsertionOrderNumericDocValues.wrap(bdv);
                for (int doc = view.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = view.nextDoc()) {
                    long[] expected = expectedDocs.get(totalDocs++);
                    assertEquals("docValueCount for doc " + doc, expected.length, view.docValueCount());
                    long[] actual = new long[view.docValueCount()];
                    for (int i = 0; i < actual.length; i++) {
                        actual[i] = view.nextValue();
                    }
                    assertArrayEquals("insertion order mismatch for doc " + doc, expected, actual);
                }
            }
            assertEquals("total doc count", expectedDocs.size(), totalDocs);
        }
    }

    public void testWrapperEncodingRoundtrip() {
        long[] in = { 0L, 1L, -1L, 100L, -100L, Long.MIN_VALUE, Long.MAX_VALUE, 0L };
        InsertionOrderNumericDocValuesField f = new InsertionOrderNumericDocValuesField("x");
        for (long v : in) {
            f.addValue(v);
        }
        long[] out = new long[in.length];
        int decoded = InsertionOrderNumericCodec.decodeLongs(f.binaryValue(), out, 0);
        assertEquals(in.length, decoded);
        assertArrayEquals(in, Arrays.copyOf(out, decoded));
    }
}
