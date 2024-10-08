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
package org.kie.kogito.quarkus.serverless.workflow.python;

import org.kie.kogito.internal.process.workitem.KogitoWorkItemHandler;
import org.kie.kogito.process.impl.CachedWorkItemHandlerConfig;
import org.kie.kogito.serverless.workflow.python.PythonScriptWorkItemHandler;
import org.kie.kogito.serverless.workflow.python.PythonServiceWorkItemHandler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PythonWorkItemHandlerConfig extends CachedWorkItemHandlerConfig {

    private PythonScriptWorkItemHandler scriptHandler;
    private PythonServiceWorkItemHandler serviceHandler;

    @PostConstruct
    void init() {
        scriptHandler = registerHandler(new PythonScriptWorkItemHandler());
        serviceHandler = registerHandler(new PythonServiceWorkItemHandler());
    }

    @PreDestroy
    void cleanup() {
        scriptHandler.close();
        serviceHandler.close();
    }

    private <T extends KogitoWorkItemHandler> T registerHandler(T handler) {
        register(handler.getName(), handler);
        return handler;
    }
}
