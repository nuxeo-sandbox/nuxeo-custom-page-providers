# nuxeo-custom-page-providers

A plugin that provides custom page providers for custom/specialized search.

## Custom Page Providers

* VectorSearchPageProvider
* StringListPageProvider

### VectorSearchPageProvider • "VectorSearchPP" PageProvider Contribution

> [!NOTE]
> This page provider is a copy of the same PageProvider found in [nuxeo-aws-bedrock-connector](https://github.com/nuxeo-sandbox/nuxeo-aws-bedrock-connector). We just renamed the PageProvider contribution (from "simple-vector-search" to "VectorSearchPP").
> (we will likely remove this Pageprovider from the aws-bedrock plugin)

Vector search enables use cases such as semantic search and RAG.
A [sample configuration template](./nuxeo-custom-page-providers-package/src/main/resources/install/templates/embedding-sample) is provided in this plugin.

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

The search input is either `vector_value` or the combination `input_text` and `embedding_automation_processor`.

> [!IMPORTANT]
> When using `input_text` and `embedding_automation_processor`, the model used to generate the embeddings must be same as the model used to generate the embedding vectors for `vector_index`

> [!TIP]
> When calculating embeddings, you will use another plugin (such as [nuxeo-aws-bedrock-connector](https://github.com/nuxeo-sandbox/nuxeo-aws-bedrock-connector) or [nuxeo-hyland-content-intelligence-connector](https://github.com/nuxeo-sandbox/nuxeo-hyland-content-intelligence-connector), once the service is ready to provide embeddings). Just make sure to use the same embeddingLenght than the one used in the OpenSearch mapping (see below).

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
  namedParametersValues += "\nvector_index=embedding:image";
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
This feature is implemented only for OpenSearch 1.3.x. In order to use the feature, knn must be enabled at the index level. This can only be done with a package configuration template.
A sample index configuration is available [here](./nuxeo-custom-page-providers-package/src/main/resources/install/templates/opensearch-knn/nxserver/config/elasticsearch-doc-settings.json.nxftl)

Typically, after deploying the plugin, you would change nuxeo.conf (or any configuration file used in a Docker build) to append the template. For example:

nuxeo.append.templates.system=default,mongodb<b>,opensearch-knn</b>

Vector fields must be explicitly declared in the index mapping.

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

### StringList PageProvider

This PageProvider will return a `DocumentModelList` ordered in the same order as a StringList field.

> [!NOTE]
> This is the upgrade of a very old PageProvider. Notice it is easy to now to get the same result with just some JS Automation.
> We keeop it here as an example of use.


### Usage

* You need to:
  * Contribute a Pageprovider using the `StringListPageProvider` class
  * Called the page provider with named parameter, `currentDocumentId`, whose value must be set to the current document ID (the document holding the list of related document IDs)

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
cd nuxeo-ustom-page-providers
mvn clean install
```

To skip docker build/test, add `-DskipDocker`. Ti skip unit testing, add `-DskipTests`

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
