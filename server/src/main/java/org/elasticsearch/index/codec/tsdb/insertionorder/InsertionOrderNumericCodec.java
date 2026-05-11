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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Map;

/**
 * Shared encoding helpers for the insertion-order numeric prototype.
 *
 * <p>At the API boundary the field looks like {@link org.apache.lucene.index.BinaryDocValues}
 * — the writer wrapper packs longs into a {@link BytesRef} and the reader wrapper unpacks
 * them. The TSDB codec recognises the field via a {@link org.apache.lucene.index.FieldInfo}
 * attribute and replaces the byte-block path with the numeric path internally.
 *
 * <p>The BytesRef payload is a transient serialization shape — it never reaches disk; the
 * codec immediately re-encodes the longs through the numeric pipeline (bit-packing, delta,
 * jump table). So we optimize this format for decode/encode speed, not compactness: each
 * value is 8 bytes little-endian, read/written via a single {@link VarHandle} access.
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

    private static final VarHandle LE_LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private InsertionOrderNumericCodec() {}

    /**
     * Whether the given field is encoded with the insertion-order numeric scheme.
     *
     * @param attributes the field info attributes (may be {@code null})
     */
    public static boolean isInsertionOrderNumeric(Map<String, String> attributes) {
        return attributes != null && ATTRIBUTE_VALUE.equals(attributes.get(ATTRIBUTE_KEY));
    }

    /**
     * Write {@code count} longs as little-endian 8-byte values into {@code dst}, starting at
     * {@code dstOffset}.
     *
     * @return new offset after writing
     */
    public static int encodeLongs(long[] values, int count, byte[] dst, int dstOffset) {
        for (int i = 0; i < count; i++) {
            LE_LONG.set(dst, dstOffset, values[i]);
            dstOffset += Long.BYTES;
        }
        return dstOffset;
    }

    /** Write a single long into {@code dst} at {@code offset} (little-endian, 8 bytes). */
    public static void writeLongAt(byte[] dst, int offset, long value) {
        LE_LONG.set(dst, offset, value);
    }

    /** Exact bytes required to encode {@code count} longs. */
    public static int encodedLength(int count) {
        return count * Long.BYTES;
    }

    /**
     * Decode the BytesRef into longs, appending into {@code dst} starting at {@code dstOffset}.
     * The caller is responsible for sizing {@code dst}.
     *
     * @return number of longs decoded
     */
    public static int decodeLongs(BytesRef src, long[] dst, int dstOffset) {
        int count = countValues(src);
        int pos = src.offset;
        for (int i = 0; i < count; i++) {
            dst[dstOffset + i] = (long) LE_LONG.get(src.bytes, pos);
            pos += Long.BYTES;
        }
        return count;
    }

    /** Number of long values encoded in the given byte range. */
    public static int countValues(BytesRef src) {
        assert src.length % Long.BYTES == 0 : "insertion-order numeric payload not aligned: " + src.length;
        return src.length >>> 3;
    }
}
