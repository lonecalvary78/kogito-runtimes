/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jbpm.process;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.jbpm.process.core.ContextContainer;
import org.jbpm.process.core.context.exception.CompensationHandler;
import org.jbpm.process.core.context.exception.CompensationScope;
import org.jbpm.process.core.context.variable.Variable;
import org.jbpm.process.core.datatype.impl.type.ObjectDataType;
import org.jbpm.process.core.event.EventFilter;
import org.jbpm.process.core.event.EventTypeFilter;
import org.jbpm.process.core.event.NonAcceptingEventTypeFilter;
import org.jbpm.process.instance.impl.Action;
import org.jbpm.process.test.NodeCreator;
import org.jbpm.process.test.TestWorkItemHandler;
import org.jbpm.ruleflow.core.RuleFlowProcess;
import org.jbpm.test.util.AbstractBaseTest;
import org.jbpm.workflow.core.DroolsAction;
import org.jbpm.workflow.core.Node;
import org.jbpm.workflow.core.impl.DroolsConsequenceAction;
import org.jbpm.workflow.core.node.ActionNode;
import org.jbpm.workflow.core.node.BoundaryEventNode;
import org.jbpm.workflow.core.node.CompositeContextNode;
import org.jbpm.workflow.core.node.CompositeNode;
import org.jbpm.workflow.core.node.EndNode;
import org.jbpm.workflow.core.node.EventSubProcessNode;
import org.jbpm.workflow.core.node.StartNode;
import org.jbpm.workflow.core.node.WorkItemNode;
import org.jbpm.workflow.instance.impl.WorkflowProcessInstanceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.kie.api.definition.process.NodeContainer;
import org.kie.kogito.internal.process.runtime.KogitoProcessContext;
import org.kie.kogito.internal.process.runtime.KogitoProcessInstance;
import org.kie.kogito.internal.process.runtime.KogitoProcessRuntime;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jbpm.process.test.NodeCreator.connect;

public class CompensationTest extends AbstractBaseTest {

    public void addLogger() {
        logger = LoggerFactory.getLogger(this.getClass());
    }

    private KogitoProcessRuntime kruntime;

    @AfterEach
    public void cleanUp() {
        if (kruntime != null && kruntime.getKieSession() != null) {
            kruntime.getKieSession().dispose();
            kruntime = null;
        }
    }

    /*
     * General HELPER methods
     */

    private void addCompensationScope(final Node node, final org.kie.api.definition.process.NodeContainer parentContainer,
            final String compensationHandlerId) {
        ContextContainer contextContainer = (ContextContainer) parentContainer;
        CompensationScope scope = null;
        boolean addScope = false;
        if (contextContainer.getContexts(CompensationScope.COMPENSATION_SCOPE) == null) {
            addScope = true;
        } else {
            scope = (CompensationScope) contextContainer.getContexts(CompensationScope.COMPENSATION_SCOPE).get(0);
            if (scope == null) {
                addScope = true;
            }
        }
        if (addScope) {
            scope = new CompensationScope();
            contextContainer.addContext(scope);
            contextContainer.setDefaultContext(scope);
            scope.setContextContainer(contextContainer);
        }

        CompensationHandler handler = new CompensationHandler();
        handler.setNode(node);
        scope.setExceptionHandler(compensationHandlerId, handler);

        node.setMetaData("isForCompensation", Boolean.TRUE);
    }

    private Node findNode(RuleFlowProcess process, String nodeName) {
        Node found = null;
        Queue<org.kie.api.definition.process.Node> nodes = new LinkedList<>(Arrays.asList(process.getNodes()));
        while (!nodes.isEmpty()) {
            org.kie.api.definition.process.Node node = nodes.poll();
            if (node.getName().equals(nodeName)) {
                found = (Node) node;
            }
            if (node instanceof NodeContainer) {
                nodes.addAll(Arrays.asList(((NodeContainer) node).getNodes()));
            }
        }
        assertThat(found).as("Could not find node (" + nodeName + ").").isNotNull();

        return found;
    }

    /*
     * TESTS
     */

