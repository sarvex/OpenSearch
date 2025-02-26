/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.action.bulk;

import org.opensearch.action.ActionFuture;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.OpenSearchRejectedExecutionException;
import org.opensearch.index.IndexService;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.InternalSettingsPlugin;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;

@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 2)
public class BulkRejectionIT extends OpenSearchIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            .put("thread_pool.write.size", 1)
            .put("thread_pool.write.queue_size", 1)
            .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(InternalSettingsPlugin.class);
    }

    @Override
    public Settings indexSettings() {
        return Settings.builder().put(super.indexSettings())
            // sync global checkpoint quickly so we can verify seq_no_stats aligned between all copies after tests.
            .put(IndexService.GLOBAL_CHECKPOINT_SYNC_INTERVAL_SETTING.getKey(), "1s").build();
    }

    @Override
    protected int numberOfReplicas() {
        return 1;
    }

    protected int numberOfShards() {
        return 5;
    }

    public void testBulkRejectionAfterDynamicMappingUpdate() throws Exception {
        final String index = "test";
        assertAcked(prepareCreate(index));
        ensureGreen();
        final BulkRequest request1 = new BulkRequest();
        for (int i = 0; i < 500; ++i) {
            request1.add(new IndexRequest(index).source(Collections.singletonMap("key" + i, "value" + i)))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
        }
        // Huge request to keep the write pool busy so that requests waiting on a mapping update in the other bulk request get rejected
        // by the write pool
        final BulkRequest request2 = new BulkRequest();
        for (int i = 0; i < 10_000; ++i) {
            request2.add(new IndexRequest(index).source(Collections.singletonMap("key", "valuea" + i)))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
        }
        final ActionFuture<BulkResponse> bulkFuture1 = client().bulk(request1);
        final ActionFuture<BulkResponse> bulkFuture2 = client().bulk(request2);
        try {
            bulkFuture1.actionGet();
            bulkFuture2.actionGet();
        } catch (OpenSearchRejectedExecutionException e) {
            // ignored, one of the two bulk requests was rejected outright due to the write queue being full
        }
        internalCluster().assertSeqNos();
    }
}
