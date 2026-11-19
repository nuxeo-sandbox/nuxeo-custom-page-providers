/*
 * (C) Copyright 2025 Hyland (http://hyland.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Michael Vachette
 */
package org.nuxeo.labs.custom.page.providers.opensearch1;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.search.client.opensearch1.OpenSearchQueryTransformer;
import org.nuxeo.ecm.core.search.client.opensearch1.OpenSearchSearchClient;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateDateHistogramParser;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateDateRangeParser;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateHistogramParser;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateParserBase;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateRangeParser;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateTermParser;
import org.nuxeo.ecm.platform.query.api.Aggregate;
import org.nuxeo.ecm.platform.query.api.Bucket;
import org.nuxeo.ecm.platform.query.core.AggregateDateHistogram;
import org.nuxeo.ecm.platform.query.core.AggregateDateRange;
import org.nuxeo.ecm.platform.query.core.AggregateHistogram;
import org.nuxeo.ecm.platform.query.core.AggregateRange;
import org.nuxeo.ecm.platform.query.core.AggregateTerm;
import org.nuxeo.labs.custom.page.providers.vector.VectorSearchEngine;
import org.nuxeo.labs.custom.page.providers.vector.VectorSearchRequest;
import org.nuxeo.labs.custom.page.providers.vector.VectorSearchResponse;
import org.nuxeo.labs.custom.page.providers.vector.VectorSearchResponse.VectorSearchHit;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.opensearch1.OpenSearchClientService;
import org.nuxeo.runtime.opensearch1.client.OpenSearchClient;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.SearchHit;
import org.opensearch.search.aggregations.Aggregation;
import org.opensearch.search.aggregations.bucket.MultiBucketsAggregation;
import org.opensearch.search.aggregations.bucket.filter.Filter;
import org.opensearch.search.builder.SearchSourceBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Vector search with the {@code opensearch1} search client (OpenSearch 1.x, high-level REST client).
 * <p>
 * This class must only be referenced by name (see {@code VectorSearchEngines}): it cannot be loaded when the
 * opensearch1 client is not deployed.
 */
public class OpenSearch1VectorSearchEngine implements VectorSearchEngine {