    @Test
    public void testCompensationBoundaryEventSpecific() throws Exception {
        String processId = "org.jbpm.process.compensation.boundary";
        String[] workItemNames = { "Don-Quixote", "Sancho", "Ricote" };
        List<String> eventList = new ArrayList<String>();
        RuleFlowProcess process = createCompensationBoundaryEventProcess(processId, workItemNames, eventList);

        // run process
        kruntime = createKogitoProcessRuntime(process);

        Node compensatedNode = findNode(process, "work1");
        String compensationEvent = (String) compensatedNode.getUniqueId();

        runCompensationBoundaryEventSpecificTest(kruntime, process, processId, workItemNames, eventList, compensationEvent);
    }

    public static void runCompensationBoundaryEventSpecificTest(KogitoProcessRuntime kruntime, RuleFlowProcess process, String processId,
            String[] workItemNames, List<String> eventList, String compensationEvent) {
        TestWorkItemHandler workItemHandler = new TestWorkItemHandler();
        for (String workItem : workItemNames) {
            kruntime.getKogitoWorkItemManager().registerWorkItemHandler(workItem, workItemHandler);
        }
        KogitoProcessInstance processInstance = kruntime.createProcessInstance(processId, Collections.emptyMap());
        WorkflowProcessInstanceImpl pi = (WorkflowProcessInstanceImpl) processInstance;
        pi.reconnect();
        kruntime.startProcessInstance(processInstance.getId());

        // call compensation on the uncompleted work 1 (which should not fire)

        kruntime.signalEvent("Compensation", compensationEvent, processInstance.getStringId());
        assertThat(eventList).as("Compensation should not have fired yet.").isEmpty();

        // complete work 1
        kruntime.getKogitoWorkItemManager().completeWorkItem(workItemHandler.getWorkItems().removeLast().getStringId(), null);
        assertThat(processInstance.getState()).isEqualTo(KogitoProcessInstance.STATE_ACTIVE);

        // call compensation on work 1, which should now fire
        kruntime.signalEvent("Compensation", compensationEvent, processInstance.getStringId());
        assertThat(eventList).as("Compensation should have fired.").hasSize(1);

        // complete work 2 & 3
        kruntime.getKogitoWorkItemManager().completeWorkItem(workItemHandler.getWorkItems().removeLast().getStringId(), null);
        kruntime.getKogitoWorkItemManager().completeWorkItem(workItemHandler.getWorkItems().removeLast().getStringId(), null);
        assertThat(processInstance.getState()).isEqualTo(KogitoProcessInstance.STATE_COMPLETED);
    }

    @Test
    public void testCompensationBoundaryEventGeneral() throws Exception {
        String processId = "org.jbpm.process.compensation.boundary";
        String[] workItemNames = { "Don-Quixote", "Sancho", "Ricote" };
        List<String> eventList = new ArrayList<String>();
        RuleFlowProcess process = createCompensationBoundaryEventProcess(processId, workItemNames, eventList);

        // run process
        kruntime = createKogitoProcessRuntime(process);
        String compensationEvent = CompensationScope.IMPLICIT_COMPENSATION_PREFIX + processId;

        runCompensationBoundaryEventGeneralTest(kruntime, process, processId, workItemNames, eventList, compensationEvent);
    }

