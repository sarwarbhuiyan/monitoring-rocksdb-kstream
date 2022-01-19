package com.sample;

import com.sample.avro.Command;
import com.sample.avro.Product;
import com.sample.avro.ProductCommand;
import com.sample.bean.BestProduct;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.state.KeyValueBytesStoreSupplier;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class SimpleKafkaStreams {

    private static final String KAFKA_ENV_PREFIX = "KAFKA_";
    private final Logger logger = LoggerFactory.getLogger(SimpleKafkaStreams.class);
    private final Properties properties;
    private final String inputTopic;

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        SimpleKafkaStreams simpleKafkaStreams = new SimpleKafkaStreams();
        simpleKafkaStreams.start2();
    }

    public SimpleKafkaStreams() throws InterruptedException, ExecutionException {

        properties = buildProperties(defaultProps, System.getenv(), KAFKA_ENV_PREFIX);
        inputTopic = System.getenv().getOrDefault("INPUT_TOPIC","sample");

        final Integer numberOfPartitions =  Integer.valueOf(System.getenv().getOrDefault("NUMBER_OF_PARTITIONS","2"));
        final Short replicationFactor =  Short.valueOf(System.getenv().getOrDefault("REPLICATION_FACTOR","3"));

        AdminClient adminClient = KafkaAdminClient.create(properties);
        createTopic(adminClient, inputTopic, numberOfPartitions, replicationFactor);
        createTopic(adminClient, "total-command-by-user", numberOfPartitions, replicationFactor);
        createTopic(adminClient, "total-command-by-product", numberOfPartitions, replicationFactor);
        createTopic(adminClient, "best-products-sales", 1, replicationFactor);
    }

    private void start2()  throws InterruptedException {

        Map<String, Object> configAvroSerde = new HashMap<>();
        for(Map.Entry<Object, Object> kv : properties.entrySet())
            configAvroSerde.put(kv.getKey().toString(), kv.getValue());

        logger.info("creating streams with props: {}", properties);
        final StreamsBuilder builder = new StreamsBuilder();

        SpecificAvroSerde<Command> commandSerde = new SpecificAvroSerde<>();
        commandSerde.configure(configAvroSerde, false);
        Serde<List<BestProduct>> bestProductSerde = Serdes.ListSerde(ArrayList.class, BestProduct.Serdes());
        SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");

        // TODO : 4. Add a layer to get this data

        KStream<String, Command> commands = builder.stream(inputTopic, Consumed.with(Serdes.String(), commandSerde));

        // 1. total price command by day and user
        commands
                .groupBy((k,v)->v.getUser(), Grouped.with(Serdes.String(), commandSerde))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofDays(1)))
                .aggregate(
                        () -> 0F,
                        (k,value, old) -> old + value.getTotal(),
                        Materialized.as("total-command-by-user-store"))
                .toStream((k,v)->k.key())
                .to("total-command-by-user", Produced.with(Serdes.String(), Serdes.Float()));

        // 2. total price command by day and products
        KTable<Windowed<String>, Float> tableCommandByDayProducts = commands
                .flatMap((k,v)-> {
                            List<KeyValue<String, Float>> products = new ArrayList<>();
                            for(ProductCommand p : v.getItems()) {
                                Optional<Product> product = v.getProducts().stream().filter(_p -> _p.getProductId().equals(p.getProductId())).findFirst();
                                if(product.isPresent())
                                    products.add(KeyValue.pair(p.getProductId(), p.getQuantity() * product.get().getPrice()));
                            }
                            return products;
                        })
                .groupByKey()
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofDays(1)))
                .aggregate(
                        () -> 0F,
                        (k, value, old) -> value + old,
                        Materialized.as("total-command-by-product-store"));

        tableCommandByDayProducts
                .toStream((k,v) -> {
                    Date d = Date.from(k.window().startTime());
                    String key = formatter.format(d) + " " + k.key();
                    return key;
                })
                .to("total-command-by-product", Produced.with(Serdes.String(), Serdes.Float()));

        // 3. publish the 3 best products sales by day
        Repartitioned<Windowed<String>, Float> repartitioned = Repartitioned
                .with(WindowedSerdes.timeWindowedSerdeFrom(String.class, Duration.ofDays(1).toMillis()), Serdes.Float())
                .withNumberOfPartitions(1);

        tableCommandByDayProducts
                .toStream()
                .repartition(repartitioned)
                .transform(new TransformerSupplier<Windowed<String>, Float, KeyValue<String, List<BestProduct>>>() {
                               @Override
                               public Transformer<Windowed<String>, Float, KeyValue<String, List<BestProduct>>> get() {
                                   return new Transformer<>() {

                                       private KeyValueStore<String, List<BestProduct>> store;

                                       @Override
                                       public void init(org.apache.kafka.streams.processor.ProcessorContext processorContext) {
                                           store = processorContext.getStateStore("best-sales-products-store");
                                           processorContext.schedule(
                                                   Duration.ofMinutes(1),
                                                   PunctuationType.WALL_CLOCK_TIME,
                                                   (ts) -> {
                                                       // todo : remove old days
                                                       // get the current list of current timestamp and forward
                                                       Date d = Date.from(Instant.ofEpochMilli(ts));
                                                       String day = formatter.format(d);
                                                       List<BestProduct> products = store.get(day);
                                                       processorContext.forward(day, products);
                                                   });
                                       }

                                       @Override
                                       public KeyValue<String, List<BestProduct>> transform(Windowed<String> key, Float value) {

                                           Date d = Date.from(key.window().startTime());
                                           String day = formatter.format(d);

                                           List<BestProduct> products = store.get(day);
                                           if(products == null)
                                               products = new ArrayList<>();
                                           // remove old value for the same products
                                           products.removeIf((b) -> b.getProductId().equals(key.key()));
                                           products.add(new BestProduct(key.key(), value));
                                           Collections.sort(products, Collections.reverseOrder(new BestProduct.BestProductComparator()));

                                           List<BestProduct> newList;

                                           if(products.size() > 3)
                                               newList = products.subList(0, 3);
                                           else
                                               newList = products;

                                           store.put(day, newList);

                                           return null;
                                       }

                                       @Override
                                       public void close() {

                                       }
                                   };
                               }

                               @Override
                               public Set<StoreBuilder<?>> stores() {

                                   KeyValueBytesStoreSupplier supplier =
                                           Stores.persistentKeyValueStore("best-sales-products-store");

                                   return Set.of(Stores.keyValueStoreBuilder(
                                           supplier,
                                           Serdes.String(),
                                           bestProductSerde));
                               }
                })
                .to("best-products-sales", Produced.with(Serdes.String(), bestProductSerde));


        Topology topology = builder.build();

        logger.info(topology.describe().toString());

        KafkaStreams streams = new KafkaStreams(topology,properties);
        streams.start();

        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }

    private Map<String, Object> defaultProps = Map.of(
            StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:19092,localhost:19093,localhost:19094",
            StreamsConfig.APPLICATION_ID_CONFIG, System.getenv().getOrDefault("APPLICATION_ID","sample-streams2"),
            StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class,
            StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.FloatSerde.class,
            StreamsConfig.REPLICATION_FACTOR_CONFIG, "3",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            KafkaAvroSerializerConfig.AUTO_REGISTER_SCHEMAS, "true",
            KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, "http://localhost:8081"
            );

    private Properties buildProperties(Map<String, Object> baseProps, Map<String, String> envProps, String prefix) {
        Map<String, String> systemProperties = envProps.entrySet()
                .stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .collect(Collectors.toMap(
                        e -> e.getKey()
                                .replace(prefix, "")
                                .toLowerCase()
                                .replace("_", ".")
                        , e -> e.getValue())
                );

        Properties props = new Properties();
        props.putAll(baseProps);
        props.putAll(systemProperties);
        return props;
    }

    private void createTopic(AdminClient adminClient, String topicName, Integer numberOfPartitions, Short replicationFactor) throws InterruptedException, ExecutionException {
        if (!adminClient.listTopics().names().get().contains(topicName)) {
            logger.info("Creating topic {}", topicName);
            final NewTopic newTopic = new NewTopic(topicName, numberOfPartitions, replicationFactor);
            try {
                CreateTopicsResult topicsCreationResult = adminClient.createTopics(Collections.singleton(newTopic));
                topicsCreationResult.all().get();
            } catch (ExecutionException e) {
                //silent ignore if topic already exists
            }
        }
    }

}
