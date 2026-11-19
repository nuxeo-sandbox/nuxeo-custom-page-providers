# nuxeo-custom-page-providers

A plugin that provides custom page providers for custom/specialized search.

## Custom Page Providers

* Vector Search Page Provider
* StringList Page Provider

### The Vector Search Page Provider • "VectorSearchPP" Page Provider Contribution

> [!NOTE]
> This page provider is a copy of the same PageProvider found in [nuxeo-aws-bedrock-connector](https://github.com/nuxeo-sandbox/nuxeo-aws-bedrock-connector). We just renamed the PageProvider contribution (from "simple-vector-search" to "VectorSearchPP").
> 
> We will likely remove this Pageprovider from the aws-bedrock plugin.

> [!CAUTION]
> Starting with Nuxeo LTS2025, the search engine is pluggable. **The Vector Search Page Provider requires OpenSearch**, with either the OpenSearch 1 or the OpenSearch 2 search client (packages `nuxeo-search-client-opensearch1` or `nuxeo-search-client-opensearch2`, see _OpenSearch Configuration_ below). With any other search client (the default `repository` one, Elasticsearch), calling it fails with an explicit error.

Vector search enables use cases such as semantic search and RAG.

The plugin provides configuration template with OpenSearch configuration and storage/usage of embeddings/vectors (see _OpenSearch Configuration_ below)


There are two main parts for this vector search:

* The PageProvider and its parameters
* The required configuration of OpenSearch

#### The PageProvider
Assuming the configuration (see below) is correct and embeddings/vectors are correctly stored in OpenSearch, the plugin brings vector search capabilities to the Nuxeo search API.

The Pageprovider exposes several named parameters:

| Named Parameter                | Description                                                                      | Type    | Required | Default value |
|:-------------------------------|:---------------------------------------------------------------------------------|:--------|:---------|:--------------|
| vector_index                   | The vector field name to use for search                                          | string  | true     |               |
| vector_value                   | The input vector                                                                 | string  | false    |               |
| input_text                     | A text string can be passed instead of a vector                                  | string  | false    |               |
| embedding_automation_processor | The automation chain/script to use to convert `input_text` to a vector embedding | boolean | false    |               |
| k                              | The k value for knn                                                              | integer | false    | 10            |
| min_score                      | The min_score for results the a hit must satisfied                               | float   | false    | 0.4           |
| opensearch_client_id           | The id of the low-level OpenSearch client to use (same id for OpenSearch 1 and 2) | string  | false    | search/default |

The search input is either `vector_value` or the combination `input_text` and `embedding_automation_processor`.

> [!IMPORTANT]
> When using `input_text` and `embedding_automation_processor`, the model used to generate the embeddings must be same as the model used to generate the embedding vectors for `vector_index`

> [!TIP]
> When calculating embeddings, you will use another plugin (such as [nuxeo-aws-bedrock-connector](https://github.com/nuxeo-sandbox/nuxeo-aws-bedrock-connector) or [nuxeo-labs-knowledge-enrichment-connector](https://github.com/nuxeo-sandbox/nuxeo-labs-knowledge-enrichment-connector). Just make sure to use the same embeddingLenght than the one used in the OpenSearch mapping (see below).

The plugin contributes this PageProvider in the `VectorSearch-contrib.xml` file, under the name `"VectorSearchPP"`, so it can be used immediately. You can of course contribute another one (or as many as you want) with a different `name`, or override this one if you just want to change the `fixedPart`. Here for example, we contribute a new one, named "myVectorSearchPP" in Nuxeo Studio XML. It filters also by document type and `isProxy`.

> [!IMPORTANT]
> You must always use the `<property name="coreSession">#{documentManager}</property>` property, this is mandatory.

```xml
<extension point="providers" target="org.nuxeo.ecm.platform.query.api.PageProviderService">
  <genericPageProvider class="org.nuxeo.labs.custom.page.providers.VectorSearchPageProvider"
                        name="myVectorSearchPP">
      <trackUsage>false</trackUsage>
      <property name="coreSession">#{documentManager}</property>
      <whereClause>
          <fixedPart>ecm:mixinType != 'HiddenInNavigation' AND ecm:isVersion = 0 AND ecm:isTrashed = 0 AND ecm:isProxy = 0 AND ecm:primaryType = 'MyDocType'</fixedPart>
      </whereClause>
      <pageSize>10</pageSize>
  </genericPageProvider>
</extension>
```

* Example with `curl` and the Default "VectorSearchPP" Contribution

```curl
curl 'http://localhost:8080/nuxeo/api/v1/search/pp/VectorSearchPP/execute?input_text=japanese%20kei%20car&vector_index=embedding%3Aimage&embedding_automation_processor=javascript.text2embedding&k=10' \
  -H 'Content-Type: application/json' \
  -H 'accept: text/plain,application/json, application/json' \
```
<br>

* Example with Nuxeo Automation Scripting and the Default "VectorSearchPP" Contribution

```javascript
  . . .
  // Set the page provider parameters
  var namedParametersValues = "k=5";
  namedParametersValues += "\nmin_score=0.6";
  // Vectors are, in this example, stored in the "embedding:image" field
  namedParametersValues += "\nvector_index=embedding:image"; // WARNING: your field may be named embeddings:image, or something else
  var embbedings = input['embedding:image'];
  // (input['embedding:image'] is a Java array, to be converted to JS)
  namedParametersValues += "\nvector_value=" + JSON.stringify(toJsArray(embbedings));

  // Perform the search
  Console.log("Searching similar assets using vector search...");
  var similarAssets = Repository.PageProvider(input, {
    'providerName': 'VectorSearchPP',
    'namedParameters': namedParametersValues
  });

  // Handle results
  Console.log("  Found similar asset(s): " + similarAssets.size());
  if(similarAssets.size() > 0) {
    // . . . process the similar assets . . .
  }
. . .
```

#### OpenSearch Configuration

##### Supported Search Clients

The Vector Search Page Provider works with the two OpenSearch search clients available for Nuxeo LTS 2025. Install one of them (they conflict with each other, only one can be installed):

| Nuxeo package                     | OpenSearch server |
|:----------------------------------|:------------------|
| `nuxeo-search-client-opensearch1` | OpenSearch 1.3.x  |
| `nuxeo-search-client-opensearch2` | OpenSearch 2.x    |

It is the same plugin (and the same Marketplace Package) in both cases: it contains one small adapter for each search client and uses the one matching the search client deployed on your server. There is nothing to configure.

##### Configuration Templates

In order to use the feature, knn must be enabled at the index level, and the vector fields must be declared in the index mapping. This can only be done with a package configuration template.

The plugin provides 3 configuration templates (see the source code of nuxeo-custom-page-providers-package). **Each template works with both search clients**: it contains the files for OpenSearch 1 (`opensearch1-doc-*`) and the files for OpenSearch 2 (`opensearch2-doc-*`), and only the files of the installed search client are used.

* `opensearch-with-knn`
  * Deploys only the index settings ([OpenSearch 1](./nuxeo-custom-page-providers-package/src/main/resources/install/templates/opensearch-with-knn/nxserver/config/opensearch1-doc-settings.json.nxftl), [OpenSearch 2](./nuxeo-custom-page-providers-package/src/main/resources/install/templates/opensearch-with-knn/nxserver/config/opensearch2-doc-settings.json.nxftl)), to add the vector Search (knn type)
  * This means: When deploying this template, you still need to configure the document mapping for using the index on the fields you want (can be done in Studio)
* `embeddings-basic`
  * Deploys
    * The same index settings, to add the vector Search (knn type)
    * And the [doc mapping](./nuxeo-custom-page-providers-package/src/main/resources/install/templates/embeddings-basic/nxserver/config) to add 2 indexes, one for `embeddings:image` and one for `embeddings:text`.
  * **This requires that these 2 fields exist in your configuration**, it is your responsibility to create the `embeddings` schema and add `image` and `text` to it (as `double multivalued`)
* `embedding-example`
  * Deploys
    * The same index settings
    * The [doc mapping](./nuxeo-custom-page-providers-package/src/main/resources/install/templates/embedding-example/nxserver/config) for `embedding:image` and `embedding:text`
    * And "everything" for using it. See [source code](./nuxeo-custom-page-providers-package/src/main/resources/install/templates/embedding-example/nxserver/config).
      * `embedding` schema
      * `Embedding` facet, added to `Picture`
      * UI elements (buttons, tab)
      * etc.

> [!IMPORTANT]
> * `embeddings-basic` expects the `embeddings` schema, plural form
> * While `embedding-example` deploys the `embedding` schema.

Typically, after deploying the plugin, you would change nuxeo.conf (or any configuration file used in a Docker build) to append the template. For example:

nuxeo.append.templates.system=default,mongodb<b>,embeddings-basic</b>

> [!IMPORTANT]
> * Templates are applied in order, and the last one wins. The template of this plugin replaces files of the template of the search client (`opensearch1-search-client` or `opensearch2-search-client`, added when its package is installed), so it must come after it. This is the case when you append it as above.
> * Index settings and mapping are applied only when the index is created. If the index already exists (for example, you add the template to an existing server), you must drop it and re-index the repository.

##### OpenSearch 1 vs OpenSearch 2 mapping

The templates declare the vector fields with the same parameters for both versions (`hnsw`, `l2`), except for the knn engine:

* OpenSearch 1 mappings use the `nmslib` engine.
* OpenSearch 2 mappings use the `faiss` engine: `nmslib` is deprecated since OpenSearch 2.19, and cannot be used anymore for creating an index in OpenSearch 3.


#### If you need to created your own mapping:

1. Vector fields must be explicitly declared in the index mapping.

2. Here is an example JSON, for OpenSearch 1. For OpenSearch 2, use `"engine": "faiss"` (see above).
> [!IMPORTANT]
> The `dimension` property must correspond to the embbedings size when you asked AI to calculate embeddings (see for example [nuxeo-aws-bedrock-connector](https://github.com/nuxeo-sandbox/nuxeo-aws-bedrock-connector))

```json
{
  "embedding:text": {
    "type": "knn_vector",
    "dimension": 1024,
    "method": {
      "name": "hnsw",
      "space_type": "l2",
      "engine": "nmslib",
      "parameters": {
        "ef_construction": 128,
        "m": 24
      }
    }
  },
  "embedding:image": {
    "type": "knn_vector",
    "dimension": 1024,
    "method": {
      "name": "hnsw",
      "space_type": "l2",
      "engine": "nmslib",
      "parameters": {
        "ef_construction": 128,
        "m": 24
      }
    }
  }
}
```
This can be done by overriding the whole mapping configuration in a package configuration template or by using Nuxeo Studio.

### StringList Page Provider

This PageProvider will return a `DocumentModelList` ordered in the same order as a StringList field. If you don't set an `xpath` in the contribution(s) (see below), then it means the current document holds a `Collection` and the plugin with use the corresponding field.

> [!NOTE]
> This is the upgrade of a very old PageProvider, coded even before Nuxeo WebUI existed. Notice it is now easy to get the same result with, for example, just some JS Automation.
> 
> We keep it here as an example of use.


### Usage

* You need to:
  * Contribute a Pageprovider using the `StringListPageProvider` class
  * Call the page provider with a _named parameter_, `currentDocumentId`, whose value must be set to the current document ID (the document holding the list of related document IDs)

#### Contribute a New PageProvider Using `StringListPageProvider`

Copy the following contribution in a Studio XML Extension, and set the `xpath` parameter to the StringList field you want to use. For example, here we named the page provider "pp_mystringlistfield" and used the `mysschema:myStringListField` field.

> [!IMPORTANT]
> * You must use the `coreSession` property, it is required as in the example below.
> * The `class` property must not be changed too, of course.

> [!TIP]
> If you need this feature on several fields, you must create as many XML extension and just change the `name` of the provider and the `xpath`.

In this example we named the page provider "pp_mystringlistfield" and used the `mysschema:myStringListField` field:

```
<extension target="org.nuxeo.ecm.platform.query.api.PageProviderService"
           point="providers">
  <genericPageProvider name="pp_mystringlistfield" class="org.nuxeo.labs.custom.page.providers.StringListPageProvider">
    <property name="coreSession">#{documentManager}</property>
    <!-- Put the xpath of your String Multivalued field here -->
    <!-- no xpath means the current document is a Collection -->
    <property name="xpath">myschema:myStringListField</property>
  </genericPageProvider>
</extension>
```

#### Call this PageProvider

##### Example with JS Automation:

```javascript
// input is a document holding the values for myschema:myStringListField
function run(input, params) {
  var docs = Repository.PageProvider(
    input, {
      'providerName': "pp_mystringlistfield",
      'namedParameters': "currentDocumentId=" + input.id
    });
  
  . . . docs hold the documents in the correct order . . .
}
```

## How to build
```bash
git clone https://github.com/nuxeo-sandbox/nuxeo-custom-page-providers
cd nuxeo-custom-page-providers
mvn clean install
```

To skip docker build/test, add `-DskipDocker`. To skip unit testing, add `-DskipTests`

The build has 3 code modules: `nuxeo-custom-page-providers-core` (the page providers, no dependency on a search engine), and one adapter for each OpenSearch search client, `nuxeo-custom-page-providers-opensearch1` and `nuxeo-custom-page-providers-opensearch2`. Each adapter is compiled against its own search client only. The Marketplace Package deploys the 3 of them.

> [!NOTE]
> Unit tests do not run a knn query: this requires an OpenSearch server with the k-NN plugin. Test the vector search on a real deployment (see below).

## Run locally with Docker

After building the docker image (`mvn clean install`, without `-DskipDocker`), `docker-compose.yml` starts Nuxeo with MongoDB and OpenSearch, using the `embedding-example` template (see `conf.d/00-docker.conf`). It expects a `.env` file with, at least, your `NUXEO_CLID`.

It runs OpenSearch 1 by default. To run OpenSearch 2, add to the `.env` file:

```
OPENSEARCH_IMAGE=opensearchproject/opensearch:2.19.7
SEARCH_CLIENT_PACKAGE=nuxeo-search-client-opensearch2
```

When switching from one to the other, remove the volumes first (`docker compose down -v`): an OpenSearch 2 node cannot be downgraded, and the index must be re-created with the right mapping.

Other variables: `PLUGIN_VERSION` (tag of the image to run, if not the current snapshot) and `NUXEO_PACKAGES` (more packages to install).

## Support
**These features are not part of the Nuxeo Production platform.**

These solutions are provided for inspiration and we encourage customers to use them as code samples and learning
resources.

This is a moving project (no API maintenance, no deprecation process, etc.) If any of these solutions are found to be
useful for the Nuxeo Platform in general, they will be integrated directly into platform, not maintained here.

## Nuxeo Marketplace
[here](https://connect.nuxeo.com/nuxeo/site/marketplace/package/nuxeo-custom-page-providers)

## License
[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)

## About Nuxeo
Nuxeo Platform is an open source Content Services platform, written in Java. Data can be stored in both SQL & NoSQL
databases.

The development of the Nuxeo Platform is mostly done by Nuxeo employees with an open development model.

The source code, documentation, roadmap, issue tracker, testing, benchmarks are all public.

Typically, Nuxeo users build different types of information management solutions
for [document management](https://www.nuxeo.com/solutions/document-management/), [case management](https://www.nuxeo.com/solutions/case-management/),
and [digital asset management](https://www.nuxeo.com/solutions/dam-digital-asset-management/), use cases. It uses
schema-flexible metadata & content models that allows content to be repurposed to fulfill future use cases.

More information is available at [www.nuxeo.com](https://www.nuxeo.com).
