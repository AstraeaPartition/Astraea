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

package org.apache.spark.streaming.receiver


import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import scala.collection.mutable.ArrayBuffer
import org.apache.spark.{SparkConf, SparkContext, SparkException}
import org.apache.spark.internal.Logging
import org.apache.spark.storage.StreamBlockId
import org.apache.spark.streaming.util.RecurringTimer
import org.apache.spark.util.{Clock, SystemClock}
import org.apache.spark.shuffle.sort.AstraeaBatchKeyInfo
import org.apache.spark.streaming.StreamingContext

import scala.collection.mutable


/** Listener object for BlockGenerator events */
//BlockGeneratorListener的默认实现defaultBlockGeneratorListener在ReceiverSupervisorImpl.scala里
private[streaming] trait BlockGeneratorListener {
  /**
   * Called after a data item is added into the BlockGenerator. The data addition and this
   * callback are synchronized with the block generation and its associated callback,
   * so block generation waits for the active data addition+callback to complete. This is useful
   * for updating metadata on successful buffering of a data item, specifically that metadata
   * that will be useful when a block is generated. Any long blocking operation in this callback
   * will hurt the throughput.
   */
  def onAddData(data: Any, metadata: Any): Unit

  /**
   * Called when a new block of data is generated by the block generator. The block generation
   * and this callback are synchronized with the data addition and its associated callback, so
   * the data addition waits for the block generation+callback to complete. This is useful
   * for updating metadata when a block has been generated, specifically metadata that will
   * be useful when the block has been successfully stored. Any long blocking operation in this
   * callback will hurt the throughput.
   */
  def onGenerateBlock(blockId: StreamBlockId): Unit

  /**
   * Called when a new block is ready to be pushed. Callers are supposed to store the block into
   * Spark in this method. Internally this is called from a single
   * thread, that is not synchronized with any other callbacks. Hence it is okay to do long
   * blocking operation in this callback.
   */
  def onPushBlock(blockId: StreamBlockId, arrayBuffer: ArrayBuffer[_]): Unit

  /**
   * Called when an error has occurred in the BlockGenerator. Can be called form many places
   * so better to not do any long block operation in this callback.
   */
  def onError(message: String, throwable: Throwable): Unit

  def onPushSplitKeyPar(SplitKeyPar: mutable.HashMap[String, Integer]): Unit
}

/**
 * Generates batches of objects received by a
 * [[org.apache.spark.streaming.receiver.Receiver]] and puts them into appropriately
 * named blocks at regular intervals. This class starts two threads,
 * one to periodically start a new batch and prepare the previous batch of as a block,
 * the other to push the blocks into the block manager.
 *
 * Note: Do not create BlockGenerator instances directly inside receivers. Use
 * `ReceiverSupervisor.createBlockGenerator` to create a BlockGenerator and use it.
 */