    public static void runCompensationBoundaryEventGeneralTest(KogitoProcessRuntime kruntime, RuleFlowProcess process, String processId,
            String[] workItemNames, List<String> eventList, String compensationEvent) {
        TestWorkItemHandler workItemHandler = new TestWorkItemHandler();
        for (String workItem : workItemNames) {
            kruntime.getKogitoWorkItemManager().registerWorkItemHandler(workItem, workItemHandler);
        }
        KogitoProcessInstance processInstance = kruntime.createProcessInstance(processId, Collections.emptyMap());
        WorkflowProcessInstanceImpl pi = (WorkflowProcessInstanceImpl) processInstance;
        pi.reconnect();
        kruntime.startProcessInstance(processInstance.getId());

        // general compensation should not cause anything to happen
        kruntime.signalEvent("Compensation", compensationEvent, processInstance.getStringId());
        assertThat(eventList).as("Compensation should not have fired yet.").isEmpty();

        // complete work 1 & 2
        kruntime.getKogitoWorkItemManager().completeWorkItem(workItemHandler.getWorkItems().removeLast().getStringId(), null);
        kruntime.getKogitoWorkItemManager().completeWorkItem(workItemHandler.getWorkItems().removeLast().getStringId(), null);
        assertThat(processInstance.getState()).isEqualTo(KogitoProcessInstance.STATE_ACTIVE);
        assertThat(eventList).as("Compensation should not have fired yet.").isEmpty();

        // general compensation should now cause the compensation handlers to fire in reverse order
        kruntime.signalEvent("Compensation", compensationEvent, processInstance.getStringId());
        assertThat(eventList).as("Compensation should have fired.").hasSize(2);

        // complete work 3 and finish
        kruntime.getKogitoWorkItemManager().completeWorkItem(workItemHandler.getWorkItems().removeLast().getStringId(), null);
        assertThat(processInstance.getState()).isEqualTo(KogitoProcessInstance.STATE_COMPLETED);
    }

    private RuleFlowProcess createCompensationBoundaryEventProcess(String processId, String[] workItemNames,
            final List<String> eventList) throws Exception {
        RuleFlowProcess process = new RuleFlowProcess();
        process.setAutoComplete(true);
        process.setId(processId);
        process.setName("CESP Process");
        process.setMetaData("Compensation", true);

        List<Variable> variables = new ArrayList<Variable>();
        Variable variable = new Variable();
        variable.setName("event");
        ObjectDataType personDataType = new ObjectDataType();
        personDataType.setClassName("java.lang.String");
        variable.setType(personDataType);
        variables.add(variable);
        process.getVariableScope().setVariables(variables);

        NodeCreator<StartNode> startNodeCreator = new NodeCreator<StartNode>(process, StartNode.class);
        NodeCreator<EndNode> endNodeCreator = new NodeCreator<EndNode>(process, EndNode.class);
        NodeCreator<WorkItemNode> workItemNodeCreator = new NodeCreator<WorkItemNode>(process, WorkItemNode.class);
        NodeCreator<BoundaryEventNode> boundaryNodeCreator = new NodeCreator<BoundaryEventNode>(process, BoundaryEventNode.class);
        NodeCreator<ActionNode> actionNodeCreator = new NodeCreator<ActionNode>(process, ActionNode.class);

        // Create process
        StartNode startNode = startNodeCreator.createNode("start");
        Node lastNode = startNode;
        WorkItemNode[] workItemNodes = new WorkItemNode[3];
        for (int i = 0; i < 3; ++i) {
            workItemNodes[i] = workItemNodeCreator.createNode("work" + (i + 1));
            workItemNodes[i].getWork().setName(workItemNames[i]);
            connect(lastNode, workItemNodes[i]);
            lastNode = workItemNodes[i];
        }

        EndNode endNode = endNodeCreator.createNode("end");
        connect(workItemNodes[2], endNode);

        // Compensation (boundary event) handlers
        for (int i = 0; i < 3; ++i) {
            createBoundaryEventCompensationHandler(process, workItemNodes[i], eventList, "" + i + 1);
        }

        return process;
    }

    @Test
    public void testCompensationEventSubProcessSpecific() throws Exception {
        String processId = "org.jbpm.process.compensation.event.subprocess";
        String[] workItemNames = { "kwik", "kwek", "kwak" };
        List<String> eventList = new ArrayList<String>();
        RuleFlowProcess process = createCompensationEventSubProcessProcess(processId, workItemNames, eventList);

        Node toCompensateNode = findNode(process, "sub0");
        String compensationEvent = (String) toCompensateNode.getUniqueId();

        // run process
        kruntime = createKogitoProcessRuntime(process);

        runCompensationEventSubProcessSpecificTest(kruntime, process, processId, workItemNames, eventList, compensationEvent);
    }

