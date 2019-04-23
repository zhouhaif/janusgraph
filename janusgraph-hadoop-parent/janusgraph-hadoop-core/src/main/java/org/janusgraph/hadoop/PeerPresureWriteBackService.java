package org.janusgraph.hadoop;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.MapConfiguration;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.tinkerpop.gremlin.hadoop.structure.io.VertexWritable;
import org.apache.tinkerpop.gremlin.process.computer.VertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.util.VertexProgramHelper;
import org.apache.tinkerpop.gremlin.spark.process.computer.payload.ViewOutgoingPayload;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.star.StarGraph;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.janusgraph.core.Cardinality;
import org.janusgraph.core.JanusGraphTransaction;
import org.janusgraph.core.RelationType;
import org.janusgraph.core.schema.JanusGraphManagement;
import org.janusgraph.diskstorage.configuration.backend.CommonsConfiguration;
import org.janusgraph.diskstorage.hbase.HBaseStoreManager;
import org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration;
import org.janusgraph.graphdb.database.StandardJanusGraph;
import org.janusgraph.hadoop.config.JanusGraphHadoopConfiguration;
import scala.Tuple2;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;


/**
 * Created by Think on 2019/4/18.
 */
public class PeerPresureWriteBackService implements VertexProgram.WriteBackService<JavaPairRDD<Object, VertexWritable>, Object>, Serializable {
    private Map<String, Object> configMap;
    private static final String VOTE_STRENGTH = "gremlin.peerPressureVertexProgram.voteStrength";
    private int commitSize = 10000;

    @Override
    public void setConfigration(Configuration configration) {
        configMap = new HashMap<>();
//        configMap.put(GraphDatabaseConfiguration.STORAGE_BATCH.toStringWithoutRoot(),false);
//        configMap.put(GraphDatabaseConfiguration.SCHEMA_CONSTRAINTS.toStringWithoutRoot(),false);
//        configMap.put(GraphDatabaseConfiguration.AUTO_TYPE.toStringWithoutRoot(),"default");
        configMap.put(GraphDatabaseConfiguration.GREMLIN_GRAPH.toStringWithoutRoot(), "org.janusgraph.core.JanusGraphFactory");
        configMap.put(GraphDatabaseConfiguration.STORAGE_BACKEND.toStringWithoutRoot(), configration.getProperty(JanusGraphHadoopConfiguration.GRAPH_CONFIG_KEYS + "." + GraphDatabaseConfiguration.STORAGE_BACKEND.toStringWithoutRoot()));
        configMap.put(GraphDatabaseConfiguration.STORAGE_HOSTS.toStringWithoutRoot(), configration.getProperty(JanusGraphHadoopConfiguration.GRAPH_CONFIG_KEYS + "." + GraphDatabaseConfiguration.STORAGE_HOSTS.toStringWithoutRoot()));
        configMap.put(GraphDatabaseConfiguration.STORAGE_PORT.toStringWithoutRoot(), configration.getProperty(JanusGraphHadoopConfiguration.GRAPH_CONFIG_KEYS + "." + GraphDatabaseConfiguration.STORAGE_PORT.toStringWithoutRoot()));
        configMap.put(HBaseStoreManager.HBASE_TABLE.toStringWithoutRoot(), configration.getProperty(JanusGraphHadoopConfiguration.GRAPH_CONFIG_KEYS + "." + HBaseStoreManager.HBASE_TABLE.toStringWithoutRoot()));
    }

    @Override
    public void execute(final JavaPairRDD<Object, VertexWritable> input, VertexProgram<Object> vertexProgram) {
        final String[] vertexComputeKeysArray = VertexProgramHelper.vertexComputeKeysAsArray(vertexProgram.getVertexComputeKeys());
        String propertyName = null;
        for (String key : vertexComputeKeysArray) {
            if (!VOTE_STRENGTH.equals(key)) {
                propertyName = key;
                break;
            }
        }
        final String name = propertyName;
        if(name!=null){
            final CommonsConfiguration config = new CommonsConfiguration(new MapConfiguration(configMap));
            StandardJanusGraph graph = new StandardJanusGraph(new GraphDatabaseConfiguration(config));
            JanusGraphManagement management = graph.openManagement();
            RelationType key = management.getRelationType(name);
            if (key == null) {
                management.makePropertyKey(name).dataType(String.class).cardinality(Cardinality.SINGLE).make();
            }
            management.commit();
            graph.close();
        }
        int count = (int) input.count();
        int partition = count / commitSize + 1;
        JavaPairRDD<Object, VertexWritable> writableRDD = input.repartition(partition);
        writableRDD = writableRDD.mapPartitionsToPair(partitionIterator -> {
            final CommonsConfiguration config = new CommonsConfiguration(new MapConfiguration(configMap));
            StandardJanusGraph graph = new StandardJanusGraph(new GraphDatabaseConfiguration(config));
            JanusGraphTransaction tx = graph.buildTransaction().enableBatchLoading().start();
            while(partitionIterator.hasNext()){
                StarGraph.StarVertex vertex = partitionIterator.next()._2().get();
                Vertex v = tx.getVertex(Long.valueOf(vertex.id().toString()));
                v.property(VertexProperty.Cardinality.single, name, vertex.property(name).value());
            }
            Iterator s = IteratorUtils.map(partitionIterator, tuple -> new Tuple2<>(tuple._2().get().get().id(),null));
            tx.commit();
            tx.close();
            graph.close();
            return s;
        });
        writableRDD.count();
    }

    @Override
    public Map<String, Object> getGraphConfig() {
        return configMap;
    }
}
