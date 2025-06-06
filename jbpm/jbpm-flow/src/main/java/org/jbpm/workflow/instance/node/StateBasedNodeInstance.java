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
package org.jbpm.workflow.instance.node;

import java.time.ZonedDateTime;
import java.util.*;

import org.drools.core.common.InternalAgenda;
import org.drools.core.common.ReteEvaluator;
import org.drools.core.rule.consequence.InternalMatch;
import org.jbpm.process.core.timer.DateTimeUtils;
import org.jbpm.process.core.timer.Timer;
import org.jbpm.process.instance.InternalProcessRuntime;
import org.jbpm.process.instance.impl.Action;
import org.jbpm.ruleflow.core.Metadata;
import org.jbpm.util.ContextFactory;
import org.jbpm.workflow.core.DroolsAction;
import org.jbpm.workflow.core.WorkflowProcess;
import org.jbpm.workflow.core.impl.NodeImpl;
import org.jbpm.workflow.core.node.StateBasedNode;
import org.jbpm.workflow.instance.impl.ExtendedNodeInstanceImpl;
import org.jbpm.workflow.instance.impl.WorkflowProcessInstanceImpl;
import org.kie.api.event.rule.MatchCreatedEvent;
import org.kie.api.runtime.KieRuntime;
import org.kie.kogito.calendar.BusinessCalendar;
import org.kie.kogito.internal.process.event.KogitoEventListener;
import org.kie.kogito.internal.process.runtime.KogitoNodeInstance;
import org.kie.kogito.internal.process.runtime.KogitoProcessContext;
import org.kie.kogito.internal.process.runtime.KogitoProcessInstance;
import org.kie.kogito.internal.process.runtime.KogitoProcessRuntime;
import org.kie.kogito.jobs.*;
import org.kie.kogito.jobs.descriptors.ProcessInstanceJobDescription;
import org.kie.kogito.process.expr.Expression;
import org.kie.kogito.process.expr.ExpressionHandlerFactory;
import org.kie.kogito.timer.TimerInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Objects.isNull;
import static org.jbpm.process.core.constants.CalendarConstants.BUSINESS_CALENDAR_ENVIRONMENT_KEY;
import static org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE;
import static org.jbpm.workflow.instance.node.TimerNodeInstance.TIMER_TRIGGERED_EVENT;
import static org.kie.kogito.internal.utils.ConversionUtils.isEmpty;
import static org.kie.kogito.internal.utils.ConversionUtils.isNotEmpty;

public abstract class StateBasedNodeInstance extends ExtendedNodeInstanceImpl implements EventBasedNodeInstanceInterface, KogitoEventListener {

    private static final long serialVersionUID = 510l;

    private static final Logger logger = LoggerFactory.getLogger(StateBasedNodeInstance.class);

    private List<String> timerInstances;

    private Map<String, String> timerInstancesReference;

    private transient KogitoProcessContext context;

    public StateBasedNode getEventBasedNode() {
        return (StateBasedNode) getNode();
    }

    @Override
    public void internalTrigger(KogitoNodeInstance from, String type) {
        super.internalTrigger(from, type);
        // if node instance was cancelled, abort
        if (getNodeInstanceContainer().getNodeInstance(getStringId()) == null) {
            return;
        }
        // activate timers
        Map<Timer, DroolsAction> timers = getEventBasedNode().getTimers();
        if (timers != null) {
            addTimerListener();
            timerInstances = new ArrayList<>(timers.size());
            timerInstancesReference = new HashMap<>(timers.size());
            JobsService jobService = ((KogitoProcessRuntime.Provider) getProcessInstance().getKnowledgeRuntime().getProcessRuntime()).getKogitoProcessRuntime().getJobsService();
            for (Timer timer : timers.keySet()) {
                ProcessInstanceJobDescription jobDescription =
                        ProcessInstanceJobDescription.newProcessInstanceJobDescriptionBuilder()
                                .generateId()
                                .timerId(Long.toString(timer.getId()))
                                .expirationTime(createTimerInstance(timer))
                                .rootProcessId(getProcessInstance().getRootProcessId())
                                .rootProcessInstanceId(getProcessInstance().getRootProcessInstanceId())
                                .processId(getProcessInstance().getProcessId())
                                .processInstanceId(getProcessInstance().getStringId())
                                .nodeInstanceId(this.getId())
                                .build();
                String jobId = jobService.scheduleJob(jobDescription);
                timerInstances.add(jobId);
                timerInstancesReference.put(jobId, Long.toString(timer.getId()));
            }
        }

        if (getEventBasedNode().getBoundaryEvents() != null) {

            for (String name : getEventBasedNode().getBoundaryEvents()) {
                boolean isActive = ((InternalAgenda) getProcessInstance().getKnowledgeRuntime().getAgenda())
                        .isRuleActiveInRuleFlowGroup("DROOLS_SYSTEM", name, getProcessInstance().getId());
                if (isActive) {
                    getProcessInstance().getKnowledgeRuntime().signalEvent(name, null);
                } else {
                    addActivationListener();
                }
            }
        }

        ((WorkflowProcessInstanceImpl) getProcessInstance()).addActivatingNodeId((String) getNode().getUniqueId());
    }