    public static void runCompensationEventSubProcessSpecificTest(KogitoProcessRuntime kruntime, RuleFlowProcess process, String processId,
            String[] workItemNames, List<String> eventList, String compensationEvent) {

        // run process
        TestWorkItemHandler workItemHandler = new TestWorkItemHandler();
        for (String workItem : workItemNames) {
            kruntime.getKogitoWorkItemManager().registerWorkItemHandler(workItem, workItemHandler);
        }
        KogitoProcessInstance processInstance = kruntime.createProcessInstance(processId, Collections.emptyMap());
        WorkflowProcessInstanceImpl pi = (WorkflowProcessInstanceImpl) processInstance;
        pi.reconnect();
        kruntime.startProcessInstance(processInstance.getId());

        // call compensation on the uncompleted work 1 (which should not fire)
        kruntime.signalEvent("Compensation", compensationEvent, processInstance.getStringId());
        assertThat(eventList).as("Compensation should not have fired yet.").isEmpty();

        // pre work item
        kruntime.getKogitoWorkItemManager().completeWorkItem(workItemHandler.getWorkItems().removeLast().getStringId(), null);
        // sub-process is active, but not complete
        kruntime.signalEvent("Compensation", compensationEvent, processInstance.getStringId());
        assertThat(eventList).as("Compensation should not have fired yet.").isEmpty();

        // sub process work item
        kruntime.getKogitoWorkItemManager().completeWorkItem(workItemHandler.getWorkItems().removeLast().getStringId(), null);
        // sub-process has completed 
        kruntime.signalEvent("Compensation", compensationEvent, processInstance.getStringId());
        assertThat(eventList).as("Compensation should have fired once.").hasSize(1);

        // post work item
        kruntime.getKogitoWorkItemManager().completeWorkItem(workItemHandler.getWorkItems().removeLast().getStringId(), null);
        assertThat(processInstance.getState()).isEqualTo(KogitoProcessInstance.STATE_COMPLETED);
    }

    @Test
    public void testCompensationEventSubProcessGeneral() throws Exception {
        String processId = "org.jbpm.process.compensation.event.subprocess.general";
        String[] workItemNames = { "kwik", "kwek", "kwak" };
        List<String> eventList = new ArrayList<String>();
        RuleFlowProcess process = createCompensationEventSubProcessProcess(processId, workItemNames, eventList);

        String compensationEvent = CompensationScope.IMPLICIT_COMPENSATION_PREFIX + process.getId();

        // run process
        kruntime = createKogitoProcessRuntime(process);

        runCompensationEventSubProcessGeneralTest(kruntime, process, processId, workItemNames, eventList, compensationEvent);
    }

    public static void runCompensationEventSubProcessGeneralTest(KogitoProcessRuntime kruntime, RuleFlowProcess process, String processId,
            String[] workItemNames, List<String> eventList, String compensationEvent) {
        TestWorkItemHandler workItemHandler = new TestWorkItemHandler();
        for (String workItem : workItemNames) {
            kruntime.getKogitoWorkItemManager().registerWorkItemHandler(workItem, workItemHandler);
        }
        KogitoProcessInstance processInstance = kruntime.createProcessInstance(processId, Collections.emptyMap());
        WorkflowProcessInstanceImpl pi = (WorkflowProcessInstanceImpl) processInstance;
        pi.reconnect();
        kruntime.startProcessInstance(processInstance.getId());

        // pre and sub process work item
        kruntime.getKogitoWorkItemManager().completeWorkItem(workItemHandler.getWorkItems().removeLast().getStringId(), null);
        kruntime.getKogitoWorkItemManager().completeWorkItem(workItemHandler.getWorkItems().removeLast().getStringId(), null);
        // Call general compensation 

        kruntime.signalEvent("Compensation", compensationEvent, processInstance.getStringId());
        assertThat(eventList).as("Compensation should have fired once.").hasSize(1);

        // post work item
        kruntime.getKogitoWorkItemManager().completeWorkItem(workItemHandler.getWorkItems().removeLast().getStringId(), null);
        assertThat(processInstance.getState()).isEqualTo(KogitoProcessInstance.STATE_COMPLETED);
    }

