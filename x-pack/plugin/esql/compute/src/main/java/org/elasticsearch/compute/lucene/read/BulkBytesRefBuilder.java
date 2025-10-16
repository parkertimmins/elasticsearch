/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.lucene.read;

import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.util.BytesRefArray;
import org.elasticsearch.common.util.LongArray;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.index.mapper.BlockLoader;

import java.io.IOException;
import java.util.List;

public class BulkBytesRefBuilder implements BlockLoader.BulkBytesRefBuilder {

    private final int count;
    private final BlockFactory blockFactory;

    private BytesRefArray bytesRefArray;

    public BulkBytesRefBuilder(int count, BlockFactory blockFactory) {
        this.count = count;
        this.blockFactory = blockFactory;
    }

    @Override
    public BlockLoader.BulkBytesRefBuilder appendBytesRefs(List<byte[]> arrays, long[] offsets, int[] arrayLookup) throws IOException {
        var arrayStarts = new int[arrays.size()];
        arrayStarts[0] = 0;
        for (int i = 1; i < arrays.size(); i++) {
            arrayStarts[i] = arrayStarts[i - 1] + arrays.get(i - 1).length;
        }

        var values = blockFactory.bigArrays().newByteMultipleArrayWrapper(arrays, arrayLookup.length);
        bytesRefArray = new BytesRefArray(new LongArrayWrapper(offsets), values, count, blockFactory.bigArrays(), arrayLookup, arrayStarts);
        return this;
    }

    @Override
    public BlockLoader.Block build() {
        return blockFactory.newBytesRefArrayVector(bytesRefArray, count).asBlock();
    }

    @Override
    public BlockLoader.Builder appendNull() {
        throw new UnsupportedOperationException();
    }

    @Override
    public BlockLoader.Builder beginPositionEntry() {
        throw new UnsupportedOperationException();
    }

    @Override
    public BlockLoader.Builder endPositionEntry() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}


    static class LongArrayWrapper implements LongArray {

        final long[] values;

        LongArrayWrapper(long[] values) {
            this.values = values;
        }

        @Override
        public long get(long index) {
            return values[(int) index];
        }

        @Override
        public long getAndSet(long index, long value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void set(long index, long value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long increment(long index, long inc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void fill(long fromIndex, long toIndex, long value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void fillWith(StreamInput in) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void set(long index, byte[] buf, int offset, int len) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public long size() {
            return values.length;
        }

        @Override
        public long ramBytesUsed() {
            return RamUsageEstimator.NUM_BYTES_ARRAY_HEADER + (long) values.length * Long.BYTES;
        }

        @Override
        public void close() {}
    }

}
