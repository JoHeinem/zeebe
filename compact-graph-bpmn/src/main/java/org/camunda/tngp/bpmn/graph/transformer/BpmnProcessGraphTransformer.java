package org.camunda.tngp.bpmn.graph.transformer;

import static org.camunda.tngp.bpmn.graph.BpmnEdgeTypes.EDGE_TYPE_COUNT;
import static org.camunda.tngp.bpmn.graph.BpmnEdgeTypes.NODE_INCOMMING_SEQUENCE_FLOWS;
import static org.camunda.tngp.bpmn.graph.BpmnEdgeTypes.NODE_OUTGOING_SEQUENCE_FLOWS;
import static org.camunda.tngp.bpmn.graph.BpmnEdgeTypes.SEQUENCE_FLOW_SOURCE_NODE;
import static org.camunda.tngp.bpmn.graph.BpmnEdgeTypes.SEQUENCE_FLOW_TARGET_NODE;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import org.camunda.bpm.model.bpmn.impl.BpmnModelConstants;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.BpmnModelElementInstance;
import org.camunda.bpm.model.bpmn.instance.FlowElement;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.SubProcess;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.camunda.bpm.model.xml.type.ModelElementType;
import org.camunda.tngp.bpmn.graph.ProcessGraph;
import org.camunda.tngp.bpmn.graph.transformer.aspect.BpmnAspectHandlers;
import org.camunda.tngp.compactgraph.GraphEncoder;
import org.camunda.tngp.compactgraph.builder.GraphBuilder;
import org.camunda.tngp.compactgraph.builder.NodeBuilder;
import org.camunda.tngp.graph.bpmn.BpmnAspect;
import org.camunda.tngp.graph.bpmn.ExecutionEventType;
import org.camunda.tngp.graph.bpmn.FlowElementDescriptorEncoder;
import org.camunda.tngp.graph.bpmn.FlowElementDescriptorEncoder.EventBehaviorMappingEncoder;
import org.camunda.tngp.graph.bpmn.FlowElementType;
import org.camunda.tngp.graph.bpmn.ProcessDescriptorEncoder;

import org.agrona.concurrent.UnsafeBuffer;

public class BpmnProcessGraphTransformer
{
    protected static final float UTF8_MAX_CHARS_PER_BYTE = StandardCharsets.UTF_8.newDecoder().maxCharsPerByte();

    protected final GraphBuilder graphBuilder = new GraphBuilder();
    protected final Process process;
    protected final long id;
    protected final FlowElementDescriptorEncoder flowElementDescriptorEncoder = new FlowElementDescriptorEncoder();
    protected final ProcessDescriptorEncoder processDescriptorEncoder = new ProcessDescriptorEncoder();

    protected Map<String, Integer> nodeIdMap = new HashMap<>();

    public BpmnProcessGraphTransformer(Process process, long id)
    {
        this.process = process;
        this.id = id;
        graphBuilder.edgeTypeCount(EDGE_TYPE_COUNT);
    }

    public ProcessGraph transform()
    {
        createProcessNode();
        createFlowElements();
        connectSequenceFlows(process);
        writeProcessData();
        return encodeGraph();
    }

    protected void createProcessNode()
    {
        final NodeBuilder nodeBuilder = graphBuilder
            .newNode()
            .nodeData(encodeFlowElementData(process));

        nodeIdMap.put(process.getId(), nodeBuilder.id());
    }

    protected void writeProcessData()
    {
        final String processId = process.getId();

        final int processDataBufferLength = processDescriptorEncoder.sbeBlockLength() +
                FlowElementDescriptorEncoder.stringIdHeaderLength() +
                (int) Math.ceil(processId.length() / UTF8_MAX_CHARS_PER_BYTE);

        final byte[] dataBuffer = new byte[processDataBufferLength];

        processDescriptorEncoder.wrap(new UnsafeBuffer(dataBuffer), 0)
            .id(id)
            .intialFlowNodeId(findInitialFlowNode(process))
            .stringId(processId);

        graphBuilder.graphData(dataBuffer);
    }

    private int findInitialFlowNode(BpmnModelElementInstance scope)
    {
        final Collection<StartEvent> startEvents = scope.getChildElementsByType(StartEvent.class);

        for (StartEvent startEvent : startEvents)
        {
            if (startEvent.getEventDefinitions().isEmpty())
            {
                return nodeIdMap.get(startEvent.getId());
            }
        }

        throw new RuntimeException("Cannot find none-start event");
    }