    @Override
    protected void configureSla() {
        String slaDueDateExpression = (String) getNode().getMetaData().get("customSLADueDate");
        if (slaDueDateExpression != null) {
            TimerInstance timer = ((WorkflowProcessInstanceImpl) getProcessInstance()).configureSLATimer(slaDueDateExpression, this.getId());
            if (timer != null) {
                this.slaTimerId = timer.getId();
                this.slaDueDate = new Date(System.currentTimeMillis() + timer.getDelay());
                this.slaCompliance = KogitoProcessInstance.SLA_PENDING;
                logger.debug("SLA for node instance {} is PENDING with due date {}", this.getStringId(), this.slaDueDate);
                addTimerListener();
            }
        }
    }

    protected ExpirationTime createTimerInstance(Timer timer) {

        KieRuntime kruntime = getProcessInstance().getKnowledgeRuntime();
        if (kruntime != null && kruntime.getEnvironment().get(BUSINESS_CALENDAR_ENVIRONMENT_KEY) != null) {
            BusinessCalendar businessCalendar = (BusinessCalendar) kruntime.getEnvironment().get(BUSINESS_CALENDAR_ENVIRONMENT_KEY);
            String delay = null;
            switch (timer.getTimeType()) {
                case Timer.TIME_CYCLE:

                    String tempDelay = resolveTimerExpression(timer.getDelay());
                    String tempPeriod = resolveTimerExpression(timer.getPeriod());
                    if (DateTimeUtils.isRepeatable(tempDelay)) {
                        String[] values = DateTimeUtils.parseISORepeatable(tempDelay);
                        String tempRepeatLimit = values[0];
                        tempDelay = values[1];
                        tempPeriod = values[2];

                        if (!tempRepeatLimit.isEmpty()) {
                            try {
                                int repeatLimit = Integer.parseInt(tempRepeatLimit);
                                if (repeatLimit <= -1) {
                                    repeatLimit = Integer.MAX_VALUE;
                                }

                                return DurationExpirationTime.repeat(businessCalendar.calculateBusinessTimeAsDuration(tempDelay), businessCalendar.calculateBusinessTimeAsDuration(tempPeriod),
                                        repeatLimit);
                            } catch (NumberFormatException e) {
                                // ignore
                            }
                        }
                    }
                    long actualDelay = businessCalendar.calculateBusinessTimeAsDuration(tempDelay);
                    if (tempPeriod == null) {
                        return DurationExpirationTime.repeat(actualDelay, actualDelay, Integer.MAX_VALUE);
                    } else {
                        return DurationExpirationTime.repeat(actualDelay, businessCalendar.calculateBusinessTimeAsDuration(tempPeriod), Integer.MAX_VALUE);
                    }

                case Timer.TIME_DURATION:
                    delay = resolveTimerExpression(timer.getDelay());

                    return DurationExpirationTime.after(businessCalendar.calculateBusinessTimeAsDuration(delay));
                case Timer.TIME_DATE:
                    // even though calendar is available concrete date was provided so it shall be used
                    return ExactExpirationTime.of(timer.getDate());

                default:
                    throw new UnsupportedOperationException("Not supported timer definition");
            }
        } else {
            return configureTimerInstance(timer);
        }
    }

