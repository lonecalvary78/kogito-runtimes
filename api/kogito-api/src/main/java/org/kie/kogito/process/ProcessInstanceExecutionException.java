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
package org.kie.kogito.process;

/**
 * Thrown when there is problems encountered during process instance execution.
 * Usually caused by one of the node instances not able to perform desired action.
 * 
 */
public class ProcessInstanceExecutionException extends RuntimeException {

    private static final long serialVersionUID = 8031225233775014572L;

    private final String processInstanceId;
    private final String failedNodeId;
    private final String failedNodeInstanceId;
    private final String errorMessage;

    public ProcessInstanceExecutionException(String processInstanceId, String failedNodeId, String failedNodeInstanceId, String errorMessage) {
        this(processInstanceId, failedNodeId, failedNodeInstanceId, errorMessage, null);
    }

    public ProcessInstanceExecutionException(String processInstanceId, String failedNodeId, String failedNodeInstanceId, String errorMessage, Throwable rootCause) {
        super("Process instance with id " + processInstanceId + " failed because of " + errorMessage, rootCause);
        this.processInstanceId = processInstanceId;
        this.failedNodeId = failedNodeId;
        this.failedNodeInstanceId = failedNodeInstanceId;
        this.errorMessage = errorMessage;
    }

    /**
     * Returns process instance id of the instance that failed.
     * 
     * @return process instance id
     */
    public String getProcessInstanceId() {
        return processInstanceId;
    }

    /**
     * Returns node definition id of the node instance that failed to execute
     * 
     * @return node definition id
     */
    public String getFailedNodeId() {
        return failedNodeId;
    }

    /**
     * Returns error message associated with this failure. Usually will consists of
     * error id, fully qualified class name of the root cause exception and error message
     * 
     * @return error message
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    public String getFailedNodeInstanceId() {
        return failedNodeInstanceId;
    }

    public static ProcessInstanceExecutionException fromError(ProcessInstance<?> processInstance) {
        ProcessError error = processInstance.error().get();
        return new ProcessInstanceExecutionException(processInstance.id(), error.failedNodeId(), error.failedNodeInstanceId(), error.errorMessage(), error.errorCause());
    }
}
