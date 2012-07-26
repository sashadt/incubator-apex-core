/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.stram;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang.builder.ReflectionToStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.malhartech.dag.SerDe;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.ContainerHeartbeat;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.ContainerHeartbeatResponse;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StramToNodeRequest;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StreamingContainerContext;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StreamingNodeHeartbeat;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StreamingNodeHeartbeat.DNodeState;
import com.malhartech.stram.conf.TopologyBuilder;
import com.malhartech.stram.conf.TopologyBuilder.NodeConf;
import com.malhartech.stram.conf.TopologyBuilder.StreamConf;

/**
 * Tracks topology provisioning/allocation to containers.
 */
public class DNodeManager
{
  private final static Logger LOG = LoggerFactory.getLogger(DNodeManager.class);
  private final static String NO_PARTITION = "";
  private AtomicInteger nodeSequence = new AtomicInteger();
  private long windowStartMillis = System.currentTimeMillis();
  private long windowSizeMillis = 500;

  private class NodeStatus
  {
    StreamingNodeHeartbeat lastHeartbeat;
    final NodePConf pnode;
    int tuplesTotal;
    int bytesTotal;

    private NodeStatus(NodePConf pnode)
    {
      this.pnode = pnode;
    }

    boolean canShutdown()
    {
      // idle or output adapter
      if ((lastHeartbeat != null && DNodeState.IDLE.name().equals(lastHeartbeat.getState()))) {
        return true;
      }
      return outputAdapters.contains(pnode);
    }
  }
  /**
   * Nodes grouped for deployment, nodes connected with inline streams go to same container
   */
  private Map<NodeConf, Set<NodeConf>> nodeGroups = new HashMap<NodeConf, Set<NodeConf>>();
  /**
   * Count of instances to create for each logical node
   */
  private Map<NodeConf, List<byte[]>> nodePartitioning = new HashMap<NodeConf, List<byte[]>>();
  private List<Set<NodeConf>> deployGroups = new ArrayList<Set<NodeConf>>();
  private Map<String, NodeStatus> deployedNodes = new ConcurrentHashMap<String, NodeStatus>();
  private Map<String, StreamingContainerContext> containerContextMap = new HashMap<String, StreamingContainerContext>();
  private Map<NodeConf, Map<String, NodePConf>> logical2PhysicalNode = new ConcurrentHashMap<NodeConf, Map<String, NodePConf>>();
  private Map<String, NodeConf> nodeId2NodeConfMap = new ConcurrentHashMap<String, NodeConf>();
  private Map<StreamConf, NodePConf> adapterNodes = new ConcurrentHashMap<StreamConf, NodePConf>();

  public DNodeManager(TopologyBuilder topology)
  {
    addNodes(topology.getAllNodes().values());

    /*
     * try to align to it pleases eyes.
     */
    windowStartMillis -= (windowStartMillis % windowSizeMillis);
  }

  public int getNumRequiredContainers()
  {
    int numContainers = deployGroups.size() - nodePartitioning.size();
    if (!this.nodePartitioning.isEmpty()) {
      for (List<byte[]> partitions : this.nodePartitioning.values()) {
        numContainers += partitions.size();
      }
    }
    return numContainers;
  }

  /**
   * Create node tracking context for logical node. Exposed here for tests.
   *
   * @param dnodeId
   * @param nodeConf
   * @return
   */
  public static NodePConf createNodeContext(String dnodeId, NodeConf nodeConf)
  {
    NodePConf snc = new NodePConf();
    snc.setDnodeClassName(nodeConf.getProperties().get(TopologyBuilder.NODE_CLASSNAME));
    if (snc.getDnodeClassName() == null) {
      throw new IllegalArgumentException(String.format("Configuration for node '%s' is missing property '%s'", nodeConf.getId(), TopologyBuilder.NODE_CLASSNAME));
    }
    snc.setProperties(nodeConf.getProperties());
    snc.setLogicalId(nodeConf.getId());
    snc.setDnodeId(dnodeId);
    return snc;
  }
  private Set<NodePConf> outputAdapters = new HashSet<NodePConf>();

