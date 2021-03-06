/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle.sort;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import javax.annotation.Nullable;

import org.apache.spark.*;
import scala.None$;
import scala.Option;
import scala.Product2;
import scala.Tuple2;
import scala.collection.Iterator;


import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Closeables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.executor.ShuffleWriteMetrics;
import org.apache.spark.scheduler.MapStatus;
import org.apache.spark.scheduler.MapStatus$;
import org.apache.spark.serializer.Serializer;
import org.apache.spark.serializer.SerializerInstance;
import org.apache.spark.shuffle.IndexShuffleBlockResolver;
import org.apache.spark.shuffle.ShuffleWriter;
import org.apache.spark.storage.*;
import org.apache.spark.util.Utils;



/**
 * This class implements sort-based shuffle's hash-style shuffle fallback path. This write path
 * writes incoming records to separate files, one file per reduce partition, then concatenates these
 * per-partition files to form a single output file, regions of which are served to reducers.
 * Records are not buffered in memory. This is essentially identical to
 * {HashShuffleWriter}, except that it writes output in a format
 * that can be served / consumed via {@link org.apache.spark.shuffle.IndexShuffleBlockResolver}.
 * <p>
 * This write path is inefficient for shuffles with large numbers of reduce partitions because it
 * simultaneously opens separate serializers and file streams for all partitions. As a result,
 * {@link SortShuffleManager} only selects this write path when
 * <ul>
 *    <li>no Ordering is specified,</li>
 *    <li>no Aggregator is specific, and</li>
 *    <li>the number of partitions is less than
 *      <code>spark.shuffle.sort.bypassMergeThreshold</code>.</li>
 * </ul>
 * <p>
 * This code used to be part of {@link org.apache.spark.util.collection.ExternalSorter} but was
 * refactored into its own class in order to reduce code complexity; see SPARK-7855 for details.
 * <p>
 * There have been proposals to completely remove this code path; see SPARK-6026 for details.
 * <p>
 * ?????????????????????????????????????????????map??????????????????combine????????????????????????
 * ???BypassMergeSortShuffleHandle????????????????????????
 * ?????????handle?????????BypassMergeSortShuffleWriter??????????????????RDD???????????????????????????????????????
 * ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
 * ??????????????????????????????????????????????????????32k???
 * ???????????????shuffle????????????map?????????????????????????????????????????????????????????????????????????????????
 * ?????????key???????????????????????????????????????????????????I/O????????????????????????????????????????????????
 * <p>
 * ??????????????????????????????????????????????????????RDD???????????????????????????????????????????????????????????????????????????IO?????????????????????????????????
 */
final class BypassMergeSortShuffleWriter<K, V> extends ShuffleWriter<K, V> {

    private static final Logger logger = LoggerFactory.getLogger(BypassMergeSortShuffleWriter.class);

    private final int fileBufferSize;
    private final boolean transferToEnabled;
    private final int numPartitions;
    private final BlockManager blockManager;
    private final Partitioner partitioner;
    private final ShuffleWriteMetrics writeMetrics;
    private final int shuffleId;
    private final int mapId;
    private final Serializer serializer;
    private final IndexShuffleBlockResolver shuffleBlockResolver;

    /**
     * Array of file writers, one for each partition
     */
    private DiskBlockObjectWriter[] partitionWriters;
    private FileSegment[] partitionWriterSegments;
    @Nullable
    private MapStatus mapStatus;
    private long[] partitionLengths;

    /**
     * Are we in the process of stopping? Because map tasks can call stop() with success = true
     * and then call stop() with success = false if they get an exception, we want to make sure
     * we don't try deleting files, etc twice.
     */
    private boolean stopping = false;

