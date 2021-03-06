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

package org.elasticsearch.indices.recovery;

import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.index.replication.ESIndexLevelReplicationTestCase;
import org.elasticsearch.index.replication.RecoveryDuringReplicationTests;
import org.elasticsearch.index.shard.IndexShard;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

import static org.hamcrest.Matchers.equalTo;

public class RecoveryTests extends ESIndexLevelReplicationTestCase {

    public void testTranslogHistoryTransferred() throws Exception {
        try (ReplicationGroup shards = createGroup(0)) {
            shards.startPrimary();
            int docs = shards.indexDocs(10);
            shards.getPrimary().getTranslog().rollGeneration();
            shards.flush();
            if (randomBoolean()) {
                docs += shards.indexDocs(10);
            }
            shards.addReplica();
            shards.startAll();
            final IndexShard replica = shards.getReplicas().get(0);
            assertThat(replica.getTranslog().totalOperations(), equalTo(docs));
        }
    }


    public void testRetentionPolicyChangeDuringRecovery() throws Exception {
        try (ReplicationGroup shards = createGroup(0)) {
            shards.startPrimary();
            shards.indexDocs(10);
            shards.getPrimary().getTranslog().rollGeneration();
            shards.flush();
            shards.indexDocs(10);
            final IndexShard replica = shards.addReplica();
            final CountDownLatch recoveryBlocked = new CountDownLatch(1);
            final CountDownLatch releaseRecovery = new CountDownLatch(1);
            Future<Void> future = shards.asyncRecoverReplica(replica,
                (indexShard, node) -> new RecoveryDuringReplicationTests.BlockingTarget(RecoveryState.Stage.TRANSLOG,
                    recoveryBlocked, releaseRecovery, indexShard, node, recoveryListener, logger));
            recoveryBlocked.await();
            IndexMetaData.Builder builder = IndexMetaData.builder(replica.indexSettings().getIndexMetaData());
            builder.settings(Settings.builder().put(replica.indexSettings().getSettings())
                .put(IndexSettings.INDEX_TRANSLOG_RETENTION_AGE_SETTING.getKey(), "-1")
                .put(IndexSettings.INDEX_TRANSLOG_RETENTION_SIZE_SETTING.getKey(), "-1")
                // force a roll and flush
                .put(IndexSettings.INDEX_TRANSLOG_FLUSH_THRESHOLD_SIZE_SETTING.getKey(), "100b")
            );
            replica.indexSettings().updateIndexMetaData(builder.build());
            replica.onSettingsChanged();
            releaseRecovery.countDown();
            future.get();
            // rolling/flushing is async
            assertBusy(() -> assertThat(replica.getTranslog().totalOperations(), equalTo(0)));
        }
    }

    public void testRecoveryWithOutOfOrderDelete() throws Exception {
        try (ReplicationGroup shards = createGroup(1)) {
            shards.startAll();
            // create out of order delete and index op on replica
            final IndexShard orgReplica = shards.getReplicas().get(0);
            orgReplica.applyDeleteOperationOnReplica(1, 1, 2, "type", "id", VersionType.EXTERNAL, u -> {});
            orgReplica.getTranslog().rollGeneration(); // isolate the delete in it's own generation
            orgReplica.applyIndexOperationOnReplica(0, 1, 1, VersionType.EXTERNAL, IndexRequest.UNSET_AUTO_GENERATED_TIMESTAMP, false,
                SourceToParse.source(orgReplica.shardId().getIndexName(), "type", "id", new BytesArray("{}"), XContentType.JSON),
                u -> {});

            // index a second item into the second generation, skipping seq# 2. Local checkpoint is now 1, which will make this generation
            // stick around
            orgReplica.applyIndexOperationOnReplica(3, 1, 1, VersionType.EXTERNAL, IndexRequest.UNSET_AUTO_GENERATED_TIMESTAMP, false,
                SourceToParse.source(orgReplica.shardId().getIndexName(), "type", "id2", new BytesArray("{}"), XContentType.JSON), u -> {});

            final int translogOps;
            if (randomBoolean()) {
                if (randomBoolean()) {
                    logger.info("--> flushing shard (translog will be trimmed)");
                    IndexMetaData.Builder builder = IndexMetaData.builder(orgReplica.indexSettings().getIndexMetaData());
                    builder.settings(Settings.builder().put(orgReplica.indexSettings().getSettings())
                        .put(IndexSettings.INDEX_TRANSLOG_RETENTION_AGE_SETTING.getKey(), "-1")
                        .put(IndexSettings.INDEX_TRANSLOG_RETENTION_SIZE_SETTING.getKey(), "-1")
                    );
                    orgReplica.indexSettings().updateIndexMetaData(builder.build());
                    orgReplica.onSettingsChanged();
                    translogOps = 3; // 2 ops + seqno gaps
                } else {
                    logger.info("--> flushing shard (translog will be retained)");
                    translogOps = 4; // 3 ops + seqno gaps
                }
                flushShard(orgReplica);
            } else {
                translogOps = 4; // 3 ops + seqno gaps
            }

            final IndexShard orgPrimary = shards.getPrimary();
            shards.promoteReplicaToPrimary(orgReplica).get(); // wait for primary/replica sync to make sure seq# gap is closed.

            IndexShard newReplica = shards.addReplicaWithExistingPath(orgPrimary.shardPath(), orgPrimary.routingEntry().currentNodeId());
            shards.recoverReplica(newReplica);
            shards.assertAllEqual(1);

            assertThat(newReplica.getTranslog().totalOperations(), equalTo(translogOps));
        }
    }
}
