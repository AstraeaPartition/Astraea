package org.apache.spark.shuffle.sort


import java.util
import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.collection.JavaConverters._

/**
 * 存储一个batch内的key的频率信息，用于block划分
 *
 * <p>问题：
 * <p>1.如何传递信息给map task，指导reduce划分？
 * <p>通过静态变量
 * <p>2.如果设使用object类，如何存储多个batch的数据？
 * <p>未使用的输入数据存储在队列中
 * <p>3.当有多条流，即多个receiver时，如何保证相同key在reduce阶段划分到同一个任务
 */
class AstraeaBatchKeyInfo {
  //val keySize = keys.size
  //val batchTime: Long
  var keys: mutable.HashMap[String, Int] = mutable.HashMap()
  //key和其对应的那行数据(包括key本身)
  //var dataBuffers: mutable.HashMap[String, ArrayBuffer[Any]] = mutable.HashMap()
  //var splitKeys: mutable.HashMap[String, Int] = mutable.HashMap()
  var splitKeys: Map[String, Int] = ListMap()
  //splitKeyPartition存储splitKey对应的reduce划分
  var splitKeyPartition: mutable.HashMap[String, Integer] = mutable.HashMap()

  /**
   * 统计一个batch内key的频率
   * @param key 要保存的key
   * @return
   */
  def addKey(key: String): mutable.HashMap[String, Int] = {
    if (keys.contains(key)) {
      //报错java.util.NoSuchElementException: key not found: goal
      val newValue = keys(key) + 1
      keys += (key -> newValue)
    }
    else {
      keys += (key -> 1)
    }
  }

  /**
   * 记录splitKey，以便reduce阶段划分
   * @param key 要保存的splitKey
   * @param frequency splitKey的频率
   * @return
   */
  def addSplitKey(key: String, frequency: Int): Unit = {
    //splitKey在加入时就是有序的
    splitKeys += (key -> frequency)
  }

  /*
  /**
   * 把真正的数据和key对应并保存
   * @param key receiver接收到的数据的key
   * @param data receiver接收到真正的数据
   * @return
   */
   //现在把addData放到了BlockGenerator里面
  def addData(key: String, data: Any): Unit = {
    if (dataBuffers.contains(key)) {
      //dataBuffers(key)获取到的是ArrayBuffer[Any]
      dataBuffers(key) += data
      //这里报错java.util.NoSuchElementException: key not found: a
      //dataBuffers += (key -> tempBuffer)
    } else {
      dataBuffers += (key -> ArrayBuffer(data))
    }
  }

   */

  /**
   * 将高频的key排序
   * <p>高频定义：key的频率大于|T|/(m*r)
   */
    //这里必须是ListMap，如果用Map或者HashMap就不能返回有序的key
  def sortKeys(): Map[String, Int] = ListMap(keys.toSeq.sortWith(_._2 > _._2): _*)
  def sortSplitKeys(): Map[String, Int] = ListMap(splitKeys.toSeq.sortWith(_._2 > _._2): _*)

  /**
   * 保存splitKeys的划分信息到静态变量中
   * @return
   */
  def updateSplitKeys(numReduce: Int): Unit = {


    //更新splitKeys的时候，就需要确定reduce阶段这些key的划分了
    val bucketSize: Array[Int] = new Array[Int](numReduce)
    //如果在BlockGenerator里没对splitKeys排序，那就要在这排序
    val sortedSplitKeys = sortSplitKeys()
    val it = sortedSplitKeys.keysIterator
    while (it.hasNext) {
      val key = it.next()

      var leastID = 0
      var leastSize = bucketSize(0)
      for (i <- 1 until numReduce) {
        if (bucketSize(i) < leastSize) {
          leastID = i
          leastSize = bucketSize(i)
        }
      }
      bucketSize(leastID) += splitKeys(key)
      //获取global划分时key与reduce任务的对应关系
      splitKeyPartition += (key -> leastID)
    }
    //将对应关系存入队列
    //AstraeaBatchKeyInfo.splitKeyPartitionQueue += splitKeyPartition
    AstraeaBatchKeyInfo.globalSplitKeyPartition = splitKeyPartition

  }


  /**
   * keys和buffers需要在获取后马上清理
   */
  def keysClear(): Unit = {
    keys = new mutable.HashMap[String, Int]()
    //dataBuffers.clear()
  }

  /**
   * 在每次划分block后清空AstraeaBatchKeyInfo
   * <p>因为其他变量需要更新存下一个batch的信息
   * <p>clear发生在updateSplitKeys后
   */
  def splitKeysClear(): Unit = {
    splitKeys = ListMap.empty
  }

  def splitKeyPartitionClear(): Unit = {
    splitKeyPartition = mutable.HashMap()
  }

}


object AstraeaBatchKeyInfo {

  //object类只有这一个对象（即单例对象）

  //当有多个receiver的时候存储key的全局信息
  var globalKeys: mutable.HashMap[String, Int] = mutable.HashMap()
  var oldGlobalKeys: mutable.HashMap[String, Int] = mutable.HashMap()
  var globalSplitKeyPartition: mutable.HashMap[String, Integer] = mutable.HashMap()

  //全局广播变量，用于传递信息给不同机器上的map任务
  //var keyParBC: Broadcast[mutable.HashMap[String, Integer]] = null



  /*
  def getBroadcast: util.Map[String, Integer] = {
    if (keyParBC != null) {
      keyParBC.value.asJava
    } else {
      mutable.HashMap[String, Integer]().asJava
    }
  }

   */