  private NodePConf newAdapterNodeContext(StreamConf streamConf, boolean isInputAdapter)
  {
    NodePConf snc = new NodePConf();
    snc.setDnodeClassName(AdapterWrapperNode.class.getName());
    Map<String, String> properties = new HashMap<String, String>(streamConf.getProperties());
    String streamClassName = properties.get(TopologyBuilder.STREAM_CLASSNAME);
    if (streamClassName == null) {
      throw new IllegalArgumentException(String.format("Configuration for node '%s' is missing property '%s'", streamConf.getId(), TopologyBuilder.STREAM_CLASSNAME));
    }
    properties.put(AdapterWrapperNode.KEY_STREAM_CLASS_NAME, streamClassName);
    properties.put(AdapterWrapperNode.KEY_IS_INPUT, String.valueOf(isInputAdapter));
    snc.setProperties(properties);
    snc.setLogicalId(streamConf.getId());
    snc.setDnodeId("" + nodeSequence.incrementAndGet());

    if (!isInputAdapter) {
      outputAdapters.add(snc);
    }
    return snc;
  }

  /**
   * Group nodes and return the number of required containers
   */
  private int addNodes(Collection<NodeConf> nodes)
  {
    // group the nodes
    for (NodeConf nc : nodes) {
      // if the node has inline links to other nodes, cluster
      groupNodes(nc.getInputStreams());
      for (StreamConf sc : nc.getInputStreams()) {
        List<byte[]> partitions = getStreamPartitions(sc);
        if (partitions != null) {
          // deploy instance for each partition
          nodePartitioning.put(nc, partitions);
        }
      }
      groupNodes(nc.getOutputStreams());
    }
    return nodeGroups.size();
  }

  private List<byte[]> getStreamPartitions(StreamConf streamConf)
  {
    try {
      SerDe serde = StramUtils.getSerdeInstance(streamConf.getProperties());
      byte[][] partitions = serde.getPartitions();
      if (partitions != null) {
        return new ArrayList<byte[]>(Arrays.asList(serde.getPartitions()));
      }
    }
    catch (Exception e) {
      LOG.error("Failed to get partition info from SerDe", e);
    }
    return null;
  }

  private void groupNodes(Collection<StreamConf> streams)
  {
    for (StreamConf sc : streams) {
      if (sc.isInline()) {
        if (sc.getSourceNode() == null || sc.getTargetNode() == null) {
          LOG.error("Invalid inline setting on stream {}", sc);
        }
        else {
          groupNodes(sc.getSourceNode(), sc.getTargetNode());
        }
      }
      else {
        // single node grouping
        if (sc.getSourceNode() != null) {
          groupNodes(sc.getSourceNode());
        }
        if (sc.getTargetNode() != null) {
          groupNodes(sc.getTargetNode());
        }
      }
    }
  }

  private void groupNodes(NodeConf... nodes)
  {
    Set<NodeConf> group = null;
    for (NodeConf node : nodes) {
      group = nodeGroups.get(node);
      if (group != null) {
        break;
      }
    }
    if (group == null) {
      group = new HashSet<NodeConf>();
      this.deployGroups.add(group);
    }
    for (NodeConf node : nodes) {
      group.add(node);
      nodeGroups.put(node, group);
    }
  }

