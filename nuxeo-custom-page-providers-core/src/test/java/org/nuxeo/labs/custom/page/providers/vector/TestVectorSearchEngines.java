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
package org.nuxeo.labs.custom.page.providers.vector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.search.SearchClient;

/**
 * Plain unit test (no Nuxeo runtime): how the engine is chosen from the deployed search client.
 */
public class TestVectorSearchEngines {

    public static class FakeEngine implements VectorSearchEngine {
        @Override
        public VectorSearchResponse search(VectorSearchRequest request) {
            return new VectorSearchResponse(List.of(), false);
        }
    }

    /** A search client of a class the plugin knows nothing about. */
    protected static SearchClient someSearchClient() {
        return (SearchClient) Proxy.newProxyInstance(TestVectorSearchEngines.class.getClassLoader(),
                new Class<?>[] { SearchClient.class }, (proxy, method, args) -> null);
    }

    @Test
    public void bothOpenSearchClientsAreRegistered() {
        assertEquals("org.nuxeo.labs.custom.page.providers.opensearch1.OpenSearch1VectorSearchEngine",
                VectorSearchEngines.ENGINE_CLASSES.get(
                        "org.nuxeo.ecm.core.search.client.opensearch1.OpenSearchSearchClient"));
        assertEquals("org.nuxeo.labs.custom.page.providers.opensearch2.OpenSearch2VectorSearchEngine",
                VectorSearchEngines.ENGINE_CLASSES.get(
                        "org.nuxeo.ecm.core.search.client.opensearch2.OpenSearchSearchClient"));
    }

    @Test
    public void unsupportedSearchClientIsRejectedWithAnExplicitMessage() {
        SearchClient client = someSearchClient();
        NuxeoException e = assertThrows(NuxeoException.class, () -> VectorSearchEngines.forClient(client));
        assertTrue(e.getMessage(), e.getMessage().contains("OpenSearch 1 or OpenSearch 2"));
        assertTrue(e.getMessage(), e.getMessage().contains(client.getClass().getName()));
    }

    @Test
    public void missingSearchClientIsRejected() {
        assertThrows(NuxeoException.class, () -> VectorSearchEngines.forClient(null));
    }

    @Test
    public void engineIsLoadedByNameAndReused() {
        SearchClient client = someSearchClient();
        Map<String, String> engines = Map.of(client.getClass().getName(), FakeEngine.class.getName());

        VectorSearchEngine engine = VectorSearchEngines.forClient(client, engines);
        assertEquals(FakeEngine.class, engine.getClass());
        assertSame(engine, VectorSearchEngines.forClient(client, engines));
    }

    @Test
    public void subclassOfASupportedSearchClientIsSupported() {
        // every dynamic proxy class extends java.lang.reflect.Proxy
        SearchClient client = someSearchClient();
        Map<String, String> engines = Map.of(Proxy.class.getName(), FakeEngine.class.getName());

        assertEquals(FakeEngine.class, VectorSearchEngines.forClient(client, engines).getClass());
    }

    @Test
    public void engineWhoseBundleIsNotDeployedGivesAnExplicitError() {
        SearchClient client = someSearchClient();
        Map<String, String> engines = Map.of(client.getClass().getName(), "org.example.NotDeployedEngine");

        NuxeoException e = assertThrows(NuxeoException.class, () -> VectorSearchEngines.forClient(client, engines));
        assertTrue(e.getMessage(), e.getMessage().contains("org.example.NotDeployedEngine"));
    }

}
