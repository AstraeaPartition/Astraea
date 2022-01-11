# Astraea

Astraea is a novel non-hash based differentiated data partitioning scheme for micro-batch stream processing.


## Building

Astraea is built using [Apache Maven](http://maven.apache.org/).
To build Astraea, run:

    mvn -Pyarn -Phadoop-2.7 -Dhadoop.version=2.7.3 -Phive -Phive-thriftserver -Dmaven.test.skip=true clean package -pl core
    mvn -Pyarn -Phadoop-2.7 -Dhadoop.version=2.7.3 -Phive -Phive-thriftserver -Dmaven.test.skip=true clean package -pl streaming

