/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.logical;

import org.elasticsearch.index.IndexMode;
import org.elasticsearch.xpack.esql.TestAnalyzer;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.core.type.KeywordEsField;
import org.elasticsearch.xpack.esql.index.EsIndex;
import org.elasticsearch.xpack.esql.index.IndexProperties;
import org.elasticsearch.xpack.esql.optimizer.AbstractLogicalPlanOptimizerTests;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.TopN;

import java.util.Map;
import java.util.Set;

import static org.elasticsearch.xpack.esql.EsqlTestUtils.loadMapping;
import static org.elasticsearch.xpack.esql.optimizer.rules.logical.ValueCardinality.MAYBE_MULTI;
import static org.elasticsearch.xpack.esql.optimizer.rules.logical.ValueCardinality.SINGLE;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Tests {@link ValueCardinality} on analyzed (not optimized) plans, so the nodes the proof walks through are the ones the user wrote.
 * Every query ends in a {@code STATS} and asks about one of its grouping keys, as seen in the aggregate's child.
 */
public class ValueCardinalityTests extends AbstractLogicalPlanOptimizerTests {

    private static final int DEEP_ALIASES = 10_000;

    public ValueCardinalityTests(VersionMode versionMode) {
        super(versionMode);
    }

    public void testFieldFlaggedByTheMappingIsSingle() {
        String query = "FROM test | STATS c = COUNT(*) BY languages";
        assertThat(cardinalityOfKey(query, "languages", Set.of("languages")), equalTo(SINGLE));
    }

    public void testFieldNotFlaggedByTheMappingIsUnknown() {
        assertThat(cardinalityOfKey("FROM test | STATS c = COUNT(*) BY languages", "languages"), equalTo(MAYBE_MULTI));
        assertThat(cardinalityOfKey("FROM test | STATS c = COUNT(*) BY salary", "salary", Set.of("languages")), equalTo(MAYBE_MULTI));
    }

    public void testEvalAliasOfFlaggedFieldIsSingle() {
        String query = "FROM test | EVAL k = languages | STATS c = COUNT(*) BY k";
        assertThat(cardinalityOfKey(query, "k", Set.of("languages")), equalTo(SINGLE));
    }

    public void testEvalAliasOfUnflaggedFieldIsUnknown() {
        assertThat(cardinalityOfKey("FROM test | EVAL k = languages | STATS c = COUNT(*) BY k", "k"), equalTo(MAYBE_MULTI));
    }

    public void testFieldDefinedFromEarlierFieldOfSameEval() {
        String query = "FROM test | EVAL a = languages, k = a | STATS c = COUNT(*) BY k";
        assertThat(cardinalityOfKey(query, "k", Set.of("languages")), equalTo(SINGLE));
    }

    public void testRenameOfFlaggedFieldIsSingle() {
        String query = "FROM test | RENAME languages AS k | STATS c = COUNT(*) BY k";
        assertThat(cardinalityOfKey(query, "k", Set.of("languages")), equalTo(SINGLE));
    }

    public void testFilterSortKeepAndLimitKeepTheProof() {
        String query =
            "FROM test | WHERE languages > 1 | KEEP languages, salary | SORT languages | LIMIT 5 | STATS c = COUNT(*) BY languages";
        assertThat(cardinalityOfKey(query, "languages", Set.of("languages")), equalTo(SINGLE));
    }

    /**
     * The optimizer turns {@code SORT} followed by {@code LIMIT} into one {@code TopN}, which keeps the proof like the two nodes it
     * replaces. The other tests run on analyzed plans, so this one runs on an optimized plan, where {@code TopN} is what the walk meets.
     */
    public void testTopNKeepsTheProofOnAnOptimizedPlan() {
        String query = "FROM test | SORT languages | LIMIT 10 | STATS c = COUNT(*) BY languages";
        LogicalPlan optimized = optimize(analyzerWithSingleValuedFields(Set.of("languages")).query(query));
        assertThat("the optimizer made a TopN", optimized.collect(TopN.class), hasSize(1));
        assertThat(cardinalityOfKey(optimized, "languages"), equalTo(SINGLE));
    }

