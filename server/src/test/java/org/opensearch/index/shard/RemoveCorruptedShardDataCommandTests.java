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
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package org.opensearch.index.shard;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.apache.lucene.store.BaseDirectoryWrapper;
import org.opensearch.OpenSearchException;
import org.opensearch.Version;
import org.opensearch.cli.MockTerminal;
import org.opensearch.cli.Terminal;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.routing.RecoverySource;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingHelper;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.TestShardRouting;
import org.opensearch.common.CheckedFunction;
import org.opensearch.common.UUIDs;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.BigArrays;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.env.TestEnvironment;
import org.opensearch.gateway.PersistedClusterStateService;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.MergePolicyConfig;
import org.opensearch.index.engine.EngineException;
import org.opensearch.index.engine.InternalEngineFactory;
import org.opensearch.index.seqno.RetentionLeaseSyncer;
import org.opensearch.index.store.Store;
import org.opensearch.index.translog.TestTranslog;
import org.opensearch.index.translog.TranslogCorruptedException;
import org.opensearch.test.CorruptionUtils;
import org.opensearch.test.DummyShardLock;
import org.junit.Before;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.opensearch.index.shard.RemoveCorruptedShardDataCommand.TRUNCATE_CLEAN_TRANSLOG_FLAG;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

public class RemoveCorruptedShardDataCommandTests extends IndexShardTestCase {

    private ShardId shardId;
    private ShardRouting routing;
    private Environment environment;
    private ShardPath shardPath;
    private IndexMetadata indexMetadata;
    private ClusterState clusterState;
    private IndexShard indexShard;
    private Path[] dataPaths;
    private Path translogPath;
    private Path indexPath;

    private static final Pattern NUM_CORRUPT_DOCS_PATTERN =
        Pattern.compile("Corrupted Lucene index segments found -\\s+(?<docs>\\d+) documents will be lost.");