    private static final Logger log = LogManager.getLogger(OpenSearch1VectorSearchEngine.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public VectorSearchResponse search(VectorSearchRequest request) {
        // Get the OpenSearchSearchClient to obtain technical index name mappings
        if (!(request.searchClient() instanceof OpenSearchSearchClient osSearchClient)) {
            throw new NuxeoException("Vector search requires an OpenSearch 1 search client. Got: "
                    + (request.searchClient() != null ? request.searchClient().getClass().getName() : "null"));
        }
        Map<String, String> technicalIndexes = osSearchClient.getTechnicalIndexes();

        // Use OpenSearchQueryTransformer to convert NXQL + aggregates to a proper OpenSearch SearchRequest
        OpenSearchQueryTransformer transformer = new OpenSearchQueryTransformer(technicalIndexes, Map.of());
        SearchRequest osRequest = transformer.apply(request.searchQuery());

        // Aggregates are already in the source from the transformer -- no changes needed
        applyKnnQuery(osRequest.source(), request.vectorField(), request.vector(), request.k(), request.minScore());

        // Get the raw OpenSearch client.
        OpenSearchClientService osClientService = Framework.getService(OpenSearchClientService.class);
        if (osClientService == null) {
            throw new NuxeoException(
                    "OpenSearchClientService is not available. Vector search requires an OpenSearch backend.");
        }
        OpenSearchClient client = osClientService.getClient(request.clientId());
        if (client == null) {
            throw new NuxeoException(String.format(
                    "OpenSearch client '%s' is not available. "
                            + "Check your OpenSearch configuration or set the 'opensearch_client_id' named parameter.",
                    request.clientId()));
        }

        // Execute the search
        SearchResponse response = client.search(osRequest);

        List<VectorSearchHit> hits = new ArrayList<>();
        for (SearchHit hit : response.getHits().getHits()) {
            hits.add(new VectorSearchHit(hit.getId(), hit.getScore()));
        }

        // Parse aggregate results from the response
        List<Aggregate<? extends Bucket>> aggregates = request.searchQuery().getAggregates();
        boolean aggregatesParsed = response.getAggregations() != null && aggregates != null && !aggregates.isEmpty();
        if (aggregatesParsed) {
            for (Aggregate<? extends Bucket> agg : aggregates) {
                parseAggregate(agg, response);
            }
        }

        return new VectorSearchResponse(hits, aggregatesParsed);
    }

    /**
     * Swaps the queries of the request built from the NXQL query: the knn query becomes the main scoring query, and
     * the NXQL query joins the existing post-filter.
     */
    static void applyKnnQuery(SearchSourceBuilder source, String vectorField, float[] vector, int k, float minScore) {
        QueryBuilder nxqlQuery = source.query();
        QueryBuilder existingPostFilter = source.postFilter();

        // There is no knn query builder in the OpenSearch 1 client: wrap the raw JSON
        source.query(QueryBuilders.wrapperQuery(knnQueryJson(vectorField, vector, k)));
        source.minScore(minScore);

        BoolQueryBuilder postFilter = QueryBuilders.boolQuery();
        if (nxqlQuery != null) {
            postFilter.must(nxqlQuery);
        }
        if (existingPostFilter != null) {
            postFilter.must(existingPostFilter);
        }
        if (postFilter.hasClauses()) {
            source.postFilter(postFilter);
        }
    }

    /**
     * @return <code>{ "knn": { "&lt;vectorField&gt;": { "vector": [...], "k": k } } }</code>
     */
    static String knnQueryJson(String vectorField, float[] vector, int k) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode field = root.putObject("knn").putObject(vectorField);
        ArrayNode values = field.putArray("vector");
        for (float value : vector) {
            values.add(value);
        }
        field.put("k", k);
        return root.toString();
    }

    /**
     * Parses an aggregate result from the OpenSearch response. Each aggregate is wrapped in a Filter aggregation by the
     * transformer.
     */
    @SuppressWarnings("unchecked")
    protected void parseAggregate(Aggregate<? extends Bucket> agg, SearchResponse response) {
        String filterId = AggregateParserBase.getFilterId(agg);
        Filter filter = response.getAggregations().get(filterId);
        if (filter == null) {
            log.debug("No filter aggregation found for aggregate '{}' (filterId='{}')", agg.getId(), filterId);
            return;
        }
        Aggregation aggregation = filter.getAggregations().get(agg.getId());
        if (aggregation == null) {
            log.debug("No aggregation found for aggregate '{}'", agg.getId());
            return;
        }

        if (agg instanceof AggregateTerm a && aggregation instanceof MultiBucketsAggregation mba) {
            a.setBuckets(AggregateTermParser.parseBuckets(mba.getBuckets()));
        } else if (agg instanceof AggregateRange a && aggregation instanceof MultiBucketsAggregation mba) {
            a.setBuckets(AggregateRangeParser.parseBuckets(mba.getBuckets()));
        } else if (agg instanceof AggregateDateRange a && aggregation instanceof MultiBucketsAggregation mba) {
            a.setBuckets(AggregateDateRangeParser.parseBuckets(mba.getBuckets()));
        } else if (agg instanceof AggregateHistogram a && aggregation instanceof MultiBucketsAggregation mba) {
            a.setBuckets(AggregateHistogramParser.parseBuckets(mba.getBuckets(), a));
        } else if (agg instanceof AggregateDateHistogram a && aggregation instanceof MultiBucketsAggregation mba) {
            a.setBuckets(AggregateDateHistogramParser.parseBuckets(mba.getBuckets(), a));
        } else {
            log.warn("Unsupported aggregate type for parsing: {} ({})", agg.getId(), agg.getClass().getSimpleName());
        }
    }

}