  /**
   * Find next group of nodes to deploy. There is no deployment dependency between groups of nodes other than the requirement that buffer servers have to be
   * deployed first. Inline stream dependencies are handled through the grouping. Make best effort to deploy first groups w/o upstream dependencies else pick
   * first group from list
   */
  private Set<NodeConf> findDeployableNodeGroup()
  {
    // preference is to find a group that has no upstream dependencies
    // or they are already deployed
    for (Set<NodeConf> nodes : deployGroups) {
      boolean allInputsReady = true;
      for (NodeConf nodeConf : nodes) {
        if (nodeConf.getInputStreams().size() != 0) {
          // check if all inputs are deployed
          for (StreamConf streamConf : nodeConf.getInputStreams()) {
            NodeConf sourceNode = streamConf.getSourceNode();
            if (sourceNode != null && !streamConf.isInline()) {
              Set<NodeConf> sourceGroup = nodeGroups.get(sourceNode);
              if (nodes != sourceGroup && deployGroups.contains(sourceGroup)) {
                LOG.debug("Skipping group {} as input dependency {} is not satisfied", nodes, sourceNode);
                allInputsReady = false;
                break;
              }
            }
          }
        }
      }
      if (allInputsReady) {
        return nodes;
      }
      else {
        break; // try next group
      }
    }
    return !deployGroups.isEmpty() ? deployGroups.get(0) : null;
  }
  private Map<StreamConf, List<StreamPConf>> logical2PhysicalStream = new HashMap<StreamConf, List<StreamPConf>>();

  private StreamPConf newStreamContext(StreamConf streamConf, InetSocketAddress bufferServerAddress,
                                       byte[] subscriberPartition, NodePConf source, NodePConf target)
  {
    // create new stream info and assign buffer server
    StreamPConf sc = new StreamPConf();
    sc.setId(streamConf.getId());
    sc.setBufferServerHost(bufferServerAddress.getHostName());
    sc.setBufferServerPort(bufferServerAddress.getPort());
    sc.setInline(streamConf.isInline());
    if (subscriberPartition != null) {
      sc.setPartitionKeys(Arrays.asList(subscriberPartition));
    }
    sc.setSourceNodeId(source.getDnodeId());
    sc.setTargetNodeId(target.getDnodeId());
    return sc;
  }