    protected ExpirationTime configureTimerInstance(Timer timer) {
        String s = null;
        long duration = -1;
        switch (timer.getTimeType()) {
            case Timer.TIME_CYCLE:
                if (timer.getPeriod() != null) {

                    long actualDelay = DateTimeUtils.parseDuration(resolveTimerExpression(timer.getDelay()));
                    if (timer.getPeriod() == null) {
                        return DurationExpirationTime.repeat(actualDelay, actualDelay, Integer.MAX_VALUE);
                    } else {
                        return DurationExpirationTime.repeat(actualDelay, DateTimeUtils.parseDuration(resolveTimerExpression(timer.getPeriod())), Integer.MAX_VALUE);
                    }
                } else {
                    String resolvedDelay = resolveTimerExpression(timer.getDelay());

                    // when using ISO date/time period is not set
                    long[] repeatValues = null;
                    try {
                        repeatValues = DateTimeUtils.parseRepeatableDateTime(timer.getDelay());
                    } catch (RuntimeException e) {
                        // cannot parse delay, trying to interpret it
                        repeatValues = DateTimeUtils.parseRepeatableDateTime(resolvedDelay);
                    }
                    if (repeatValues.length == 3) {
                        int parsedReapedCount = (int) repeatValues[0];
                        if (parsedReapedCount <= -1) {
                            parsedReapedCount = Integer.MAX_VALUE;
                        }

                        return DurationExpirationTime.repeat(repeatValues[1], repeatValues[2], parsedReapedCount);
                    } else if (repeatValues.length == 2) {
                        return DurationExpirationTime.repeat(repeatValues[0], repeatValues[1], Integer.MAX_VALUE);
                    } else {
                        return DurationExpirationTime.repeat(repeatValues[0], repeatValues[0], Integer.MAX_VALUE);
                    }
                }

            case Timer.TIME_DURATION:

                try {
                    duration = DateTimeUtils.parseDuration(timer.getDelay());
                } catch (RuntimeException e) {
                    // cannot parse delay, trying to interpret it
                    s = resolveTimerExpression(timer.getDelay());
                    duration = DateTimeUtils.parseDuration(s);
                }
                return DurationExpirationTime.after(duration);

            case Timer.TIME_DATE:
                try {
                    return ExactExpirationTime.of(timer.getDate());
                } catch (RuntimeException e) {
                    // cannot parse delay, trying to interpret it
                    s = resolveTimerExpression(timer.getDate());
                    return ExactExpirationTime.of(s);
                }
        }
        throw new UnsupportedOperationException("Not supported timer definition");
    }

    private String resolveTimerExpression(String expression) {
        if (!isExpression(expression)) {
            WorkflowProcess process = ((NodeImpl) getNode()).getProcess();
            String lang = process.getExpressionLanguage();
            if (lang != null) {
                Expression exprObject = ExpressionHandlerFactory.get(lang, expression);
                if (exprObject.isValid()) {
                    if (context == null) {
                        context = ContextFactory.fromNode(this);
                    }
                    String varName = (String) process.getMetaData().get(Metadata.VARIABLE);
                    Object target = varName == null ? this.getProcessInstance().getVariables() : context.getVariable(varName);
                    return exprObject.eval(target, String.class, context);
                }
            }
        }
        return resolveExpression(expression);
    }

    protected void handleSLAViolation() {
        if (slaCompliance == KogitoProcessInstance.SLA_PENDING) {
            InternalProcessRuntime processRuntime = ((InternalProcessRuntime) getProcessInstance().getKnowledgeRuntime().getProcessRuntime());
            processRuntime.getProcessEventSupport().fireBeforeSLAViolated(getProcessInstance(), this, getProcessInstance().getKnowledgeRuntime());
            logger.debug("SLA violated on node instance {}", getStringId());
            this.slaCompliance = KogitoProcessInstance.SLA_VIOLATED;
            this.slaTimerId = null;
            processRuntime.getProcessEventSupport().fireAfterSLAViolated(getProcessInstance(), this, getProcessInstance().getKnowledgeRuntime());
        }
    }

