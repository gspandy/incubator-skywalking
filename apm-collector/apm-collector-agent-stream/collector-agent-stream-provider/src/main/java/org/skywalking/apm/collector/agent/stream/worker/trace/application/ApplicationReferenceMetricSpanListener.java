/*
 * Copyright 2017, OpenSkywalking Organization All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Project repository: https://github.com/OpenSkywalking/skywalking
 */

package org.skywalking.apm.collector.agent.stream.worker.trace.application;

import java.util.LinkedList;
import java.util.List;
import org.skywalking.apm.collector.agent.stream.graph.TraceStreamGraph;
import org.skywalking.apm.collector.agent.stream.parser.EntrySpanListener;
import org.skywalking.apm.collector.agent.stream.parser.ExitSpanListener;
import org.skywalking.apm.collector.agent.stream.parser.RefsListener;
import org.skywalking.apm.collector.agent.stream.parser.standardization.ReferenceDecorator;
import org.skywalking.apm.collector.agent.stream.parser.standardization.SpanDecorator;
import org.skywalking.apm.collector.cache.CacheModule;
import org.skywalking.apm.collector.cache.service.InstanceCacheService;
import org.skywalking.apm.collector.configuration.ConfigurationModule;
import org.skywalking.apm.collector.configuration.service.IApdexThresholdService;
import org.skywalking.apm.collector.core.graph.Graph;
import org.skywalking.apm.collector.core.graph.GraphManager;
import org.skywalking.apm.collector.core.module.ModuleManager;
import org.skywalking.apm.collector.core.util.ApdexThresholdUtils;
import org.skywalking.apm.collector.core.util.CollectionUtils;
import org.skywalking.apm.collector.core.util.Const;
import org.skywalking.apm.collector.core.util.TimeBucketUtils;
import org.skywalking.apm.collector.storage.table.application.ApplicationReferenceMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author peng-yongsheng
 */
public class ApplicationReferenceMetricSpanListener implements EntrySpanListener, ExitSpanListener, RefsListener {

    private final Logger logger = LoggerFactory.getLogger(ApplicationReferenceMetricSpanListener.class);

    private final IApdexThresholdService apdexThresholdService;
    private final InstanceCacheService instanceCacheService;
    private final List<ApplicationReferenceMetric> applicationReferenceMetrics;
    private final List<ApplicationReferenceMetric> references;

    public ApplicationReferenceMetricSpanListener(ModuleManager moduleManager) {
        this.applicationReferenceMetrics = new LinkedList<>();
        this.references = new LinkedList<>();
        this.instanceCacheService = moduleManager.find(CacheModule.NAME).getService(InstanceCacheService.class);
        this.apdexThresholdService = moduleManager.find(ConfigurationModule.NAME).getService(IApdexThresholdService.class);
    }

    @Override
    public void parseExit(SpanDecorator spanDecorator, int applicationId, int instanceId, String segmentId) {
        ApplicationReferenceMetric applicationReferenceMetric = new ApplicationReferenceMetric(Const.EMPTY_STRING);
        applicationReferenceMetric.setFrontApplicationId(applicationId);
        applicationReferenceMetric.setBehindApplicationId(spanDecorator.getPeerId());
        applicationReferenceMetric.setTimeBucket(TimeBucketUtils.INSTANCE.getMinuteTimeBucket(spanDecorator.getStartTime()));

        String idBuilder = String.valueOf(applicationReferenceMetric.getTimeBucket()) + Const.ID_SPLIT + applicationId +
            Const.ID_SPLIT + spanDecorator.getPeerId();

        applicationReferenceMetric.setId(idBuilder);
        applicationReferenceMetrics.add(buildNodeRefSum(applicationReferenceMetric, spanDecorator.getStartTime(), spanDecorator.getEndTime(), spanDecorator.getIsError()));
    }

    @Override
    public void parseEntry(SpanDecorator spanDecorator, int applicationId, int instanceId,
        String segmentId) {
        if (CollectionUtils.isNotEmpty(references)) {
            references.forEach(nodeReference -> {
                nodeReference.setTimeBucket(TimeBucketUtils.INSTANCE.getMinuteTimeBucket(spanDecorator.getStartTime()));
                String idBuilder = String.valueOf(nodeReference.getTimeBucket()) + Const.ID_SPLIT + nodeReference.getFrontApplicationId() +
                    Const.ID_SPLIT + nodeReference.getBehindApplicationId();

                nodeReference.setId(idBuilder);
                applicationReferenceMetrics.add(buildNodeRefSum(nodeReference, spanDecorator.getStartTime(), spanDecorator.getEndTime(), spanDecorator.getIsError()));
            });
        } else {
            ApplicationReferenceMetric applicationReferenceMetric = new ApplicationReferenceMetric(Const.EMPTY_STRING);
            applicationReferenceMetric.setFrontApplicationId(Const.USER_ID);
            applicationReferenceMetric.setBehindApplicationId(applicationId);
            applicationReferenceMetric.setTimeBucket(TimeBucketUtils.INSTANCE.getMinuteTimeBucket(spanDecorator.getStartTime()));

            String idBuilder = String.valueOf(applicationReferenceMetric.getTimeBucket()) + Const.ID_SPLIT + applicationReferenceMetric.getFrontApplicationId() +
                Const.ID_SPLIT + applicationReferenceMetric.getBehindApplicationId();

            applicationReferenceMetric.setId(idBuilder);
            applicationReferenceMetrics.add(buildNodeRefSum(applicationReferenceMetric, spanDecorator.getStartTime(), spanDecorator.getEndTime(), spanDecorator.getIsError()));
        }
    }

    @Override public void parseRef(ReferenceDecorator referenceDecorator, int applicationId, int instanceId,
        String segmentId) {
        int parentApplicationId = instanceCacheService.get(referenceDecorator.getParentApplicationInstanceId());

        ApplicationReferenceMetric referenceSum = new ApplicationReferenceMetric(Const.EMPTY_STRING);
        referenceSum.setFrontApplicationId(parentApplicationId);
        referenceSum.setBehindApplicationId(applicationId);
        references.add(referenceSum);
    }

    @Override public void build() {
        logger.debug("node reference summary listener build");
        Graph<ApplicationReferenceMetric> graph = GraphManager.INSTANCE.createIfAbsent(TraceStreamGraph.APPLICATION_REFERENCE_METRIC_GRAPH_ID, ApplicationReferenceMetric.class);
        for (ApplicationReferenceMetric applicationReferenceMetric : applicationReferenceMetrics) {
            graph.start(applicationReferenceMetric);
        }
    }

    private ApplicationReferenceMetric buildNodeRefSum(ApplicationReferenceMetric reference,
        long startTime, long endTime, boolean isError) {
        long duration = endTime - startTime;

        reference.setCalls(1L);
        reference.setDurationSum(duration);
        if (isError) {
            reference.setErrorCalls(1L);
            reference.setErrorDurationSum(duration);
        }

        ApdexThresholdUtils.Apdex apdex = ApdexThresholdUtils.compute(apdexThresholdService.getApplicationApdexThreshold(reference.getBehindApplicationId()), duration);
        if (ApdexThresholdUtils.Apdex.Satisfied.equals(apdex)) {
            reference.setSatisfiedCount(1L);
        } else if (ApdexThresholdUtils.Apdex.Tolerating.equals(apdex)) {
            reference.setToleratingCount(1L);
        } else {
            reference.setFrustratedCount(1L);
        }
        return reference;
    }
}