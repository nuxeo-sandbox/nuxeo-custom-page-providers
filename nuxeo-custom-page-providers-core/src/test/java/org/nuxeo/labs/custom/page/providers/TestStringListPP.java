/*
 * (C) Copyright 2016 Nuxeo (http://nuxeo.com/) and others.
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
 *     Thibaud Arguillere
 */
package org.nuxeo.labs.custom.page.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.core.util.PageProviderHelper;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.test.DefaultRepositoryInit;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.ecm.platform.query.api.PageProvider;
import org.nuxeo.ecm.platform.query.api.PageProviderDefinition;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

@RunWith(FeaturesRunner.class)
@Features(AutomationFeature.class)
@RepositoryConfig(init = DefaultRepositoryInit.class, cleanup = Granularity.METHOD)
@Deploy({"nuxeo-custom-page-providers-core"})
public class TestStringListPP {

    @Inject
    protected CoreSession session;

    @Inject
    protected TransactionalFeature transactionalFeature;
    
    protected DocumentModel createDocument(String title) {
        
        DocumentModel doc = session.createDocumentModel("/", title, "File");
        return session.createDocument(doc);
    }
    
    @Test
    // Deploy the TestStringList doc type, its simplestringlist field, the contrib. to the PP.
    @Deploy("nuxeo-custom-page-providers-core:StringListPageProvider-test.xml")
    public void testPP() {
        
        DocumentModel doc;

        // Create a couple docs
        ArrayList<String> uids = new ArrayList<String>();
        doc = createDocument("file1");
        uids.add(doc.getId());
        doc = createDocument("file2");
        uids.add(doc.getId());
        doc = createDocument("file3");
        uids.add(doc.getId());
        
        // Revert order
        Collections.reverse(uids);
        
        // Main doc
        doc = session.createDocumentModel("/", "test", "TestStringList");
        doc.setPropertyValue("simplestringlist:stringList", uids);
        doc = session.createDocument(doc);
        assertNotNull(doc);
        
        transactionalFeature.nextTransaction();
        
        doc.refresh();
        
        // Search
        PageProviderDefinition def = PageProviderHelper.getPageProviderDefinition("stringlistPP");
        HashMap<String,String> namedParameters = new HashMap<>();
        namedParameters.put("currentDocumentId", doc.getId());
        PageProvider<?> pp = PageProviderHelper.getPageProvider(session, def, namedParameters);
        @SuppressWarnings("unchecked")
        List<DocumentModel> docs = (List<DocumentModel>) pp.getCurrentPage();
        
        // Check, correct order
        assertEquals("file3", docs.get(0).getName());
        assertEquals("file2", docs.get(1).getName());
        assertEquals("file1", docs.get(2).getName());
        
    }

}
