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
import org.nuxeo.ecm.core.api.*;
import org.nuxeo.ecm.core.api.impl.DocumentModelListImpl;
import org.nuxeo.ecm.core.search.SearchIndex;
import org.nuxeo.ecm.core.search.SearchService;
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
import org.opensearch.search.builder.SearchSourceBuilder;

import java.util.*;

import static org.nuxeo.ecm.platform.query.api.PageProviderService.NAMED_PARAMETERS;


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

        QueryBuilder queryBuilder = QueryBuilders.wrapperQuery(String.format("""
                {
                    "knn": {
                        "%s": {
                            "vector": %s,
                            "k": %s
                         }
                    }
                }
                """, namedParameters.get("vector_index"), vector, namedParameters.getOrDefault("k", "10")));

        SearchRequest searchRequest = new SearchRequest();
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder().query(queryBuilder)
                .from((int) getCurrentPageOffset()).minScore(minScore);

        // Build NXQL post filter using the query string built from the page provider definition.
        // We convert the NXQL query to an OpenSearch query_string filter.
        BoolQueryBuilder postFilter = QueryBuilders.boolQuery();
        if (StringUtils.isNotBlank(query)) {
            // Use query_string to pass the NXQL-generated WHERE clause as an OpenSearch filter
            postFilter.must(QueryBuilders.queryStringQuery(query));
        }

        searchSourceBuilder.postFilter(postFilter);

        // Resolve the OpenSearch index name from the SearchService
        SearchService searchService = Framework.getService(SearchService.class);
        String repository = coreSession.getRepositoryName();
        String defaultIndexName = searchService.getDefaultIndexName(repository);
        SearchIndex defaultSearchIndex = searchService.getSearchIndex(defaultIndexName);
        String osIndexName = defaultSearchIndex.index();
        searchRequest.indices(osIndexName);

        searchRequest.source(searchSourceBuilder);

        // Get the raw OpenSearch client
        OpenSearchClientService osService = Framework.getService(OpenSearchClientService.class);
        if (osService == null) {
            throw new NuxeoException(
                    "OpenSearchClientService is not available. Vector search requires an OpenSearch backend.");
        }
        String clientId = defaultSearchIndex.client();
        OpenSearchClient client = osService.getClient(clientId);

        SearchResponse response = client.search(searchRequest);

        SearchHits hits = response.getHits();

        // Load documents from the repository by their IDs
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

        // set total number of hits
        setResultsCount(result.size());

        return result;
    }

    public DocumentModelList getEmptyResult() {
        setResultsCount(0);
        return new DocumentModelListImpl();
    }

}
