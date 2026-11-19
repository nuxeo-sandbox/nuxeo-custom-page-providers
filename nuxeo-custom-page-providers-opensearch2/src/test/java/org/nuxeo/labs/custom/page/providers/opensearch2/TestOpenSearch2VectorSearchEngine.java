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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.nuxeo.ecm.core.search.client.opensearch2.OpenSearchSearchClient;
import org.nuxeo.labs.custom.page.providers.vector.VectorSearchEngine;
import org.nuxeo.labs.custom.page.providers.vector.VectorSearchEngines;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SearchType;
import org.opensearch.client.opensearch._types.query_dsl.KnnQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;

/**
 * Plain unit test (no Nuxeo runtime, no OpenSearch server): how the request built from the NXQL query is turned into a
 * knn request. Running a real knn query needs an OpenSearch server with the k-NN plugin.
 */
public class TestOpenSearch2VectorSearchEngine {

    protected static final float[] VECTOR = { 0.5f, -1.25f, 3f };

    protected static Query term(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(FieldValue.of(value))));
    }

    @Test
    public void engineIsRegisteredForTheOpenSearch2SearchClient() {
        // the core module only knows these two classes by name
        assertEquals(OpenSearch2VectorSearchEngine.class.getName(),
                VectorSearchEngines.ENGINE_CLASSES.get(OpenSearchSearchClient.class.getName()));
        assertTrue(VectorSearchEngine.class.isAssignableFrom(OpenSearch2VectorSearchEngine.class));
    }

    @Test
    public void knnBecomesTheMainQueryAndNxqlJoinsThePostFilter() {
        Query nxqlQuery = term("ecm:primaryType", "Picture");
        Query aggregatePostFilter = term("dc:nature", "photo");
        SearchRequest nxqlRequest = new SearchRequest.Builder().index("nuxeo")
                                                               .query(nxqlQuery)
                                                               .postFilter(aggregatePostFilter)
                                                               .from(20)
                                                               .size(10)
                                                               .searchType(SearchType.DfsQueryThenFetch)
                                                               .aggregations("nature_agg",
                                                                       a -> a.terms(t -> t.field("dc:nature")))
                                                               .build();

        SearchRequest knnRequest = OpenSearch2VectorSearchEngine.toKnnRequest(nxqlRequest, "embedding:image", VECTOR,
                7, 0.4f);

        // main query: { "knn": { "embedding:image": { "vector": [...], "k": 7 } } }
        assertTrue(knnRequest.query().isKnn());
        KnnQuery knn = knnRequest.query().knn();
        assertEquals("embedding:image", knn.field());
        assertArrayEquals(VECTOR, knn.vector(), 0f);
        assertEquals(Integer.valueOf(7), knn.k());

        assertEquals(0.4d, knnRequest.minScore(), 1e-6);

        // post-filter: the NXQL query (which holds the ACL filter) and the post-filter that was already there
        assertTrue(knnRequest.postFilter().isBool());
        assertEquals(List.of(nxqlQuery, aggregatePostFilter), knnRequest.postFilter().bool().must());

        // the rest of the request is carried over
        assertEquals(List.of("nuxeo"), knnRequest.index());
        assertEquals(Integer.valueOf(20), knnRequest.from());
        assertEquals(Integer.valueOf(10), knnRequest.size());
        assertEquals(SearchType.DfsQueryThenFetch, knnRequest.searchType());
        assertTrue(knnRequest.aggregations().containsKey("nature_agg"));

        // requests are immutable: the original one is unchanged
        assertSame(nxqlQuery, nxqlRequest.query());
        assertSame(aggregatePostFilter, nxqlRequest.postFilter());
    }

    @Test
    public void knnQueryIsSentInTheSameJsonFormAsWithOpenSearch1() {
        SearchRequest knnRequest = OpenSearch2VectorSearchEngine.toKnnRequest(new SearchRequest.Builder().build(),
                "embedding:image", VECTOR, 7, 0.4f);

        String json = knnRequest.query().toJsonString();

        assertEquals("{\"knn\":{\"embedding:image\":{\"vector\":[0.5,-1.25,3.0],\"k\":7}}}", json);
    }

    @Test
    public void nxqlAloneBecomesThePostFilter() {
        Query nxqlQuery = term("ecm:primaryType", "Picture");
        SearchRequest nxqlRequest = new SearchRequest.Builder().query(nxqlQuery).build();

        SearchRequest knnRequest = OpenSearch2VectorSearchEngine.toKnnRequest(nxqlRequest, "embedding:image", VECTOR,
                10, 0.4f);

        assertEquals(List.of(nxqlQuery), knnRequest.postFilter().bool().must());
    }

    @Test
    public void noPostFilterWhenThereIsNothingToFilterOn() {
        SearchRequest knnRequest = OpenSearch2VectorSearchEngine.toKnnRequest(new SearchRequest.Builder().build(),
                "embedding:image", VECTOR, 10, 0.4f);

        assertTrue(knnRequest.query().isKnn());
        assertNull(knnRequest.postFilter());
    }

}
