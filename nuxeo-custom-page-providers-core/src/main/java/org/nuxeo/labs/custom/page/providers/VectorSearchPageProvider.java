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
import org.nuxeo.ecm.core.search.SearchClient;
import org.nuxeo.ecm.core.search.SearchIndex;
import org.nuxeo.ecm.core.search.SearchIndexingService;
import org.nuxeo.ecm.core.search.SearchQuery;
import org.nuxeo.ecm.core.search.SearchService;
import org.nuxeo.ecm.platform.query.api.Aggregate;
import org.nuxeo.ecm.platform.query.api.Bucket;
import org.nuxeo.ecm.platform.query.nxql.SearchServicePageProvider;
import org.nuxeo.labs.custom.page.providers.vector.VectorSearchEngine;
import org.nuxeo.labs.custom.page.providers.vector.VectorSearchEngines;
import org.nuxeo.labs.custom.page.providers.vector.VectorSearchRequest;
import org.nuxeo.labs.custom.page.providers.vector.VectorSearchResponse;
import org.nuxeo.labs.custom.page.providers.vector.VectorSearchResponse.VectorSearchHit;
import org.nuxeo.runtime.api.Framework;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.nuxeo.ecm.platform.query.api.PageProviderService.NAMED_PARAMETERS;

/**
 * A page provider that performs a vector (knn) search against OpenSearch,
 * combined with NXQL filtering and optional aggregates.
 * <p>
 * Works with the OpenSearch 1 and the OpenSearch 2 search clients: the engine-specific part is delegated to the
 * {@link VectorSearchEngine} matching the deployed search client.
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
 *   <li>{@code opensearch_client_id} - id of the low-level OpenSearch client (default: search/default)</li>
 * </ul>
 */
public class VectorSearchPageProvider extends SearchServicePageProvider {

    private static final long serialVersionUID = 1L;

    public static final String RELEVANCE_SCORE = "relevance_score";

    public static final String DEFAULT_CLIENT_ID = "search/default";

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

        float[] vectorValues = parseVector(vector);
        int k = Integer.parseInt(namedParameters.getOrDefault("k", "10").trim());

        // Resolve the search index and build a SearchQuery holding the NXQL query, the paging and the aggregates
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

        // The knn query can only be built with the native API of the search engine: delegate to the engine
        // matching the deployed search client (OpenSearch 1 or OpenSearch 2).
        // It runs the knn query as the main scoring query and the NXQL query as a post-filter.
        SearchClient searchClient = indexingService.getClient(searchIndex.client());
        VectorSearchEngine engine = VectorSearchEngines.forClient(searchClient);

        // The low-level client id follows the convention "search/" + search client descriptor name,
        // production templates use "search/default" for the "opensearch" search client.
        String clientId = namedParameters.getOrDefault("opensearch_client_id", DEFAULT_CLIENT_ID);

        VectorSearchResponse response = engine.search(
                new VectorSearchRequest(searchClient, searchQuery, clientId, index, vectorValues, k, minScore));

        // Load documents from the repository, preserving knn relevance ordering
        List<DocumentModel> result = new ArrayList<>();
        for (VectorSearchHit hit : response.hits()) {
            IdRef docRef = new IdRef(hit.id());
            if (coreSession.exists(docRef)) {
                DocumentModel doc = coreSession.getDocument(docRef);
                doc.putContextData(RELEVANCE_SCORE, hit.score());
                result.add(doc);
            }
        }

        currentPageDocuments = result;

        // The buckets of the aggregates were filled by the engine
        if (response.aggregatesParsed() && !aggregates.isEmpty()) {
            currentAggregates = new HashMap<>();
            for (Aggregate<? extends Bucket> agg : aggregates) {
                currentAggregates.put(agg.getId(), agg);
            }
        }

        // set total number of hits
        setResultsCount(result.size());

        return result;
    }

    /**
     * Parses the vector, received as a JSON array of numbers.
     */
    protected float[] parseVector(String vector) {
        try {
            return MAPPER.readValue(vector, float[].class);
        } catch (JsonProcessingException e) {
            throw new NuxeoException("The vector must be a JSON array of numbers: " + e.getOriginalMessage(), e);
        }
    }

    public DocumentModelList getEmptyResult() {
        setResultsCount(0);
        return new DocumentModelListImpl();
    }

}
