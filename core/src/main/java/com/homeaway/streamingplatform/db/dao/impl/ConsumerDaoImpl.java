/* Copyright (c) 2018 Expedia Group.
 * All rights reserved.  http://www.homeaway.com

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *      http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.homeaway.streamingplatform.db.dao.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import com.google.common.collect.Lists;

import com.homeaway.digitalplatform.streamregistry.Actor;
import com.homeaway.digitalplatform.streamregistry.AvroStream;
import com.homeaway.digitalplatform.streamregistry.AvroStreamKey;
import com.homeaway.digitalplatform.streamregistry.Consumer;
import com.homeaway.digitalplatform.streamregistry.OperationType;
import com.homeaway.digitalplatform.streamregistry.RegionStreamConfiguration;
import com.homeaway.streamingplatform.db.dao.AbstractDao;
import com.homeaway.streamingplatform.db.dao.KafkaManager;
import com.homeaway.streamingplatform.db.dao.RegionDao;
import com.homeaway.streamingplatform.db.dao.StreamClientDao;
import com.homeaway.streamingplatform.dto.AvroToJsonDTO;
import com.homeaway.streamingplatform.exceptions.ConsumerNotFoundException;
import com.homeaway.streamingplatform.exceptions.StreamNotFoundException;
import com.homeaway.streamingplatform.exceptions.UnknownRegionException;
import com.homeaway.streamingplatform.provider.InfraManager;
import com.homeaway.streamingplatform.streams.ManagedKStreams;
import com.homeaway.streamingplatform.streams.ManagedKafkaProducer;

@Slf4j
public class ConsumerDaoImpl extends AbstractDao implements StreamClientDao<com.homeaway.streamingplatform.model.Consumer> {

    private static final List<String> TOPIC_POST_FIXES = Collections.unmodifiableList(Arrays.asList("", ".global"));

    private static final String ACTOR_TYPE = "consumer";

    public ConsumerDaoImpl(ManagedKafkaProducer managedKafkaProducer,
        ManagedKStreams kStreams,
        String env,
        RegionDao regionDao,
        InfraManager infraManager,
        KafkaManager kafkaManager) {
        super(managedKafkaProducer, kStreams, env, regionDao, infraManager, kafkaManager);
    }

    @Override
    public Optional<com.homeaway.streamingplatform.model.Consumer> update(String streamName, String actorName, String region) {
        return updateConsumer(streamName, actorName, region);
    }

    @Override
    public Optional<com.homeaway.streamingplatform.model.Consumer> get(String streamName, String actorName) {
        return getConsumer(streamName, actorName);
    }

    @Override
    public void delete(String streamName, String actorName) {
        deleteConsumer(streamName, actorName);
    }

    @Override
    public List<com.homeaway.streamingplatform.model.Consumer> getAll(String streamName) {
        return getAllConsumers(streamName);
    }

    private Optional<com.homeaway.streamingplatform.model.Consumer> updateConsumer(String streamName, String consumerName, String region) {
        Optional<AvroStream> avroStream = getAvroStreamKeyValue(streamName).getValue();

        if (avroStream.isPresent()) {
            List<Consumer> consumers = avroStream.get().getConsumers();
            if (consumers != null) {
                for (com.homeaway.digitalplatform.streamregistry.Consumer consumer : consumers) {
                    if (consumer.getActor().getName().equalsIgnoreCase(consumerName)) {
                        for (RegionStreamConfiguration streamConfiguration : consumer.getActor().getRegionStreamConfigurations()) {
                            if (streamConfiguration.getRegion().equals(region)) {
                                // Existing consumer for this region
                                String streamHint = avroStream.get().getTags().getHint();
                                String hint = (streamHint == null || streamHint.trim().matches("(?i:string)?")) ? AbstractDao.PRIMARY_HINT
                                    : streamHint.trim().toLowerCase();
                                Actor consumerActor = populateActorStreamConfig(streamName, region, consumer.getActor(),
                                    OPERATION.UPDATE.name(), TOPIC_POST_FIXES, hint,
                                    ACTOR_TYPE, avroStream.get().getTopicConfig());
                                consumer.setActor(consumerActor);
                                updateAvroStream(avroStream.get());
                                log.info(
                                    "Consumer updated in source-processor-topic. streamName={} ; consumerName={} ; consumer={} ; region={}",
                                    streamName, consumerName, consumer, region);
                                return Optional.of(AvroToJsonDTO.getJsonConsumer(consumer));
                            }
                        }
                    }
                }
            }
            log.info("Registering new Consumer. Stream={} Consumer={} ; region={}", streamName, consumerName, region);
            return createConsumer(avroStream.get(), consumerName, region);
        }
        return Optional.empty();
    }

    private Optional<com.homeaway.streamingplatform.model.Consumer> createConsumer(AvroStream avroStream, String consumerName,
        String region) {

        log.info("==>>> getting into creating consumer. Initial Stream: {}", avroStream.toString());

        if (!regionDao.getSupportedRegions(avroStream.getTags().getHint()).contains(region))
            throw new UnknownRegionException(region);

        List<com.homeaway.digitalplatform.streamregistry.Consumer> listConsumers = avroStream.getConsumers();
        if (listConsumers == null) {
            listConsumers = new ArrayList<>();
        }

        com.homeaway.digitalplatform.streamregistry.Consumer consumer = com.homeaway.digitalplatform.streamregistry.Consumer
            .newBuilder()
            .setActor(Actor.newBuilder()
                .setName(consumerName)
                .build())
            .build();

        String streamHint = avroStream.getTags().getHint();
        String hint =
            (streamHint == null || streamHint.trim().matches("(?i:string)?")) ? AbstractDao.PRIMARY_HINT : streamHint.trim().toLowerCase();

        Actor actor =
            populateActorStreamConfig(avroStream.getName(), region, consumer.getActor(), OPERATION.CREATE.name(), TOPIC_POST_FIXES, hint,
                ACTOR_TYPE, avroStream.getTopicConfig());

        consumer = Consumer.newBuilder()
            .setActor(actor)
            .build();

        listConsumers.add(consumer);
        avroStream.setConsumers(listConsumers);

        updateAvroStream(avroStream);

        return Optional.of(AvroToJsonDTO.getJsonConsumer(consumer));
    }

    private Optional<com.homeaway.streamingplatform.model.Consumer> getConsumer(String streamName, String consumerName) {
        // pull data from state store of this instance.
        log.info("Pulling stream information from local instance's state-store for streamName={} ; consumerName={}", streamName,
            consumerName);
        Optional<AvroStream> streamValue = kStreams.getAvroStreamForKey(
            AvroStreamKey.newBuilder().setStreamName(streamName).build());
        if (streamValue.isPresent()) {
            streamValue.get().setOperationType(OperationType.GET);
            for (com.homeaway.digitalplatform.streamregistry.Consumer consumer : streamValue.get().getConsumers()) {
                if (consumer.getActor().getName().equals(consumerName))
                    return Optional.of(AvroToJsonDTO.getJsonConsumer(consumer));
            }
        }
        return Optional.empty();
    }

    private List<com.homeaway.streamingplatform.model.Consumer> getAllConsumers(String streamName) {
        List<com.homeaway.streamingplatform.model.Consumer> consumers = new ArrayList<>();
        // pull data from state store of this instance.
        log.info("Pulling stream information from local instance's state-store for stream={} ; consumers=all", streamName);
        Optional<AvroStream> streamValue = kStreams.getAvroStreamForKey(AvroStreamKey.newBuilder().setStreamName(streamName).build());
        if (streamValue.isPresent() && streamValue.get().getConsumers() != null) {
            streamValue.get().setOperationType(OperationType.GET);
            for (com.homeaway.digitalplatform.streamregistry.Consumer consumer : streamValue.get().getConsumers()) {
                consumers.add(AvroToJsonDTO.getJsonConsumer(consumer));
            }
        }
        return consumers;
    }

    private void deleteConsumer(String streamName, String consumerName) {
        Optional<AvroStream> avroStream = getAvroStreamKeyValue(streamName).getValue();

        if (avroStream.isPresent()) {
            List<com.homeaway.digitalplatform.streamregistry.Consumer> listConsumer = avroStream.get().getConsumers();
            int consumerInitialSize = listConsumer.size();
            for (Iterator<com.homeaway.digitalplatform.streamregistry.Consumer> iter = listConsumer.listIterator(); iter.hasNext();) {
                com.homeaway.digitalplatform.streamregistry.Consumer consumerEntity = iter.next();
                if (consumerEntity.getActor().getName().equalsIgnoreCase(consumerName)) {
                    // Remove the consumer
                    iter.remove();
                    avroStream.get().setConsumers(Lists.newArrayList(iter));
                }
            }
            if (avroStream.get().getConsumers().size() < consumerInitialSize)
                updateAvroStream(avroStream.get());
            else
                throw new ConsumerNotFoundException(consumerName);
        } else {
            throw new StreamNotFoundException(streamName);
        }
    }

}
