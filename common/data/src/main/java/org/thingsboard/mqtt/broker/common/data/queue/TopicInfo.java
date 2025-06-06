/**
 * Copyright © 2016-2025 The Thingsboard Authors
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
package org.thingsboard.mqtt.broker.common.data.queue;

import lombok.Builder;
import lombok.ToString;

import java.util.Objects;

@ToString
public class TopicInfo {

    private final String topic;

    @Builder
    public TopicInfo(String topic) {
        this.topic = topic;
    }

    public String getTopic() {
        return topic;
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TopicInfo that = (TopicInfo) o;
        return topic.equals(that.topic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(topic);
    }
}
