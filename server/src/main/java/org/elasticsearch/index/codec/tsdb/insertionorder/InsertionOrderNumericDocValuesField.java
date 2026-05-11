/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.tsdb.insertionorder;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.InvertableType;
import org.apache.lucene.document.StoredValue;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.IndexableFieldType;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;

import java.io.Reader;

/**
 * Indexable field for the insertion-order numeric prototype.
 *
 * <p>From Lucene's point of view this is a {@code BinaryDocValues} field — but the
 * accompanying {@link FieldType} carries an attribute that tells the TSDB codec to swap
 * the byte-block binary encoding for the numeric encoding pipeline. The result on disk is
 * a tightly packed long stream (delta, bit-packed, jump-table) plus a per-doc value-count
 * address table, identical to {@code SortedNumericDocValues} except that values within a
 * document are stored in insertion order rather than ascending order, and duplicates are
 * preserved.
 */
public final class InsertionOrderNumericDocValuesField implements IndexableField {

    private static final FieldType TYPE;
    static {
        FieldType ft = new FieldType();
        ft.setDocValuesType(DocValuesType.BINARY);
        ft.setOmitNorms(true);
        ft.putAttribute(InsertionOrderNumericCodec.ATTRIBUTE_KEY, InsertionOrderNumericCodec.ATTRIBUTE_VALUE);
        ft.freeze();
        TYPE = ft;
    }

    private final String name;
    private long[] values = new long[4];
    private int count = 0;
    private BytesRef encoded;

    public InsertionOrderNumericDocValuesField(String name) {
        this.name = name;
    }

    /** Append a value in insertion order. */
    public void addValue(long value) {
        values = ArrayUtil.grow(values, count + 1);
        values[count++] = value;
        encoded = null;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public IndexableFieldType fieldType() {
        return TYPE;
    }

    @Override
    public BytesRef binaryValue() {
        if (encoded == null) {
            byte[] dst = new byte[InsertionOrderNumericCodec.encodedLength(count)];
            InsertionOrderNumericCodec.encodeLongs(values, count, dst, 0);
            encoded = new BytesRef(dst, 0, dst.length);
        }
        return encoded;
    }

    @Override
    public String stringValue() {
        return null;
    }

    @Override
    public Reader readerValue() {
        return null;
    }

    @Override
    public Number numericValue() {
        return null;
    }

    @Override
    public TokenStream tokenStream(Analyzer analyzer, TokenStream reuse) {
        return null;
    }

    @Override
    public StoredValue storedValue() {
        return null;
    }

    @Override
    public InvertableType invertableType() {
        return InvertableType.BINARY;
    }
}
