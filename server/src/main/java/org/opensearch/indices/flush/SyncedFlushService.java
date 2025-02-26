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

package org.opensearch.indices.flush;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.opensearch.LegacyESVersion;
import org.opensearch.OpenSearchException;
import org.opensearch.Version;
import org.opensearch.action.ActionListener;
import org.opensearch.action.StepListener;
import org.opensearch.action.admin.indices.flush.FlushRequest;
import org.opensearch.action.admin.indices.flush.SyncedFlushResponse;
import org.opensearch.action.support.IndicesOptions;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.routing.IndexShardRoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Nullable;
import org.opensearch.common.Strings;
import org.opensearch.common.UUIDs;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.logging.DeprecationLogger;
import org.opensearch.common.util.concurrent.AbstractRunnable;
import org.opensearch.common.util.concurrent.ConcurrentCollections;
import org.opensearch.common.util.concurrent.CountDown;
import org.opensearch.index.Index;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.IndexService;
import org.opensearch.index.engine.CommitStats;
import org.opensearch.index.engine.Engine;
import org.opensearch.index.shard.IndexEventListener;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.shard.IndexShardState;
import org.opensearch.index.shard.ShardId;
import org.opensearch.index.shard.ShardNotFoundException;
import org.opensearch.indices.IndexClosedException;
import org.opensearch.indices.IndicesService;
import org.opensearch.tasks.Task;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportChannel;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportRequest;
import org.opensearch.transport.TransportRequestHandler;
import org.opensearch.transport.TransportResponse;
import org.opensearch.transport.TransportResponseHandler;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

public class SyncedFlushService implements IndexEventListener {

    private static final Logger logger = LogManager.getLogger(SyncedFlushService.class);

    private static final DeprecationLogger DEPRECATION_LOGGER = DeprecationLogger.getLogger(logger.getName());

    public static final String SYNCED_FLUSH_DEPRECATION_MESSAGE =
        "Synced flush is deprecated and will be removed in 8.0. Use flush at _/flush or /{index}/_flush instead.";

    private static final String PRE_SYNCED_FLUSH_ACTION_NAME = "internal:indices/flush/synced/pre";
    private static final String SYNCED_FLUSH_ACTION_NAME = "internal:indices/flush/synced/sync";
    private static final String IN_FLIGHT_OPS_ACTION_NAME = "internal:indices/flush/synced/in_flight";

    private final IndicesService indicesService;
    private final ClusterService clusterService;
    private final TransportService transportService;
    private final IndexNameExpressionResolver indexNameExpressionResolver;

    @Inject
    public SyncedFlushService(IndicesService indicesService,
                              ClusterService clusterService,
                              TransportService transportService,
                              IndexNameExpressionResolver indexNameExpressionResolver) {
        this.indicesService = indicesService;
        this.clusterService = clusterService;
        this.transportService = transportService;
        this.indexNameExpressionResolver = indexNameExpressionResolver;
        transportService.registerRequestHandler(PRE_SYNCED_FLUSH_ACTION_NAME, ThreadPool.Names.FLUSH, PreShardSyncedFlushRequest::new,
            new PreSyncedFlushTransportHandler());
        transportService.registerRequestHandler(SYNCED_FLUSH_ACTION_NAME, ThreadPool.Names.FLUSH, ShardSyncedFlushRequest::new,
            new SyncedFlushTransportHandler());
        transportService.registerRequestHandler(IN_FLIGHT_OPS_ACTION_NAME, ThreadPool.Names.SAME, InFlightOpsRequest::new,
            new InFlightOpCountTransportHandler());
    }