    @Override
    public void signalEvent(String type, Object event) {
        if (TIMER_TRIGGERED_EVENT.equals(type)) {
            TimerInstance timerInstance = (TimerInstance) event;
            if (timerInstances != null && timerInstances.contains(timerInstance.getId())) {
                triggerTimer(timerInstance);
            } else if (timerInstance.getId().equals(slaTimerId)) {
                handleSLAViolation();
            }
        } else if (("slaViolation:" + getStringId()).equals(type)) {
            handleSLAViolation();
        } else if (type.equals(getActivationType()) && event instanceof MatchCreatedEvent) {
            String name = ((MatchCreatedEvent) event).getMatch().getRule().getName();
            if (checkProcessInstance((InternalMatch) ((MatchCreatedEvent) event).getMatch())) {
                ((MatchCreatedEvent) event).getKieRuntime().signalEvent(name, null);
            }
        }
    }

    private void triggerTimer(TimerInstance timerInstance) {
        for (Map.Entry<Timer, DroolsAction> entry : getEventBasedNode().getTimers().entrySet()) {
            if (Objects.equals(Objects.toString(entry.getKey().getId()), timerInstance.getTimerId())) {
                if (timerInstance.getRepeatLimit() == 0) {
                    timerInstances.remove(timerInstance.getId());
                    if (timerInstancesReference != null) {
                        timerInstancesReference.remove(timerInstance.getId());
                    }
                }
                executeAction((Action) entry.getValue().getMetaData("Action"));
                return;
            }
        }
    }

    @Override
    public String[] getEventTypes() {
        return new String[] { TIMER_TRIGGERED_EVENT, getActivationType() };
    }

    public void triggerCompleted() {
        triggerCompleted(CONNECTION_DEFAULT_TYPE, true);
    }

    @Override
    public void addEventListeners() {
        if (timerInstances != null && (!timerInstances.isEmpty()) || isNotEmpty(this.slaTimerId)) {
            addTimerListener();
        }
        if (slaCompliance == KogitoProcessInstance.SLA_PENDING) {
            getProcessInstance().addEventListener("slaViolation:" + getStringId(), this, true);
        }
    }

    protected void addTimerListener() {
        getProcessInstance().addEventListener(TIMER_TRIGGERED_EVENT, this, false);
        getProcessInstance().addEventListener("timer", this, true);
        getProcessInstance().addEventListener("slaViolation:" + getStringId(), this, true);
    }

    @Override
    public void removeEventListeners() {
        getProcessInstance().removeEventListener(TIMER_TRIGGERED_EVENT, this, false);
        getProcessInstance().removeEventListener("timer", this, true);
        getProcessInstance().removeEventListener("slaViolation:" + getStringId(), this, true);
    }

    @Override
    public void triggerCompleted(String type, boolean remove) {
        if (this.slaCompliance == KogitoProcessInstance.SLA_PENDING) {
            if (System.currentTimeMillis() > slaDueDate.getTime()) {
                // completion of the node instance is after expected SLA due date, mark it accordingly
                this.slaCompliance = KogitoProcessInstance.SLA_VIOLATED;
            } else {
                this.slaCompliance = KogitoProcessInstance.STATE_COMPLETED;
            }
        }
        cancelSlaTimer();
        ((org.jbpm.workflow.instance.NodeInstanceContainer) getNodeInstanceContainer()).setCurrentLevel(getLevel());
        cancelTimers();
        removeActivationListener();
        super.triggerCompleted(type, remove);
    }

    public List<String> getTimerInstances() {
        return timerInstances;
    }

    public void internalSetTimerInstances(List<String> timerInstances) {
        this.timerInstances = timerInstances;
    }

    public Map<String, String> getTimerInstancesReference() {
        return timerInstancesReference;
    }

    public void internalSetTimerInstancesReference(Map<String, String> timerInstancesReference) {
        this.timerInstancesReference = timerInstancesReference;
    }

    @Override
    public void cancel(CancelType cancelType) {
        if (this.slaCompliance == KogitoProcessInstance.SLA_PENDING) {
            if (System.currentTimeMillis() > slaDueDate.getTime()) {
                // completion of the process instance is after expected SLA due date, mark it accordingly
                this.slaCompliance = KogitoProcessInstance.SLA_VIOLATED;
            } else {
                this.slaCompliance = KogitoProcessInstance.SLA_ABORTED;
            }
        }
        cancelSlaTimer();
        cancelTimers();
        removeEventListeners();
        removeActivationListener();
        super.cancel(cancelType);
    }

