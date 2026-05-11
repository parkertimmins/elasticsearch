/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.tsdb.insertionorder;

import org.apache.lucene.util.BytesRef;

/**
 * Shared encoding helpers for the insertion-order numeric prototype.
 *
 * <p>At the API boundary the field looks like {@link org.apache.lucene.index.BinaryDocValues}
 * — the writer wrapper packs longs into a {@link BytesRef} and the reader wrapper unpacks
 * them. The TSDB codec recognises the field via a {@link org.apache.lucene.index.FieldInfo}
 * attribute and replaces the byte-block path with the numeric path internally.
 */
public final class InsertionOrderNumericCodec {

    /**
     * Attribute key set on the {@link org.apache.lucene.document.FieldType} (and propagated by
     * Lucene's indexing chain into {@code FieldInfo.attributes()}) that signals the TSDB codec
     * to use the numeric encoding pipeline for this binary field.
     */
    public static final String ATTRIBUTE_KEY = "es.tsdb.encoding";

    /** Attribute value identifying the insertion-order numeric encoding. */
    public static final String ATTRIBUTE_VALUE = "insertion_order_numeric";

    private InsertionOrderNumericCodec() {}

    /**
     * Whether the given field is encoded with the insertion-order numeric scheme.
     *
     * @param attributes the field info attributes (may be {@code null})
     */
    public static boolean isInsertionOrderNumeric(java.util.Map<String, String> attributes) {
        return attributes != null && ATTRIBUTE_VALUE.equals(attributes.get(ATTRIBUTE_KEY));
    }

    /**
     * Encode a sequence of longs as zigzag-varlong bytes appended to {@code dst}. No length
     * prefix — the byte stream is terminated by the buffer end so callers can decode by
     * iterating until the read offset reaches the buffer length.
     *
     * @return new offset after appending
     */
    public static int encodeLongs(long[] values, int count, byte[] dst, int dstOffset) {
        for (int i = 0; i < count; i++) {
            dstOffset = writeZigZagVLong(values[i], dst, dstOffset);
        }
        return dstOffset;
    }

    /** Worst-case bytes required to encode {@code count} longs in zigzag varlong form. */
    public static int maxEncodedLength(int count) {
        return count * 10;
    }

    /**
     * Decode the BytesRef into longs, appending into {@code dst} starting at {@code dstOffset}.
     * The caller is responsible for sizing {@code dst}.
     *
     * @return number of longs decoded
     */
    public static int decodeLongs(BytesRef src, long[] dst, int dstOffset) {
        final byte[] bytes = src.bytes;
        final int end = src.offset + src.length;
        int pos = src.offset;
        int count = 0;
        while (pos < end) {
            long shift = 0;
            long encoded = 0;
            while (true) {
                byte b = bytes[pos++];
                encoded |= ((long) (b & 0x7F)) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            dst[dstOffset + count++] = (encoded >>> 1) ^ -(encoded & 1L);
        }
        return count;
    }

    /**
     * Count the number of zigzag-varlong-encoded values in the given byte range without
     * decoding them. Used by the codec when it just needs the per-doc value count.
     */
    public static int countValues(BytesRef src) {
        final byte[] bytes = src.bytes;
        final int end = src.offset + src.length;
        int pos = src.offset;
        int count = 0;
        while (pos < end) {
            if ((bytes[pos++] & 0x80) == 0) {
                count++;
            }
        }
        return count;
    }

    private static int writeZigZagVLong(long value, byte[] dst, int offset) {
        long encoded = (value << 1) ^ (value >> 63);
        while ((encoded & ~0x7FL) != 0L) {
            dst[offset++] = (byte) ((encoded & 0x7F) | 0x80);
            encoded >>>= 7;
        }
        dst[offset++] = (byte) encoded;
        return offset;
    }
}