    @Override
    public void onShardInactive(final IndexShard indexShard) {
        // A normal flush has the same effect as a synced flush if all nodes are on 7.6 or later.
        final boolean preferNormalFlush = clusterService.state().nodes().getMinNodeVersion().onOrAfter(LegacyESVersion.V_7_6_0);
        if (preferNormalFlush) {
            performNormalFlushOnInactive(indexShard);
        } else if (indexShard.routingEntry().primary()) {
            // we only want to call sync flush once, so only trigger it when we are on a primary
            attemptSyncedFlush(indexShard.shardId(), new ActionListener<ShardsSyncedFlushResult>() {
                @Override
                public void onResponse(ShardsSyncedFlushResult syncedFlushResult) {
                    logger.trace("{} sync flush on inactive shard returned successfully for sync_id: {}",
                        syncedFlushResult.getShardId(), syncedFlushResult.syncId());
                }

                @Override
                public void onFailure(Exception e) {
                    logger.debug(() -> new ParameterizedMessage("{} sync flush on inactive shard failed", indexShard.shardId()), e);
                }
            });
        }
    }

    private void performNormalFlushOnInactive(IndexShard shard) {
        logger.debug("flushing shard {} on inactive", shard.routingEntry());
        shard.getThreadPool().executor(ThreadPool.Names.FLUSH).execute(new AbstractRunnable() {
            @Override
            public void onFailure(Exception e) {
                if (shard.state() != IndexShardState.CLOSED) {
                    logger.warn(new ParameterizedMessage("failed to flush shard {} on inactive", shard.routingEntry()), e);
                }
            }

            @Override
            protected void doRun() {
                shard.flush(new FlushRequest().force(false).waitIfOngoing(false));
            }
        });
    }