    /**
     * Only a column that is copied is followed. A function is unknown even for a flagged field, because a function of a single value
     * can still return a list, for example {@code SPLIT}.
     */
    public void testFunctionsAreUnknown() {
        String query = "FROM test | EVAL k = languages + 1 | STATS c = COUNT(*) BY k";
        assertThat(cardinalityOfKey(query, "k", Set.of("languages")), equalTo(MAYBE_MULTI));
        query = "FROM test | EVAL k = MV_MAX(languages) | STATS c = COUNT(*) BY k";
        assertThat(cardinalityOfKey(query, "k", Set.of("languages")), equalTo(MAYBE_MULTI));
    }

    /**
     * A node that can rewrite columns stops the proof, even above a flagged field.
     */
    public void testOtherNodesAreUnknown() {
        String mvExpand = "FROM test | MV_EXPAND languages | STATS c = COUNT(*) BY languages";
        assertThat(cardinalityOfKey(mvExpand, "languages", Set.of("languages")), equalTo(MAYBE_MULTI));

        String stats = "FROM test | STATS c = COUNT(*) BY languages | STATS n = COUNT(*) BY languages";
        assertThat(cardinalityOfKey(stats, "languages", Set.of("languages")), equalTo(MAYBE_MULTI));

        String values = "FROM test | STATS v = VALUES(languages) BY first_name | STATS n = COUNT(*) BY v";
        assertThat(cardinalityOfKey(values, "v", Set.of("languages")), equalTo(MAYBE_MULTI));
    }

    /**
     * A chain longer than the parser allows an expression to be nested answers unknown, and does so without recursing to the end
     * of the chain on the test thread's stack.
     */
    public void testVeryLongAliasChainIsUnknownAndDoesNotOverflow() {
        StringBuilder query = new StringBuilder("FROM test | EVAL a0 = languages");
        for (int i = 1; i < DEEP_ALIASES; i++) {
            query.append(", a").append(i).append(" = a").append(i - 1);
        }
        query.append(" | STATS c = COUNT(*) BY a").append(DEEP_ALIASES - 1);
        assertThat(cardinalityOfKey(query.toString(), "a" + (DEEP_ALIASES - 1), Set.of("languages")), equalTo(MAYBE_MULTI));
    }

    /**
     * An analyzer over {@code test}, where the named top level fields carry the single-value guarantee that {@code IndexResolver}
     * gives a field every index guarantees. Only plain {@link EsField} and {@link KeywordEsField} are used by the fields it flags.
     */
    private TestAnalyzer analyzerWithSingleValuedFields(Set<String> singleValuedFields) {
        Map<String, EsField> mapping = loadMapping("mapping-basic.json");
        for (String name : singleValuedFields) {
            mapping.put(name, asSingleValued(mapping.get(name)));
        }
        var index = new EsIndex("test", mapping, Map.of("test", new IndexProperties(IndexMode.STANDARD, 0)), Map.of(), Map.of());
        return analyzerWithEnrichPolicies().addIndex(index);
    }

    private static EsField asSingleValued(EsField field) {
        if (field.getClass() == KeywordEsField.class) {
            KeywordEsField keyword = (KeywordEsField) field;
            return new KeywordEsField(
                keyword.getName(),
                keyword.getProperties(),
                keyword.isAggregatable(),
                keyword.getPrecision(),
                keyword.getNormalized(),
                keyword.isAlias(),
                keyword.getTimeSeriesFieldType(),
                true
            );
        }
        assertThat(field.getClass(), equalTo(EsField.class));
        return new EsField(
            field.getName(),
            field.getDataType(),
            field.getProperties(),
            field.isAggregatable(),
            field.isAlias(),
            field.getTimeSeriesFieldType(),
            field.isPassthrough(),
            true
        );
    }

    private ValueCardinality cardinalityOfKey(String query, String keyName) {
        return cardinalityOfKey(query, keyName, Set.of());
    }

    private ValueCardinality cardinalityOfKey(String query, String keyName, Set<String> singleValuedFields) {
        return cardinalityOfKey(analyzerWithSingleValuedFields(singleValuedFields).query(query), keyName);
    }

    private ValueCardinality cardinalityOfKey(LogicalPlan plan, String keyName) {
        var aggregates = plan.collect(Aggregate.class);
        assertThat(aggregates, hasSize(greaterThanOrEqualTo(1)));
        // The last STATS is the one whose key the query asks about; collect lists the outermost first.
        Aggregate aggregate = aggregates.get(0);
        Attribute key = aggregate.child().output().stream().filter(a -> a.name().equals(keyName)).findFirst().orElseThrow();
        return ValueCardinality.of(key, aggregate.child());
    }
}