    private void cancelTimers() {
        // deactivate still active timers
        if (timerInstances != null) {
            JobsService jobService = ((InternalProcessRuntime) getProcessInstance().getKnowledgeRuntime().getProcessRuntime()).getJobsService();
            for (String id : timerInstances) {
                jobService.cancelJob(id);
                if (timerInstancesReference != null) {
                    timerInstancesReference.remove(id);
                }
            }
        }
    }

    private void cancelSlaTimer() {
        if (isNotEmpty(this.slaTimerId)) {
            JobsService jobService = ((InternalProcessRuntime) getProcessInstance().getKnowledgeRuntime().getProcessRuntime()).getJobsService();
            jobService.cancelJob(this.slaTimerId);
            logger.debug("SLA Timer {} has been canceled", this.slaTimerId);
        }
    }

    protected String getActivationType() {
        return "RuleFlowStateEvent-" + this.getProcessInstance().getProcessId();
    }

    private void addActivationListener() {
        getProcessInstance().addEventListener(getActivationType(), this, true);
    }

    private void removeActivationListener() {
        getProcessInstance().removeEventListener(getActivationType(), this, true);
    }

    protected boolean checkProcessInstance(InternalMatch match) {
        ReteEvaluator workingMemory = (ReteEvaluator) getProcessInstance().getKnowledgeRuntime();
        return match.checkProcessInstance(workingMemory, getProcessInstance().getStringId());
    }

    public Map<String, String> extractTimerEventInformation() {
        if (getTimerInstances() != null) {
            for (String id : getTimerInstances()) {
                String referenceId = timerInstancesReference != null ? timerInstancesReference.get(id) : null;
                for (Timer entry : getEventBasedNode().getTimers().keySet()) {
                    if (Objects.equals(Long.toString(entry.getId()), referenceId)) {
                        Map<String, String> properties = new HashMap<>();
                        properties.put("TimerID", referenceId);
                        properties.put("Delay", entry.getDelay());
                        properties.put("Period", entry.getPeriod());
                        properties.put("Date", entry.getDate());

                        return properties;
                    }
                }
            }
        }

        return null;
    }

    protected final KogitoProcessContext getProcessContext(Throwable e) {
        KogitoProcessContext context = ContextFactory.fromNode(this);
        context.getContextData().put("Exception", e);
        return context;
    }

    @Override
    public Collection<TimerDescription> timers() {
        Collection<TimerDescription> toReturn = super.timers();

        if (isNotEmpty(slaTimerId)) {
            TimerDescription slaTimer = TimerDescription.Builder.ofNodeInstance(this)
                    .timerId(slaTimerId)
                    .timerDescription("[SLA] " + resolveExpression(getNodeName()))
                    .build();
            toReturn.add(slaTimer);
        }

        if (timerInstancesReference != null) {
            Set<Timer> nodeTimers = getEventBasedNode().getTimers().keySet();
            for (Timer timer : nodeTimers) {
                Optional<String> jobIdOptional = timerInstancesReference.entrySet()
                        .stream()
                        .filter(entry -> String.valueOf(timer.getId()).equals(entry.getValue()))
                        .findFirst()
                        .map(Map.Entry::getKey);

                jobIdOptional.ifPresent(jobId -> {
                    TimerDescription timerDescription = TimerDescription.Builder.ofNodeInstance(this)
                            .timerId(jobId)
                            .timerDescription(resolveExpression(timer.getName()))
                            .build();
                    toReturn.add(timerDescription);
                });
            }
        }

        return toReturn;
    }

    @Override
    public void rescheduleSlaTimer(ZonedDateTime slaDueDate) {
        if (isNull(slaDueDate)) {
            throw new IllegalArgumentException("Cannot update SLA: slaDueDate cannot be null");
        }

        if (isEmpty(slaTimerId)) {
            throw new IllegalStateException("Cannot update SLA: Node has NO SLA configured");
        }

        InternalProcessRuntime processRuntime = ((InternalProcessRuntime) getProcessInstance().getKnowledgeRuntime().getProcessRuntime());
        ((WorkflowProcessInstanceImpl) getProcessInstance()).rescheduleTimer(slaTimerId, slaDueDate, getId());
        this.slaDueDate = Date.from(slaDueDate.toInstant());
        processRuntime.getProcessEventSupport().fireOnNodeStateChanged(this, getProcessInstance().getKnowledgeRuntime());
    }
}
