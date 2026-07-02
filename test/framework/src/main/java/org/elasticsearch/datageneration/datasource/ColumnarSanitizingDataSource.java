/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.datageneration.datasource;

import org.elasticsearch.index.mapper.Mapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.test.ESTestCase.randomBoolean;
import static org.elasticsearch.test.ESTestCase.randomFrom;

/**
 * A {@link DataSource} for strict-columnar indices. {@link DataSourceHandler}s stay fully index-mode agnostic and are free to produce
 * parameters that are only valid outside strict-columnar mode (e.g. {@code store: true}); this class corrects their output after the
 * fact, and additionally exercises the {@code doc_values} object form ({@code multi_value}), which only strict-columnar indices accept.
 * Only ever construct this in place of the base {@link DataSource} when the target index mode is strict-columnar.
 */
public class ColumnarSanitizingDataSource extends DataSource {
    /**
     * Field types whose mappers accept the object form of {@code doc_values} (the {@code multi_value} sub-parameter).
     */
    public static final Set<String> EXTENDED_DOC_VALUES_FIELD_TYPES = Set.of(
        "keyword",
        "text",
        "match_only_text",
        "long",
        "integer",
        "short",
        "byte",
        "double",
        "float",
        "half_float",
        "unsigned_long",
        "scaled_float",
        "boolean",
        "date",
        "ip"
    );

    public ColumnarSanitizingDataSource(Collection<DataSourceHandler> additionalHandlers) {
        super(additionalHandlers);
    }

    @Override
    public <T extends DataSourceResponse> T get(DataSourceRequest<T> request) {
        return sanitize(request, super.get(request));
    }

    @SuppressWarnings("unchecked") // response was just matched to the same concrete type T is erased from
    private <T extends DataSourceResponse> T sanitize(DataSourceRequest<T> request, T response) {
        if (response instanceof DataSourceResponse.LeafMappingParametersGenerator leaf) {
            String fieldType = ((DataSourceRequest.LeafMappingParametersGenerator) request).fieldType();
            return (T) new DataSourceResponse.LeafMappingParametersGenerator(
                () -> sanitizeLeafMapping(new HashMap<>(leaf.mappingGenerator().get()), fieldType)
            );
        }
        if (response instanceof DataSourceResponse.ObjectMappingParametersGenerator object) {
            boolean isRoot = ((DataSourceRequest.ObjectMappingParametersGenerator) request).isRoot();
            return (T) new DataSourceResponse.ObjectMappingParametersGenerator(
                () -> sanitizeObjectMapping(new HashMap<>(object.mappingGenerator().get()), isRoot)
            );
        }
        return response;
    }

    private static Map<String, Object> sanitizeLeafMapping(Map<String, Object> mapping, String fieldType) {
        // Every field on a strict-columnar index must be reconstructable from its own doc values.
        if (Boolean.FALSE.equals(mapping.get("doc_values"))) {
            mapping.put("doc_values", true);
        }
        // Occasionally exercise the doc_values object form, which only strict-columnar indices accept. Only multi_value: true is
        // generated here; multi_value: false is exercised by a coordinated handler, since it also requires capping array sizes.
        if (Boolean.TRUE.equals(mapping.get("doc_values")) && EXTENDED_DOC_VALUES_FIELD_TYPES.contains(fieldType) && randomBoolean()) {
            mapping.put("doc_values", Map.of("multi_value", true));
        }
        // store, synthetic_source_keep and copy_to are not allowed on fields in strict-columnar mode.
        if (Boolean.TRUE.equals(mapping.get("store"))) {
            mapping.put("store", false);
        }
        mapping.remove(Mapper.SYNTHETIC_SOURCE_KEEP_PARAM);
        mapping.remove("copy_to");
        return mapping;
    }

    private static Map<String, Object> sanitizeObjectMapping(Map<String, Object> mapping, boolean isRoot) {
        // subobjects and synthetic_source_keep are not allowed on objects in strict-columnar mode.
        mapping.remove("subobjects");
        mapping.remove(Mapper.SYNTHETIC_SOURCE_KEEP_PARAM);
        // dynamic:runtime is not supported in strict-columnar mode.
        if ("runtime".equals(mapping.get("dynamic"))) {
            mapping.put("dynamic", randomFrom("true", "false", "strict"));
        }
        // enabled:false is not allowed on the root object in strict-columnar mode.
        if (isRoot && "false".equals(mapping.get("enabled"))) {
            mapping.put("enabled", "true");
        }
        return mapping;
    }
}
