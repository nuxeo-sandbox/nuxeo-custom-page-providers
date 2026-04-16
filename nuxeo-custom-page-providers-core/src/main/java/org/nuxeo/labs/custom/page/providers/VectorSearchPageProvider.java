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
package org.nuxeo.labs.custom.page.providers;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.impl.DocumentModelListImpl;
import org.nuxeo.ecm.core.search.SearchIndex;
import org.nuxeo.ecm.core.search.SearchIndexingService;
import org.nuxeo.ecm.core.search.SearchQuery;
import org.nuxeo.ecm.core.search.SearchService;
import org.nuxeo.ecm.core.search.client.opensearch1.OpenSearchQueryTransformer;
import org.nuxeo.ecm.core.search.client.opensearch1.OpenSearchSearchClient;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateParserBase;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateDateHistogramParser;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateDateRangeParser;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateHistogramParser;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateRangeParser;
import org.nuxeo.ecm.core.search.client.opensearch1.aggregate.AggregateTermParser;
import org.nuxeo.ecm.platform.query.api.Aggregate;
import org.nuxeo.ecm.platform.query.api.Bucket;
import org.nuxeo.ecm.platform.query.core.AggregateDateHistogram;
import org.nuxeo.ecm.platform.query.core.AggregateDateRange;
import org.nuxeo.ecm.platform.query.core.AggregateHistogram;
import org.nuxeo.ecm.platform.query.core.AggregateRange;
import org.nuxeo.ecm.platform.query.core.AggregateTerm;
import org.nuxeo.ecm.platform.query.nxql.SearchServicePageProvider;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.opensearch1.OpenSearchClientService;
import org.nuxeo.runtime.opensearch1.client.OpenSearchClient;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.search.aggregations.Aggregation;
import org.opensearch.search.aggregations.bucket.MultiBucketsAggregation;
import org.opensearch.search.aggregations.bucket.filter.Filter;
import org.opensearch.search.builder.SearchSourceBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.nuxeo.ecm.platform.query.api.PageProviderService.NAMED_PARAMETERS;

/**
 * A page provider that performs a vector (knn) search against OpenSearch,
 * combined with NXQL filtering and optional aggregates.
 * <p>
 * The knn query is used as the main scoring query, while the NXQL query
 * (from the page provider definition) is applied as a post-filter.
 * Aggregates defined in the page provider are also computed.
 * <p>
 * Named parameters:
 * <ul>
 *   <li>{@code vector_index} - the knn index field name (required)</li>
 *   <li>{@code vector_value} - the vector as a JSON array string (optional if input_text is provided)</li>
 *   <li>{@code input_text} - text to convert to a vector via an automation chain (optional if vector_value is provided)</li>
 *   <li>{@code embedding_automation_processor} - automation chain/script to compute embedding from input_text</li>
 *   <li>{@code k} - number of nearest neighbors (default: 10)</li>
 *   <li>{@code min_score} - minimum relevance score threshold (default: 0.4)</li>
 * </ul>
 */
public class VectorSearchPageProvider extends SearchServicePageProvider {

    private static final long serialVersionUID = 1L;

    public static final String RELEVANCE_SCORE = "relevance_score";

    private static final Logger log = LogManager.getLogger(VectorSearchPageProvider.class);

