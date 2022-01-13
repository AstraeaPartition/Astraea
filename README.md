# Astraea

Astraea is a novel non-hash based differentiated data partitioning scheme for micro-batch stream processing.


## Building

Astraea is built using [Apache Maven](http://maven.apache.org/).
To build Astraea, first run:

    mvn -Pyarn -Phadoop-2.7 -Dhadoop.version=2.7.3 -Phive -Phive-thriftserver -Dmaven.test.skip=true clean package -pl core
    
Then, edit ./Streaming/pom.xml, modify the property of Astraea.home as the location of the newly compiled spark-core_2.11-2.1.0.jar, then run:

    mvn -Pyarn -Phadoop-2.7 -Dhadoop.version=2.7.3 -Phive -Phive-thriftserver -Dmaven.test.skip=true clean package -pl streaming

You can get the compiled spark-streaming_2.11-2.1.0.jar

Next, you can download the pre-build spark-2.1 from https://spark.apache.org/downloads.html.
Put the previously obtaind spark-core_2.11-2.1.0.jar and spark-streaming_2.11-2.1.0.jar to $SPARK_HOME$/jar/ and replace the old jars.