    protected ProcessGraph encodeGraph()
    {
        final byte[] encodedGraph = new GraphEncoder(graphBuilder).encode();
        return new ProcessGraph().wrap(encodedGraph);
    }

    protected void connectSequenceFlows(BpmnModelElementInstance scope)
    {
        final Collection<SequenceFlow> sequenceFlows = scope.getChildElementsByType(SequenceFlow.class);

        for (SequenceFlow sequenceFlow : sequenceFlows)
        {
            final int sequenceFlowNodeId = nodeIdMap.get(sequenceFlow.getId());
            final int sourceNodeId = nodeIdMap.get(sequenceFlow.getSource().getId());
            final int targetNodeId = nodeIdMap.get(sequenceFlow.getTarget().getId());

            graphBuilder.node(sequenceFlowNodeId)
                .connect(sourceNodeId, SEQUENCE_FLOW_SOURCE_NODE, NODE_OUTGOING_SEQUENCE_FLOWS);

            graphBuilder.node(sequenceFlowNodeId)
                .connect(targetNodeId, SEQUENCE_FLOW_TARGET_NODE, NODE_INCOMMING_SEQUENCE_FLOWS);
        }

        final Collection<SubProcess> subProcesses = scope.getChildElementsByType(SubProcess.class);
        for (SubProcess subProcess : subProcesses)
        {
            connectSequenceFlows(subProcess);
        }
    }

    protected void createFlowElements()
    {
        final TreeSet<FlowElement> flowElements = new TreeSet<>((o1, o2) -> o1.getId().compareTo(o2.getId()));

        collectFlowElements(process, flowElements);

        for (FlowElement flowElement : flowElements)
        {
            final NodeBuilder nodeBuilder = graphBuilder
                .newNode()
                .nodeData(encodeFlowElementData(flowElement));

            nodeIdMap.put(flowElement.getId(), nodeBuilder.id());
        }
    }

    protected byte[] encodeFlowElementData(final BaseElement element)
    {
        final ModelElementType elementType = element.getElementType();
        final Class<? extends ModelElementInstance> instanceType = elementType.getInstanceType();
        final String id = element.getId();

        final byte[] nodeDataBuffer = new byte[1024 * 1024];

        final FlowElementType flowElementType = FlowElementTypeMapping.graphNodeTypeForModelType(instanceType);
        flowElementDescriptorEncoder.wrap(new UnsafeBuffer(nodeDataBuffer), 0)
            .type(flowElementType);

        String taskType = "";
        short taskQueueId = FlowElementDescriptorEncoder.taskQueueIdNullValue();

        if (element instanceof ServiceTask)
        {
            final String taskQueueIdAttribute = element.getAttributeValueNs(BpmnModelConstants.CAMUNDA_NS, "taskQueueId");
            taskQueueId = Short.parseShort(taskQueueIdAttribute);

            taskType = element.getAttributeValueNs(BpmnModelConstants.CAMUNDA_NS, "taskType");
        }

        flowElementDescriptorEncoder.taskQueueId(taskQueueId);

        final Map<ExecutionEventType, BpmnAspect> aspectMap = BpmnAspectHandlers.getBehavioralAspects(element);

        final EventBehaviorMappingEncoder eventBehaviorMappingEncoder =
            flowElementDescriptorEncoder.eventBehaviorMappingCount(aspectMap.size());

        for (Map.Entry<ExecutionEventType, BpmnAspect> aspect : aspectMap.entrySet())
        {
            eventBehaviorMappingEncoder
                .next()
                .event(aspect.getKey())
                .behavioralAspect(aspect.getValue());
        }

        flowElementDescriptorEncoder.stringId(id);
        flowElementDescriptorEncoder.taskType(taskType);

        final byte[] nodeData = new byte[flowElementDescriptorEncoder.encodedLength()];

        System.arraycopy(nodeDataBuffer, 0, nodeData, 0, nodeData.length);

        return nodeData;
    }

    private static void collectFlowElements(BpmnModelElementInstance scope, TreeSet<FlowElement> flowElements)
    {
        final Collection<FlowElement> childElements = scope.getChildElementsByType(FlowElement.class);
        flowElements.addAll(childElements);

        for (FlowElement flowElement : childElements)
        {
            collectFlowElements(flowElement, flowElements);
        }
    }

}
