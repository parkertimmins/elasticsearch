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
import org.apache.lucene.util.BitUtil;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.io.stream.ByteArrayStreamInput;
import org.elasticsearch.common.io.stream.StreamInput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PatternedTextDocValues extends BinaryDocValues {
    private final SortedSetDocValues templateDocValues;
    private final SortedSetDocValues argsDocValues;
    private final SortedSetDocValues argsInfoDocValues;
    private final SortedSetDocValues argsOffsetDocValues;

    PatternedTextDocValues(SortedSetDocValues templateDocValues, SortedSetDocValues argsDocValues, SortedSetDocValues argsInfoDocValues, SortedSetDocValues argsOffsetDocValues) {
        this.templateDocValues = templateDocValues;
        this.argsDocValues = argsDocValues;
        this.argsInfoDocValues = argsInfoDocValues;
        this.argsOffsetDocValues = argsOffsetDocValues;
    }

    static PatternedTextDocValues from(LeafReader leafReader, String templateFieldName, String argsFieldName, String argsInfoFieldName, String argsOffsetFieldName)
        throws IOException {
        SortedSetDocValues templateDocValues = DocValues.getSortedSet(leafReader, templateFieldName);
        if (templateDocValues.getValueCount() == 0) {
            return null;
        }

        SortedSetDocValues argsDocValues = DocValues.getSortedSet(leafReader, argsFieldName);
        SortedSetDocValues argsInfoDocValues = DocValues.getSortedSet(leafReader, argsInfoFieldName);
        SortedSetDocValues argsOffsetDocValues = DocValues.getSortedSet(leafReader, argsOffsetFieldName);
        return new PatternedTextDocValues(templateDocValues, argsDocValues, argsInfoDocValues, argsOffsetDocValues);
    }

    private String getNextStringValue() throws IOException {
        assert templateDocValues.docValueCount() == 1;
        String template = templateDocValues.lookupOrd(templateDocValues.nextOrd()).utf8ToString();
        List<Arg.Info> argsInfo = Arg.decodeInfo(argsInfoDocValues.lookupOrd(argsInfoDocValues.nextOrd()).utf8ToString());

        if (argsInfo.isEmpty() == false) {
            assert argsOffsetDocValues.docValueCount() == 1;
            assert argsDocValues.docValueCount() >= 1;

            List<String> argsDedupSorted = new ArrayList<>(argsDocValues.docValueCount());
            for (int i = 0; i < argsDocValues.docValueCount(); i++) {
                argsDedupSorted.add(argsDocValues.lookupOrd(argsDocValues.nextOrd()).utf8ToString());
            }

            ByteArrayStreamInput input = new ByteArrayStreamInput();
            var encodedValue = argsOffsetDocValues.lookupOrd(argsOffsetDocValues.nextOrd());
            input.reset(encodedValue.bytes, encodedValue.offset, encodedValue.length);
            int[] offsetToOrds = parseOffsetArray(argsInfo.size(), input);

            String[] args = new String[argsInfo.size()];
            for (int offset = 0; offset < offsetToOrds.length; offset++) {
                int ord = offsetToOrds[offset];
                args[offset] = argsDedupSorted.get(ord);
            }

            return PatternedTextValueProcessor.merge(template, args, argsInfo);
        } else {
            return template;
        }
    }

    static int[] parseOffsetArray(int len, StreamInput in) throws IOException {
        int[] offsetToOrd = new int[len];
        for (int i = 0; i < offsetToOrd.length; i++) {
            offsetToOrd[i] = in.readVInt();
        }
        return offsetToOrd;
    }

    @Override
    public BytesRef binaryValue() throws IOException {
        return new BytesRef(getNextStringValue());
    }

    @Override
    public boolean advanceExact(int i) throws IOException {
        argsDocValues.advanceExact(i);
        argsInfoDocValues.advanceExact(i);
        argsOffsetDocValues.advanceExact(i);
        // If template has a value, then message has a value. We don't have to check args here, since there may not be args for the doc
        return templateDocValues.advanceExact(i);
    }

    @Override
    public int docID() {
        return templateDocValues.docID();
    }

    @Override
    public int nextDoc() throws IOException {
        int templateNext = templateDocValues.nextDoc();
        var argsInfoAdvance = argsInfoDocValues.advance(templateNext);
        var argsAdvance = argsDocValues.advance(templateNext);
        var argsOffsetAdvance = argsOffsetDocValues.advance(templateNext);
        // args and offset do not always have a value
        assert argsAdvance >= templateNext;
        assert argsOffsetAdvance>= templateNext;
        assert argsInfoAdvance == templateNext;
        return templateNext;
    }

    @Override
    public int advance(int i) throws IOException {
        int templateAdvance = templateDocValues.advance(i);
        var argsInfoAdvance = argsInfoDocValues.advance(templateAdvance);
        var argsAdvance = argsDocValues.advance(templateAdvance);
        var argsOffsetAdvance = argsOffsetDocValues.advance(templateAdvance);
        assert argsAdvance >= templateAdvance;
        assert argsOffsetAdvance >= templateAdvance;
        assert argsInfoAdvance == templateAdvance;
        return templateAdvance;
    }

    @Override
    public long cost() {
        return templateDocValues.cost() + argsDocValues.cost() + argsInfoDocValues.cost() + argsOffsetDocValues.cost();
    }
}