  /**
   * Find the stream context(s) for the given logical stream. Returns multiple streams if either source or target use partitioning or load balancing.
   *
   * @param streamConf
   * @param nodeConf
   * @return
   */
  private List<StreamPConf> getPhysicalStreams(StreamConf streamConf, InetSocketAddress bufferServerAddress)
  {

    List<StreamPConf> pstreams = logical2PhysicalStream.get(streamConf);
    if (pstreams != null) {
      return pstreams;
    }

    pstreams = new ArrayList<StreamPConf>();
    logical2PhysicalStream.put(streamConf, pstreams);

    // map logical source and target to assigned nodes
    if (streamConf.getSourceNode() != null && streamConf.getTargetNode() != null) {
      // all publisher nodes
      Map<String, NodePConf> publishers = getPhysicalNodes(streamConf.getSourceNode());
      for (Map.Entry<String, NodePConf> publisherEntry : publishers.entrySet()) {
        // all subscriber nodes
        Map<String, NodePConf> subscribers = getPhysicalNodes(streamConf.getTargetNode());
        for (Map.Entry<String, NodePConf> subscriberEntry : subscribers.entrySet()) {
          byte[] subscriberPartition = null;
          if (NO_PARTITION != subscriberEntry.getKey()) {
            subscriberPartition = subscriberEntry.getKey().getBytes();
          }
          StreamPConf sc = newStreamContext(streamConf, bufferServerAddress, subscriberPartition,
                                            publisherEntry.getValue(), subscriberEntry.getValue());
          // type is upstream node logical name to allow multiple logical downstream nodes
          sc.setBufferServerChannelType(streamConf.getSourceNode().getId());
          sc.setProperties(streamConf.getProperties());
          pstreams.add(sc);
        }
      }
    }
    else {
      // adapters
      if (streamConf.getSourceNode() == null) {
        // input adapter, with implementation class
        NodePConf adapterNode = this.adapterNodes.get(streamConf);
        if (adapterNode == null) {
          adapterNode = newAdapterNodeContext(streamConf, true);
          this.adapterNodes.put(streamConf, adapterNode);
        }
        Map<String, NodePConf> subscribers = getPhysicalNodes(streamConf.getTargetNode());
        if (subscribers.size() == 1) {
          // inline adapter and target node
          StreamPConf sc = newStreamContext(streamConf, bufferServerAddress, null,
                                            adapterNode, subscribers.values().iterator().next());
          sc.setInline(true);
          sc.setProperties(streamConf.getProperties());
          pstreams.add(sc);
        }
        else {
          // input to partitioned target node - adapter publishes to buffer server(s)
          for (Map.Entry<String, NodePConf> subscriberEntry : subscribers.entrySet()) {
            byte[] subscriberPartition = null;
            if (NO_PARTITION != subscriberEntry.getKey()) {
              subscriberPartition = subscriberEntry.getKey().getBytes();
            }
            StreamPConf sc = newStreamContext(streamConf, bufferServerAddress, subscriberPartition,
                                              adapterNode, subscriberEntry.getValue());
            sc.setInline(false);
            // type is adapter name for multiple downstream nodes to be able to subscribe
            sc.setBufferServerChannelType(streamConf.getId());
            sc.setProperties(streamConf.getProperties());
            pstreams.add(sc);
          }
        }
      }
      else if (streamConf.getTargetNode() == null) {
        // output adapter, with implementation class
        NodePConf adapterNode = this.adapterNodes.get(streamConf);
        if (adapterNode == null) {
          adapterNode = newAdapterNodeContext(streamConf, false);
          this.adapterNodes.put(streamConf, adapterNode);
        }
        Map<String, NodePConf> publishers = getPhysicalNodes(streamConf.getSourceNode());
        if (publishers.size() == 1) {
          // inline adapter and source node
          StreamPConf sc = newStreamContext(streamConf, bufferServerAddress, null,
                                            publishers.values().iterator().next(), adapterNode);
          sc.setInline(true);
          sc.setProperties(streamConf.getProperties());
          pstreams.add(sc);
        }
        else {
          // output from partitioned source node - adapter subscribes to buffer server(s)
          for (Map.Entry<String, NodePConf> publisherEntry : publishers.entrySet()) {
            StreamPConf sc = newStreamContext(streamConf, bufferServerAddress, null,
                                              publisherEntry.getValue(), adapterNode);
            sc.setInline(false); // set to true in assignContainer when adapter is deployed to same container
            sc.setBufferServerChannelType(streamConf.getId());
            sc.setProperties(streamConf.getProperties());
            pstreams.add(sc);
          }
        }
      }
    }

    return pstreams;
  }

