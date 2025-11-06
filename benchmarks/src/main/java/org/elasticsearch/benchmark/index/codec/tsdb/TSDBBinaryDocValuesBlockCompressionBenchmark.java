/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.index.codec.tsdb;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.LogByteSizeMergePolicy;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.index.codec.Elasticsearch92Lucene103Codec;
import org.elasticsearch.index.codec.tsdb.BinaryDVCompressionMode;
import org.elasticsearch.index.codec.tsdb.es819.ES819TSDBDocValuesFormat;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.AsyncProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
public class TSDBBinaryDocValuesBlockCompressionBenchmark {

    static {
        LogConfigurator.loadLog4jPlugins();
        LogConfigurator.configureESLogging();
        LogConfigurator.setNodeName("test");
    }

    private static final String BINARY_FIELD = "message";

    public static void main(String[] args) throws RunnerException {
        final Options options = new OptionsBuilder().include(TSDBBinaryDocValuesBlockCompressionBenchmark.class.getSimpleName())
            .addProfiler(AsyncProfiler.class)
            .build();

        new Runner(options).run();
    }

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        // -1 means not compressed
        @Param({"-1", "10", "100", "1000", "10000"})
        private int numDocsInBlock;

        @Param({"1000", "100", "10"})
        private int avgFieldLength;

        @Param({"10000000"}) // 10M
        private int numTotalDocs;

        // 1, 10, 100, 1K, 10K, 100K, 1M, 10M values queries
        @Param({"0.0000001", "0.000001", "0.00001", "0.0001", "0.001", "0.01", "0.1", "1.0"})
        private float selectivity;

        private Directory directory;
        private List<Integer> queries;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            directory = FSDirectory.open(Files.createTempDirectory("temp1-"));
            IndexWriterConfig indexWriterConfig = createIndexWriterConfig(numDocsInBlock);

            int seed = avgFieldLength; // use average field length, so same data is generated. could avoid re-generating if too slow
            var values = generateData(numTotalDocs, avgFieldLength, seed);
            createIndex(directory, indexWriterConfig, values);

            queries = generateDocIdQueries(selectivity, numTotalDocs, seed);
        }
    }

    @Benchmark
    public void benchmark(BenchmarkState state, Blackhole bh) throws IOException {
        doQueries(state.directory, state.queries, bh);
    }

    private static void doQueries(Directory directory, List<Integer> docIdQueries, Blackhole bh) throws IOException {
        try (IndexReader reader = DirectoryReader.open(directory)) {
            List<LeafReaderContext> leaves = reader.leaves();
            assert leaves.size() == 1;
            LeafReaderContext leaf = leaves.get(0);
            BinaryDocValues docValues = leaf.reader().getBinaryDocValues(BINARY_FIELD);

            for (var query : docIdQueries) {
                docValues.advanceExact(query);
                BytesRef bytesRef = docValues.binaryValue();
                bh.consume(bytesRef);
            }
        }
    }

    private static List<Integer> generateDocIdQueries(float selectivity, int numDocs, int seed) {
        if (selectivity < 1.0) {
            int numQueriedDocs = (int) (selectivity * numDocs);
            System.out.println("numQueriedDocs: " + numQueriedDocs);
            Random random = new Random(seed);
            Set<Integer> docIds = new HashSet<>();
            while (docIds.size() < numQueriedDocs) {
                int doc = random.nextInt(numDocs);
                docIds.add(doc);
            }
            List<Integer> docIdsSorted = new ArrayList<>(docIds);
            Collections.sort(docIdsSorted);
            return docIdsSorted;
        } else {
            return IntStream.range(0, numDocs).boxed().toList();
        }
    }

    private static List<String> generateData(int numTotalDocs, int averageLength, int seed) throws IOException {
        final Random random = new Random(seed);
        List<String> values = new ArrayList<>();

        int maxLength = 2 * averageLength;
        for (int i = 0; i < numTotalDocs; i++) {
            int length = random.nextInt(0, maxLength + 1);
            values.add(generateRandomString(length));
        }
        return values;
    }

    static String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    static String generateRandomString(int length) {
        Random random = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(characters.charAt(random.nextInt(characters.length())));
        }
        return sb.toString();
    }

    static void createIndex(Directory directory, IndexWriterConfig iwc, List<String> values)
        throws IOException {
        try (var indexWriter = new IndexWriter(directory, iwc)) {
            for (int i = 0; i < values.size(); i++) {
                final Document doc = new Document();

                doc.add(new BinaryDocValuesField(BINARY_FIELD, new BytesRef(values.get(i))));
                indexWriter.addDocument(doc);

                if (i % 10000 == 0) {
                    indexWriter.commit();
                }
            }

            // force merge down to 1 segment
            indexWriter.forceMerge(1);
        }
    }

    private static IndexWriterConfig createIndexWriterConfig(int numDocsPerBlock) {
        boolean useCompression = numDocsPerBlock > 0;
        var config = new IndexWriterConfig(new StandardAnalyzer());
        config.setMergePolicy(new LogByteSizeMergePolicy());

        final DocValuesFormat docValuesFormat = new ES819TSDBDocValuesFormat(
            useCompression ? BinaryDVCompressionMode.COMPRESSED_ZSTD_LEVEL_1 : BinaryDVCompressionMode.NO_COMPRESS,
            numDocsPerBlock
        );

        config.setCodec(new Elasticsearch92Lucene103Codec() {
            @Override
            public DocValuesFormat getDocValuesFormatForField(String field) {
                return docValuesFormat;
            }
        });
        return config;
    }
}
