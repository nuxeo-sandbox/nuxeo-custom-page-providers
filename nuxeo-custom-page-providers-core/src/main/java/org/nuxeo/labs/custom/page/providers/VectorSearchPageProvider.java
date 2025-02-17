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
import org.nuxeo.ecm.platform.query.api.Aggregate;
import org.nuxeo.ecm.platform.query.api.Bucket;
import org.nuxeo.elasticsearch.aggregate.AggregateEsBase;
import org.nuxeo.elasticsearch.api.ESClient;
import org.nuxeo.elasticsearch.api.ElasticSearchAdmin;
import org.nuxeo.elasticsearch.fetcher.VcsFetcher;
import org.nuxeo.elasticsearch.provider.ElasticSearchNxqlPageProvider;
import org.nuxeo.elasticsearch.query.NxQueryBuilder;
import org.nuxeo.runtime.api.Framework;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.search.aggregations.AbstractAggregationBuilder;
import org.opensearch.search.aggregations.Aggregation;
import org.opensearch.search.aggregations.bucket.filter.Filter;
import org.opensearch.search.builder.SearchSourceBuilder;

import java.util.*;

import static org.nuxeo.ecm.platform.query.api.PageProviderService.NAMED_PARAMETERS;


public class VectorSearchPageProvider extends ElasticSearchNxqlPageProvider {

    public static final String RELEVANCE_SCORE = "relevance_score";

    private static final Logger log = LogManager.getLogger(VectorSearchPageProvider.class);

    @Override
    public List<DocumentModel> getCurrentPage() {

        // use a cache
        if (currentPageDocuments != null) {
            return currentPageDocuments;
        }

        //fallback to default implementation if there is no vector search
        DocumentModel searchDoc = getSearchDocumentModel();
        if (searchDoc == null) {
            return getEmptyResult();
        }

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

        NxQueryBuilder nxQuery = new NxQueryBuilder(coreSession).nxql(query).addAggregates(buildAggregates());

        if (StringUtils.isBlank(vector)) {
            //get text input and create embedding
            if (StringUtils.isBlank(inputText)) {
                return getEmptyResult();
            }

            //get embedding automation processor
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
                .from(nxQuery.getOffset()).minScore(minScore);

        //build nxql post filter
        QueryBuilder nxqlPostFilter = nxQuery.makeQuery();

        //build aggregate post filter
        QueryBuilder aggregatePostFilter = getAggregateFilter(nxQuery);

        BoolQueryBuilder postFilter = QueryBuilders.boolQuery().must(nxqlPostFilter);

        if (aggregatePostFilter != null) {
            postFilter.must(aggregatePostFilter);
        }

        searchSourceBuilder.postFilter(postFilter);

        //add aggregates
        for (AbstractAggregationBuilder<?> aggregate : nxQuery.getEsAggregates()) {
            searchSourceBuilder.aggregation(aggregate);
        }

        searchRequest.source(searchSourceBuilder);

        ElasticSearchAdmin esa = Framework.getService(ElasticSearchAdmin.class);
        ESClient client = esa.getClient();

        SearchResponse response = client.search(searchRequest);

        VcsFetcher fetcher = new VcsFetcher(getCoreSession(), response, null);

        SearchHits hits = response.getHits();

        List<DocumentModel> documents = fetcher.fetchDocuments();

        //reorder using relevance
        List<DocumentModel> result = new ArrayList<>();
        for (SearchHit hit : hits.getHits()) {
            Optional<DocumentModel> documentOpt = documents.stream().filter(doc -> doc.getId().equals(hit.getId())).findFirst();
            documentOpt.ifPresent(doc -> {
                doc.putContextData(RELEVANCE_SCORE,hit.getScore());
                result.add(doc);
            });
        }

        currentPageDocuments = result;

        currentAggregates = new HashMap<>(getResultAggregates(nxQuery, response).size());
        for (Aggregate<Bucket> agg : getResultAggregates(nxQuery, response)) {
            currentAggregates.put(agg.getId(), agg);
        }

        // set total number of hits
        setResultsCount(result.size());

        return result;
    }

    public DocumentModelList getEmptyResult() {
        setResultsCount(0);
        return new DocumentModelListImpl();
    }

    public List<Aggregate<Bucket>> getResultAggregates(NxQueryBuilder queryBuilder, SearchResponse response) {
        for (AggregateEsBase<Aggregation, Bucket> agg : queryBuilder.getAggregates()) {
            Filter filter = response.getAggregations().get(NxQueryBuilder.getAggregateFilterId(agg));
            if (filter == null) {
                continue;
            }
            Aggregation aggregation = filter.getAggregations().get(agg.getId());
            if (aggregation == null) {
                continue;
            }
            agg.parseAggregation(aggregation);
        }
        @SuppressWarnings("unchecked")
        List<Aggregate<Bucket>> ret = (List<Aggregate<Bucket>>) (List<?>) queryBuilder.getAggregates();
        return ret;
    }

    public QueryBuilder getAggregateFilter(NxQueryBuilder builder) {
        BoolQueryBuilder ret = QueryBuilders.boolQuery();
        for (AggregateEsBase<?, ?> agg : builder.getAggregates()) {
            QueryBuilder filter = agg.getEsFilter();
            if (filter != null) {
                ret.must(filter);
            }
        }
        if (!ret.hasClauses()) {
            return null;
        }
        return ret;
    }

}