    private RuleFlowProcess createCompensationEventSubProcessProcess(String processId, String[] workItemNames,
            final List<String> eventList) throws Exception {
        RuleFlowProcess process = new RuleFlowProcess();
        process.setAutoComplete(true);
        process.setId(processId);
        process.setName("CESP Process");
        process.setMetaData("Compensation", true);

        NodeCreator<StartNode> startNodeCreator = new NodeCreator<StartNode>(process, StartNode.class);
        NodeCreator<WorkItemNode> workItemNodeCreator = new NodeCreator<WorkItemNode>(process, WorkItemNode.class);
        NodeCreator<CompositeContextNode> compNodeCreator = new NodeCreator<CompositeContextNode>(process, CompositeContextNode.class);
        NodeCreator<EndNode> endNodeCreator = new NodeCreator<EndNode>(process, EndNode.class);

        // outer process
        StartNode startNode = startNodeCreator.createNode("start0");
        WorkItemNode workItemNode = workItemNodeCreator.createNode("work0-pre");
        workItemNode.getWork().setName(workItemNames[0]);
        connect(startNode, workItemNode);

        CompositeNode compositeNode = compNodeCreator.createNode("sub0");
        connect(workItemNode, compositeNode);

        workItemNode = workItemNodeCreator.createNode("work0-post");
        workItemNode.getWork().setName(workItemNames[2]);
        connect(compositeNode, workItemNode);

        EndNode endNode = endNodeCreator.createNode("end0");
        connect(workItemNode, endNode);

        // 1rst level nested subprocess 
        startNodeCreator.setNodeContainer(compositeNode);
        workItemNodeCreator.setNodeContainer(compositeNode);
        endNodeCreator.setNodeContainer(compositeNode);

        startNode = startNodeCreator.createNode("start1");
        workItemNode = workItemNodeCreator.createNode("work1");
        workItemNode.getWork().setName(workItemNames[1]);
        connect(startNode, workItemNode);

        endNode = endNodeCreator.createNode("end1");
        connect(workItemNode, endNode);

        // 2nd level nested event subprocess in 1rst level subprocess
        NodeCreator<EventSubProcessNode> espNodeCreator = new NodeCreator<EventSubProcessNode>(compositeNode, EventSubProcessNode.class);
        EventSubProcessNode espNode = espNodeCreator.createNode("eventSub1");
        EventTypeFilter eventFilter = new NonAcceptingEventTypeFilter();
        eventFilter.setType("Compensation");
        espNode.addEvent(eventFilter);

        addCompensationScope(espNode, process, compositeNode.getUniqueId());

        startNodeCreator.setNodeContainer(espNode);
        endNodeCreator.setNodeContainer(espNode);
        NodeCreator<ActionNode> actionNodeCreator = new NodeCreator<ActionNode>(espNode, ActionNode.class);

        startNode = startNodeCreator.createNode("start1*");
        ActionNode actionNode = actionNodeCreator.createNode("action1*");
        actionNode.setName("Execute");
        DroolsAction action = new DroolsConsequenceAction("java", null);
        action.setMetaData("Action", new Action() {
            public void execute(KogitoProcessContext context) throws Exception {
                eventList.add("Executed action");
            }
        });
        actionNode.setAction(action);
        connect(startNode, actionNode);

        endNode = endNodeCreator.createNode("end1*");
        connect(actionNode, endNode);

        return process;
    }

    @Test
    public void testNestedCompensationEventSubProcessSpecific() throws Exception {
        String processId = "org.jbpm.process.compensation.event.nested.subprocess";
        String[] workItemNames = { "kwik", "kwek", "kwak" };
        List<String> eventList = new ArrayList<String>();
        RuleFlowProcess process = createNestedCompensationEventSubProcessProcess(processId, workItemNames, eventList);

        Node toCompensateNode = findNode(process, "sub1");
        String compensationEvent = toCompensateNode.getUniqueId();

        kruntime = createKogitoProcessRuntime(process);

        runCompensationEventSubProcessSpecificTest(kruntime, process, processId, workItemNames, eventList, compensationEvent);
    }

