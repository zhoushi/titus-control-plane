/*
 * Copyright 2017 Netflix, Inc.
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

package io.netflix.titus.ext.aws.loadbalancer;

public class AwsLoadBalancerRateLimitException extends RuntimeException {
    /**
     * Constructs a new AwsLoadBalancerRateLimitException from the provided Throwable to
     * identify rate limiting errors, which AWS currently does not specifically identify.
     * @param error
     */
    public AwsLoadBalancerRateLimitException(Throwable error) {
        super(error.getMessage());
    }
}