  /**
   * Assign streaming nodes to newly available container. Multiple nodes can run in a container.
   *
   * @param containerId
   * @param bufferServerAddress Buffer server for publishers on the container.
   * @return
   */
  public synchronized StreamingContainerContext assignContainer(String containerId, InetSocketAddress bufferServerAddress)
  {
    if (deployGroups.isEmpty()) {
      throw new IllegalStateException("There are no nodes to deploy.");
    }
    Set<NodeConf> nodes = findDeployableNodeGroup();
    if (nodes == null) {
      throw new IllegalStateException("Cannot find a streaming node for new container, remaining unassigned nodes are " + this.deployGroups);
    }

    // figure physical nodes for logical set
    List<byte[]> inputPartitions = null;
    List<NodePConf> pnodeList = new ArrayList<NodePConf>(nodes.size());
    for (NodeConf nodeConf : nodes) {
      Map<String, NodePConf> pnodes = getPhysicalNodes(nodeConf);
      if (this.nodePartitioning.containsKey(nodeConf)) {
        // partitioned deployment
        if (inputPartitions != null) {
          LOG.error("Cannot partition more than one node in group {}.", nodes);
        }
        inputPartitions = this.nodePartitioning.get(nodeConf);
        // pick the next partition
        String partKey = new String(inputPartitions.remove(0));
        NodePConf sc = pnodes.get(partKey);
        if (sc == null) {
          throw new IllegalStateException("Node not found for partition key " + partKey);
        }
        if (inputPartitions.isEmpty()) {
          // all partitions deployed
          this.nodePartitioning.remove(nodeConf);
        }
        pnodeList.add(sc);
      }
      else {
        // no partitioning
        if (pnodes.size() != 1) {
          String msg = String.format("There should be a single instance for non-partitioned nodes, but found {}.", pnodes);
          throw new IllegalStateException(msg);
        }
        pnodeList.add(pnodes.values().iterator().next());
      }
    }

    if (inputPartitions == null || inputPartitions.isEmpty()) {
      deployGroups.remove(nodes);
    }

    // find streams for to be deployed node(s)
    // eliminate source duplicates within container (inline or not)
    Map<String, StreamPConf> streams = new HashMap<String, StreamPConf>();
    for (NodePConf snc : pnodeList.toArray(new NodePConf[pnodeList.size()])) {
      NodeConf nodeConf = nodeId2NodeConfMap.get(snc.getDnodeId());
      // DAG node inputs
      for (StreamConf streamConf : nodeConf.getInputStreams()) {
        // find incoming stream(s)
        // if source is partitioned, it is one entry per upstream partition,
        List<StreamPConf> pstreams = getPhysicalStreams(streamConf, bufferServerAddress);
        for (StreamPConf pstream : pstreams) {
          if (pstream.getTargetNodeId() == snc.getDnodeId()) {
            // node instance is subscriber
            streams.put(pstream.getSourceNodeId() + pstream.getId(), pstream);
            if (streamConf.getSourceNode() == null) {
              // input adapter: deploy with first subscriber
              if (!this.deployedNodes.containsKey(pstream.getSourceNodeId())) {
                pnodeList.add(adapterNodes.get(streamConf));
              }
            }
          }
        }
      }
      // DAG node outputs
      for (StreamConf streamConf : nodeConf.getOutputStreams()) {
        // find outgoing stream(s)
        // if this stream/target is partitioned, one entry per partition,
        List<StreamPConf> pstreams = getPhysicalStreams(streamConf, bufferServerAddress);
        for (StreamPConf pstream : pstreams) {
          if (pstream.getSourceNodeId() == snc.getDnodeId()) {
            // node is publisher
            streams.put(pstream.getSourceNodeId() + pstream.getId(), pstream);
            if (streamConf.getTargetNode() == null) {
              // output adapter: deploy with first publisher
              if (!this.deployedNodes.containsKey(pstream.getTargetNodeId())) {
                pnodeList.add(adapterNodes.get(streamConf));
                // adapter needs to subscribe to all partitions
                for (StreamPConf pstream2 : pstreams) {
                  streams.put(pstream2.getSourceNodeId() + pstream2.getId(), pstream2);
                  if (snc.getDnodeId() == pstream2.getSourceNodeId()) {
                    // both nodes in same container, make stream inline
                    pstream2.setInline(true);
                  }
                }
              }
            }
          }
        }
      }
    }

    for (NodePConf pnode : pnodeList) {
      this.deployedNodes.put(pnode.getDnodeId(), new NodeStatus(pnode));
    }

    StreamingContainerContext scc = new StreamingContainerContext();
    scc.setWindowSizeMillis(this.windowSizeMillis);
    scc.setStartWindowMillis(this.windowStartMillis);
    scc.setNodes(pnodeList);
    scc.setStreams(new ArrayList<StreamPConf>(streams.values()));
    containerContextMap.put(containerId, scc);

    return scc;
  }

  /**
   * Map of partition to node (if incoming streams use partitions). If no partitions are used, resulting map will have single entry.
   *
   * @param nodeConf
   * @return
   */
  private Map<String, NodePConf> getPhysicalNodes(NodeConf nodeConf)
  {
    synchronized (logical2PhysicalNode) {
      Map<String, NodePConf> pNodes = logical2PhysicalNode.get(nodeConf);
      if (pNodes == null) {
        pNodes = new HashMap<String, NodePConf>();
        List<byte[]> partitions = this.nodePartitioning.get(nodeConf);
        if (partitions != null) {
          for (byte[] p : partitions) {
            pNodes.put(new String(p), newNodeContext(nodeConf));
          }
        }
        else {
          pNodes.put(NO_PARTITION, newNodeContext(nodeConf));
        }
        logical2PhysicalNode.put(nodeConf, pNodes);
      }
      return pNodes;
    }
  }

