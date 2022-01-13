# Astraea

Astraea is a novel non-hash based differentiated data partitioning scheme for micro-batch stream processing.


## Building

Astraea is built using [Apache Maven](http://maven.apache.org/).
To build Astraea, first run:

    mvn -Pyarn -Phadoop-2.7 -Dhadoop.version=2.7.3 -Phive -Phive-thriftserver -Dmaven.test.skip=true clean package -pl core
    
Edit ./Streaming/pom.xml, modify the property of Astraea.home as the location of the newly compiled spark-core_2.11-2.1.0.jar, then run:

    mvn -Pyarn -Phadoop-2.7 -Dhadoop.version=2.7.3 -Phive -Phive-thriftserver -Dmaven.test.skip=true clean package -pl streaming

Then, one can get the compiled spark-streaming_2.11-2.1.0.jar

Next, one can download the pre-build spark-2.1 from https://spark.apache.org/downloads.html.
Put the previously compiled spark-core_2.11-2.1.0.jar and spark-streaming_2.11-2.1.0.jar to $SPARK_HOME$/jar/ for replacing the old jars. Then one can start computation.

The input data should be of csv format. Astraea regard the first element of a record as the key, which is used for partitioning in the reduce stage.

Astraea receives the input data using the socketTextStream, for example

    val lines = ssc.socketTextStream("localhost", 9999)
    val data = lines.map(line => (line.split(",", 2)(0), line.split(",", 2)(1)))
    val result = data.groupByKey()