    @Override
    public List<DocumentModel> getCurrentPage() {

        // use a cache
        if (currentPageDocuments != null) {
            return currentPageDocuments;
        }

        // fallback to default implementation if there is no vector search
        DocumentModel searchDoc = getSearchDocumentModel();
        if (searchDoc == null) {
            return getEmptyResult();
        }

        @SuppressWarnings("unchecked")
        Map<String, String> namedParameters = (Map<String, String>) searchDoc.getContextData(NAMED_PARAMETERS);
        if (namedParameters == null) {
            return super.getCurrentPage();
        }

        String index = namedParameters.get("vector_index");
        String vector = namedParameters.get("vector_value");
        String inputText = namedParameters.get("input_text");
        if (index == null && vector == null && inputText == null) {
            return super.getCurrentPage();
        }

        // proceed with vector search implementation

        error = null;
        errorMessage = null;

        currentPageDocuments = new ArrayList<>();
        CoreSession coreSession = getCoreSession();
        if (query == null) {
            buildQuery(coreSession);
        }
        if (query == null) {
            throw new NuxeoException(String.format("Cannot perform null query: check provider '%s'", getName()));
        }

        if (StringUtils.isBlank(vector)) {
            // get text input and create embedding
            if (StringUtils.isBlank(inputText)) {
                return getEmptyResult();
            }

            // get embedding automation processor
            String chainName = namedParameters.get("embedding_automation_processor");

            AutomationService automationService = Framework.getService(AutomationService.class);
            OperationContext ctx = new OperationContext(coreSession);
            Map<String, Object> params = new HashMap<>();
            params.put("input_text", inputText);
            try {
                vector = (String) automationService.run(ctx, chainName, params);
            } catch (OperationException e) {
                throw new NuxeoException(e);
            }

            if (StringUtils.isBlank(vector)) {
                return getEmptyResult();
            }
        }

        float minScore = Float.parseFloat(namedParameters.getOrDefault("min_score", "0.4"));

        if (StringUtils.isBlank(index) || StringUtils.isBlank(vector)) {
            return getEmptyResult();
        }

        // Build the knn query
        QueryBuilder knnQueryBuilder = QueryBuilders.wrapperQuery(String.format("""
                {
                    "knn": {
                        "%s": {
                            "vector": %s,
                            "k": %s
                         }
                    }
                }
                """, namedParameters.get("vector_index"), vector, namedParameters.getOrDefault("k", "10")));

        // Resolve search index and build a SearchQuery for NXQL-to-OpenSearch conversion
        SearchService searchService = Framework.getService(SearchService.class);
        SearchIndexingService indexingService = Framework.getService(SearchIndexingService.class);
        String repository = coreSession.getRepositoryName();
        String defaultIndexName = searchService.getDefaultIndexName(repository);
        SearchIndex searchIndex = searchService.getSearchIndex(defaultIndexName);

        NuxeoPrincipal principal = coreSession.getPrincipal();

        // Build aggregates from the page provider definition
        List<Aggregate<? extends Bucket>> aggregates = buildAggregates();

        SearchQuery searchQuery = SearchQuery.builder(query, principal)
                .searchIndex(List.of(searchIndex))
                .offset((int) getCurrentPageOffset())
                .limit((int) getPageSize())
                .addAggregates(aggregates)
                .build();

        // Get the OpenSearchSearchClient to obtain technical index name mappings
        var searchClient = indexingService.getClient(searchIndex.client());
        if (!(searchClient instanceof OpenSearchSearchClient osSearchClient)) {
            throw new NuxeoException(
                    "Vector search requires an OpenSearch search client. Got: "
                            + (searchClient != null ? searchClient.getClass().getName() : "null"));
        }
        Map<String, String> technicalIndexes = osSearchClient.getTechnicalIndexes();

        // Use OpenSearchQueryTransformer to convert NXQL + aggregates to a proper OpenSearch SearchRequest
        OpenSearchQueryTransformer transformer = new OpenSearchQueryTransformer(technicalIndexes, Map.of());
        SearchRequest osRequest = transformer.apply(searchQuery);
        SearchSourceBuilder source = osRequest.source();

        // Swap queries: knn becomes the main scoring query, NXQL becomes post-filter
        QueryBuilder nxqlQuery = source.query();
        QueryBuilder existingPostFilter = source.postFilter();

        source.query(knnQueryBuilder);
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

        // Aggregates are already in the source from the transformer -- no changes needed

        // Get the raw OpenSearch client.
        // The low-level client ID in Nuxeo follows the convention "search/default".
        OpenSearchClientService osClientService = Framework.getService(OpenSearchClientService.class);
        if (osClientService == null) {
            throw new NuxeoException(
                    "OpenSearchClientService is not available. Vector search requires an OpenSearch backend.");
        }

        // Derive low-level client ID: convention is "search/" + descriptor name,
        // production templates use "search/default" for the "opensearch" search client.
        String lowLevelClientId = namedParameters.getOrDefault("opensearch_client_id", "search/default");
        OpenSearchClient client = osClientService.getClient(lowLevelClientId);
        if (client == null) {
            throw new NuxeoException(
                    String.format("OpenSearch client '%s' is not available. "
                            + "Check your OpenSearch configuration or set the 'opensearch_client_id' named parameter.",
                            lowLevelClientId));
        }

        // Execute the search
        SearchResponse response = client.search(osRequest);

        SearchHits hits = response.getHits();

        // Load documents from the repository, preserving knn relevance ordering
        List<DocumentModel> result = new ArrayList<>();
        for (SearchHit hit : hits.getHits()) {
            IdRef docRef = new IdRef(hit.getId());
            if (coreSession.exists(docRef)) {
                DocumentModel doc = coreSession.getDocument(docRef);
                doc.putContextData(RELEVANCE_SCORE, hit.getScore());
                result.add(doc);
            }
        }

        currentPageDocuments = result;

        // Parse aggregate results from the response
        if (response.getAggregations() != null && !aggregates.isEmpty()) {
            currentAggregates = new HashMap<>();
            for (Aggregate<? extends Bucket> agg : aggregates) {
                parseAggregate(agg, response);
                currentAggregates.put(agg.getId(), agg);
            }
        }

        // set total number of hits
        setResultsCount(result.size());

        return result;
    }

    /**
     * Parses an aggregate result from the OpenSearch response.
     * Each aggregate is wrapped in a Filter aggregation by the transformer.
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

    public DocumentModelList getEmptyResult() {
        setResultsCount(0);
        return new DocumentModelListImpl();
    }

}
