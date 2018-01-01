package com.github.framiere;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Serialized;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.processor.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * ./confluent destroy
 * ./confluent start
 * ./kafka-topics --zookeeper localhost:2181 --create --topic telegraf --partitions 3 --replication-factor 1
 * run application
 * seq 10000 | ./kafka-console-producer --broker-list localhost:9092 --topic telegraf
 * or type youself words : ./kafka-console-producer --broker-list localhost:9092 --topic telegraf
 * ./kafka-topics --zookeeper localhost:2181 --list
 * ./kafka-console-consumer --bootstrap-server localhost:9092 --topic telegraf-input-by-thread --from-beginning
 * ./kafka-console-consumer --bootstrap-server localhost:9092 --topic telegraf-10s-window-count --property print.key=true --value-deserializer org.apache.kafka.common.serialization.LongDeserializer --from-beginning
 */
public class SimpleStream {

    public void stream(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 3);
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "simple-stream");
        properties.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 5 * 1000);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 0);
        properties.put(StreamsConfig.TIMESTAMP_EXTRACTOR_CLASS_CONFIG, WallclockTimestampExtractor.class);

        StreamsBuilder builder = new StreamsBuilder();
        KStream<String, String> input = builder.stream("telegraf", Consumed.with(Serdes.String(), Serdes.String()));

        // map each value and add the thread that processed it
        input
                .mapValues(v -> Thread.currentThread().getName() + " " + v)
                .to("telegraf-input-by-thread", Produced.with(Serdes.String(), Serdes.String()));

        // grab the first word as a key, and make a global count out of it, and push the changes to telegraf-global-count
        input
                .map((key, value) -> new KeyValue<>(value.split("[, ]")[0], 0L))
                .groupByKey(Serialized.with(Serdes.String(), Serdes.Long()))
                .count()
                .toStream()
                .to("telegraf-global-count", Produced.with(Serdes.String(), Serdes.Long()));

        // count the first word on a sliding window, and push the changes to telegraf-10s-window-count
        // check with ./kafka-console-consumer --bootstrap-server localhost:9092 --topic telegraf-10s-window-count --property print.key=true --value-deserializer org.apache.kafka.common.serialization.LongDeserializer
        input
                .map((key, value) -> new KeyValue<>(value.split("[, ]")[0], 1L))
                .groupByKey(Serialized.with(Serdes.String(), Serdes.Long()))
                .windowedBy(TimeWindows.of(TimeUnit.SECONDS.toMillis(10)))
                .count()
                .toStream((windowedRegion, count) -> windowedRegion.toString())
                .to("telegraf-10s-window-count", Produced.with(Serdes.String(), Serdes.Long()));

        // You can also use the low level APIs if you need to handle complex use cases,
        input
                .process(() -> new AbstractProcessor<String, String>() {
                    private final List<String> batch = new ArrayList<>();

                    @Override
                    public void init(ProcessorContext context) {
                        super.init(context);
                        context().schedule(TimeUnit.SECONDS.toMillis(10), PunctuationType.WALL_CLOCK_TIME, this::flush);
                    }

                    private void flush(long timestamp) {
                        synchronized (batch) {
                            if (!batch.isEmpty()) {
                                // sending to an external system ?
                                System.out.println(timestamp + " " + Thread.currentThread().getName() + " Flushing batch of " + batch.size());
                                batch.clear();
                            }
                        }
                    }

                    @Override
                    public void process(String key, String value) {
                        synchronized (batch) {
                            batch.add(value);
                            context().forward(key, value);
                        }
                    }
                });

        Topology build = builder.build();

        System.out.println(build.describe());

        KafkaStreams kafkaStreams = new KafkaStreams(build, properties);
        kafkaStreams.cleanUp();
        kafkaStreams.start();
    }

    public static void main(String[] args) {
        String bootstrapServers = args.length == 1 ? args[0] : "localhost:9092";
        System.out.println(bootstrapServers);
        new SimpleStream().stream(bootstrapServers);
    }
}