  def addGlobalKey(key: String): mutable.HashMap[String, Int] = {
    if (globalKeys.contains(key)) {
      //报错java.util.NoSuchElementException: key not found: goal
      val newValue = globalKeys(key) + 1
      globalKeys += (key -> newValue)
    }
    else {
      globalKeys += (key -> 1)
    }
  }
  def globalKeysClear(): Unit = {
    oldGlobalKeys = globalKeys
    globalKeys = new mutable.HashMap[String, Int]()
  }

  var globalSplitKeys: Map[String, Int] = ListMap()
  def addGlobalSplitKey(key: String, frequency: Int): Unit = {
    globalSplitKeys += (key -> frequency)
  }
  def globalSplitKeysClear(): Unit = {
    globalSplitKeys = ListMap.empty
  }

  def globalSplitKeyPartitionClear(): Unit = {
    globalSplitKeyPartition = mutable.HashMap()
  }

  def sortGlobalSplitKeys(): Map[String, Int] = ListMap(globalSplitKeys.toSeq.sortWith(_._2 > _._2): _*)
  //mutable.HashMap()不确定能不能这么排序
  def sortOldGlobalKeys(): Map[String, Int] = ListMap(oldGlobalKeys.toSeq.sortWith(_._2 > _._2): _*)
  def updateMultiReceiverSplitKeys(numReduce: Int): Unit = {
    //多receiver的reduce阶段划分效果没有单receiver的好，可能是因为低频没有排序？splitKeyPartition放到全局静态变量里
    //val splitKeyPartition: mutable.HashMap[String, Integer] = mutable.HashMap()
    //更新splitKeys的时候，就需要确定reduce阶段这些key的划分了
    val bucketSize: Array[Int] = new Array[Int](numReduce)

    /*
    val sortedOldGlobalKeys = sortOldGlobalKeys()
    val it = sortedOldGlobalKeys.keysIterator
    while (it.hasNext) {
      var leastID = 0
      val key = it.next()

      var leastSize = bucketSize(0)
      for (i <- 1 until numReduce) {
        if (bucketSize(i) < leastSize) {
          leastID = i
          leastSize = bucketSize(i)
        }
      }
      bucketSize(leastID) += sortedOldGlobalKeys(key)
      //获取global划分时key与reduce任务的对应关系
      splitKeyPartition += (key -> leastID)
    }

     */

    //如果在BlockGenerator里没对splitKeys排序，那就要在这排序
    val sortedGlobalSplitKeys: Map[String, Int] = sortGlobalSplitKeys()
    val it = sortedGlobalSplitKeys.keysIterator
    //先划分高频key
    while (it.hasNext) {
      var leastID = 0
      val key = it.next()

      var leastSize = bucketSize(0)
      for (i <- 1 until numReduce) {
        if (bucketSize(i) < leastSize) {
          leastID = i
          leastSize = bucketSize(i)
        }
      }
      bucketSize(leastID) += sortedGlobalSplitKeys(key)
      //获取global划分时key与reduce任务的对应关系
      globalSplitKeyPartition += (key -> leastID)
    }
    //再划分低频key
    val it2 = oldGlobalKeys.keysIterator
    while (it2.hasNext) {
      var leastID = 0
      val key = it2.next()
      //高频key已经被划分，如果是高频key就跳过
      if(!sortedGlobalSplitKeys.contains(key)) {
        var leastSize = bucketSize(0)
        for (i <- 1 until numReduce) {
          if (bucketSize(i) < leastSize) {
            leastID = i
            leastSize = bucketSize(i)
          }
        }
        bucketSize(leastID) += oldGlobalKeys(key)
        //获取global划分时key与reduce任务的对应关系
        globalSplitKeyPartition += (key -> leastID)
      }
    }


    //将对应关系存入队列
    //AstraeaBatchKeyInfo.splitKeyPartitionQueue += splitKeyPartition

  }




  //splitKey的频率信息
  //val splitKeyQueue: mutable.Queue[mutable.HashMap[String, Int]] = new mutable.Queue[mutable.HashMap[String, Int]]

  //splitKey的global 划分信息
  //splitKey相关的结构可能都需要转成[String, Integer]类型，因为java不支持[String, Int]
  val splitKeyPartitionQueue: mutable.Queue[mutable.HashMap[String, Integer]] = new mutable.Queue[mutable.HashMap[String, Integer]]

  var currentID: String = "000"
  //存储global划分时候的高频key的划分信息
  var splitPartition: mutable.HashMap[String, Integer] = mutable.HashMap[String, Integer]()
  /**
   * 获取当前未进行reduce阶段划分的第一个batch的splitKey的信息
   * <p>当一个阶段的多个Map任务分别请求时，如何返回相同的划分信息？
   * @return
   */
  def getFirstGlobal(uniqueID: String): util.Map[String, Integer] = {

    //假设可以传入stageID或jobID
    //这里uniqueID: String = "" + jobId.getOrElse(-1) + stageId
    //为了支持多receier和多stage，修改为了val uniqueID: String = "123" + jobId.getOrElse(-1)
    if (uniqueID == currentID) {
      //如果不是第一次来，即跟之前的任务是同一个stage的，则不能再出队，仍然使用上一次的信息
      splitPartition.asJava
    } else {
      //如果是第一次来，即新的stage，则出队一个
      currentID = uniqueID
      if (splitKeyPartitionQueue.nonEmpty) {
        splitPartition = splitKeyPartitionQueue.dequeue()
      }
      splitPartition.asJava
    }
  }

}
