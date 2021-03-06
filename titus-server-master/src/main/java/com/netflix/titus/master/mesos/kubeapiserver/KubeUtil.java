/*
 * Copyright 2019 Netflix, Inc.
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
 */

package com.netflix.titus.master.mesos.kubeapiserver;

import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.netflix.titus.api.jobmanager.JobAttributes;
import com.netflix.titus.api.jobmanager.JobConstraints;
import com.netflix.titus.api.jobmanager.TaskAttributes;
import com.netflix.titus.api.jobmanager.model.job.Job;
import com.netflix.titus.api.jobmanager.model.job.Task;
import com.netflix.titus.common.util.CollectionsExt;
import com.netflix.titus.common.util.Evaluators;
import com.netflix.titus.common.util.ExceptionExt;
import com.netflix.titus.common.util.NetworkExt;
import com.netflix.titus.common.util.StringExt;
import com.netflix.titus.grpc.protogen.JobDescriptor;
import com.netflix.titus.master.mesos.TitusExecutorDetails;
import com.netflix.titus.master.mesos.kubeapiserver.direct.DirectKubeConfiguration;
import com.netflix.titus.runtime.endpoint.v3.grpc.GrpcJobManagementModelConverters;
import com.netflix.titus.runtime.kubernetes.KubeConstants;
import io.kubernetes.client.openapi.ApiCallback;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ContainerState;
import io.kubernetes.client.openapi.models.V1ContainerStateRunning;
import io.kubernetes.client.openapi.models.V1ContainerStateTerminated;
import io.kubernetes.client.openapi.models.V1ContainerStateWaiting;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeAddress;
import io.kubernetes.client.openapi.models.V1NodeCondition;
import io.kubernetes.client.openapi.models.V1NodeStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Taint;
import io.kubernetes.client.openapi.models.V1Toleration;
import okhttp3.Call;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class KubeUtil {

    private static final Logger logger = LoggerFactory.getLogger(KubeUtil.class);

    /**
     * Like {@link Function}, but with {@link ApiException} throws clause.
     */
    public interface KubeFunction<I, O> {
        O apply(I argument) throws ApiException;
    }

    private static final String SUCCEEDED = "Succeeded";

    private static final String FAILED = "Failed";

    public static final String TYPE_INTERNAL_IP = "InternalIP";

    private static final JsonFormat.Printer grpcJsonPrinter = JsonFormat.printer().includingDefaultValueFields();

    private static final Gson GSON = new Gson();

    /**
     * As it is not possible to capture pod size at the transport level, we try to estimate it directly using the same
     * JSON serializer as the Kube client (gson).
     */
    public static int estimatePodSize(V1Pod v1Pod) {
        try {
            String json = GSON.toJson(v1Pod);
            return json == null ? 0 : json.length();
        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean isPodPhaseTerminal(String phase) {
        return SUCCEEDED.equals(phase) || FAILED.equals(phase);
    }

    public static Optional<Long> findFinishedTimestamp(V1Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return Optional.empty();
        }
        return pod.getStatus().getContainerStatuses().stream()
                .filter(status -> status.getState() != null && status.getState().getTerminated() != null && status.getState().getTerminated().getFinishedAt() != null)
                .findFirst()
                .map(terminatedState -> terminatedState.getState().getTerminated().getFinishedAt().getMillis());
    }

    public static Optional<TitusExecutorDetails> getTitusExecutorDetails(V1Pod pod) {
        Map<String, String> annotations = pod.getMetadata().getAnnotations();
        if (!Strings.isNullOrEmpty(annotations.get("IpAddress"))) {
            TitusExecutorDetails titusExecutorDetails = new TitusExecutorDetails(
                    Collections.emptyMap(),
                    new TitusExecutorDetails.NetworkConfiguration(
                            Boolean.parseBoolean(annotations.getOrDefault("IsRoutableIp", "true")),
                            annotations.getOrDefault("IpAddress", "UnknownIpAddress"),
                            annotations.get("EniIPv6Address"),
                            annotations.getOrDefault("EniIpAddress", "UnknownEniIpAddress"),
                            annotations.getOrDefault("EniId", "UnknownEniId"),
                            annotations.getOrDefault("ResourceId", "UnknownResourceId")
                    )
            );
            return Optional.of(titusExecutorDetails);
        }
        return Optional.empty();
    }

    public static Optional<V1ContainerState> findContainerState(V1Pod pod) {
        if (pod.getStatus() == null) {
            return Optional.empty();
        }
        List<V1ContainerStatus> containerStatuses = pod.getStatus().getContainerStatuses();
        if (containerStatuses != null) {
            for (V1ContainerStatus status : containerStatuses) {
                V1ContainerState state = status.getState();
                if (state != null) {
                    return Optional.of(state);
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<V1ContainerStateTerminated> findTerminatedContainerStatus(V1Pod pod) {
        return findContainerState(pod).flatMap(state -> Optional.ofNullable(state.getTerminated()));
    }

    public static String formatV1ContainerState(V1ContainerState containerState) {
        if (containerState.getWaiting() != null) {
            V1ContainerStateWaiting waiting = containerState.getWaiting();
            return String.format("{state=waiting, reason=%s, message=%s}", waiting.getReason(), waiting.getMessage());
        }

        if (containerState.getRunning() != null) {
            V1ContainerStateRunning running = containerState.getRunning();
            return String.format("{state=running, startedAt=%s}", running.getStartedAt());
        }

        if (containerState.getTerminated() != null) {
            V1ContainerStateTerminated terminated = containerState.getTerminated();
            return String.format("{state=terminated, startedAt=%s, finishedAt=%s, reason=%s, message=%s}",
                    terminated.getStartedAt(), terminated.getFinishedAt(),
                    terminated.getReason(), terminated.getMessage());
        }

        return "{state=<not set>}";
    }

    /**
     * If a job has an availability zone hard constraint with a farzone id, return this farzone id.
     */
    public static Optional<String> findFarzoneId(DirectKubeConfiguration configuration, Job job) {
        List<String> farzones = configuration.getFarzones();
        if (CollectionsExt.isNullOrEmpty(farzones)) {
            return Optional.empty();
        }

        Map<String, String> hardConstraints = job.getJobDescriptor().getContainer().getHardConstraints();
        String zone = hardConstraints.get(JobConstraints.AVAILABILITY_ZONE);
        if (StringExt.isEmpty(zone)) {
            return Optional.empty();
        }

        for (String farzone : farzones) {
            if (zone.equalsIgnoreCase(farzone)) {
                return Optional.of(farzone);
            }
        }
        return Optional.empty();
    }

    public static boolean isOwnedByKubeScheduler(V1Pod v1Pod) {
        List<V1Toleration> tolerations = v1Pod.getSpec().getTolerations();
        if (CollectionsExt.isNullOrEmpty(tolerations)) {
            return false;
        }
        for (V1Toleration toleration : tolerations) {
            if (KubeConstants.TAINT_SCHEDULER.equals(toleration.getKey()) && KubeConstants.TAINT_SCHEDULER_VALUE_KUBE.equals(toleration.getValue())) {
                return true;
            }
        }
        return false;
    }

    public static Optional<String> getNodeIpV4Address(V1Node node) {
        return Optional.ofNullable(node.getStatus().getAddresses())
                .map(Collection::stream)
                .orElseGet(Stream::empty)
                .filter(a -> a.getType().equalsIgnoreCase(TYPE_INTERNAL_IP) && NetworkExt.isIpV4(a.getAddress()))
                .findFirst()
                .map(V1NodeAddress::getAddress);
    }

    public static Map<String, String> createPodAnnotations(
            Job<?> job,
            Task task,
            byte[] containerInfoData,
            Map<String, String> passthroughAttributes,
            boolean includeJobDescriptor
    ) {
        String encodedContainerInfo = Base64.getEncoder().encodeToString(containerInfoData);

        Map<String, String> annotations = new HashMap<>(passthroughAttributes);
        annotations.putAll(PerformanceToolUtil.toAnnotations(job));
        annotations.put("containerInfo", encodedContainerInfo);
        Evaluators.acceptNotNull(
                job.getJobDescriptor().getAttributes().get(JobAttributes.JOB_ATTRIBUTES_RUNTIME_PREDICTION_SEC),
                runtimeInSec -> annotations.put(KubeConstants.JOB_RUNTIME_PREDICTION, runtimeInSec + "s")
        );
        Evaluators.acceptNotNull(
                task.getTaskContext().get(TaskAttributes.TASK_ATTRIBUTES_OPPORTUNISTIC_CPU_COUNT),
                count -> annotations.put(KubeConstants.OPPORTUNISTIC_CPU_COUNT, count)
        );
        Evaluators.acceptNotNull(
                task.getTaskContext().get(TaskAttributes.TASK_ATTRIBUTES_OPPORTUNISTIC_CPU_ALLOCATION),
                id -> annotations.put(KubeConstants.OPPORTUNISTIC_ID, id)
        );

        if (includeJobDescriptor) {
            JobDescriptor grpcJobDescriptor = GrpcJobManagementModelConverters.toGrpcJobDescriptor(job.getJobDescriptor());
            try {
                String jobDescriptorJson = grpcJsonPrinter.print(grpcJobDescriptor);
                annotations.put("jobDescriptor", StringExt.gzipAndBase64Encode(jobDescriptorJson));
            } catch (InvalidProtocolBufferException e) {
                logger.error("Unable to convert protobuf message into json: ", e);
            }
        }

        annotations.putAll(createPodAnnotationsFromJobParameters(job));

        return annotations;
    }

    public static Map<String, String> createPodAnnotationsFromJobParameters(Job<?> job) {
        Map<String, String> annotations = new HashMap<>();
        Map<String, String> containerAttributes = job.getJobDescriptor().getContainer().getAttributes();
        Evaluators.acceptNotNull(
                containerAttributes.get(JobAttributes.JOB_CONTAINER_ATTRIBUTE_ACCOUNT_ID),
                accountId -> annotations.put(KubeConstants.POD_LABEL_ACCOUNT_ID, accountId)
        );
        Evaluators.acceptNotNull(
                containerAttributes.get(JobAttributes.JOB_CONTAINER_ATTRIBUTE_SUBNETS),
                accountId -> annotations.put(KubeConstants.POD_LABEL_SUBNETS, accountId)
        );
        return annotations;
    }

    /**
     * A node is owned by Fenzo if:
     * <ul>
     *     <li>There is no taint with {@link KubeConstants#TAINT_SCHEDULER} key and it is not a farzone node</li>
     *     <li>There is one taint with {@link KubeConstants#TAINT_SCHEDULER} key and 'fenzo' value</li>
     * </ul>
     */
    public static boolean isNodeOwnedByFenzo(List<String> farzones, V1Node node) {
        if (isFarzoneNode(farzones, node)) {
            logger.debug("Not owned by fenzo (farzone node): {}", node.getMetadata().getName());
            return false;
        }

        if (!hasFenzoSchedulerTaint(node)) {
            logger.debug("Not owned by fenzo (non Fenzo scheduler taint): {}", node.getMetadata().getName());
            return false;
        }

        logger.debug("Owned by fenzo");
        return true;
    }

    /**
     * Returns true if there is {@link KubeConstants#TAINT_SCHEDULER} taint with {@link KubeConstants#TAINT_SCHEDULER_VALUE_FENZO} value
     * or this taint is missing (no explicit scheduler taint == Fenzo).
     */
    public static boolean hasFenzoSchedulerTaint(V1Node node) {
        List<V1Taint> taints = node.getSpec().getTaints();

        // Ignore nodes with no taints or an 'uninitialized' taint
        if (CollectionsExt.isNullOrEmpty(taints) || hasUninitializedTaint(node)) {
            return false;
        }

        Set<String> schedulerTaintValues = taints.stream()
                .filter(t -> KubeConstants.TAINT_SCHEDULER.equals(t.getKey()))
                .map(t -> StringExt.safeTrim(t.getValue()))
                .collect(Collectors.toSet());

        if (schedulerTaintValues.isEmpty()) {
            return true;
        }

        return schedulerTaintValues.size() == 1 && KubeConstants.TAINT_SCHEDULER_VALUE_FENZO.equalsIgnoreCase(CollectionsExt.first(schedulerTaintValues));
    }

    public static boolean isFarzoneNode(List<String> farzones, V1Node node) {
        Map<String, String> labels = node.getMetadata().getLabels();
        if (CollectionsExt.isNullOrEmpty(labels)) {
            return false;
        }
        String nodeZone = labels.get(KubeConstants.NODE_LABEL_ZONE);
        if (StringExt.isEmpty(nodeZone)) {
            logger.debug("Node without zone label: {}", node.getMetadata().getName());
            return false;
        }
        for (String farzone : farzones) {
            if (farzone.equalsIgnoreCase(nodeZone)) {
                logger.debug("Farzone node: nodeId={}, zoneId={}", node.getMetadata().getName(), nodeZone);
                return true;
            }
        }
        logger.debug("Non-farzone node: nodeId={}, zoneId={}", node.getMetadata().getName(), nodeZone);
        return false;
    }

    public static String toErrorDetails(Throwable e) {
        if (!(e instanceof ApiException)) {
            return ExceptionExt.toMessageChain(e);
        }

        ApiException apiException = (ApiException) e;
        return String.format("{message=%s, httpCode=%d, responseBody=%s",
                Evaluators.getOrDefault(apiException.getMessage(), "<not set>"),
                apiException.getCode(),
                Evaluators.getOrDefault(apiException.getResponseBody(), "<not set>")
        );
    }

    public static <T> Mono<T> toReact(KubeFunction<ApiCallback<T>, Call> handler) {
        return Mono.create(sink -> {
            Call call;
            try {
                call = handler.apply(new ApiCallback<T>() {
                    @Override
                    public void onFailure(ApiException e, int statusCode, Map<String, List<String>> responseHeaders) {
                        sink.error(e);
                    }

                    @Override
                    public void onSuccess(T result, int statusCode, Map<String, List<String>> responseHeaders) {
                        if (result == null) {
                            sink.success();
                        } else {
                            sink.success(result);
                        }
                    }

                    @Override
                    public void onUploadProgress(long bytesWritten, long contentLength, boolean done) {
                    }

                    @Override
                    public void onDownloadProgress(long bytesRead, long contentLength, boolean done) {
                    }
                });
            } catch (ApiException e) {
                sink.error(e);
                return;
            }

            sink.onCancel(call::cancel);
        });
    }

    /**
     * Get pod name
     */
    public static String getMetadataName(V1ObjectMeta metadata) {
        if (metadata == null) {
            return "";
        }

        return metadata.getName();
    }

    public static Optional<V1NodeCondition> findNodeCondition(V1Node node, String type) {
        V1NodeStatus status = node.getStatus();
        if (status == null) {
            return Optional.empty();
        }
        List<V1NodeCondition> conditions = status.getConditions();
        if (conditions != null) {
            for (V1NodeCondition condition : conditions) {
                if (condition.getType().equals(type)) {
                    return Optional.of(condition);
                }
            }
        }
        return Optional.empty();
    }

    public static boolean hasUninitializedTaint(V1Node node) {
        if (node.getSpec() != null && node.getSpec().getTaints() != null) {
            return node.getSpec().getTaints().stream()
                    .anyMatch(t -> KubeConstants.TAINT_NODE_UNINITIALIZED.equals(t.getKey()));
        }
        return false;
    }
}