    BypassMergeSortShuffleWriter(
            BlockManager blockManager,
            IndexShuffleBlockResolver shuffleBlockResolver,
            BypassMergeSortShuffleHandle<K, V> handle,
            int mapId,
            TaskContext taskContext,
            SparkConf conf) {
        // Use getSizeAsKb (not bytes) to maintain backwards compatibility if no units are provided
        this.fileBufferSize = (int) conf.getSizeAsKb("spark.shuffle.file.buffer", "32k") * 1024;
        this.transferToEnabled = conf.getBoolean("spark.file.transferTo", true);
        this.blockManager = blockManager;
        final ShuffleDependency<K, V, V> dep = handle.dependency();
        this.mapId = mapId;
        this.shuffleId = dep.shuffleId();
        this.partitioner = dep.partitioner();
        this.numPartitions = partitioner.numPartitions();
        this.writeMetrics = taskContext.taskMetrics().shuffleWriteMetrics();
        this.serializer = dep.serializer();
        this.shuffleBlockResolver = shuffleBlockResolver;
    }

    //???????????????stageID??????jobID?????????????????????map???????????????splitKey??????????????????
    //val uniqueID: String = "" + jobId.getOrElse(-1) + stageId
    //???????????????receiver??????stage??????????????????stageID
    //val uniqueID: String = "123" + jobId.getOrElse(-1)
    @Override
    public void write(Iterator<Product2<K, V>> records, Map<String, Integer> splitKeyPar, String uniqueID) throws IOException {
    //public void write(Iterator<Product2<K, V>> records, String uniqueID) throws IOException {
        assert (partitionWriters == null);
        if (!records.hasNext()) {
            partitionLengths = new long[numPartitions];
            shuffleBlockResolver.writeIndexFileAndCommit(shuffleId, mapId, partitionLengths, null);
            mapStatus = MapStatus$.MODULE$.apply(blockManager.shuffleServerId(), partitionLengths);
            return;
        }
        logger.info("---------Astraea info:--------- map task " + mapId +" starts writing");
        final SerializerInstance serInstance = serializer.newInstance();
        final long openStartTime = System.nanoTime();
        //?????????reduce bucket????????????writer
        partitionWriters = new DiskBlockObjectWriter[numPartitions];
        partitionWriterSegments = new FileSegment[numPartitions];
        for (int i = 0; i < numPartitions; i++) {
            final Tuple2<TempShuffleBlockId, File> tempShuffleBlockIdPlusFile =
                    blockManager.diskBlockManager().createTempShuffleBlock();
            final File file = tempShuffleBlockIdPlusFile._2();
            final BlockId blockId = tempShuffleBlockIdPlusFile._1();
            //?????????reduce bucket??????????????????writer
            partitionWriters[i] =
                    blockManager.getDiskWriter(blockId, file, serInstance, fileBufferSize, writeMetrics);
        }
        // Creating the file to write to and creating a disk writer both involve interacting with
        // the disk, and can take a long time in aggregate when we open many files, so should be
        // included in the shuffle write time.
        writeMetrics.incWriteTime(System.nanoTime() - openStartTime);


        //?????????local??????,unsplitKeys???frequency
        Map<String, Integer> unSplitKeys = new HashMap<String, Integer>();
        //???AstraeaBatchKeyInfo?????????????????????splitKeys???????????????
        //????????????batch???splitKeys?????????
        //Map<String, Integer> splitKeyPartition = AstraeaBatchKeyInfo.getFirstGlobal(uniqueID);
        //Map<String, Integer> splitKeyPar = new HashMap<String, Integer>();
        //Map<String, Integer> splitKeyPar = AstraeaBatchKeyInfo.getBroadcast();
        //logger.info("---------Astraea info:--------- splitKeyPar.size:" + splitKeyPar.size());
        Map<String, Integer> unSplitKeyPartition = new HashMap<String, Integer>();
        //outputData????????????????????????????????????split???
        List<Product2<K, V>> outputData = new ArrayList<Product2<K, V>>();
        //????????????bucket??????????????????
        int[] bucket = new int[numPartitions];
        //?????????????????????splitKeys
        while (records.hasNext()) {
            //???????????????????????????????????????????????????????????????????????????????????????????????????????????????
            //????????????????????????????????????????????????????????????????????????
            Product2<K, V> record = records.next();
            String key = String.valueOf(record._1());
            if (!splitKeyPar.containsKey(key)) {
                //???????????????splitkey???????????????????????????????????????????????????splitkey???
                outputData.add(record);
                if (unSplitKeys.containsKey(key)) {
                    unSplitKeys.put(key, unSplitKeys.get(key) + 1);
                } else {
                    unSplitKeys.put(key, 1);
                }
            } else {
                //?????????????????????????????????splitkey??????????????????
                //local?????????????????????????????????????????????
                int sLocation = splitKeyPar.get(key)==null?0:splitKeyPar.get(key);
                partitionWriters[sLocation].write(key, record._2());
                bucket[sLocation] += 1;
            }
        }
        logger.info("---------Astraea info:--------- map task " + mapId +": SplitKeyPartition size = " + splitKeyPar.size());
        //??????splitKeyPar
        for (Map.Entry<String, Integer> e : splitKeyPar.entrySet()) {
            logger.info("---------Astraea info:--------- map task " + mapId +": splitKEY = " + e.getKey() + " keyPar = " + e.getValue());
        }
        for (int i = 0; i < numPartitions; i++) {
            logger.info("---------Astraea info:--------- After SplitKeyPartition, map task " + mapId +": bucket " + i + " size = " + bucket[i]);
        }

        long startTime = System.currentTimeMillis();
        logger.info("---------Astraea info:--------- map task " + mapId +" starts sorting at " + startTime);
        //??????frequency value??????????????????(????????????????????????????????????????????????)
        List<Map.Entry<String, Integer>> unSplitKeyInfo = new ArrayList<Map.Entry<String, Integer>>(unSplitKeys.entrySet());
        Collections.sort(unSplitKeyInfo, new Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> o1, Map.Entry<String, Integer> o2) {
                return (o2.getValue() - o1.getValue());
                //return (o1.getKey()).toString().compareTo(o2.getKey());
            }
        });
        long endTime = System.currentTimeMillis();
        long sortTime = endTime - startTime;
        logger.info("---------Astraea info:--------- map task " + mapId +" finishes sorting at " + endTime);
        logger.info("---------Astraea info:--------- map task " + mapId +" sort time = " + sortTime);

        //????????????unSplitKeyInfo???unSplitKeyPartition???size??????
        /*
        logger.info("---------Astraea info:--------- map task " + mapId +": unSplitKeyInfo size = " + unSplitKeyInfo.size());
        if (unSplitKeyInfo.size() > 3) {
            logger.info("---------Astraea info:--------- map task " + mapId +": unSplitKeyInfo max 3 = "
                    + unSplitKeyInfo.get(0).getValue() + " " + unSplitKeyInfo.get(1).getValue() + " " + unSplitKeyInfo.get(2).getValue());
            logger.info("---------Astraea info:--------- map task " + mapId +": unSplitKeyInfo min 3 = "
                    + unSplitKeyInfo.get(unSplitKeyInfo.size()-1).getValue() + " " + unSplitKeyInfo.get(unSplitKeyInfo.size()-2).getValue() + " " + unSplitKeyInfo.get(unSplitKeyInfo.size()-3).getValue());
        }

         */

        //????????????local?????????(key->partition),??????numPartitions???reduce bucket
        //local???global??????????????????????????????global?????????????????????
        int leastSize = 0;
        java.util.Iterator<Map.Entry<String, Integer>> unSplitIterator = unSplitKeyInfo.iterator();
        while (unSplitIterator.hasNext()) {
            Map.Entry<String, Integer> entry = unSplitIterator.next();
            int leastID = 0;
            leastSize = bucket[0];
            for (int i = 1; i < numPartitions; i++) {
                if (bucket[i] < leastSize) {
                    leastID = i;
                    leastSize = bucket[i];
                }
            }
            unSplitKeyPartition.put(entry.getKey(), leastID);
            bucket[leastID] += entry.getValue();
        }
        logger.info("---------Astraea info:--------- map task " + mapId +": unSplitKeyPartition size = " + unSplitKeyPartition.size());
        for (int i = 0; i < numPartitions; i++) {
            logger.info("---------Astraea info:--------- After unSplitKeyPartition, map task " + mapId +": bucket " + i + " size = " + bucket[i]);
        }


        //????????????????????????record?????????reduce????????????????????????
        //??????shuffleMapTask????????????BypassMergeSortShuffleWriter
        //?????????ShuffleWriter????????????write???????????????
        java.util.Iterator<Product2<K, V>> it = outputData.iterator();
        while (it.hasNext()) {
            final Product2<K, V> record = it.next();
            final K key = record._1();
            String myKey = String.valueOf(key);
            int sLocation;
            //local partition,?????????????????????unSplitKeys?????????
            sLocation = unSplitKeyPartition.get(myKey)==null?0:unSplitKeyPartition.get(myKey);
            //splitKeyPartition???????????????outputData???????????????????????????????????????
            /*
            if (splitKeyPartition.containsKey(myKey)) {
                //global partition,???batching???????????????splitKeys???????????????
                sLocation = splitKeyPartition.get(myKey)==null?0:splitKeyPartition.get(myKey);
            } else {
                //local partition,?????????????????????unSplitKeys?????????
                sLocation = unSplitKeyPartition.get(myKey)==null?0:unSplitKeyPartition.get(myKey);
            }
             */
            partitionWriters[sLocation].write(key, record._2());


            //??????AstraeaPartitioner????????????
            //partitionWriters[partitioner.getPartition(key)].write(key, record._2());
        }


        for (int i = 0; i < numPartitions; i++) {
            final DiskBlockObjectWriter writer = partitionWriters[i];
            partitionWriterSegments[i] = writer.commitAndGet();
            writer.close();
        }

        File output = shuffleBlockResolver.getDataFile(shuffleId, mapId);
        File tmp = Utils.tempFileWith(output);
        try {
            partitionLengths = writePartitionedFile(tmp);
            shuffleBlockResolver.writeIndexFileAndCommit(shuffleId, mapId, partitionLengths, tmp);
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                logger.error("Error while deleting temp file {}", tmp.getAbsolutePath());
            }
        }
        mapStatus = MapStatus$.MODULE$.apply(blockManager.shuffleServerId(), partitionLengths);
    }

    @VisibleForTesting
    long[] getPartitionLengths() {
        return partitionLengths;
    }

    /**
     * Concatenate all of the per-partition files into a single combined file.
     *
     * @return array of lengths, in bytes, of each partition of the file (used by map output tracker).
     */
    private long[] writePartitionedFile(File outputFile) throws IOException {
        // Track location of the partition starts in the output file
        final long[] lengths = new long[numPartitions];
        if (partitionWriters == null) {
            // We were passed an empty iterator
            return lengths;
        }

        final FileOutputStream out = new FileOutputStream(outputFile, true);
        final long writeStartTime = System.nanoTime();
        boolean threwException = true;
        try {
            for (int i = 0; i < numPartitions; i++) {
                final File file = partitionWriterSegments[i].file();
                if (file.exists()) {
                    final FileInputStream in = new FileInputStream(file);
                    boolean copyThrewException = true;
                    try {
                        lengths[i] = Utils.copyStream(in, out, false, transferToEnabled);
                        copyThrewException = false;
                    } finally {
                        Closeables.close(in, copyThrewException);
                    }
                    if (!file.delete()) {
                        logger.error("Unable to delete file for partition {}", i);
                    }
                }
            }
            threwException = false;
        } finally {
            Closeables.close(out, threwException);
            writeMetrics.incWriteTime(System.nanoTime() - writeStartTime);
        }
        partitionWriters = null;
        return lengths;
    }

    @Override
    public Option<MapStatus> stop(boolean success) {
        if (stopping) {
            return None$.empty();
        } else {
            stopping = true;
            if (success) {
                if (mapStatus == null) {
                    throw new IllegalStateException("Cannot call stop(true) without having called write()");
                }
                return Option.apply(mapStatus);
            } else {
                // The map task failed, so delete our output data.
                if (partitionWriters != null) {
                    try {
                        for (DiskBlockObjectWriter writer : partitionWriters) {
                            // This method explicitly does _not_ throw exceptions:
                            File file = writer.revertPartialWritesAndClose();
                            if (!file.delete()) {
                                logger.error("Error while deleting file {}", file.getAbsolutePath());
                            }
                        }
                    } finally {
                        partitionWriters = null;
                    }
                }
                return None$.empty();
            }
        }
    }
}
