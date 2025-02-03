package org.nuxeo.labs.custom.page.providers;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.core.util.PageProviderHelper;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.test.DefaultRepositoryInit;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.ecm.platform.query.api.PageProvider;
import org.nuxeo.ecm.platform.query.api.PageProviderDefinition;
import org.nuxeo.ecm.platform.test.PlatformFeature;
import org.nuxeo.elasticsearch.test.RepositoryElasticSearchFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.opensearch.OpenSearchStatusException;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.HashMap;

@RunWith(FeaturesRunner.class)
@Features({PlatformFeature.class, RepositoryElasticSearchFeature.class})
@RepositoryConfig(init = DefaultRepositoryInit.class, cleanup = Granularity.METHOD)
@Deploy({"nuxeo-custom-page-providers-core"})
public class TestVectorSearchPageProvider {

    @Inject
    protected CoreSession session;

    @Test
    public void testPP() {
        PageProviderDefinition def = PageProviderHelper.getPageProviderDefinition("simple-vector-search");
        HashMap<String,String> namedParameters = new HashMap<>();
        namedParameters.put("vector_index", "embedding");
        namedParameters.put("vector_value", Arrays.toString(new double[]{1.0,2.0}));
        PageProvider<?> pp = PageProviderHelper.getPageProvider(session, def, namedParameters);
        try {
            pp.getCurrentPage();
            Assert.fail("Knn should have failed");
        } catch (NuxeoException e) {
            Throwable t = e.getCause();
            Assert.assertTrue(t instanceof OpenSearchStatusException);
            Assert.assertTrue(t.getMessage().contains("reason=unknown query [knn]"));
        }
    }

}
