package blog.braindose.kafka;

import java.util.Arrays;
import java.util.Random;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.RecordMetadata;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.util.Properties;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.examples.clients.basicavro.Payment;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.StringSerializer;

public class KafkaPerformance {

    private String kafkaMessage;
    private byte[] payload;
    private int recordSize;
    private int numRecords;
    private String bootstrapServer;
    private String compressionType;
    private int batchSize;
    private long lingerMS;
    private long bufferMemory = 33554432L;
    private String acks;
    private int sendBufferBytes;
    private int receiveBufferBytes;
    private long maxBlockMS;
    private int deliveryTimeoutMS;
    private Properties props;
    private String kafkaTopic;
    private KafkaProducer<byte[], byte[]> producer;
    
    private int throttleSizeRate; // Throttle by message size per second
    private int throttleMessageRate; // Throttle by number message per second
    private KafkaStatistics stats;
    private Throttle throttle;

    public KafkaPerformance(String kafkaTopic, String kafkaMessage, int recordSize, int numRecords,
            String bootstrapServer, String compressionType, int batchSize, long lingerMS, String bufferMemory,
            String acks, int sendBufferBytes, int receiveBufferBytes, long maxBlockMS, int deliveryTimeoutMS,
            int throttleSizeRate, int throttleMessageRate) {
        this.kafkaTopic = kafkaTopic;
        this.kafkaMessage = kafkaMessage;
        this.recordSize = recordSize;
        this.numRecords = numRecords;
        this.bootstrapServer = bootstrapServer;
        this.compressionType = compressionType;
        this.batchSize = batchSize;
        this.lingerMS = lingerMS;
        try {
            this.bufferMemory = Long.parseLong(bufferMemory);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        this.acks = acks;
        this.sendBufferBytes = sendBufferBytes;
        this.receiveBufferBytes = receiveBufferBytes;
        this.maxBlockMS = maxBlockMS;
        this.deliveryTimeoutMS = deliveryTimeoutMS;
        this.throttleMessageRate = throttleMessageRate;
        this.throttleSizeRate = throttleSizeRate;

        this.props = new Properties();

        this.props.put("bootstrap.servers", this.bootstrapServer);
        this.props.put("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        this.props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        this.props.put("compression.type", this.compressionType);
        this.props.put("batch.size", this.batchSize);
        this.props.put("linger.ms", this.lingerMS);
        this.props.put("buffer.memory", this.bufferMemory);
        this.props.put("acks", this.acks);
        this.props.put("send.buffer.bytes", this.sendBufferBytes);
        this.props.put("receive.buffer.bytes", this.receiveBufferBytes);
        this.props.put("max.block.ms", this.maxBlockMS);
        this.props.put("delivery.timeout.ms", this.deliveryTimeoutMS);

        // SSL enabled
        this.props.put("ssl.truststore.location", System.getenv("KAFKA_TRUSTSTORE_FILE"));
        this.props.put("ssl.truststore.password", System.getenv("KAFKA_TRUSTSTORE_PASSWORD"));
        this.props.put("ssl.truststore.type", System.getenv("KAFKA_TRUSTSTORE_TYPE"));
        this.props.put("ssl.keystore.location", System.getenv("KAFKA_KEYSTORE_FILE"));
        this.props.put("ssl.keystore.password", System.getenv("KAFKA_KEYSTORE_PASSWORD"));
        this.props.put("ssl.keystore.type", System.getenv("KAFKA_KEYSTORE_TYPE"));
        this.props.put("security.protocol", System.getenv("KAFKA_SECURITY_PROTOCOL"));
        this.props.put("ssl.key.password", System.getenv("KAFKA_KEYSTORE_PASSWORD"));

        // AVRO SUPPORT
        if (isMessageTypeAvro()) {
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        }

        if (useSchemaRegistry()) {
            String schemaRegistryUrl = System.getenv("KAFKA_SCHEMA_REGISTRY_URL_CONFIG");
            props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        }

        initMessage();

    }

    private boolean isMessageTypeAvro() {
         String messageType = System.getenv("KAFKA_MESSAGE_TYPE");
        if (messageType != null && messageType.equalsIgnoreCase("AVRO")) {
            System.out.println("Message type is AVRO:  " +  messageType);
            return true;
        }
        System.out.println("Message type is Plaintext: " + messageType);
        return false;
    }

    private boolean useSchemaRegistry() {
        String schemaRegistryUrl = System.getenv("KAFKA_SCHEMA_REGISTRY_URL_CONFIG");
        System.out.println("Schema Registry URL:  " +  schemaRegistryUrl);
        return (schemaRegistryUrl != null && schemaRegistryUrl.length() > 0) ? true : false;
    }

    public KafkaPerformance(String kafkaTopic, String kafkaMessage, int recordSize, int numRecords,
            String bootstrapServer, String compressionType, int batchSize, long lingerMS, String bufferMemory,
            String acks, int sendBufferBytes, int receiveBufferBytes, long maxBlockMS, int deliveryTimeoutMS) {

        this(kafkaTopic, kafkaMessage, recordSize, numRecords, bootstrapServer, compressionType, batchSize, lingerMS,
                bufferMemory, acks, sendBufferBytes, receiveBufferBytes, maxBlockMS, deliveryTimeoutMS, 0, 0);
    }

    private void sendAvroMessage() {
        System.out.println(" Sending AVRO message");
         try (KafkaProducer<String, Payment> producer = new KafkaProducer<String, Payment>(props)) {
            this.throttle = new Throttle(this.throttleSizeRate, this.throttleMessageRate);
            this.stats = new KafkaStatistics(System.currentTimeMillis(), this.numRecords);
            for (int i = 0; i < numRecords; i++) {
                this.throttle.throttle(this.stats.getAverageBytesRate(), this.stats.getAverageRecordRate());
                final String orderId = "id" + Integer.toString(i);
                final String message =  new String(this.payload, "UTF-8");
                final Payment payment = new Payment(orderId, message);
                System.out.print("Message ="); System.out.println(message); 
                final ProducerRecord<String, Payment> record = new ProducerRecord<String, Payment>(this.kafkaTopic, payment.getId().toString(), payment);
                long start = System.currentTimeMillis();
                producer.send(record, new KafkaRequestCallback(i, start, this.recordSize, this.stats));
            }
            producer.flush();
            this.stats.printTotalResult();
            producer.close();
        } catch (final Exception e) {
            e.printStackTrace();
        }  
    }

    private void initMessage() {
        if (this.kafkaMessage != null && this.kafkaMessage.trim() != "") {
            payload = kafkaMessage.getBytes();
        } else {
            resizePayload();
        }
    }

    private void resizePayload() {
        if (this.recordSize > 0) {
            this.payload = new byte[this.recordSize];
            Random random = new Random(0);
            for (int i = 0; i < payload.length; ++i)
                this.payload[i] = (byte) (random.nextInt(26) + 65);
        }
    }

    public void sendTextMessage() {
         // Text message
        System.out.println(" Sending text messages ");
        KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(this.props);
        ProducerRecord<byte[], byte[]> record;

        this.throttle = new Throttle(this.throttleSizeRate, this.throttleMessageRate);
        this.stats = new KafkaStatistics(System.currentTimeMillis(), this.numRecords);

        for (int i = 0; i < numRecords; i++) {
            this.throttle.throttle(this.stats.getAverageBytesRate(), this.stats.getAverageRecordRate());
            //this.recordSize = this.throttle.getKeepUpSize(this.stats.getAverageBytesRate(), this.stats.getAverageRecordRate(), this.recordSize);
            //resizePayload();
            record = new ProducerRecord<byte[], byte[]>(this.kafkaTopic, this.payload);
            long start = System.currentTimeMillis();
            producer.send(record, new KafkaRequestCallback(i, start, this.recordSize, this.stats));
        }

        producer.flush();
        this.stats.printTotalResult();
        producer.close();
    }



    public void send() {
        if (this.isMessageTypeAvro()) {
            sendAvroMessage();
        } else {
            sendTextMessage();
        }
        String strSleepTime= System.getenv("SLEEP_TIME");
        System.out.println("Sleep Time =" + strSleepTime);
        if (strSleepTime != null) {
            try {
                
                int sleepTime= Integer.parseInt(strSleepTime);
                System.out.println("Start Sleeping");
                Thread.sleep(sleepTime*1000);
                System.out.println("Awake");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    public static class KafkaStatistics {

        private long totalLatency, totalWindowLatency;
        private int[] latencies;
        private int sampling, latencyIndex;
        private int totalRecords, totalWindowRecords;
        private long totalBytes, totalWindowBytes;
        private long startTime, startWindow;
        // private int minLatency = 60 * 60 * 1000, minWindowLatency = 60 * 60 * 1000;
        // // defaulted to 1 hour
        private int maxLatency, maxWindowLatency;
        // private double averageSizeRate, averageWindowSizeRate; // average bytes per
        // ms
        // private double averageMessageRate, averageWindowMessageRate; // average
        // number per ms

        public KafkaStatistics(long startTime, int numRecords) {
            this.startTime = startTime;
            this.startWindow = startTime;
            this.sampling = (int) (numRecords / Math.min(numRecords, 500000));
            this.latencies = new int[(int) (numRecords / this.sampling) + 1];
        }

        public void record(int index, int latency, long byteCount) {
            this.totalLatency += latency;
            this.totalWindowLatency += latency;
            this.totalRecords++;
            this.totalWindowRecords++;
            this.totalBytes += byteCount;
            this.totalWindowBytes += byteCount;
            this.maxLatency = Math.max(this.maxLatency, latency);
            this.maxWindowLatency = Math.max(this.maxWindowLatency, latency);
            // this.averageSizeRate = this.totalBytes / (double) this.totalLatency;
            // this.averageMessageRate = this.totalRecords / (double) this.totalLatency;
            // this.averageWindowSizeRate = this.totalWindowBytes / (double)
            // this.totalWindowLatency;
            // this.averageWindowMessageRate = this.totalWindowRecords / (double)
            // this.totalWindowLatency;
            // this.minLatency = Math.min(this.minLatency, latency);
            // this.minWindowLatency = Math.min(this.minLatency, latency);

            if (index % this.sampling == 0) {
                this.latencies[latencyIndex] = latency;
                this.latencyIndex++;
            }

            int timePast = (int) (System.currentTimeMillis() - this.startWindow);
            if (timePast > 5000) {
                printWindowResult();
                newWindow();
            }
        }

        private void newWindow() {
            this.startWindow = System.currentTimeMillis();
            this.totalWindowLatency = 0;
            this.totalWindowRecords = 0;
            this.totalWindowBytes = 0;
            this.maxWindowLatency = 0;
            // this.averageWindowMessageRate = 0;
            // this.averageWindowSizeRate = 0;
            // this.minWindowLatency = 60 * 60 * 1000;
        }

        public void printWindowResult() {
            int duration = (int) (System.currentTimeMillis() - this.startWindow);
            System.out.printf(
                    "Average Latency(ms): %.2f, Max Latency(ms): %d, Average MB/sec: %.2f, Record Rate: %.0f, Total Record: %.0f %n",
                    this.totalWindowLatency / (double) this.totalWindowRecords, this.maxWindowLatency,
                    1000.0 * this.totalWindowBytes / (1024.0 * 1024.0) / duration,
                    1000.0 * this.totalWindowRecords / duration, (double) this.totalWindowRecords);
        }

        public void printTotalResult() {

            System.out.println("\nSummary of Overall Results:");

            int[] percs = percentiles(this.latencies, this.latencyIndex, 0.5, 0.95, 0.99, 0.999);
            int duration = (int) (System.currentTimeMillis() - this.startTime);
            System.out.printf(
                    "Average Latency(ms): %.2f, Max Latency(ms): %d, Average MB/sec: %.2f, Record Rate: %.0f, Total Record: %.0f, 50th: %d ms, 95th: %d ms, 99th: %d ms, 99.9th: %d ms %n\n",
                    this.totalLatency / (double) this.totalRecords, this.maxLatency,
                    1000.0 * this.totalBytes / (1024.0 * 1024.0) / duration, 1000.0 * this.totalRecords / duration,
                    (double) this.totalRecords, percs[0], percs[1], percs[2], percs[3]);
        }

        private int[] percentiles(int[] latencies, int count, double... percentiles) {
            int size = Math.min(count, latencies.length);
            Arrays.sort(latencies, 0, size);
            int[] values = new int[percentiles.length];
            for (int i = 0; i < percentiles.length; i++) {
                int index = (int) (percentiles[i] * size);
                values[i] = latencies[index];
            }
            return values;
        }

        public double getAverageBytesRate() {
            int duration = (int) (System.currentTimeMillis() - this.startTime);
            return 1000.0 * this.totalBytes / duration;
            // return this.averageSizeRate;
        }

        public double getAverageRecordRate() {
            int duration = (int) (System.currentTimeMillis() - this.startTime);
            return 1000.0 * this.totalRecords / duration;
            // return this.averageMessageRate;
        }
    }

    public static final class KafkaRequestCallback implements Callback {
        private final long bytes;
        private final long start;
        private final KafkaStatistics stats;
        private final int index;

        public KafkaRequestCallback(int index, long start, int recordSize, KafkaStatistics stats) {
            this.index = index;
            this.start = start;
            this.bytes = recordSize;
            this.stats = stats;
        }

        public void onCompletion(RecordMetadata metadata, Exception exception) {

            long end = System.currentTimeMillis();
            int duration = (int) (end - this.start);
            // System.out.println("duration = " + (duration/1000) + "s");

            if (exception != null) {
                System.out.println("Error received from kafka request 11: " + exception.getMessage());
                System.out.println(exception);
                // System.out.println("Latency: %d ms." + (double)duration);
            } else {
                // stats.printResult();
                System.out.println (" message send successfully ");
                System.out.println(metadata );
                this.stats.record(this.index, duration, this.bytes);
            }

            // System.out.println("DURATION: " + duration + " ms");
        }
    }
}
