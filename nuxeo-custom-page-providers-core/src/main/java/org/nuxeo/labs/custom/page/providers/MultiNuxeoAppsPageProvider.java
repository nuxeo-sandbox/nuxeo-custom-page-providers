/*
 * (C) Copyright 2025 Hyland (http://hyland.com/)  and others.
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

import static org.nuxeo.ecm.platform.query.api.PageProviderService.NAMED_PARAMETERS;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.impl.DocumentModelListImpl;
import org.nuxeo.ecm.platform.query.api.AbstractPageProvider;
import org.nuxeo.ecm.platform.query.api.PageProvider;
import org.nuxeo.runtime.api.Framework;

/**
 * @since TODO
 */
public class MultiNuxeoAppsPageProvider extends AbstractPageProvider<DocumentModel>
        implements PageProvider<DocumentModel> {

    private static final long serialVersionUID = -8773839665513784995L;

    public static final String MULTI_APPS_CONFIG_PARAM = "multi.nuxeo.apps.search";

    protected static List<NuxeoApp> nuxeoApps = null;

    protected void loadNuxeoApps() {
        if (nuxeoApps == null) {
            nuxeoApps = new ArrayList<NuxeoApp>();

            String config = Framework.getProperty(MULTI_APPS_CONFIG_PARAM);
            JSONArray nuxeoAppsJson = new JSONArray(config);
            for (int i = 0; i < nuxeoAppsJson.length(); i++) {
                JSONObject obj = nuxeoAppsJson.getJSONObject(i);
                nuxeoApps.add(new NuxeoApp(obj));
            }
        }
    }

    @Override
    public List<DocumentModel> getCurrentPage() {

        DocumentModelList docs = new DocumentModelListImpl();
        JSONArray finalresult = new JSONArray();

        // fallback to default implementation if there is no vector search
        DocumentModel searchDoc = getSearchDocumentModel();
        if (searchDoc == null) {
            return getEmptyResult();
        }

        Map<String, String> namedParameters = (Map<String, String>) searchDoc.getContextData(NAMED_PARAMETERS);
        String fulltextSearchValues = namedParameters.get("fulltextSearchValues");
        String overrideNXQL = namedParameters.get("overrideNXQL");
        nuxeoApps.forEach(oneApp -> {
            JSONObject result = oneApp.call(fulltextSearchValues, overrideNXQL);
            finalresult.put(result);
        });

        return docs;
    }

    public DocumentModelList getEmptyResult() {
        setResultsCount(0);
        return new DocumentModelListImpl();
    }

    protected class NuxeoApp {
        public String appName;

        public String appUrl;

        public String basicUser;

        public String basicPwd;

        public NuxeoApp(JSONObject obj) {
            appName = obj.getString("appName");
            appUrl = obj.getString("appUrl");
            basicUser = obj.getString("basicUser");
            basicPwd = obj.getString("basicPwd");
        }

        public JSONObject call(String fulltextSearchValues, String overrideNXQL) {

            JSONObject result = null;
            HttpURLConnection connection = null;

            String targetUrl = appUrl + "/api/v1/search/lang/NXQL/execute";
            String nxql;

            if (StringUtils.isNotBlank(overrideNXQL)) {
                nxql = overrideNXQL;
            } else {
                nxql = "SELECT * FROM Document WHERE ecm:fulltext='" + fulltextSearchValues + "'";
                nxql += " AND ecm:isVersion = 0 AND ecm:isProxy = 0 AND ecm:isTrashed = 0";
                nxql += " AND ecm:mixinType != 'HiddenInNavigation'";
            }

            try {
                String encodedNxql;
                encodedNxql = URLEncoder.encode(nxql, "UTF-8");
                targetUrl += "?query=" + encodedNxql;

                URL url = new URL(targetUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                // Add Basic Auth header
                String auth = basicUser + ":" + basicPwd;
                String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes("UTF-8"));
                connection.setRequestProperty("Authorization", "Basic " + encodedAuth);
                // More headers
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("enrichers.document", "thumbnail");
                connection.setRequestProperty("properties", "dublincore,file,uid");

                // Read response
                int status = connection.getResponseCode();
                if (status == 200) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    result = new JSONObject(response.toString());

                    JSONArray entries = result.getJSONArray("entries");
                    for (int i = 0; i < entries.length(); i++) {
                        JSONObject oneDoc = entries.getJSONObject(i);
                        oneDoc.put("docFullUrl", appUrl + "/ui/#!/doc/" + oneDoc.getString("uid"));
                        oneDoc.put("appName", appName);
                    }

                    result.put("responseStatus", status);
                    // System.out.println("Response JSON: " + result.toString(2));

                } else {
                    result = new JSONObject();
                    result.put("responseStatus", status);
                    result.put("responseMessage", connection.getResponseMessage());
                }

                connection.disconnect();
                connection = null;

            } catch (IOException e) {
                throw new NuxeoException(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                    connection = null;
                }
            }

            return result;
        }

    }

}