  private NodePConf newNodeContext(NodeConf nodeConf)
  {
    NodePConf scc = createNodeContext("" + nodeSequence.incrementAndGet(), nodeConf);
    nodeId2NodeConfMap.put(scc.getDnodeId(), nodeConf);
    return scc;
  }

  public StreamingContainerContext getContainerContext(String containerId)
  {
    StreamingContainerContext ctx = containerContextMap.get(containerId);
    if (ctx == null) {
      throw new IllegalArgumentException("No context for container " + containerId);
    }
    return ctx;
  }

  public ContainerHeartbeatResponse processHeartbeat(ContainerHeartbeat heartbeat)
  {
    boolean containerIdle = true;

    for (StreamingNodeHeartbeat shb : heartbeat.getDnodeEntries()) {
      ReflectionToStringBuilder b = new ReflectionToStringBuilder(shb);

      NodeStatus status = deployedNodes.get(shb.getNodeId());
      if (status == null) {
        LOG.error("Heartbeat for unknown node {} (container {})", shb.getNodeId(), heartbeat.getContainerId());
        continue;
      }

      LOG.info("node {} ({}) heartbeat: {}, totalTuples: {}, totalBytes: {} - {}",
               new Object[]{shb.getNodeId(), status.pnode.getLogicalId(), b.toString(), status.tuplesTotal, status.bytesTotal, heartbeat.getContainerId()});

      status.lastHeartbeat = shb;
      if (!status.canShutdown()) {
        containerIdle = false;
        status.bytesTotal += shb.getNumberBytesProcessed();
        status.tuplesTotal += shb.getNumberTuplesProcessed();
        checkNodeLoad(shb);
      }
    }

    List<StramToNodeRequest> requests = new ArrayList<StramToNodeRequest>();
    ContainerHeartbeatResponse rsp = new ContainerHeartbeatResponse();
    if (containerIdle && isApplicationIdle()) {
      LOG.info("requesting shutdown for container {}", heartbeat.getContainerId());
      rsp.setShutdown(true);
    }
    rsp.setNodeRequests(requests);
    return rsp;
  }

  private boolean isApplicationIdle()
  {
    for (NodeStatus nodeStatus : this.deployedNodes.values()) {
      if (!nodeStatus.canShutdown()) {
        return false;
      }
    }
    return true;
  }

  private void checkNodeLoad(StreamingNodeHeartbeat shb)
  {
    NodeConf nodeConf = nodeId2NodeConfMap.get(shb.getNodeId());
    // TODO: synchronization
    if (nodeConf == null) {
      LOG.warn("Cannot find the configuration for node {}", shb.getNodeId());
      return;
    }
    // check load constraints
    int tuplesProcessed = shb.getNumberTuplesProcessed();
    // TODO: populate into bean at initialization time
    Map<String, String> properties = nodeConf.getProperties();
    if (properties.containsKey(TopologyBuilder.NODE_LB_TUPLECOUNT_MIN)) {
      int minTuples = new Integer(properties.get(TopologyBuilder.NODE_LB_TUPLECOUNT_MIN));
      if (tuplesProcessed < minTuples) {
        LOG.warn("Node {} processed {} messages below configured min {}", new Object[]{shb.getNodeId(), tuplesProcessed, minTuples});
      }
    }
    if (properties.containsKey(TopologyBuilder.NODE_LB_TUPLECOUNT_MAX)) {
      int maxTuples = new Integer(properties.get(TopologyBuilder.NODE_LB_TUPLECOUNT_MAX));
      if (tuplesProcessed > maxTuples) {
        LOG.warn("Node {} processed {} messages and exceeds configured max {}", new Object[]{shb.getNodeId(), tuplesProcessed, maxTuples});
      }
    }

  }
}
