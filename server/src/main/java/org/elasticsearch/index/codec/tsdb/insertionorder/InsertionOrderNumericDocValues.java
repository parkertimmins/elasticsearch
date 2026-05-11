/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.tsdb.insertionorder;

import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.ArrayUtil;

import java.io.IOException;

/**
 * Reader-side wrapper that exposes an insertion-order multi-valued long view over a
 * {@link BinaryDocValues} source produced by the insertion-order numeric encoding. Shaped
 * like {@link org.apache.lucene.index.SortedNumericDocValues} but the per-document values
 * are returned in insertion order (duplicates allowed, no sort).
 *
 * <p>The current prototype materialises each doc's values via the BytesRef interface. A
 * future revision can bypass the BytesRef round-trip by unwrapping a direct numeric reader
 * when the underlying codec exposes one.
 */
public final class InsertionOrderNumericDocValues extends DocIdSetIterator {

    private final BinaryDocValues source;
    private long[] values = new long[4];
    private int count;
    private int next;

    public static InsertionOrderNumericDocValues wrap(BinaryDocValues source) {
        return new InsertionOrderNumericDocValues(source);
    }

    private InsertionOrderNumericDocValues(BinaryDocValues source) {
        this.source = source;
    }

    /** Number of values for the document the iterator is currently positioned on. */
    public int docValueCount() {
        return count;
    }

    /** Returns the next value for the current document. Must be called {@link #docValueCount()} times. */
    public long nextValue() {
        return values[next++];
    }

    @Override
    public int docID() {
        return source.docID();
    }

    @Override
    public int nextDoc() throws IOException {
        int doc = source.nextDoc();
        if (doc != NO_MORE_DOCS) {
            loadCurrent();
        }
        return doc;
    }

    @Override
    public int advance(int target) throws IOException {
        int doc = source.advance(target);
        if (doc != NO_MORE_DOCS) {
            loadCurrent();
        }
        return doc;
    }

    public boolean advanceExact(int target) throws IOException {
        if (source.advanceExact(target)) {
            loadCurrent();
            return true;
        }
        return false;
    }

    @Override
    public long cost() {
        return source.cost();
    }

    private void loadCurrent() throws IOException {
        var bytes = source.binaryValue();
        int expected = InsertionOrderNumericCodec.countValues(bytes);
        values = ArrayUtil.grow(values, expected);
        count = InsertionOrderNumericCodec.decodeLongs(bytes, values, 0);
        assert count == expected;
        next = 0;
    }
}