private[streaming] class BlockGenerator(
                                         listener: BlockGeneratorListener,
                                         receiverId: Int,
                                         conf: SparkConf,
                                         //sc: SparkContext,
                                         clock: Clock = new SystemClock(),
                                         //加上receiver的数量（默认为1）,前一行要加逗号
                                         numReceivers: Int = 1
                                       ) extends RateLimiter(conf) with Logging {

  private case class Block(id: StreamBlockId, buffer: ArrayBuffer[Any])

  /**
   * The BlockGenerator can be in 5 possible states, in the order as follows.
   *
   *  - Initialized: Nothing has been started.
   *  - Active: start() has been called, and it is generating blocks on added data.
   *  - StoppedAddingData: stop() has been called, the adding of data has been stopped,
   *    but blocks are still being generated and pushed.
   *  - StoppedGeneratingBlocks: Generating of blocks has been stopped, but
   *    they are still being pushed.
   *  - StoppedAll: Everything has been stopped, and the BlockGenerator object can be GCed.
   */
  // Generator的状态枚举类
  private object GeneratorState extends Enumeration {
    type GeneratorState = Value
    val Initialized, Active, StoppedAddingData, StoppedGeneratingBlocks, StoppedAll = Value
  }

  import GeneratorState._


  //获取batchInterval,如果没法从程序中读取，可能需要从在conf文件中新加字段并读取
  //如果可以获取ssc,则可以这么获取batchInterval: ssc.graph.batchDuration.milliseconds
  val batchIntervalMs: Long = conf.getTimeAsMs("spark.streaming.batchInterval", "3000ms")
  //生成Block的时间间隔
  private val blockIntervalMs = conf.getTimeAsMs("spark.streaming.blockInterval", "200ms")
  require(blockIntervalMs > 0, s"'spark.streaming.blockInterval' should be a positive value")

  val numReduce: Int = conf.getInt("spark.default.parallelism", 15)

  // Call updateCurrentBuffer function when time reaches blockIntervalMs
  // 生成block的定时器
  //private val blockIntervalTimer =
  //  new RecurringTimer(clock, blockIntervalMs, updateCurrentBuffer, "BlockGenerator")
  //不能直接修改RecurringTimer，因为JobGenerator也用到了这个类确定产生作业的时间。需要新建一个
  private val blockIntervalTimer =
  new RecurringTimer(clock, batchIntervalMs, updateCurrentBuffer, "BlockGenerator", blockIntervalMs)

  //生成Block的阻塞队列
  //defaultValue可能需要更改以适应一次性划分多个block,原始是10
  //private val blockQueueSize = conf.getInt("spark.streaming.blockQueueSize", 10)
  private val blockQueueSize = conf.getInt("spark.streaming.blockQueueSize", (batchIntervalMs / blockIntervalMs).toInt)
  private val blocksForPushing = new ArrayBlockingQueue[Block](blockQueueSize + 1)
  //将生成的Block信息推送给BlockManager
  private val blockPushingThread = new Thread() {
    override def run() {
      keepPushingBlocks()
    }
  }

  var astraeaBatchKeyInfo = new AstraeaBatchKeyInfo()
  //var splitkeyParBC: Broadcast[mutable.Map[String, Integer]] = null

  //数据接收的数组
  @volatile private var currentBuffer = new ArrayBuffer[Any]
  @volatile private var state = Initialized

  /** Start block generating and pushing threads. */
  def start(): Unit = synchronized {
    if (state == Initialized) {
      state = Active
      //启动定时器定期将currentBuffer中的数据转换为一个Block并放入队列blocksForPushing中
      //考虑将这里的定时器blockinterval改成batchinterval，即每个batchinterval触发一次划分
      //每次划分通过updateCurrentBuffer一次性划分成batchinterval/blockinterval个block
      //为了避免触发计算的hearbeat到来时还没划分好block，可能需要提前一个blockinterval
      //即第一个划分的触发时间是batchinterval-blockinterval，之后间隔为batchinterval
      blockIntervalTimer.start()
      //获取队列blocksForPushing中的数据，发送给BlockManager
      blockPushingThread.start()
      logInfo("Started BlockGenerator")
    } else {
      throw new SparkException(
        s"Cannot start BlockGenerator as its not in the Initialized state [state = $state]")
    }
  }

  /**
   * Stop everything in the right order such that all the data added is pushed out correctly.
   *
   *  - First, stop adding data to the current buffer.
   *  - Second, stop generating blocks.
   *  - Finally, wait for queue of to-be-pushed blocks to be drained.
   */
  def stop(): Unit = {
    // Set the state to stop adding data
    synchronized {
      if (state == Active) {
        state = StoppedAddingData
      } else {
        logWarning(s"Cannot stop BlockGenerator as its not in the Active state [state = $state]")
        return
      }
    }

    // Stop generating blocks and set the state for block pushing thread to start draining the queue
    logInfo("Stopping BlockGenerator")
    blockIntervalTimer.stop(interruptTimer = false)
    synchronized {
      state = StoppedGeneratingBlocks
    }

    // Wait for the queue to drain and mark state as StoppedAll
    logInfo("Waiting for block pushing thread to terminate")
    blockPushingThread.join()
    synchronized {
      state = StoppedAll
    }
    logInfo("Stopped BlockGenerator")
  }


  //key和其对应的那行数据(包括key本身)
  var dataBuffers: mutable.HashMap[String, ArrayBuffer[Any]] = mutable.HashMap()

  /**
   * 把真正的数据和key对应并保存
   *
   * @param key  receiver接收到的数据的key
   * @param data receiver接收到真正的数据
   * @return
   */
  def addAstraeaData(key: String, data: Any): Unit = {
    if (dataBuffers.contains(key)) {
      //dataBuffers(key)获取到的是ArrayBuffer[Any]
      dataBuffers(key) += data
      //这里报错java.util.NoSuchElementException: key not found: a
      //dataBuffers += (key -> tempBuffer)
    } else {
      dataBuffers += (key -> ArrayBuffer(data))
    }
  }

  /**
   * Push a single data item into the buffer.
   */
  def addData(data: Any): Unit = {
    if (state == Active) {
      waitToPush()
      synchronized {
        if (state == Active) {
          //在addData的时候更新AstraeaBatchKeyInfo
          val stringData: String = data.asInstanceOf[String]
          //当字符串不包含分隔符时，返回数组只包含一个元素（该字符串本身）
          val str: Array[String] = stringData.split(",", 2)
          //第一个字符串为key
          val key = str(0)
          //统计当前receiver的key的频率
          astraeaBatchKeyInfo.addKey(key)
          //统计多个receiver的全局信息
          AstraeaBatchKeyInfo.addGlobalKey(key)
          //把这条数据加到key对应的buffer中
          //这里报错java.util.NoSuchElementException: key not found: a
          addAstraeaData(key, data)

          currentBuffer += data
        } else {
          throw new SparkException(
            "Cannot add data as BlockGenerator has not been started or has been stopped")
        }
      }
    } else {
      throw new SparkException(
        "Cannot add data as BlockGenerator has not been started or has been stopped")
    }
  }

  /**
   * Push a single data item into the buffer. After buffering the data, the
   * `BlockGeneratorListener.onAddData` callback will be called.
   */
  def addDataWithCallback(data: Any, metadata: Any): Unit = {
    if (state == Active) {
      waitToPush()
      synchronized {
        if (state == Active) {
          //在addData的时候更新AstraeaBatchKeyInfo
          val stringData: String = data.asInstanceOf[String]
          //当字符串不包含分隔符时，返回数组只包含一个元素（该字符串本身）
          val str: Array[String] = stringData.split(",", 2)
          //第一个字符串为key
          val key = str(0)
          //统计当前receiver的key的频率
          astraeaBatchKeyInfo.addKey(key)
          //统计多个receiver的全局信息
          AstraeaBatchKeyInfo.addGlobalKey(key)
          //把这条数据加到key对应的buffer中
          addAstraeaData(key, data)

          currentBuffer += data
          listener.onAddData(data, metadata)
        } else {
          throw new SparkException(
            "Cannot add data as BlockGenerator has not been started or has been stopped")
        }
      }
    } else {
      throw new SparkException(
        "Cannot add data as BlockGenerator has not been started or has been stopped")
    }
  }

  /**
   * Push multiple data items into the buffer. After buffering the data, the
   * `BlockGeneratorListener.onAddData` callback will be called. Note that all the data items
   * are atomically added to the buffer, and are hence guaranteed to be present in a single block.
   */
  def addMultipleDataWithCallback(dataIterator: Iterator[Any], metadata: Any): Unit = {
    if (state == Active) {
      // Unroll iterator into a temp buffer, and wait for pushing in the process
      val tempBuffer = new ArrayBuffer[Any]
      dataIterator.foreach { data =>
        waitToPush()
        tempBuffer += data
      }
      synchronized {
        if (state == Active) {
          currentBuffer ++= tempBuffer
          listener.onAddData(tempBuffer, metadata)
        } else {
          throw new SparkException(
            "Cannot add data as BlockGenerator has not been started or has been stopped")
        }
      }
    } else {
      throw new SparkException(
        "Cannot add data as BlockGenerator has not been started or has been stopped")
    }
  }

  def isActive(): Boolean = state == Active

  def isStopped(): Boolean = state == StoppedAll

  /** Change the buffer to which single records are added to.
   * 这里是划分block，然后通过keepPushingBlocks()方法告诉BlockManager并进行转储
   * 最后会在在JobGenerator中定时器定期执行generateJobs
   * 通过jobScheduler.receiverTracker.allocateBlocksToBatch(time)生成batch
   * 所以需要在定期执行generateJobs时把batch划分好，即需要相对batchinterval提前一段时间划分block
   */
  private def updateCurrentBuffer(time: Long): Unit = {
    //现在运行时没有map阶段的block数据，需要查看原因
    //这里传入的time是划分block的时间，即batchInterval-100ms
    //在这之前，需要在addData方法里面统计好key的频率信息
    logInfo("---------Astraea info:--------- Entering updateCurrentBuffer")
    try {

      //还需要知道需要划分多少个block
      val numBlock: Int = (batchIntervalMs / blockIntervalMs).toInt
      //每个blockBuffers(i)对应一个block的数据
      val blockBuffers: Array[ArrayBuffer[Any]] = new Array[ArrayBuffer[Any]](numBlock)
      for (i <- blockBuffers.indices) {
        blockBuffers(i) = new ArrayBuffer[Any]()
      }
      val newBlocks: Array[Block] = new Array[Block](numBlock)
      val a = 1.0
      logInfo("---------Astraea info:--------- numBlock(Map task) is: " + numBlock)
      logInfo("---------Astraea info:--------- numReduce is: " + numReduce)
      //var newBlock: Block = null
      //先加锁
      synchronized {
        logInfo("---------Astraea info:--------- Entering synchronized")

        //准备在这里通过循环生成batchinterval/blockinterval个block
        if (currentBuffer.nonEmpty) {
          //logInfo("---------Astraea info:--------- currentBuffer is not empty!")
          //如果currentBuffer非空，则通过赋值中间变量转换得到newBlock
          val newBlockBuffer: ArrayBuffer[Any] = currentBuffer

          //获取当前时间的key的频率分布和具体数据，获取后需要清空原始的，因为要存下一批次的
          //因为后面把频率高于阈值的均分了，所以可能不需要排序。对低频的逐一分配即可
          //val keyFreq: Map[String, Int] = astraeaBatchKeyInfo.sortKeys()
          val keyFreq: mutable.Map[String, Int] = astraeaBatchKeyInfo.keys
          val newDataBuffers: mutable.Map[String, ArrayBuffer[Any]] = dataBuffers
          //当前时刻，全局（多个receiver）的key的频率信息
          val currentGlobalKeys = AstraeaBatchKeyInfo.globalKeys
          logInfo("---------Astraea info:--------- keyFreq size before clear is: " + keyFreq.size)
          //在获取后马上清空
          currentBuffer = new ArrayBuffer[Any]
          dataBuffers = new mutable.HashMap[String, ArrayBuffer[Any]]
          astraeaBatchKeyInfo.keysClear()
          if (receiverId == 0) {
            logInfo("---------Astraea info:--------- Clear globalKeys")
            AstraeaBatchKeyInfo.globalKeysClear()
          }


          logInfo("---------Astraea info:--------- keyFreq size is: " + keyFreq.size)
          logInfo("---------Astraea info:--------- dataBuffer size is:(should equal keyFreq size) " + newDataBuffers.size)

          //下面真正开始划分block
          val numRecord = newBlockBuffer.size
          logInfo("---------Astraea info:--------- newBlockBuffer size is: " + numRecord)
          //val avgSize = numRecord / numBlock
          //频率大于threshold则分割
          val frequentThreshold = a * numRecord / (numBlock * numReduce)
          val avgRecord = numRecord / numBlock + 1
          val blockFlag: Array[Int] = new Array[Int](numBlock)
          logInfo("---------Astraea info:--------- frequentThreshold is: " + frequentThreshold)

          val it: Iterator[String] = keyFreq.keysIterator

          //需要根据receiver数量决定操作
          if (numReceivers > 1) {
            while (it.hasNext) {
              val key = it.next()
              //如果receiver数量大于1，则所有key都是split的
              //但是这里要记录高频的key，用以均衡分配。低频的key用hash，否则都排序时间太长
              //划分到多个block
              if (keyFreq(key) > frequentThreshold) {
                //记录这个高频key的全局频率信息
                AstraeaBatchKeyInfo.addGlobalSplitKey(key, currentGlobalKeys(key))
                val size: Int = Math.ceil(keyFreq(key).toDouble / numBlock).toInt
                for (i <- 0 until numBlock) {
                  //对高频key平分，每个bucket里面存的数据数量
                  //避免除不尽时有剩余未分配数据
                  //如果我们提供的第二个参数值超出其可用索引的位置,它只会忽略该值并仅返回最新的可用索引
                  //这里scala不会引发ArrayIndexOutOfBoundsException
                  blockBuffers(i) ++= newDataBuffers(key).slice(i * size, (i + 1) * size)
                  //如果负载最少的block存了之后超过应有负载
                  if (blockBuffers(i).size >= avgRecord) {
                    blockFlag(i) = 1
                  }
                }
              }
              else {
                //获取负载最少的block
                var leastID = 0
                var leastSize = blockBuffers(leastID).size
                for (i <- 1 until numBlock) {
                  if (blockBuffers(i).size < leastSize) {
                    leastID = i
                    leastSize = blockBuffers(i).size
                  }
                }
                //划分到负载最少的block
                blockBuffers(leastID) ++= newDataBuffers(key)
                //如果负载最少的block存了之后超过应有负载
                if (blockBuffers(leastID).size >= avgRecord) {
                  blockFlag(leastID) = 1
                }
              }
            }
          } else {
            var flag = 0
            while (it.hasNext) {
              val key = it.next()

              //这里的逻辑是如果key的频率大于一定阈值，则split
              //2021.9.8 实际上，如果有多个receiver，为了保证reduce阶段相同的key能放在同一个reduce任务中
              //需要将所有的key都当做split的，放在AstraeaBatchKeyInfo的静态变量里，然后每个map任务通过jobID获取
              if (keyFreq(key) > frequentThreshold) {
                flag = 1
                logInfo("---------Astraea info:--------- keyFreq > frequentThreshold: " + key + "=" + keyFreq(key))
                //划分到多个block

                astraeaBatchKeyInfo.addSplitKey(key, keyFreq(key))
                val size: Int = Math.ceil(keyFreq(key).toDouble / numBlock).toInt
                for (i <- 0 until numBlock) {
                  //对高频key平分，每个bucket里面存的数据数量
                  //避免除不尽时有剩余未分配数据
                  //如果我们提供的第二个参数值超出其可用索引的位置,它只会忽略该值并仅返回最新的可用索引
                  //这里scala不会引发ArrayIndexOutOfBoundsException
                  blockBuffers(i) ++= newDataBuffers(key).slice(i * size, (i + 1) * size)
                  //logInfo("---------Astraea test test test:--------- blockBuffers(" + i + ") size is: " + blockBuffers(i).size)
                  //如果负载最少的block存了之后超过应有负载
                  if (blockBuffers(i).size >= avgRecord) {
                    blockFlag(i) = 1
                  }
                }
              } else {
                //获取负载最少的block
                var leastID = 0
                var leastSize = blockBuffers(leastID).size
                for (i <- 1 until numBlock) {
                  if (blockFlag(leastID) == 0 && blockBuffers(i).size < leastSize) {
                    leastID = i
                    leastSize = blockBuffers(i).size
                  }
                }
                //划分到负载最少的block
                //这里报错java.util.NoSuchElementException: key not found: are
                blockBuffers(leastID) ++= newDataBuffers(key)
                //如果负载最少的block存了之后超过应有负载
                if (blockBuffers(leastID).size >= avgRecord) {
                  blockFlag(leastID) = 1
                }
              }
            }
            if (flag == 0) {
              logInfo("---------Astraea info:--------- There is no keyFreq > frequentThreshold: ")
            }
          }


          logInfo("---------Astraea info:--------- Map partition complete, start generate blocks!")
          var totalBlockSize = 0
          for (i <- 0 until numBlock) {
            logInfo("---------Astraea info:--------- blockBuffers(" + i + ") size is: " + blockBuffers(i).size)
            //blockId格式是:"input-" + receiverId + "-" + uniqueId
            val blockId = StreamBlockId(receiverId, time - (numBlock - i) * blockIntervalMs)
            listener.onGenerateBlock(blockId)
            newBlocks(i) = Block(blockId, blockBuffers(i))

            //需要将生成的newBlocks依次插入到队列中，等待汇报给BlockManager并进行转储
            //因为我们是一次性生成所有block再插入，所以blocksForPushing的默认值需要改大，至少等于map任务数量
            if (newBlocks(i) != null) {
              logInfo("---------Astraea info:--------- Add newBlocks(" + i + ") to blocksForPushing")
              blocksForPushing.put(newBlocks(i)) // put is blocking when queue is full
              logInfo("---------Astraea info:--------- blocksForPushing size is: " + blocksForPushing.size())
              totalBlockSize += blockBuffers(i).size
            }
          }
          //如果最终得到的totalBlockSize会比newBlockBuffer略小一些，则说明有数据丢失了
          logInfo("---------Astraea info:--------- totalBlockSize is:(should equal newBlockBuffer size) " + totalBlockSize)
          logInfo("---------Astraea info:--------- newBlockBuffer size is: " + numRecord)
        }
      }

      if (numReceivers > 1) {
        //这里需要用全局的信息更新splitPartition
        //只需要更新一次，所以只让id为0的receiver更新
        //这里已知globalSplitKeys的频率信息,和所有当前批次的key的全局频率信息
        if (receiverId == 0) {
          //update之后得到globalSplitKeyPartition
          AstraeaBatchKeyInfo.updateMultiReceiverSplitKeys(numReduce)
          //目前多个receiver时，低频key在reduce阶段划分可能有问题
          //以后可根据这个信息，把多个receiver时，低频key按一定方式划分到同一个reduce上
          AstraeaBatchKeyInfo.globalSplitKeyPartition += ("Astraea_Num_Receiver" -> 2)

          pushSplitKeyPar(AstraeaBatchKeyInfo.globalSplitKeyPartition)
          AstraeaBatchKeyInfo.globalSplitKeysClear()
        }
      } else {
        //先将splitKeys的信息更新到静态变量里，划分map任务输出时用
        astraeaBatchKeyInfo.updateSplitKeys(numReduce)
        //添加splitKeyPartition到广播变量
        //这里无法传入sc和ssc，所以没法新建广播变量
        /*
        if (AstraeaBatchKeyInfo.splitkeyParBC != null) {
          AstraeaBatchKeyInfo.splitkeyParBC.unpersist()
        }
        AstraeaBatchKeyInfo.splitkeyParBC = ssc.sparkContext.broadcast(AstraeaBatchKeyInfo.globalSplitKeyPartition)


         */
        logInfo("---------Astraea info:--------- globalSplitKeyPartition.size = " + AstraeaBatchKeyInfo.globalSplitKeyPartition.size)
        //尝试从ReceiverTracker里面建立广播变量
        pushSplitKeyPar(AstraeaBatchKeyInfo.globalSplitKeyPartition)
        //logInfo("---------Astraea info:--------- in blockgenerator, broadcastKeyPartition.size = " + AstraeaBatchKeyInfo.keyParBC.value.size)
        //logInfo("---------Astraea info:--------- in blockgenerator, getBroadcast.size = " + AstraeaBatchKeyInfo.getBroadcast.size)

      }

      //插入block后还需要将AstraeaBatchKeyInfo的数据清空
      astraeaBatchKeyInfo.splitKeysClear()
      astraeaBatchKeyInfo.splitKeyPartitionClear()
      AstraeaBatchKeyInfo.globalSplitKeyPartitionClear()

    } catch {
      case ie: InterruptedException =>
        logInfo("Block updating timer thread was interrupted")
      case e: Exception =>
        logInfo("---------Astraea info:--------- Error in block updating thread")
        reportError("Error in block updating thread", e)
    }
    logInfo("---------Astraea info:--------- Leaving updateCurrentBuffer")
  }

  private def pushSplitKeyPar(SplitKeyPar: mutable.HashMap[String, Integer]) {
    //调用的是ReceiverSupervisorImpl里的onPushSplitKeyPar方法
    listener.onPushSplitKeyPar(SplitKeyPar)
    logInfo("Pushed splitKeyPar")
  }

  /** Keep pushing blocks to the BlockManager. */
  private def keepPushingBlocks() {
    //能进入这里，但是没有push block
    logInfo("Started block pushing thread")

    //判断BlockGenerator是否处于非停止状态，只有在非停止状态下才可能会产生block
    def areBlocksBeingGenerated: Boolean = synchronized {
      state != StoppedGeneratingBlocks
    }

    try {
      // While blocks are being generated, keep polling for to-be-pushed blocks and push them.
      // 如果处于非停止状态，则一直在这个while中无限循环获取队列中的数据进行处理
      while (areBlocksBeingGenerated) {
        //能进入这里，但是没有调用pushBlock方法
        //可能问题：1.blocksForPushing为空。
        Option(blocksForPushing.poll(10, TimeUnit.MILLISECONDS)) match {
          case Some(block) => pushBlock(block)
          case None =>
        }
      }

      // At this point, state is StoppedGeneratingBlock. So drain the queue of to-be-pushed blocks.
      //运行到这里说明BlockGenerator已经停止生成block，但是这里需要把队列中的已经生成的Block进行汇报和转储
      logInfo("Pushing out the last " + blocksForPushing.size() + " blocks")
      while (!blocksForPushing.isEmpty) {
        val block = blocksForPushing.take()
        logDebug(s"Pushing block $block")
        pushBlock(block)
        logInfo("Blocks left to push " + blocksForPushing.size())
      }
      logInfo("Stopped block pushing thread")
    } catch {
      case ie: InterruptedException =>
        logInfo("Block pushing thread was interrupted")
      case e: Exception =>
        reportError("Error in block pushing thread", e)
    }
  }

  private def reportError(message: String, t: Throwable) {
    logError(message, t)
    listener.onError(message, t)
  }

  private def pushBlock(block: Block) {
    //调用的是ReceiverSupervisorImpl里的onPushBlock方法
    listener.onPushBlock(block.id, block.buffer)
    logInfo("Pushed block " + block.id)
  }
}