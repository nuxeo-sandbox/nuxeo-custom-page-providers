/*
 * (C) Copyright 2026 Hyland (http://hyland.com/) and others.
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
 */
package org.nuxeo.labs.custom.page.providers.opensearch2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.search.client.opensearch2.OpenSearchQueryTransformer;
import org.nuxeo.ecm.core.search.client.opensearch2.OpenSearchSearchClient;
import org.nuxeo.ecm.core.search.client.opensearch2.aggregate.AggregateDateHistogramParser;
import org.nuxeo.ecm.core.search.client.opensearch2.aggregate.AggregateDateRangeParser;
import org.nuxeo.ecm.core.search.client.opensearch2.aggregate.AggregateHistogramParser;
import org.nuxeo.ecm.core.search.client.opensearch2.aggregate.AggregateParserBase;
import org.nuxeo.ecm.core.search.client.opensearch2.aggregate.AggregateRangeParser;
import org.nuxeo.ecm.core.search.client.opensearch2.aggregate.AggregateTermParser;
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
import org.nuxeo.runtime.opensearch2.OpenSearchClientService;
import org.nuxeo.runtime.opensearch2.client.JsonTDoc;
import org.nuxeo.runtime.opensearch2.client.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;

/**
 * Vector search with the {@code opensearch2} search client (nuxeo-opensearch2 addon, opensearch-java client).
 * <p>
 * This class must only be referenced by name (see {@code VectorSearchEngines}): it cannot be loaded when the
 * opensearch2 client is not deployed.
 */
public class OpenSearch2VectorSearchEngine implements VectorSearchEngine {

    private static final Logger log = LogManager.getLogger(OpenSearch2VectorSearchEngine.class);

    @Override
    public VectorSearchResponse search(VectorSearchRequest request) {
        // Get the OpenSearchSearchClient to obtain technical index name mappings
        if (!(request.searchClient() instanceof OpenSearchSearchClient osSearchClient)) {
            throw new NuxeoException("Vector search requires an OpenSearch 2 search client. Got: "
                    + (request.searchClient() != null ? request.searchClient().getClass().getName() : "null"));
        }
        Map<String, String> technicalIndexes = osSearchClient.getTechnicalIndexes();

        // Use OpenSearchQueryTransformer to convert NXQL + aggregates to a proper OpenSearch SearchRequest.
        // Aggregates are already in the request from the transformer -- no changes needed
        OpenSearchQueryTransformer transformer = new OpenSearchQueryTransformer(technicalIndexes, Map.of());
        SearchRequest osRequest = toKnnRequest(transformer.apply(request.searchQuery()), request.vectorField(),
                request.vector(), request.k(), request.minScore());

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
        SearchResponse<JsonTDoc> response = client.search(osRequest);

        List<VectorSearchHit> hits = new ArrayList<>();
        for (Hit<JsonTDoc> hit : response.hits().hits()) {
            Double score = hit.score();
            hits.add(new VectorSearchHit(hit.id(), score == null ? 0f : score.floatValue()));
        }

        // Parse aggregate results from the response
        List<Aggregate<? extends Bucket>> aggregates = request.searchQuery().getAggregates();
        var osAggregations = response.aggregations();
        boolean aggregatesParsed = osAggregations != null && !osAggregations.isEmpty() && aggregates != null
                && !aggregates.isEmpty();
        if (aggregatesParsed) {
            for (Aggregate<? extends Bucket> agg : aggregates) {
                parseAggregate(agg, osAggregations);
            }
        }

        return new VectorSearchResponse(hits, aggregatesParsed);
    }

    /**
     * Swaps the queries of the request built from the NXQL query: the knn query becomes the main scoring query, and
     * the NXQL query joins the existing post-filter.
     * <p>
     * Requests are immutable in the opensearch-java client: this returns a modified copy.
     */
    static SearchRequest toKnnRequest(SearchRequest nxqlRequest, String vectorField, float[] vector, int k,
            float minScore) {
        List<Query> postFilters = new ArrayList<>();
        if (nxqlRequest.query() != null) {
            postFilters.add(nxqlRequest.query());
        }
        if (nxqlRequest.postFilter() != null) {
            postFilters.add(nxqlRequest.postFilter());
        }

        SearchRequest.Builder builder = nxqlRequest.toBuilder();
        builder.query(q -> q.knn(knn -> knn.field(vectorField).vector(vector).k(k)));
        builder.minScore((double) minScore);
        if (!postFilters.isEmpty()) {
            builder.postFilter(q -> q.bool(b -> b.must(postFilters)));
        }
        return builder.build();
    }

    /**
     * Parses an aggregate result from the OpenSearch response. Each aggregate is wrapped in a Filter aggregation by the
     * transformer.
     */
    protected void parseAggregate(Aggregate<? extends Bucket> agg,
            Map<String, org.opensearch.client.opensearch._types.aggregations.Aggregate> osAggregations) {
        String filterId = AggregateParserBase.getFilterId(agg);
        var osAggregate = osAggregations.get(filterId);
        if (osAggregate == null) {
            log.debug("No filter aggregation found for aggregate '{}' (filterId='{}')", agg.getId(), filterId);
            return;
        }
        if (osAggregate.isFilter()) {
            osAggregate = osAggregate.filter().aggregations().get(agg.getId());
            if (osAggregate == null) {
                log.debug("No aggregation found for aggregate '{}'", agg.getId());
                return;
            }
        }

        if (agg instanceof AggregateTerm a && osAggregate.isSterms()) {
            a.setBuckets(AggregateTermParser.parseBuckets(osAggregate.sterms().buckets()));
        } else if (agg instanceof AggregateTerm a && osAggregate.isLterms()) {
            a.setBuckets(AggregateTermParser.parseLongBuckets(osAggregate.lterms().buckets()));
        } else if (agg instanceof AggregateRange a && osAggregate.isRange()) {
            a.setBuckets(AggregateRangeParser.parseBuckets(osAggregate.range().buckets()));
        } else if (agg instanceof AggregateDateRange a && osAggregate.isDateRange()) {
            a.setBuckets(AggregateDateRangeParser.parseBuckets(osAggregate.dateRange().buckets()));
        } else if (agg instanceof AggregateHistogram a && osAggregate.isHistogram()) {
            a.setBuckets(AggregateHistogramParser.parseBuckets(osAggregate.histogram().buckets(), a));
        } else if (agg instanceof AggregateDateHistogram a && osAggregate.isDateHistogram()) {
            a.setBuckets(AggregateDateHistogramParser.parseBuckets(osAggregate.dateHistogram().buckets()));
        } else {
            log.warn("Unsupported aggregate type for parsing: {} ({})", agg.getId(), agg.getClass().getSimpleName());
        }
    }

}