    /**
     * a utility method to perform a synced flush for all shards of multiple indices.
     * see {@link #attemptSyncedFlush(ShardId, ActionListener)}
     * for more details.
     */
    public void attemptSyncedFlush(final String[] aliasesOrIndices,
                                   IndicesOptions indicesOptions,
                                   final ActionListener<SyncedFlushResponse> listener) {
        final ClusterState state = clusterService.state();
        if (state.nodes().getMinNodeVersion().onOrAfter(LegacyESVersion.V_7_6_0)) {
            DEPRECATION_LOGGER.deprecate("synced_flush", SYNCED_FLUSH_DEPRECATION_MESSAGE);
        }
        final Index[] concreteIndices = indexNameExpressionResolver.concreteIndices(state, indicesOptions, aliasesOrIndices);
        final Map<String, List<ShardsSyncedFlushResult>> results = ConcurrentCollections.newConcurrentMap();
        int numberOfShards = 0;
        for (Index index : concreteIndices) {
            final IndexMetadata indexMetadata = state.metadata().getIndexSafe(index);
            numberOfShards += indexMetadata.getNumberOfShards();
            results.put(index.getName(), Collections.synchronizedList(new ArrayList<>()));

        }
        if (numberOfShards == 0) {
            listener.onResponse(new SyncedFlushResponse(results));
            return;
        }
        final CountDown countDown = new CountDown(numberOfShards);

        for (final Index concreteIndex : concreteIndices) {
            final String index = concreteIndex.getName();
            final IndexMetadata indexMetadata = state.metadata().getIndexSafe(concreteIndex);
            final int indexNumberOfShards = indexMetadata.getNumberOfShards();
            for (int shard = 0; shard < indexNumberOfShards; shard++) {
                final ShardId shardId = new ShardId(indexMetadata.getIndex(), shard);
                innerAttemptSyncedFlush(shardId, state, new ActionListener<ShardsSyncedFlushResult>() {
                    @Override
                    public void onResponse(ShardsSyncedFlushResult syncedFlushResult) {
                        results.get(index).add(syncedFlushResult);
                        if (countDown.countDown()) {
                            listener.onResponse(new SyncedFlushResponse(results));
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        logger.debug("{} unexpected error while executing synced flush", shardId);
                        final int totalShards = indexMetadata.getNumberOfReplicas() + 1;
                        results.get(index).add(new ShardsSyncedFlushResult(shardId, totalShards, e.getMessage()));
                        if (countDown.countDown()) {
                            listener.onResponse(new SyncedFlushResponse(results));
                        }
                    }
                });
            }
        }
    }

    /*
    * Tries to flush all copies of a shard and write a sync id to it.
    * After a synced flush two shard copies may only contain the same sync id if they contain the same documents.
    * To ensure this, synced flush works in three steps:
    * 1. Flush all shard copies and gather the commit ids for each copy after the flush
    * 2. Ensure that there are no ongoing indexing operations on the primary
    * 3. Perform an additional flush on each shard copy that writes the sync id
    *
    * Step 3 is only executed on a shard if
    * a) the shard has no uncommitted changes since the last flush
    * b) the last flush was the one executed in 1 (use the collected commit id to verify this)
    *
    * This alone is not enough to ensure that all copies contain the same documents.
    * Without step 2 a sync id would be written for inconsistent copies in the following scenario:
    *
    * Write operation has completed on a primary and is being sent to replicas. The write request does not reach the
    * replicas until sync flush is finished.
    * Step 1 is executed. After the flush the commit points on primary contains a write operation that the replica does not have.
    * Step 3 will be executed on primary and replica as well because there are no uncommitted changes on primary (the first flush
    * committed them) and there are no uncommitted changes on the replica (the write operation has not reached the replica yet).
    *
    * Step 2 detects this scenario and fails the whole synced flush if a write operation is ongoing on the primary.
    * Together with the conditions for step 3 (same commit id and no uncommitted changes) this guarantees that a snc id will only
    * be written on a primary if no write operation was executed between step 1 and step 3 and sync id will only be written on
    * the replica if it contains the same changes that the primary contains.
    *
    * Synced flush is a best effort operation. The sync id may be written on all, some or none of the copies.
    **/
    public void attemptSyncedFlush(final ShardId shardId, final ActionListener<ShardsSyncedFlushResult> actionListener) {
        innerAttemptSyncedFlush(shardId, clusterService.state(), actionListener);
    }

    private void innerAttemptSyncedFlush(final ShardId shardId,
                                         final ClusterState state,
                                         final ActionListener<ShardsSyncedFlushResult> actionListener) {
        try {
            final IndexShardRoutingTable shardRoutingTable = getShardRoutingTable(shardId, state);
            final List<ShardRouting> activeShards = shardRoutingTable.activeShards();
            final int totalShards = shardRoutingTable.getSize();

            if (activeShards.size() == 0) {
                actionListener.onResponse(new ShardsSyncedFlushResult(shardId, totalShards, "no active shards"));
                return;
            }

            // 1. send pre-sync flushes to all replicas
            final StepListener<Map<String, PreSyncedFlushResponse>> presyncStep = new StepListener<>();
            sendPreSyncRequests(activeShards, state, shardId, presyncStep);

            // 2. fetch in flight operations
            final StepListener<InFlightOpsResponse> inflightOpsStep = new StepListener<>();
            presyncStep.whenComplete(presyncResponses -> {
                if (presyncResponses.isEmpty()) {
                    actionListener.onResponse(new ShardsSyncedFlushResult(shardId, totalShards, "all shards failed to commit on pre-sync"));
                } else {
                    getInflightOpsCount(shardId, state, shardRoutingTable, inflightOpsStep);
                }
            }, actionListener::onFailure);

            // 3. now send the sync request to all the shards
            inflightOpsStep.whenComplete(inFlightOpsResponse -> {
                final Map<String, PreSyncedFlushResponse> presyncResponses = presyncStep.result();
                final int inflight = inFlightOpsResponse.opCount();
                assert inflight >= 0;
                if (inflight != 0) {
                    actionListener.onResponse(
                        new ShardsSyncedFlushResult(shardId, totalShards, "[" + inflight + "] ongoing operations on primary"));
                } else {
                    final String sharedSyncId = sharedExistingSyncId(presyncResponses);
                    if (sharedSyncId != null) {
                        assert presyncResponses.values().stream().allMatch(r -> r.existingSyncId.equals(sharedSyncId)) :
                            "Not all shards have the same existing sync id [" + sharedSyncId + "], responses [" + presyncResponses + "]";
                        reportSuccessWithExistingSyncId(shardId, sharedSyncId, activeShards, totalShards, presyncResponses, actionListener);
                    }else {
                        String syncId = UUIDs.randomBase64UUID();
                        sendSyncRequests(syncId, activeShards, state, presyncResponses, shardId, totalShards, actionListener);
                    }
                }
            }, actionListener::onFailure);
        } catch (Exception e) {
            actionListener.onFailure(e);
        }
    }

    private String sharedExistingSyncId(Map<String, PreSyncedFlushResponse> preSyncedFlushResponses) {
        String existingSyncId = null;
        for (PreSyncedFlushResponse resp : preSyncedFlushResponses.values()) {
            if (Strings.isNullOrEmpty(resp.existingSyncId)) {
                return null;
            }
            if (existingSyncId == null) {
                existingSyncId = resp.existingSyncId;
            }
            if (existingSyncId.equals(resp.existingSyncId) == false) {
                return null;
            }
        }
        return existingSyncId;
    }

    private void reportSuccessWithExistingSyncId(ShardId shardId,
                                                 String existingSyncId,
                                                 List<ShardRouting> shards,
                                                 int totalShards,
                                                 Map<String, PreSyncedFlushResponse> preSyncResponses,
                                                 ActionListener<ShardsSyncedFlushResult> listener) {
        final Map<ShardRouting, ShardSyncedFlushResponse> results = new HashMap<>();
        for (final ShardRouting shard : shards) {
            if (preSyncResponses.containsKey(shard.currentNodeId())) {
                results.put(shard, new ShardSyncedFlushResponse((String) null));
            }
        }
        listener.onResponse(new ShardsSyncedFlushResult(shardId, existingSyncId, totalShards, results));
    }

    final IndexShardRoutingTable getShardRoutingTable(final ShardId shardId, final ClusterState state) {
        final IndexMetadata indexMetadata = state.getMetadata().index(shardId.getIndex());
        if (indexMetadata == null) {
            throw new IndexNotFoundException(shardId.getIndexName());
        } else if (indexMetadata.getState() == IndexMetadata.State.CLOSE) {
            throw new IndexClosedException(shardId.getIndex());
        }
        final IndexShardRoutingTable shardRoutingTable = state.routingTable().index(indexMetadata.getIndex()).shard(shardId.id());
        if (shardRoutingTable == null) {
            throw new ShardNotFoundException(shardId);
        }
        return shardRoutingTable;
    }

    /**
     * returns the number of in flight operations on primary. -1 upon error.
     */
    protected void getInflightOpsCount(final ShardId shardId,
                                       ClusterState state,
                                       IndexShardRoutingTable shardRoutingTable,
                                       final ActionListener<InFlightOpsResponse> listener) {
        try {
            final ShardRouting primaryShard = shardRoutingTable.primaryShard();
            final DiscoveryNode primaryNode = state.nodes().get(primaryShard.currentNodeId());
            if (primaryNode == null) {
                logger.trace("{} failed to resolve node for primary shard {}, skipping sync", shardId, primaryShard);
                listener.onResponse(new InFlightOpsResponse(-1));
                return;
            }
            logger.trace("{} retrieving in flight operation count", shardId);
            transportService.sendRequest(primaryNode, IN_FLIGHT_OPS_ACTION_NAME, new InFlightOpsRequest(shardId),
                    new TransportResponseHandler<InFlightOpsResponse>() {
                        @Override
                        public InFlightOpsResponse read(StreamInput in) throws IOException {
                            return new InFlightOpsResponse(in);
                        }

                        @Override
                        public void handleResponse(InFlightOpsResponse response) {
                            listener.onResponse(response);
                        }

                        @Override
                        public void handleException(TransportException exp) {
                            logger.debug("{} unexpected error while retrieving in flight op count", shardId);
                            listener.onFailure(exp);
                        }

                        @Override
                        public String executor() {
                            return ThreadPool.Names.SAME;
                        }
                    });
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    private int numDocsOnPrimary(List<ShardRouting> shards, Map<String, PreSyncedFlushResponse> preSyncResponses) {
        for (ShardRouting shard : shards) {
            if (shard.primary()) {
                final PreSyncedFlushResponse resp = preSyncResponses.get(shard.currentNodeId());
                if (resp != null) {
                    return resp.numDocs;
                }
            }
        }
        return PreSyncedFlushResponse.UNKNOWN_NUM_DOCS;
    }

    void sendSyncRequests(final String syncId,
                          final List<ShardRouting> shards,
                          ClusterState state,
                          Map<String, PreSyncedFlushResponse> preSyncResponses,
                          final ShardId shardId,
                          final int totalShards,
                          final ActionListener<ShardsSyncedFlushResult> listener) {
        final CountDown countDown = new CountDown(shards.size());
        final Map<ShardRouting, ShardSyncedFlushResponse> results = ConcurrentCollections.newConcurrentMap();
        final int numDocsOnPrimary = numDocsOnPrimary(shards, preSyncResponses);
        for (final ShardRouting shard : shards) {
            final DiscoveryNode node = state.nodes().get(shard.currentNodeId());
            if (node == null) {
                logger.trace("{} is assigned to an unknown node. skipping for sync id [{}]. shard routing {}", shardId, syncId, shard);
                results.put(shard, new ShardSyncedFlushResponse("unknown node"));
                countDownAndSendResponseIfDone(syncId, shards, shardId, totalShards, listener, countDown, results);
                continue;
            }
            final PreSyncedFlushResponse preSyncedResponse = preSyncResponses.get(shard.currentNodeId());
            if (preSyncedResponse == null) {
                logger.trace("{} can't resolve expected commit id for current node, skipping for sync id [{}]. shard routing {}",
                    shardId, syncId, shard);
                results.put(shard, new ShardSyncedFlushResponse("no commit id from pre-sync flush"));
                countDownAndSendResponseIfDone(syncId, shards, shardId, totalShards, listener, countDown, results);
                continue;
            }
            if (preSyncedResponse.numDocs != numDocsOnPrimary &&
                preSyncedResponse.numDocs != PreSyncedFlushResponse.UNKNOWN_NUM_DOCS &&
                numDocsOnPrimary != PreSyncedFlushResponse.UNKNOWN_NUM_DOCS) {
                logger.debug("{} can't issue sync id [{}] for replica [{}] with num docs [{}]; num docs on primary [{}]",
                    shardId, syncId, shard, preSyncedResponse.numDocs, numDocsOnPrimary);
                results.put(shard, new ShardSyncedFlushResponse("ongoing indexing operations: " +
                    "num docs on replica [" + preSyncedResponse.numDocs + "]; num docs on primary [" + numDocsOnPrimary + "]"));
                countDownAndSendResponseIfDone(syncId, shards, shardId, totalShards, listener, countDown, results);
                continue;
            }
            logger.trace("{} sending synced flush request to {}. sync id [{}].", shardId, shard, syncId);
            ShardSyncedFlushRequest syncedFlushRequest = new ShardSyncedFlushRequest(shard.shardId(), syncId, preSyncedResponse.commitId);
            transportService.sendRequest(node, SYNCED_FLUSH_ACTION_NAME, syncedFlushRequest,
                    new TransportResponseHandler<ShardSyncedFlushResponse>() {
                        @Override
                        public ShardSyncedFlushResponse read(StreamInput in) throws IOException {
                            return new ShardSyncedFlushResponse(in);
                        }

                        @Override
                        public void handleResponse(ShardSyncedFlushResponse response) {
                            ShardSyncedFlushResponse existing = results.put(shard, response);
                            assert existing == null : "got two answers for node [" + node + "]";
                            // count after the assert so we won't decrement twice in handleException
                            countDownAndSendResponseIfDone(syncId, shards, shardId, totalShards, listener, countDown, results);
                        }

                        @Override
                        public void handleException(TransportException exp) {
                            logger.trace(() -> new ParameterizedMessage("{} error while performing synced flush on [{}], skipping",
                                shardId, shard), exp);
                            results.put(shard, new ShardSyncedFlushResponse(exp.getMessage()));
                            countDownAndSendResponseIfDone(syncId, shards, shardId, totalShards, listener, countDown, results);
                        }

                        @Override
                        public String executor() {
                            return ThreadPool.Names.SAME;
                        }
                    });
        }

    }

    private void countDownAndSendResponseIfDone(String syncId,
                                                List<ShardRouting> shards,
                                                ShardId shardId,
                                                int totalShards,
                                                ActionListener<ShardsSyncedFlushResult> listener,
                                                CountDown countDown,
                                                Map<ShardRouting, ShardSyncedFlushResponse> results) {
        if (countDown.countDown()) {
            assert results.size() == shards.size();
            listener.onResponse(new ShardsSyncedFlushResult(shardId, syncId, totalShards, results));
        }
    }

    /**
     * send presync requests to all started copies of the given shard
     */
    void sendPreSyncRequests(final List<ShardRouting> shards,
                             final ClusterState state,
                             final ShardId shardId,
                             final ActionListener<Map<String, PreSyncedFlushResponse>> listener) {
        final CountDown countDown = new CountDown(shards.size());
        final ConcurrentMap<String, PreSyncedFlushResponse> presyncResponses = ConcurrentCollections.newConcurrentMap();
        for (final ShardRouting shard : shards) {
            logger.trace("{} sending pre-synced flush request to {}", shardId, shard);
            final DiscoveryNode node = state.nodes().get(shard.currentNodeId());
            if (node == null) {
                logger.trace("{} shard routing {} refers to an unknown node. skipping.", shardId, shard);
                if (countDown.countDown()) {
                    listener.onResponse(presyncResponses);
                }
                continue;
            }
            transportService.sendRequest(node, PRE_SYNCED_FLUSH_ACTION_NAME, new PreShardSyncedFlushRequest(shard.shardId()),
                new TransportResponseHandler<PreSyncedFlushResponse>() {
                @Override
                public PreSyncedFlushResponse read(StreamInput in) throws IOException {
                    return new PreSyncedFlushResponse(in);
                }

                @Override
                public void handleResponse(PreSyncedFlushResponse response) {
                    PreSyncedFlushResponse existing = presyncResponses.putIfAbsent(node.getId(), response);
                    assert existing == null : "got two answers for node [" + node + "]";
                    // count after the assert so we won't decrement twice in handleException
                    if (countDown.countDown()) {
                        listener.onResponse(presyncResponses);
                    }
                }

                @Override
                public void handleException(TransportException exp) {
                    logger.trace(() -> new ParameterizedMessage("{} error while performing pre synced flush on [{}], skipping",
                        shardId, shard), exp);
                    if (countDown.countDown()) {
                        listener.onResponse(presyncResponses);
                    }
                }

                @Override
                public String executor() {
                    return ThreadPool.Names.SAME;
                }
            });
        }
    }

    private PreSyncedFlushResponse performPreSyncedFlush(PreShardSyncedFlushRequest request) {
        IndexShard indexShard = indicesService.indexServiceSafe(request.shardId().getIndex()).getShard(request.shardId().id());
        FlushRequest flushRequest = new FlushRequest().force(false).waitIfOngoing(true);
        logger.trace("{} performing pre sync flush", request.shardId());
        indexShard.flush(flushRequest);
        final CommitStats commitStats = indexShard.commitStats();
        final Engine.CommitId commitId = commitStats.getRawCommitId();
        logger.trace("{} pre sync flush done. commit id {}, num docs {}", request.shardId(), commitId, commitStats.getNumDocs());
        return new PreSyncedFlushResponse(commitId, commitStats.getNumDocs(), commitStats.syncId());
    }

    private ShardSyncedFlushResponse performSyncedFlush(ShardSyncedFlushRequest request) {
        IndexService indexService = indicesService.indexServiceSafe(request.shardId().getIndex());
        IndexShard indexShard = indexService.getShard(request.shardId().id());
        logger.trace("{} performing sync flush. sync id [{}], expected commit id {}",
            request.shardId(), request.syncId(), request.expectedCommitId());
        Engine.SyncedFlushResult result = indexShard.syncFlush(request.syncId(), request.expectedCommitId());
        logger.trace("{} sync flush done. sync id [{}], result [{}]", request.shardId(), request.syncId(), result);
        switch (result) {
            case SUCCESS:
                return new ShardSyncedFlushResponse((String) null);
            case COMMIT_MISMATCH:
                return new ShardSyncedFlushResponse("commit has changed");
            case PENDING_OPERATIONS:
                return new ShardSyncedFlushResponse("pending operations");
            default:
                throw new OpenSearchException("unknown synced flush result [" + result + "]");
        }
    }

    private InFlightOpsResponse performInFlightOps(InFlightOpsRequest request) {
        IndexService indexService = indicesService.indexServiceSafe(request.shardId().getIndex());
        IndexShard indexShard = indexService.getShard(request.shardId().id());
        if (indexShard.routingEntry().primary() == false) {
            throw new IllegalStateException("[" + request.shardId() +"] expected a primary shard");
        }
        int opCount = indexShard.getActiveOperationsCount();
        return new InFlightOpsResponse(opCount == IndexShard.OPERATIONS_BLOCKED ? 0 : opCount);
    }

    public static final class PreShardSyncedFlushRequest extends TransportRequest {
        private ShardId shardId;

        public PreShardSyncedFlushRequest(StreamInput in) throws IOException {
            super(in);
            this.shardId = new ShardId(in);
        }

        public PreShardSyncedFlushRequest(ShardId shardId) {
            this.shardId = shardId;
        }

        @Override
        public String toString() {
            return "PreShardSyncedFlushRequest{" +
                    "shardId=" + shardId +
                    '}';
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            shardId.writeTo(out);
        }

        public ShardId shardId() {
            return shardId;
        }
    }

    /**
     * Response for first step of synced flush (flush) for one shard copy
     */
    static final class PreSyncedFlushResponse extends TransportResponse {
        static final int UNKNOWN_NUM_DOCS = -1;

        Engine.CommitId commitId;
        int numDocs;
        @Nullable String existingSyncId = null;

        PreSyncedFlushResponse(StreamInput in) throws IOException {
            super(in);
            commitId = new Engine.CommitId(in);
            if (includeNumDocs(in.getVersion())) {
                numDocs = in.readInt();
            } else {
                numDocs = UNKNOWN_NUM_DOCS;
            }
            if (includeExistingSyncId(in.getVersion())) {
                existingSyncId = in.readOptionalString();
            }
        }

        PreSyncedFlushResponse(Engine.CommitId commitId, int numDocs, String existingSyncId) {
            this.commitId = commitId;
            this.numDocs = numDocs;
            this.existingSyncId = existingSyncId;
        }

        boolean includeNumDocs(Version version) {
            return version.onOrAfter(LegacyESVersion.V_6_2_2);
        }

        boolean includeExistingSyncId(Version version) {
            return version.onOrAfter(LegacyESVersion.V_6_3_0);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            commitId.writeTo(out);
            if (includeNumDocs(out.getVersion())) {
                out.writeInt(numDocs);
            }
            if (includeExistingSyncId(out.getVersion())) {
                out.writeOptionalString(existingSyncId);
            }
        }
    }

    public static final class ShardSyncedFlushRequest extends TransportRequest {

        private String syncId;
        private Engine.CommitId expectedCommitId;
        private ShardId shardId;

        public ShardSyncedFlushRequest(StreamInput in) throws IOException {
            super(in);
            shardId = new ShardId(in);
            expectedCommitId = new Engine.CommitId(in);
            syncId = in.readString();
        }

        public ShardSyncedFlushRequest(ShardId shardId, String syncId, Engine.CommitId expectedCommitId) {
            this.expectedCommitId = expectedCommitId;
            this.shardId = shardId;
            this.syncId = syncId;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            shardId.writeTo(out);
            expectedCommitId.writeTo(out);
            out.writeString(syncId);
        }

        public ShardId shardId() {
            return shardId;
        }

        public String syncId() {
            return syncId;
        }

        public Engine.CommitId expectedCommitId() {
            return expectedCommitId;
        }

        @Override
        public String toString() {
            return "ShardSyncedFlushRequest{" +
                    "shardId=" + shardId +
                    ",syncId='" + syncId + '\'' +
                    '}';
        }
    }

    /**
     * Response for third step of synced flush (writing the sync id) for one shard copy
     */
    public static final class ShardSyncedFlushResponse extends TransportResponse {

        /**
         * a non null value indicates a failure to sync flush. null means success
         */
        String failureReason;

        public ShardSyncedFlushResponse(StreamInput in) throws IOException {
            super(in);
            failureReason = in.readOptionalString();
        }

        public ShardSyncedFlushResponse(String failureReason) {
            this.failureReason = failureReason;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeOptionalString(failureReason);
        }

        public boolean success() {
            return failureReason == null;
        }

        public String failureReason() {
            return failureReason;
        }

        @Override
        public String toString() {
            return "ShardSyncedFlushResponse{" +
                    "success=" + success() +
                    ", failureReason='" + failureReason + '\'' +
                    '}';
        }

        public static ShardSyncedFlushResponse readSyncedFlushResponse(StreamInput in) throws IOException {
            return new ShardSyncedFlushResponse(in);
        }
    }


    public static final class InFlightOpsRequest extends TransportRequest {

        private ShardId shardId;

        public InFlightOpsRequest(StreamInput in) throws IOException {
            super(in);
            shardId = new ShardId(in);
        }

        public InFlightOpsRequest(ShardId shardId) {
            this.shardId = shardId;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            shardId.writeTo(out);
        }

        public ShardId shardId() {
            return shardId;
        }

        @Override
        public String toString() {
            return "InFlightOpsRequest{" +
                    "shardId=" + shardId +
                    '}';
        }
    }

    /**
     * Response for second step of synced flush (check operations in flight)
     */
    static final class InFlightOpsResponse extends TransportResponse {

        int opCount;

        InFlightOpsResponse(StreamInput in) throws IOException {
            super(in);
            opCount = in.readVInt();
        }

        InFlightOpsResponse(int opCount) {
            assert opCount >= 0 : opCount;
            this.opCount = opCount;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeVInt(opCount);
        }

        public int opCount() {
            return opCount;
        }

        @Override
        public String toString() {
            return "InFlightOpsResponse{" +
                    "opCount=" + opCount +
                    '}';
        }
    }

    private final class PreSyncedFlushTransportHandler implements TransportRequestHandler<PreShardSyncedFlushRequest> {

        @Override
        public void messageReceived(PreShardSyncedFlushRequest request, TransportChannel channel, Task task) throws Exception {
            channel.sendResponse(performPreSyncedFlush(request));
        }
    }

    private final class SyncedFlushTransportHandler implements TransportRequestHandler<ShardSyncedFlushRequest> {

        @Override
        public void messageReceived(ShardSyncedFlushRequest request, TransportChannel channel, Task task) throws Exception {
            channel.sendResponse(performSyncedFlush(request));
        }
    }

    private final class InFlightOpCountTransportHandler implements TransportRequestHandler<InFlightOpsRequest> {

        @Override
        public void messageReceived(InFlightOpsRequest request, TransportChannel channel, Task task) throws Exception {
            channel.sendResponse(performInFlightOps(request));
        }
    }

}
