/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.kogito.core.rules.incubation.quarkus.support;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import org.kie.kogito.incubation.common.DataContext;
import org.kie.kogito.incubation.common.Id;
import org.kie.kogito.incubation.common.MapDataContext;
import org.kie.kogito.incubation.rules.QueryId;
import org.kie.kogito.incubation.rules.RuleUnitId;
import org.kie.kogito.incubation.rules.services.RuleUnitService;
import org.kie.kogito.rules.RuleUnit;
import org.kie.kogito.rules.RuleUnitData;
import org.kie.kogito.rules.RuleUnitInstance;
import org.kie.kogito.rules.RuleUnits;

@ApplicationScoped
public class QuarkusRuleUnitService implements RuleUnitService {

    @Inject
    Instance<RuleUnits> ruleUnits;
    @Inject
    RuleUnitDataObjectMapper mapper;

    @Override
    public Stream<DataContext> evaluate(Id id, DataContext inputContext) {
        RuleUnitId ruleUnitId;
        QueryId queryId;
        if (id instanceof QueryId) {
            queryId = (QueryId) id;
            ruleUnitId = queryId.ruleUnitId();
        } else {
            // LocalDecisionId.parse(decisionId);
            throw new IllegalArgumentException(
                    "Not a valid query id " + id.toLocalId());
        }

        Map<String, Object> payload = inputContext.as(MapDataContext.class).toMap();
        RuleUnitData ruleUnitData = mapper.convertValue(payload, ruleUnitId);
        RuleUnit<RuleUnitData> ruleUnit = ruleUnits.get().create((Class<RuleUnitData>) ruleUnitData.getClass());
        RuleUnitInstance<RuleUnitData> instance = ruleUnit.createInstance(ruleUnitData);
        List<Map<String, Object>> results = instance.executeQuery(queryId.queryId());

        return results.stream().map(MapDataContext::of);

    }

}