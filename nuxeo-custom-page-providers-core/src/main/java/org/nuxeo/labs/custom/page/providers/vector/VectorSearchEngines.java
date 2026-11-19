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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.search.SearchClient;

/**
 * Finds the {@link VectorSearchEngine} matching the search client deployed on the server.
 * <p>
 * Engines are referenced by class name only, and loaded lazily: the OpenSearch 1 and OpenSearch 2 client libraries
 * cannot be deployed together, so the engine written for the absent one must never be loaded.
 */
public final class VectorSearchEngines {

    /** Search client class name -> engine class name (immutable). */
    public static final Map<String, String> ENGINE_CLASSES = Map.of(
            "org.nuxeo.ecm.core.search.client.opensearch1.OpenSearchSearchClient",
            "org.nuxeo.labs.custom.page.providers.opensearch1.OpenSearch1VectorSearchEngine",
            "org.nuxeo.ecm.core.search.client.opensearch2.OpenSearchSearchClient",
            "org.nuxeo.labs.custom.page.providers.opensearch2.OpenSearch2VectorSearchEngine");

    private static final Map<String, VectorSearchEngine> ENGINES = new ConcurrentHashMap<>();

    private VectorSearchEngines() {
        // utility class
    }

    /**
     * @return the engine able to run a knn search with the given search client
     * @throws NuxeoException if the search client is not a supported one, or if its engine cannot be loaded
     */
    public static VectorSearchEngine forClient(SearchClient searchClient) {
        return forClient(searchClient, ENGINE_CLASSES);
    }

    static VectorSearchEngine forClient(SearchClient searchClient, Map<String, String> engineClasses) {
        if (searchClient == null) {
            throw new NuxeoException("Vector search requires an OpenSearch 1 or OpenSearch 2 search client,"
                    + " but there is no search client for the default search index.");
        }
        // walk the hierarchy, so a subclass of a supported search client is supported too
        for (Class<?> c = searchClient.getClass(); c != null; c = c.getSuperclass()) {
            String engineClass = engineClasses.get(c.getName());
            if (engineClass != null) {
                return ENGINES.computeIfAbsent(engineClass, VectorSearchEngines::newEngine);
            }
        }
        throw new NuxeoException("Vector search requires an OpenSearch 1 or OpenSearch 2 search client"
                + " (package nuxeo-search-client-opensearch1 or nuxeo-search-client-opensearch2). Got: "
                + searchClient.getClass().getName());
    }

    private static VectorSearchEngine newEngine(String className) {
        try {
            return (VectorSearchEngine) loadClass(className).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            throw new NuxeoException("Cannot load the vector search engine " + className
                    + ". Check that its bundle is deployed.", e);
        }
    }

    private static Class<?> loadClass(String className) throws ClassNotFoundException {
        try {
            return Class.forName(className, true, VectorSearchEngines.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            if (contextLoader == null) {
                throw e;
            }
            return Class.forName(className, true, contextLoader);
        }
    }

}