    @Before
    public void setup() throws IOException {
        shardId = new ShardId("index0", UUIDs.randomBase64UUID(), 0);
        final String nodeId = randomAlphaOfLength(10);
        routing = TestShardRouting.newShardRouting(shardId, nodeId, true, ShardRoutingState.INITIALIZING,
            RecoverySource.EmptyStoreRecoverySource.INSTANCE);

        final Path dataDir = createTempDir();

        environment =
            TestEnvironment.newEnvironment(Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), dataDir)
                .putList(Environment.PATH_DATA_SETTING.getKey(), dataDir.toAbsolutePath().toString()).build());

        // create same directory structure as prod does
        final Path path = NodeEnvironment.resolveNodePath(dataDir, 0);
        Files.createDirectories(path);
        dataPaths = new Path[] {path};
        final Settings settings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(MergePolicyConfig.INDEX_MERGE_ENABLED, false)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetadata.SETTING_INDEX_UUID, shardId.getIndex().getUUID())
            .build();

        final NodeEnvironment.NodePath nodePath = new NodeEnvironment.NodePath(path);
        shardPath = new ShardPath(false, nodePath.resolve(shardId), nodePath.resolve(shardId), shardId);
        final IndexMetadata.Builder metadata = IndexMetadata.builder(routing.getIndexName())
            .settings(settings)
            .primaryTerm(0, randomIntBetween(1, 100))
            .putMapping("_doc", "{ \"properties\": {} }");
        indexMetadata = metadata.build();

        clusterState = ClusterState.builder(ClusterName.DEFAULT).metadata(Metadata.builder().put(indexMetadata, false).build()).build();

        try (NodeEnvironment.NodeLock lock = new NodeEnvironment.NodeLock(0, logger, environment, Files::exists)) {
            final Path[] dataPaths = Arrays.stream(lock.getNodePaths()).filter(Objects::nonNull).map(p -> p.path).toArray(Path[]::new);
            try (PersistedClusterStateService.Writer writer = new PersistedClusterStateService(dataPaths, nodeId,
                xContentRegistry(), BigArrays.NON_RECYCLING_INSTANCE,
                new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), () -> 0L).createWriter()) {
                writer.writeFullStateAndCommit(1L, clusterState);
            }
        }

        indexShard = newStartedShard(p -> newShard(routing, shardPath, indexMetadata, null, null,
            new InternalEngineFactory(), () -> { }, RetentionLeaseSyncer.EMPTY, EMPTY_EVENT_LISTENER), true);

        translogPath = shardPath.resolveTranslog();
        indexPath = shardPath.resolveIndex();
    }

    public void testShardLock() throws Exception {
        indexDocs(indexShard, true);

        final RemoveCorruptedShardDataCommand command = new RemoveCorruptedShardDataCommand();
        final MockTerminal t = new MockTerminal();
        final OptionParser parser = command.getParser();

        // Try running it before the shard is closed, it should flip out because it can't acquire the lock
        try {
            final OptionSet options = parser.parse("-d", indexPath.toString());
            command.execute(t, options, environment);
            fail("expected the command to fail not being able to acquire the lock");
        } catch (Exception e) {
            assertThat(e.getMessage(), containsString("Failed to lock shard's directory"));
        }

        // close shard
        closeShards(indexShard);

        // Try running it before the shard is corrupted
        try {
            final OptionSet options = parser.parse("-d", indexPath.toString());
            command.execute(t, options, environment);
            fail("expected the command to fail not being able to find a corrupt file marker");
        } catch (OpenSearchException e) {
            assertThat(e.getMessage(), startsWith("Shard does not seem to be corrupted at"));
            assertThat(t.getOutput(), containsString("Lucene index is clean at"));
        }
    }

    public void testCorruptedIndex() throws Exception {
        final int numDocs = indexDocs(indexShard, true);

        // close shard
        closeShards(indexShard);

        final boolean corruptSegments = randomBoolean();
        CorruptionUtils.corruptIndex(random(), indexPath, corruptSegments);

        if (randomBoolean()) {
            // test corrupted shard and add corruption marker
            final IndexShard corruptedShard = reopenIndexShard(true);
            allowShardFailures();
            expectThrows(IndexShardRecoveryException.class, () -> newStartedShard(p -> corruptedShard, true));
            closeShards(corruptedShard);
        }

        final RemoveCorruptedShardDataCommand command = new RemoveCorruptedShardDataCommand();
        final MockTerminal t = new MockTerminal();
        final OptionParser parser = command.getParser();

        // run command with dry-run
        t.addTextInput("n"); // mean dry run
        final OptionSet options = parser.parse("-d", indexPath.toString());
        t.setVerbosity(Terminal.Verbosity.VERBOSE);
        try {
            command.execute(t, options, environment);
            fail();
        } catch (OpenSearchException e) {
            if (corruptSegments) {
                assertThat(e.getMessage(), either(is("Index is unrecoverable")).or(startsWith("unable to list commits")));
            } else {
                assertThat(e.getMessage(), containsString("aborted by user"));
            }
        } finally {
            logger.info("--> output:\n{}", t.getOutput());
        }

        if (corruptSegments == false) {

            // run command without dry-run
            t.addTextInput("y");
            command.execute(t, options, environment);

            final String output = t.getOutput();
            logger.info("--> output:\n{}", output);

            // reopen shard
            failOnShardFailures();
            final IndexShard newShard = newStartedShard(p -> reopenIndexShard(false), true);

            final Set<String> shardDocUIDs = getShardDocUIDs(newShard);

            final Matcher matcher = NUM_CORRUPT_DOCS_PATTERN.matcher(output);
            assertThat(matcher.find(), equalTo(true));
            final int expectedNumDocs = numDocs - Integer.parseInt(matcher.group("docs"));

            assertThat(shardDocUIDs.size(), equalTo(expectedNumDocs));

            closeShards(newShard);
        }
    }

    public void testCorruptedTranslog() throws Exception {
        final int numDocsToKeep = indexDocs(indexShard, false);

        // close shard
        closeShards(indexShard);

        TestTranslog.corruptRandomTranslogFile(logger, random(), translogPath);

        // test corrupted shard
        final IndexShard corruptedShard = reopenIndexShard(true);

        allowShardFailures();
        // it has to fail on start up due to index.shard.check_on_startup = checksum
        final Exception exception = expectThrows(Exception.class, () -> newStartedShard(p -> corruptedShard, true));
        final Throwable cause = exception.getCause() instanceof EngineException ? exception.getCause().getCause() : exception.getCause();
        assertThat(cause, instanceOf(TranslogCorruptedException.class));

        closeShard(corruptedShard, false); // translog is corrupted already - do not check consistency

        final RemoveCorruptedShardDataCommand command = new RemoveCorruptedShardDataCommand();
        final MockTerminal t = new MockTerminal();
        final OptionParser parser = command.getParser();

        final OptionSet options = parser.parse("-d", translogPath.toString());
        // run command with dry-run
        t.addTextInput("n"); // mean dry run
        t.setVerbosity(Terminal.Verbosity.VERBOSE);
        try {
            command.execute(t, options, environment);
            fail();
        } catch (OpenSearchException e) {
            assertThat(e.getMessage(), containsString("aborted by user"));
            assertThat(t.getOutput(), containsString("Continue and remove corrupted data from the shard ?"));
        }

        logger.info("--> output:\n{}", t.getOutput());

        // run command without dry-run
        t.reset();
        t.addTextInput("y");
        command.execute(t, options, environment);

        final String output = t.getOutput();
        logger.info("--> output:\n{}", output);

        // reopen shard
        failOnShardFailures();
        final IndexShard newShard = newStartedShard(p -> reopenIndexShard(false), true);

        final Set<String> shardDocUIDs = getShardDocUIDs(newShard);

        assertThat(shardDocUIDs.size(), equalTo(numDocsToKeep));

        closeShards(newShard);
    }

    public void testCorruptedBothIndexAndTranslog() throws Exception {
        // index some docs in several segments
        final int numDocsToKeep = indexDocs(indexShard, false);

        // close shard
        closeShards(indexShard);

        CorruptionUtils.corruptIndex(random(), indexPath, false);

        if (randomBoolean()) {
            // test corrupted shard and add corruption marker
            final IndexShard corruptedShard = reopenIndexShard(true);
            allowShardFailures();
            expectThrows(IndexShardRecoveryException.class, () -> newStartedShard(p -> corruptedShard, true));
            closeShards(corruptedShard);
        }
        TestTranslog.corruptRandomTranslogFile(logger, random(), translogPath);

        final RemoveCorruptedShardDataCommand command = new RemoveCorruptedShardDataCommand();
        final MockTerminal t = new MockTerminal();
        final OptionParser parser = command.getParser();

        final OptionSet options = parser.parse("-d", translogPath.toString());
        // run command with dry-run
        t.addTextInput("n"); // mean dry run
        t.addTextInput("n"); // mean dry run
        t.setVerbosity(Terminal.Verbosity.VERBOSE);
        try {
            command.execute(t, options, environment);
            fail();
        } catch (OpenSearchException e) {
            assertThat(e.getMessage(), containsString("aborted by user"));
            assertThat(t.getOutput(), containsString("Continue and remove corrupted data from the shard ?"));
        }

        logger.info("--> output:\n{}", t.getOutput());

        // run command without dry-run
        t.reset();
        t.addTextInput("y");
        command.execute(t, options, environment);

        final String output = t.getOutput();
        logger.info("--> output:\n{}", output);

        // reopen shard
        failOnShardFailures();
        final IndexShard newShard = newStartedShard(p -> reopenIndexShard(false), true);

        final Set<String> shardDocUIDs = getShardDocUIDs(newShard);

        final Matcher matcher = NUM_CORRUPT_DOCS_PATTERN.matcher(output);
        assertThat(matcher.find(), equalTo(true));
        final int expectedNumDocs = numDocsToKeep - Integer.parseInt(matcher.group("docs"));

        assertThat(shardDocUIDs.size(), equalTo(expectedNumDocs));

        closeShards(newShard);
    }

    public void testResolveIndexDirectory() throws Exception {
        // index a single doc to have files on a disk
        indexDoc(indexShard, "_doc", "0", "{}");
        flushShard(indexShard, true);

        // close shard
        closeShards(indexShard);

        final RemoveCorruptedShardDataCommand command = new RemoveCorruptedShardDataCommand();
        final OptionParser parser = command.getParser();

        // `--index index_name --shard-id 0` has to be resolved to indexPath
        final OptionSet options = parser.parse("--index", shardId.getIndex().getName(),
            "--shard-id", Integer.toString(shardId.id()));

        command.findAndProcessShardPath(options, environment, dataPaths, 0, clusterState,
            shardPath -> assertThat(shardPath.resolveIndex(), equalTo(indexPath)));

        final OptionSet options2 = parser.parse("--dir", indexPath.toAbsolutePath().toString());
        command.findAndProcessShardPath(options2, environment, dataPaths, 0, clusterState,
            shardPath -> assertThat(shardPath.resolveIndex(), equalTo(indexPath)));
    }

    public void testFailsOnCleanIndex() throws Exception {
        indexDocs(indexShard, true);
        closeShards(indexShard);

        final RemoveCorruptedShardDataCommand command = new RemoveCorruptedShardDataCommand();
        final MockTerminal t = new MockTerminal();
        final OptionParser parser = command.getParser();

        final OptionSet options = parser.parse("-d", translogPath.toString());
        t.setVerbosity(Terminal.Verbosity.VERBOSE);
        assertThat(expectThrows(OpenSearchException.class, () -> command.execute(t, options, environment)).getMessage(),
            allOf(containsString("Shard does not seem to be corrupted"), containsString("--" + TRUNCATE_CLEAN_TRANSLOG_FLAG)));
        assertThat(t.getOutput(), containsString("Lucene index is clean"));
        assertThat(t.getOutput(), containsString("Translog is clean"));
    }

    public void testTruncatesCleanTranslogIfRequested() throws Exception {
        indexDocs(indexShard, true);
        closeShards(indexShard);

        final RemoveCorruptedShardDataCommand command = new RemoveCorruptedShardDataCommand();
        final MockTerminal t = new MockTerminal();
        final OptionParser parser = command.getParser();

        final OptionSet options = parser.parse("-d", translogPath.toString(), "--" + TRUNCATE_CLEAN_TRANSLOG_FLAG);
        t.addTextInput("y");
        t.setVerbosity(Terminal.Verbosity.VERBOSE);
        command.execute(t, options, environment);
        assertThat(t.getOutput(), containsString("Lucene index is clean"));
        assertThat(t.getOutput(), containsString("Translog was not analysed and will be truncated"));
        assertThat(t.getOutput(), containsString("Creating new empty translog"));
    }

    public void testCleanWithCorruptionMarker() throws Exception {
        // index some docs in several segments
        final int numDocs = indexDocs(indexShard, true);

        indexShard.store().markStoreCorrupted(null);

        closeShards(indexShard);

        allowShardFailures();
        final IndexShard corruptedShard = reopenIndexShard(true);
        expectThrows(IndexShardRecoveryException.class, () -> newStartedShard(p -> corruptedShard, true));
        closeShards(corruptedShard);

        final RemoveCorruptedShardDataCommand command = new RemoveCorruptedShardDataCommand();
        final MockTerminal t = new MockTerminal();
        final OptionParser parser = command.getParser();

        final OptionSet options = parser.parse("-d", translogPath.toString());
        // run command with dry-run
        t.addTextInput("n"); // mean dry run
        t.addTextInput("n"); // mean dry run
        t.setVerbosity(Terminal.Verbosity.VERBOSE);
        try {
            command.execute(t, options, environment);
            fail();
        } catch (OpenSearchException e) {
            assertThat(e.getMessage(), containsString("aborted by user"));
            assertThat(t.getOutput(), containsString("Continue and remove corrupted data from the shard ?"));
            assertThat(t.getOutput(), containsString("Lucene index is marked corrupted, but no corruption detected"));
        }

        logger.info("--> output:\n{}", t.getOutput());

        // run command without dry-run
        t.reset();
        t.addTextInput("y");
        t.addTextInput("y");
        command.execute(t, options, environment);

        final String output = t.getOutput();
        logger.info("--> output:\n{}", output);

        failOnShardFailures();
        final IndexShard newShard = newStartedShard(p -> reopenIndexShard(false), true);

        final Set<String> shardDocUIDs = getShardDocUIDs(newShard);
        assertEquals(numDocs, shardDocUIDs.size());

        assertThat(t.getOutput(), containsString("This shard has been marked as corrupted but no corruption can now be detected."));

        final Matcher matcher = NUM_CORRUPT_DOCS_PATTERN.matcher(output);
        assertFalse(matcher.find());

        closeShards(newShard);
    }

    private IndexShard reopenIndexShard(boolean corrupted) throws IOException {
        // open shard with the same location
        final ShardRouting shardRouting = ShardRoutingHelper.initWithSameId(indexShard.routingEntry(),
            RecoverySource.ExistingStoreRecoverySource.INSTANCE
        );

        final IndexMetadata metadata = IndexMetadata.builder(indexMetadata)
            .settings(Settings.builder()
                .put(indexShard.indexSettings().getSettings())
                .put(IndexSettings.INDEX_CHECK_ON_STARTUP.getKey(), "checksum"))
            .build();

        CheckedFunction<IndexSettings, Store, IOException> storeProvider =
            corrupted == false ? null :
                indexSettings -> {
                    final ShardId shardId = shardPath.getShardId();
                    final BaseDirectoryWrapper baseDirectoryWrapper = newFSDirectory(shardPath.resolveIndex());
                    // index is corrupted - don't even try to check index on close - it fails
                    baseDirectoryWrapper.setCheckIndexOnClose(false);
                    return new Store(shardId, indexSettings, baseDirectoryWrapper, new DummyShardLock(shardId));
        };

        return newShard(shardRouting, shardPath, metadata, storeProvider, null, indexShard.engineFactory,
            indexShard.getGlobalCheckpointSyncer(), indexShard.getRetentionLeaseSyncer(), EMPTY_EVENT_LISTENER);
    }

    private int indexDocs(IndexShard indexShard, boolean flushLast) throws IOException {
        // index some docs in several segments
        int numDocs = 0;
        int numDocsToKeep = 0;
        for (int i = 0, attempts = randomIntBetween(5, 10); i < attempts; i++) {
            final int numExtraDocs = between(10, 100);
            for (long j = 0; j < numExtraDocs; j++) {
                indexDoc(indexShard, "_doc", Long.toString(numDocs + j), "{}");
            }
            numDocs += numExtraDocs;

            if (flushLast || i < attempts - 1) {
                numDocsToKeep += numExtraDocs;
                flushShard(indexShard, true);
            }
        }

        logger.info("--> indexed {} docs, {} to keep", numDocs, numDocsToKeep);

        return numDocsToKeep;
    }

}
