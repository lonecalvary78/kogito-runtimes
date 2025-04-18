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
package $Package$;

import org.jbpm.process.core.timer.BusinessCalendarImpl;
import org.kie.kogito.calendar.BusinessCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.inject.Produces;

public class BusinessCalendarProducer {

    private static final Logger logger = LoggerFactory.getLogger(BusinessCalendarProducer.class);
    private BusinessCalendar businessCalendar;

    public BusinessCalendarProducer() {
        this.businessCalendar = BusinessCalendarImpl.builder().build();
    }

    @Produces
    public BusinessCalendar createBusinessCalendar() {
        return this.businessCalendar;
    }
}