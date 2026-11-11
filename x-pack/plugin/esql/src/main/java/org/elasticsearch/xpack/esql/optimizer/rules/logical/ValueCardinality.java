/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.logical;

import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.parser.ExpressionBuilder;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.Limit;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.elasticsearch.xpack.esql.plan.logical.Project;
import org.elasticsearch.xpack.esql.plan.logical.TopN;

import java.util.List;

/**
 * Whether an attribute is <em>definitely</em> single-valued: on every row it holds one value or {@code null}, never a list.
 * <p>
 * The data type does not say so, because every ES|QL type may hold a list. A rewrite that is only valid for single-valued inputs
 * needs a proof, and {@link #of} gives it. The answer is {@link #SINGLE} only when the rules below prove it. Everything else is
 * {@link #MAYBE_MULTI}, which means "unknown", not "multi-valued".
 * <p>
 * The proof follows the attribute from {@code plan} down to the {@code FROM} that produced it. It passes through {@code EVAL} and
 * {@code RENAME}, which only copy a column, and through {@code WHERE}, {@code SORT} and {@code LIMIT}. It succeeds if the source field
 * has {@link org.elasticsearch.xpack.esql.core.type.EsField#isStaticallySingleValued()}, which is known from the mapping and holds on
 * every shard. Any other node, or any other expression such as a function call, gives {@code MAYBE_MULTI}.
 */
enum ValueCardinality {
    SINGLE,
    MAYBE_MULTI;

    /**
     * Longest chain of aliases and plan nodes to follow. A longer chain answers {@link #MAYBE_MULTI}, which keeps the recursion
     * within the stack for queries that spell out thousands of chained aliases.
     */
    private static final int MAX_DEPTH = ExpressionBuilder.MAX_EXPRESSION_DEPTH;

    /**
     * Whether {@code expression} is single-valued at {@code plan}: the node of the pipeline that the expression is evaluated at. The
     * columns that the expression reads must be outputs of that node, so for an expression that belongs to a node, such as a grouping
     * of a {@code STATS}, pass the child of the node.
     */
    static ValueCardinality of(Expression expression, LogicalPlan plan) {
        return ofExpression(expression, plan, 0);
    }

    private static ValueCardinality ofExpression(Expression expression, LogicalPlan plan, int depth) {
        if (depth > MAX_DEPTH) {
            return MAYBE_MULTI;
        }
        if (expression instanceof Alias alias) {
            return ofExpression(alias.child(), plan, depth + 1);
        }
        if (expression instanceof Attribute attribute) {
            return ofAttribute(attribute, plan, depth + 1);
        }
        // Any function is unknown. Even a function of a single value can return a list, for example SPLIT.
        return MAYBE_MULTI;
    }

    /**
     * Follows {@code attribute} down {@code plan} to the node that produced it.
     */
    private static ValueCardinality ofAttribute(Attribute attribute, LogicalPlan plan, int depth) {
        if (depth > MAX_DEPTH) {
            return MAYBE_MULTI;
        }
        if (plan instanceof Eval eval) {
            Alias definition = definitionOf(attribute, eval.fields());
            if (definition != null) {
                // Resolve against this EVAL, since the definition may read an earlier field of it.
                // Example: FROM test | EVAL a = languages, k = a | STATS BY k
                return ofExpression(definition.child(), eval, depth + 1);
            }
            return ofAttribute(attribute, eval.child(), depth + 1);
        }
        if (plan instanceof Project project) {
            for (NamedExpression projection : project.projections()) {
                if (projection.id().equals(attribute.id())) {
                    if (projection instanceof Alias alias) {
                        // A rename. Projections do not read each other, so resolve it against the child.
                        return ofExpression(alias.child(), project.child(), depth + 1);
                    }
                    return ofAttribute(attribute, project.child(), depth + 1);
                }
            }
            return MAYBE_MULTI;
        }
        if (plan instanceof EsRelation) {
            return attribute instanceof FieldAttribute field && field.field().isStaticallySingleValued() ? SINGLE : MAYBE_MULTI;
        }
        if (plan instanceof Filter || plan instanceof Limit || plan instanceof OrderBy || plan instanceof TopN) {
            // These keep, drop or reorder rows without changing columns. TopN is what the optimizer makes of SORT and LIMIT.
            return ofAttribute(attribute, plan.children().get(0), depth + 1);
        }
        // Any other node, such as STATS, MV_EXPAND or LOOKUP JOIN, may rewrite columns, so nothing is known.
        return MAYBE_MULTI;
    }

    private static Alias definitionOf(Attribute attribute, List<Alias> fields) {
        for (Alias field : fields) {
            if (field.id().equals(attribute.id())) {
                return field;
            }
        }
        return null;
    }
}
