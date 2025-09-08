/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.logsdb.patternedtext;

import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;

public class PatternedTextDocValues extends BinaryDocValues {
    private final SortedSetDocValues valueDocValues;

    PatternedTextDocValues(SortedSetDocValues valueDocValues) {
        this.valueDocValues = valueDocValues;
    }

    static PatternedTextDocValues from(LeafReader leafReader, String valueFieldName)
        throws IOException {
        SortedSetDocValues values = DocValues.getSortedSet(leafReader, valueFieldName);
        if (values.getValueCount() == 0) {
            return null;
        }

        return new PatternedTextDocValues(values);
    }

    private String getNextStringValue() throws IOException {
        assert valueDocValues.docValueCount() == 1;
        return valueDocValues.lookupOrd(valueDocValues.nextOrd()).utf8ToString();
    }

    @Override
    public BytesRef binaryValue() throws IOException {
        return new BytesRef(getNextStringValue());
    }

    @Override
    public boolean advanceExact(int i) throws IOException {
        return valueDocValues.advanceExact(i);
    }

    @Override
    public int docID() {
        return valueDocValues.docID();
    }

    @Override
    public int nextDoc() throws IOException {
        return valueDocValues.nextDoc();
    }

    @Override
    public int advance(int i) throws IOException {
        return valueDocValues.advance(i);
    }

    @Override
    public long cost() {
        return valueDocValues.cost();
    }
}
