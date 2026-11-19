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

/**
 * Runs a knn (vector) search on one specific search engine.
 * <p>
 * Nuxeo's search abstraction ({@code SearchQuery}) only handles NXQL, so the knn query has to be built with the native
 * API of the engine. Each supported engine (OpenSearch 1, OpenSearch 2) has its own implementation, living in its own
 * bundle, and only the one matching the deployed search client is ever loaded (see {@link VectorSearchEngines}).
 * <p>
 * Implementations must be stateless and have a public no-arg constructor.
 */
public interface VectorSearchEngine {

    /**
     * Runs the knn query as the only scoring query, with the NXQL query of the request applied as a filter of it, so
     * that hits and aggregates are both restricted to the documents matching the NXQL query (and the ACLs it holds).
     * <p>
     * The buckets of the aggregates held by {@code request.searchQuery()} are filled in place.
     */
    VectorSearchResponse search(VectorSearchRequest request);

}
