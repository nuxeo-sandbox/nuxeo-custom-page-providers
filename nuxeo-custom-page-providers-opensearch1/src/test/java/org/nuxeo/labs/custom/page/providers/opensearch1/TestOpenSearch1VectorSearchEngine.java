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
package org.nuxeo.labs.custom.page.providers.opensearch1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.nuxeo.ecm.core.search.client.opensearch1.OpenSearchSearchClient;
import org.nuxeo.labs.custom.page.providers.vector.VectorSearchEngine;
import org.nuxeo.labs.custom.page.providers.vector.VectorSearchEngines;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.query.WrapperQueryBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plain unit test (no Nuxeo runtime, no OpenSearch server): how the request built from the NXQL query is turned into a
 * knn request. Running a real knn query needs an OpenSearch server with the k-NN plugin.
 */
public class TestOpenSearch1VectorSearchEngine {

    protected static final float[] VECTOR = { 0.5f, -1.25f, 3f };

    @Test
    public void engineIsRegisteredForTheOpenSearch1SearchClient() {
        // the core module only knows these two classes by name
        assertEquals(OpenSearch1VectorSearchEngine.class.getName(),
                VectorSearchEngines.ENGINE_CLASSES.get(OpenSearchSearchClient.class.getName()));
        assertTrue(VectorSearchEngine.class.isAssignableFrom(OpenSearch1VectorSearchEngine.class));
    }

    @Test
    public void knnIsTheScoringQueryAndNxqlFiltersIt() throws Exception {
        QueryBuilder nxqlQuery = QueryBuilders.termQuery("ecm:primaryType", "Picture");
        QueryBuilder aggregatePostFilter = QueryBuilders.termQuery("dc:nature", "photo");
        SearchSourceBuilder source = new SearchSourceBuilder().query(nxqlQuery)
                                                              .postFilter(aggregatePostFilter)
                                                              .from(20)
                                                              .size(10);

        OpenSearch1VectorSearchEngine.applyKnnQuery(source, "embedding:image", VECTOR, 7, 0.4f);

        // main query: knn is the only scoring clause...
        assertTrue(source.query() instanceof BoolQueryBuilder);
        BoolQueryBuilder query = (BoolQueryBuilder) source.query();
        assertEquals(1, query.must().size());
        assertTrue(query.should().isEmpty());
        assertTrue(query.mustNot().isEmpty());
        assertKnnQuery(query.must().get(0), "embedding:image", 7);
        // ... filtered by the NXQL query (which holds the ACL filter), so that aggregates are filtered too
        assertEquals(List.of(nxqlQuery), query.filter());

        assertEquals(0.4f, source.minScore(), 0f);

        // the rest of the request is untouched, including the post-filter (selection of aggregate buckets)
        assertSame(aggregatePostFilter, source.postFilter());
        assertEquals(20, source.from());
        assertEquals(10, source.size());
    }

    @Test
    public void knnAloneWhenThereIsNoNxqlQuery() throws Exception {
        SearchSourceBuilder source = new SearchSourceBuilder();

        OpenSearch1VectorSearchEngine.applyKnnQuery(source, "embedding:image", VECTOR, 10, 0.4f);

        assertKnnQuery(source.query(), "embedding:image", 10);
        assertNull(source.postFilter());
    }

    /** Checks the query is { "knn": { "<field>": { "vector": VECTOR, "k": k } } }, wrapped as raw JSON. */
    protected static void assertKnnQuery(QueryBuilder query, String field, int k) throws Exception {
        assertTrue(query instanceof WrapperQueryBuilder);
        JsonNode json = new ObjectMapper().readTree(((WrapperQueryBuilder) query).source());
        assertEquals(1, json.size());
        JsonNode knn = json.get("knn").get(field);
        assertEquals(k, knn.get("k").asInt());
        assertEquals(VECTOR.length, knn.get("vector").size());
        for (int i = 0; i < VECTOR.length; i++) {
            assertEquals(VECTOR[i], (float) knn.get("vector").get(i).asDouble(), 0f);
        }
    }

    @Test
    public void fieldNameCannotBreakOutOfTheKnnQuery() throws Exception {
        String field = "a\": {}}, \"match_all\": {\"x";

        JsonNode json = new ObjectMapper().readTree(OpenSearch1VectorSearchEngine.knnQueryJson(field, VECTOR, 3));

        assertEquals(1, json.size());
        assertEquals(1, json.get("knn").size());
        assertEquals(3, json.get("knn").get(field).get("k").asInt());
    }

}