    @Test
    public void testNestedCompensationEventSubProcessGeneral() throws Exception {
        String processId = "org.jbpm.process.compensation.event.subprocess.general";
        String[] workItemNames = { "apple", "banana", "orange" };
        List<String> eventList = new ArrayList<String>();
        RuleFlowProcess process = createNestedCompensationEventSubProcessProcess(processId, workItemNames, eventList);

        Node toCompensateNode = findNode(process, "sub0");
        String compensationEvent = CompensationScope.IMPLICIT_COMPENSATION_PREFIX + toCompensateNode.getUniqueId();

        kruntime = createKogitoProcessRuntime(process);

        runCompensationEventSubProcessGeneralTest(kruntime, process, processId, workItemNames, eventList, compensationEvent);
    }

    private RuleFlowProcess createNestedCompensationEventSubProcessProcess(String processId, String[] workItemNames,
            final List<String> eventList) throws Exception {
        RuleFlowProcess process = new RuleFlowProcess();
        process.setAutoComplete(true);
        process.setId(processId);
        process.setName("CESP Process");
        process.setMetaData("Compensation", true);

        NodeCreator<StartNode> startNodeCreator = new NodeCreator<StartNode>(process, StartNode.class);
        NodeCreator<WorkItemNode> workItemNodeCreator = new NodeCreator<WorkItemNode>(process, WorkItemNode.class);
        NodeCreator<CompositeContextNode> compNodeCreator = new NodeCreator<CompositeContextNode>(process, CompositeContextNode.class);
        NodeCreator<EndNode> endNodeCreator = new NodeCreator<EndNode>(process, EndNode.class);

        // outer process
        CompositeContextNode compositeNode = compNodeCreator.createNode("sub0");
        {
            StartNode startNode = startNodeCreator.createNode("start0");
            WorkItemNode workItemNode = workItemNodeCreator.createNode("work0-pre");
            workItemNode.getWork().setName(workItemNames[0]);
            connect(startNode, workItemNode);

            connect(workItemNode, compositeNode);

            EndNode endNode = endNodeCreator.createNode("end0");
            connect(compositeNode, endNode);
        }

        // 1rst level nested subprocess (contains compensation visibility scope)
        CompositeContextNode compensationScopeContainerNode = compositeNode;
        {
            startNodeCreator.setNodeContainer(compositeNode);
            workItemNodeCreator.setNodeContainer(compositeNode);
            compNodeCreator.setNodeContainer(compositeNode);
            endNodeCreator.setNodeContainer(compositeNode);

            StartNode startNode = startNodeCreator.createNode("start1");
            CompositeContextNode subCompNode = compNodeCreator.createNode("sub1");
            connect(startNode, subCompNode);

            WorkItemNode workItemNode = workItemNodeCreator.createNode("work1-post");
            workItemNode.getWork().setName(workItemNames[2]);
            connect(subCompNode, workItemNode);

            EndNode endNode = endNodeCreator.createNode("end1");
            connect(workItemNode, endNode);

            compositeNode = subCompNode;
        }

        // 2nd level nested subprocess 
        {
            startNodeCreator.setNodeContainer(compositeNode);
            workItemNodeCreator.setNodeContainer(compositeNode);
            endNodeCreator.setNodeContainer(compositeNode);

            StartNode startNode = startNodeCreator.createNode("start2");
            WorkItemNode workItemNode = workItemNodeCreator.createNode("work2");
            workItemNode.getWork().setName(workItemNames[1]);
            connect(startNode, workItemNode);

            EndNode endNode = endNodeCreator.createNode("end2");
            connect(workItemNode, endNode);
        }

        // 3nd level nested event subprocess in 2nd level subprocess
        {
            NodeCreator<EventSubProcessNode> espNodeCreator = new NodeCreator<EventSubProcessNode>(compositeNode, EventSubProcessNode.class);
            EventSubProcessNode espNode = espNodeCreator.createNode("eventSub2");

            startNodeCreator.setNodeContainer(espNode);
            endNodeCreator.setNodeContainer(espNode);
            NodeCreator<ActionNode> actionNodeCreator = new NodeCreator<ActionNode>(espNode, ActionNode.class);

            EventTypeFilter eventFilter = new NonAcceptingEventTypeFilter();
            eventFilter.setType("Compensation");
            espNode.addEvent(eventFilter);

            addCompensationScope(espNode, compensationScopeContainerNode, compositeNode.getUniqueId());

            StartNode startNode = startNodeCreator.createNode("start3*");
            ActionNode actionNode = actionNodeCreator.createNode("action3*");
            actionNode.setName("Execute");
            DroolsAction action = new DroolsConsequenceAction("java", null);
            action.setMetaData("Action", new Action() {
                public void execute(KogitoProcessContext context) throws Exception {
                    eventList.add("Executed action");
                }
            });
            actionNode.setAction(action);
            connect(startNode, actionNode);

            EndNode endNode = endNodeCreator.createNode("end3*");
            connect(actionNode, endNode);
        }

        return process;
    }

