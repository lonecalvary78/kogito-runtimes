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
package org.kie.kogito.event.impl.adapter;

import org.kie.api.event.process.ProcessStateChangeEvent;
import org.kie.kogito.event.DataEvent;
import org.kie.kogito.event.process.ProcessInstanceStateEventBody;
import org.kie.kogito.internal.process.runtime.KogitoProcessInstance;

public class ProcessStateChangeEventDataEventAdapter extends AbstractDataEventAdapter {

    public ProcessStateChangeEventDataEventAdapter() {
        super(ProcessStateChangeEvent.class);
    }

    @Override
    public DataEvent<?> adapt(Object payload) {
        ProcessStateChangeEvent event = (ProcessStateChangeEvent) payload;
        return adapt(event, ProcessInstanceStateEventBody.EVENT_TYPE_UPDATED, ((KogitoProcessInstance) event.getProcessInstance()).getStartDate());
    }
}
