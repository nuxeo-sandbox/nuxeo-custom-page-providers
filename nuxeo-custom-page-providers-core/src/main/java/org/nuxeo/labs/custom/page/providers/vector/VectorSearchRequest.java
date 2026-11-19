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

import org.nuxeo.ecm.core.search.SearchClient;
import org.nuxeo.ecm.core.search.SearchQuery;

/**
 * Engine-neutral description of a knn search.
 *
 * @param searchClient the Nuxeo search client of the index to search (gives the technical index names)
 * @param searchQuery the NXQL query (with paging and aggregates), applied as a filter of the knn query
 * @param clientId the id of the low-level engine client used to run the request, typically {@code search/default}
 * @param vectorField the name of the knn_vector field
 * @param vector the query vector
 * @param k the number of nearest neighbors
 * @param minScore the minimum relevance score of the returned hits
 */
public record VectorSearchRequest(SearchClient searchClient, SearchQuery searchQuery, String clientId,
        String vectorField, float[] vector, int k, float minScore) {
}