    @Test
    public void testNestedCompensationBoundaryEventSpecific() throws Exception {
        String processId = "org.jbpm.process.compensation.boundary.nested";
        String[] workItemNames = { "Don-Quixote", "Sancho", "Ricote" };
        List<String> eventList = new ArrayList<String>();
        RuleFlowProcess process = createNestedCompensationBoundaryEventProcess(processId, workItemNames, eventList);

        // run process
        kruntime = createKogitoProcessRuntime(process);

        Node compensatedNode = findNode(process, "work-comp-1");
        String compensationEvent = (String) compensatedNode.getUniqueId();

        runCompensationBoundaryEventSpecificTest(kruntime, process, processId, workItemNames, eventList, compensationEvent);
    }

    @Test
    public void testNestedCompensationBoundaryEventGeneral() throws Exception {
        String processId = "org.jbpm.process.compensation.boundary.general.nested";
        String[] workItemNames = { "Jip", "Janneke", "Takkie" };
        List<String> eventList = new ArrayList<String>();
        RuleFlowProcess process = createNestedCompensationBoundaryEventProcess(processId, workItemNames, eventList);

        // run process
        kruntime = createKogitoProcessRuntime(process);
        Node toCompensateNode = findNode(process, "sub2");
        String compensationEvent = CompensationScope.IMPLICIT_COMPENSATION_PREFIX
                + toCompensateNode.getUniqueId();

        runCompensationBoundaryEventGeneralTest(kruntime, process, processId, workItemNames, eventList, compensationEvent);
    }

