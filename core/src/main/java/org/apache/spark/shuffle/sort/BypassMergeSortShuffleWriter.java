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
 * 在许多使用场景下，有些算子会在map端先进行一次combine，减少数据传输，
 * 而BypassMergeSortShuffleHandle不支持这种操作，
 * 因为该handle对应的BypassMergeSortShuffleWriter是开辟和后续RDD分区数量一样数量的小文件，
 * 读取每条记录算出它的分区号，然后根据分区号判断应该追加到该文件中，此外这个过程也有缓冲区的概念，
 * 但一般这个缓冲区都不会特别大，默认为32k。
 * 这也是这种shuffle写不支持map端聚合的一个原因，因为聚合必然要在内存中储存一批数据，
 * 将相同key的数据做聚合，而这里是直接开辟多个I/O流，根据分区号往文件中追加数据。
 * <p>
 * 而正因为要同时打开多个文件，所以后续RDD的分区数也不能太多，否则同时打开多个文件，产生多个IO，消耗的资源成本很高。
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

    //能否通过把stageID或者jobID传入来避免多个map任务得到的splitKey信息不一样？
    //val uniqueID: String = "" + jobId.getOrElse(-1) + stageId
    //为了支持多receiver时多stage，修改为不加stageID
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
        //为每个reduce bucket申请一个writer
        partitionWriters = new DiskBlockObjectWriter[numPartitions];
        partitionWriterSegments = new FileSegment[numPartitions];
        for (int i = 0; i < numPartitions; i++) {
            final Tuple2<TempShuffleBlockId, File> tempShuffleBlockIdPlusFile =
                    blockManager.diskBlockManager().createTempShuffleBlock();
            final File file = tempShuffleBlockIdPlusFile._2();
            final BlockId blockId = tempShuffleBlockIdPlusFile._1();
            //为每个reduce bucket真正创建一个writer
            partitionWriters[i] =
                    blockManager.getDiskWriter(blockId, file, serInstance, fileBufferSize, writeMetrics);
        }
        // Creating the file to write to and creating a disk writer both involve interacting with
        // the disk, and can take a long time in aggregate when we open many files, so should be
        // included in the shuffle write time.
        writeMetrics.incWriteTime(System.nanoTime() - openStartTime);


        //先统计local信息,unsplitKeys和frequency
        Map<String, Integer> unSplitKeys = new HashMap<String, Integer>();
        //从AstraeaBatchKeyInfo获取当前批次的splitKeys的频率信息
        //得到当前batch的splitKeys的划分
        //Map<String, Integer> splitKeyPartition = AstraeaBatchKeyInfo.getFirstGlobal(uniqueID);
        //Map<String, Integer> splitKeyPar = new HashMap<String, Integer>();
        //Map<String, Integer> splitKeyPar = AstraeaBatchKeyInfo.getBroadcast();
        //logger.info("---------Astraea info:--------- splitKeyPar.size:" + splitKeyPar.size());
        Map<String, Integer> unSplitKeyPartition = new HashMap<String, Integer>();
        //outputData存储原始输出数据，只存未split的
        List<Product2<K, V>> outputData = new ArrayList<Product2<K, V>>();
        //存储每个bucket现在的数据量
        int[] bucket = new int[numPartitions];
        //第一次遍历划分splitKeys
        while (records.hasNext()) {
            //问题：与下面代码多次使用同一个迭代器，这个迭代器遍历完之后下一个就没数据了
            //所以第一次遍历的时候要把数据拿出来重新建立原数据
            Product2<K, V> record = records.next();
            String key = String.valueOf(record._1());
            if (!splitKeyPar.containsKey(key)) {
                //只有当不是splitkey的时候再加进去，后面就不需要再遍历splitkey了
                outputData.add(record);
                if (unSplitKeys.containsKey(key)) {
                    unSplitKeys.put(key, unSplitKeys.get(key) + 1);
                } else {
                    unSplitKeys.put(key, 1);
                }
            } else {
                //第一次遍历的时候，记录splitkey应该放的位置
                //local在这个基础上划分，更能负载均衡
                int sLocation = splitKeyPar.get(key)==null?0:splitKeyPar.get(key);
                partitionWriters[sLocation].write(key, record._2());
                bucket[sLocation] += 1;
            }
        }
        logger.info("---------Astraea info:--------- map task " + mapId +": SplitKeyPartition size = " + splitKeyPar.size());
        //打印splitKeyPar
        for (Map.Entry<String, Integer> e : splitKeyPar.entrySet()) {
            logger.info("---------Astraea info:--------- map task " + mapId +": splitKEY = " + e.getKey() + " keyPar = " + e.getValue());
        }
        for (int i = 0; i < numPartitions; i++) {
            logger.info("---------Astraea info:--------- After SplitKeyPartition, map task " + mapId +": bucket " + i + " size = " + bucket[i]);
        }

        long startTime = System.currentTimeMillis();
        logger.info("---------Astraea info:--------- map task " + mapId +" starts sorting at " + startTime);
        //根据frequency value从大到小排序(如果排序时间过长，可以尝试不排序)
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

        //经测试，unSplitKeyInfo和unSplitKeyPartition的size相同
        /*
        logger.info("---------Astraea info:--------- map task " + mapId +": unSplitKeyInfo size = " + unSplitKeyInfo.size());
        if (unSplitKeyInfo.size() > 3) {
            logger.info("---------Astraea info:--------- map task " + mapId +": unSplitKeyInfo max 3 = "
                    + unSplitKeyInfo.get(0).getValue() + " " + unSplitKeyInfo.get(1).getValue() + " " + unSplitKeyInfo.get(2).getValue());
            logger.info("---------Astraea info:--------- map task " + mapId +": unSplitKeyInfo min 3 = "
                    + unSplitKeyInfo.get(unSplitKeyInfo.size()-1).getValue() + " " + unSplitKeyInfo.get(unSplitKeyInfo.size()-2).getValue() + " " + unSplitKeyInfo.get(unSplitKeyInfo.size()-3).getValue());
        }

         */

        //然后确定local的划分(key->partition),共有numPartitions个reduce bucket
        //local在global的基础上填充，以避免global造成的负载不均
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


        //这里真正写入每个record到不同reduce任务对应的文件中
        //一个shuffleMapTask建立一个BypassMergeSortShuffleWriter
        //并通过ShuffleWriter里的这个write方法写数据
        java.util.Iterator<Product2<K, V>> it = outputData.iterator();
        while (it.hasNext()) {
            final Product2<K, V> record = it.next();
            final K key = record._1();
            String myKey = String.valueOf(key);
            int sLocation;
            //local partition,利用刚刚统计的unSplitKeys的信息
            sLocation = unSplitKeyPartition.get(myKey)==null?0:unSplitKeyPartition.get(myKey);
            //splitKeyPartition不可能包含outputData里的数据，所以这里不用判断
            /*
            if (splitKeyPartition.containsKey(myKey)) {
                //global partition,用batching阶段统计的splitKeys的划分信息
                sLocation = splitKeyPartition.get(myKey)==null?0:splitKeyPartition.get(myKey);
            } else {
                //local partition,利用刚刚统计的unSplitKeys的信息
                sLocation = unSplitKeyPartition.get(myKey)==null?0:unSplitKeyPartition.get(myKey);
            }
             */
            partitionWriters[sLocation].write(key, record._2());


            //通过AstraeaPartitioner控制分区
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