    private RuleFlowProcess createNestedCompensationBoundaryEventProcess(String processId, String[] workItemNames,
            final List<String> eventList) throws Exception {
        RuleFlowProcess process = new RuleFlowProcess();
        process.setAutoComplete(true);
        process.setId(processId);
        process.setName("CESP Process");
        process.setMetaData("Compensation", true);

        List<Variable> variables = new ArrayList<Variable>();
        Variable variable = new Variable();
        variable.setName("event");
        ObjectDataType personDataType = new ObjectDataType();
        personDataType.setClassName("java.lang.String");
        variable.setType(personDataType);
        variables.add(variable);
        process.getVariableScope().setVariables(variables);

        NodeCreator<StartNode> startNodeCreator = new NodeCreator<StartNode>(process, StartNode.class);
        NodeCreator<EndNode> endNodeCreator = new NodeCreator<EndNode>(process, EndNode.class);
        NodeCreator<CompositeContextNode> compNodeCreator = new NodeCreator<CompositeContextNode>(process, CompositeContextNode.class);

        // process level
        CompositeContextNode compositeNode = compNodeCreator.createNode("sub0");
        {
            StartNode startNode = startNodeCreator.createNode("start0");
            connect(startNode, compositeNode);

            EndNode endNode = endNodeCreator.createNode("end0");
            connect(compositeNode, endNode);
        }

        // 1rst level nested subprocess (contains compensation visibility scope)
        {
            startNodeCreator.setNodeContainer(compositeNode);
            compNodeCreator.setNodeContainer(compositeNode);
            endNodeCreator.setNodeContainer(compositeNode);

            StartNode startNode = startNodeCreator.createNode("start1");
            CompositeContextNode subCompNode = compNodeCreator.createNode("sub1");
            connect(startNode, subCompNode);

            EndNode endNode = endNodeCreator.createNode("end1");
            connect(subCompNode, endNode);

            compositeNode = subCompNode;
        }

        // 2nd level nested subprocess (contains compensation visibility scope)
        NodeCreator<WorkItemNode> workItemNodeCreator = new NodeCreator<WorkItemNode>(compositeNode, WorkItemNode.class);
        {
            startNodeCreator.setNodeContainer(compositeNode);
            compNodeCreator.setNodeContainer(compositeNode);
            endNodeCreator.setNodeContainer(compositeNode);

            StartNode startNode = startNodeCreator.createNode("start2");
            CompositeContextNode subCompNode = compNodeCreator.createNode("sub2");
            connect(startNode, subCompNode);

            WorkItemNode workItemNode = workItemNodeCreator.createNode("work2");
            workItemNode.getWork().setName(workItemNames[2]);
            connect(subCompNode, workItemNode);

            EndNode endNode = endNodeCreator.createNode("end2");
            connect(workItemNode, endNode);

            createBoundaryEventCompensationHandler(compositeNode, workItemNode, eventList, "2");

            compositeNode = subCompNode;
        }

        // Fill 3rd level with process with compensation
        {
            startNodeCreator.setNodeContainer(compositeNode);
            workItemNodeCreator.setNodeContainer(compositeNode);
            endNodeCreator.setNodeContainer(compositeNode);

            StartNode startNode = startNodeCreator.createNode("start");
            Node lastNode = startNode;
            WorkItemNode[] workItemNodes = new WorkItemNode[3];
            for (int i = 0; i < 2; ++i) {
                workItemNodes[i] = workItemNodeCreator.createNode("work-comp-" + (i + 1));
                workItemNodes[i].getWork().setName(workItemNames[i]);
                connect(lastNode, workItemNodes[i]);
                lastNode = workItemNodes[i];
            }

            EndNode endNode = endNodeCreator.createNode("end");
            connect(workItemNodes[1], endNode);

            // Compensation (boundary event) handlers
            for (int i = 0; i < 2; ++i) {
                createBoundaryEventCompensationHandler(compositeNode, workItemNodes[i], eventList, "" + i + 1);
            }
        }
        return process;
    }

    private void createBoundaryEventCompensationHandler(org.jbpm.workflow.core.NodeContainer nodeContainer,
            Node attachedToNode, final List<String> eventList, final String id) throws Exception {

        NodeCreator<BoundaryEventNode> boundaryNodeCreator = new NodeCreator<BoundaryEventNode>(nodeContainer, BoundaryEventNode.class);
        BoundaryEventNode boundaryNode = boundaryNodeCreator.createNode("boundary" + id);
        String attachedTo = attachedToNode.getUniqueId();
        boundaryNode.setMetaData("AttachedTo", attachedTo);
        boundaryNode.setAttachedToNodeId(attachedTo);

        EventTypeFilter eventFilter = new NonAcceptingEventTypeFilter();
        eventFilter.setType("Compensation");
        List<EventFilter> eventFilters = new ArrayList<EventFilter>();
        boundaryNode.setEventFilters(eventFilters);
        eventFilters.add(eventFilter);

        addCompensationScope(boundaryNode, nodeContainer, attachedTo);

        NodeCreator<ActionNode> actionNodeCreator = new NodeCreator<ActionNode>(nodeContainer, ActionNode.class);
        ActionNode actionNode = actionNodeCreator.createNode("handlerAction" + id);
        actionNode.setMetaData("isForCompensation", true);
        actionNode.setName("Execute");
        DroolsAction action = new DroolsConsequenceAction("java", null);
        action.setMetaData("Action", new Action() {
            public void execute(KogitoProcessContext context) throws Exception {
                eventList.add("action" + id);
            }
        });
        actionNode.setAction(action);
        connect(boundaryNode, actionNode);
    }
}
