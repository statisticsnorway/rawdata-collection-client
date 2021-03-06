package no.ssb.dc.collection.kostra;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import no.ssb.dapla.migration.rawdata.onprem.config.TargetConfiguration;
import no.ssb.dapla.migration.rawdata.onprem.target.BufferedRawdataProducer;
import no.ssb.dapla.migration.rawdata.onprem.utils.FixedThreadPool;
import no.ssb.dapla.migration.rawdata.onprem.worker.JsonParser;
import no.ssb.dapla.migration.rawdata.onprem.worker.MetadataContent;
import no.ssb.rawdata.api.RawdataClient;
import no.ssb.rawdata.api.RawdataClientInitializer;
import no.ssb.rawdata.api.RawdataMessage;
import no.ssb.rawdata.api.RawdataProducer;
import no.ssb.rawdata.payload.encryption.EncryptionClient;
import no.ssb.service.provider.api.ProviderConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class KostraWorker implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(KostraWorker.class);
    private static final AtomicLong publishedMessageCount = new AtomicLong();
    private final JsonParser jsonParser;
    private final SourceKostraConfiguration sourceConfiguration;
    private final FixedThreadPool threadPool;
    private final BufferedReordering<String> bufferedReordering = new BufferedReordering<>();
    private final Queue<CompletableFuture<RawdataMessageBuffer>> futures;
    private final RawdataClient client;
    private final RawdataProducer producer;
    private final int queueCapacity;
    private final JsonNode specification;
    private final EncryptionClient encryptionClient;
    private final byte[] secretKey;

    public KostraWorker(SourceKostraConfiguration sourceConfiguration, TargetConfiguration targetConfiguration) {
        this.jsonParser = JsonParser.createJsonParser();
        this.sourceConfiguration = sourceConfiguration;
        threadPool = FixedThreadPool.newInstance();
        client = ProviderConfigurator.configure(targetConfiguration.asMap(), targetConfiguration.rawdataClientProvider(), RawdataClientInitializer.class);
        producer = client.producer(targetConfiguration.topic());
        specification = loadSpecification(sourceConfiguration);
        final char[] encryptionKey = targetConfiguration.hasRawdataEncryptionKey() ?
                targetConfiguration.rawdataEncryptionKey().toCharArray() : null;
        final byte[] encryptionSalt = targetConfiguration.hasRawdataEncryptionSalt() ?
                targetConfiguration.rawdataEncryptionSalt().getBytes() : null;
        this.encryptionClient = new EncryptionClient();
        if (encryptionKey != null && encryptionKey.length > 0 && encryptionSalt != null && encryptionSalt.length > 0) {
            this.secretKey = encryptionClient.generateSecretKey(encryptionKey, encryptionSalt).getEncoded();
            Arrays.fill(encryptionKey, (char) 0);
            Arrays.fill(encryptionSalt, (byte) 0);
        } else {
            this.secretKey = null;
        }
        queueCapacity = this.sourceConfiguration.hasQueueCapacity() ? this.sourceConfiguration.queueCapacity() : 1000;
        futures = new LinkedBlockingDeque<>(queueCapacity);
    }

    private JsonNode loadSpecification(SourceKostraConfiguration sourceConfiguration) {
        Path specPath = Paths.get(sourceConfiguration.sourcePath()).resolve(Paths.get(sourceConfiguration.specificationFile()));
        try {
            byte[] yamlBytes = Files.readAllBytes(specPath);
            return JsonParser.createYamlParser().mapper().readValue(yamlBytes, JsonNode.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean validate() {
        // do nothing
        return true;
    }

    CompletableFuture<RawdataMessageBuffer> offerMessage(RawdataMessageBuffer message) {
        bufferedReordering.addExpected(message.toPosition());
        return CompletableFuture.supplyAsync(() -> {
                    message.produce();
                    return message;
                }, threadPool.getExecutor()
        ).thenApply(msg -> {
            bufferedReordering.addCompleted(msg.toPosition(), orderedPositions -> {
                String[] positions = orderedPositions.toArray(new String[0]);
                producer.publish(positions);
                publishedMessageCount.getAndAdd(positions.length);
            });
            return msg;
        }).exceptionally(throwable -> {
            if (throwable instanceof RuntimeException) {
                throw (RuntimeException) throwable;
            }
            throw new RuntimeException(throwable);
        });
    }

    void commitMessages() {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    futures.clear();
                    return v;
                })
                .join();
    }

    void parse(String charset, Consumer<ArrayNode> structureCallback, Consumer<ArrayNode> dataElementCallback) {
        try {
            Path source = Paths.get(sourceConfiguration.sourcePath()).resolve(sourceConfiguration.sourceFile());
            JsonFactory jsonfactory = new JsonFactory();
            LOG.info("Parse file {} with {} encoding", source.normalize().toAbsolutePath().toString(), Charset.forName(charset));
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(source.toFile()), charset))) {
                try (com.fasterxml.jackson.core.JsonParser parser = jsonfactory.createParser(reader)) {
                    // fail if json is not an object
                    if (parser.nextToken() != JsonToken.START_OBJECT) {
                        throw new IllegalStateException("Array node NOT found!");
                    }

                    // read array tokens
                    JsonToken jsonToken;
                    while ((jsonToken = parser.nextToken()) != JsonToken.START_ARRAY && jsonToken != null) {
                        if ("structure".equals(parser.currentName()) && (jsonToken = parser.nextToken()) == JsonToken.START_ARRAY) {
                            ArrayNode jsonNode = jsonParser.mapper().readValue(parser, ArrayNode.class);
                            structureCallback.accept(jsonNode);

                        } else if ("data".equals(parser.currentName()) && jsonToken == JsonToken.FIELD_NAME) {
                            if ((jsonToken = parser.nextToken()) == JsonToken.START_ARRAY) {
                                while ((jsonToken = parser.nextToken()) == JsonToken.START_ARRAY) {
                                    ArrayNode jsonNode = jsonParser.mapper().readValue(parser, ArrayNode.class);
                                    dataElementCallback.accept(jsonNode);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void produce() {
        JsonNode metadata = specification.withArray("metadata");
        JsonNode fileDescriptor = specification.withArray("fileDescriptor");
        String sourceCharset = getString(fileDescriptor, "charset");

        AtomicLong positionRef = new AtomicLong(0);
        AtomicReference<ArrayNode> structureArrayNodeRef = new AtomicReference<>();

        parse(sourceCharset, structureArrayNodeRef::set, dataElementArrayNode -> {
            String position = String.valueOf(positionRef.incrementAndGet());

            // produce rawdata message
            ObjectNode targetElementDocument = jsonParser.createObjectNode();
            targetElementDocument.set("structure", structureArrayNodeRef.get());
            ArrayNode targetDataArrayNode = jsonParser.createArrayNode();
            targetDataArrayNode.add(dataElementArrayNode);
            targetElementDocument.set("data", targetDataArrayNode);
            byte[] bytes = jsonParser.toJSON(targetElementDocument).getBytes();
            if (LOG.isDebugEnabled()) {
                LOG.debug("{}:\n{}", positionRef.get(), jsonParser.toPrettyJSON(targetElementDocument));
            }

            // produce manifest json
            MetadataContent.Builder metadataContentBuilder = new MetadataContent.Builder()
                    .topic(producer.topic())
                    .position(position)
                    .resourceType("entry")
                    .contentKey("entry")
                    .source(getString(metadata, "source"))
                    .dataset(getString(metadata, "dataset"))
                    .tag(getString(metadata, "tag"))
                    .description(getString(metadata, "description"))
                    .charset(StandardCharsets.UTF_8.displayName())
                    .contentType(getString(fileDescriptor, "contentType"))
                    .contentLength(bytes.length)
                    .markCreatedDate();

            // store json mapping
            metadataContentBuilder
                    .sourcePath(sourceConfiguration.sourcePath())
                    .sourceFile(sourceConfiguration.sourceFile())
                    .sourceCharset(sourceCharset)
                    .recordType(BufferedRawdataProducer.RecordType.SINGLE.name().toLowerCase());

            for (int j = 0; j < structureArrayNodeRef.get().size(); j++) {
                JsonNode structureElementNode = structureArrayNodeRef.get().get(j);
                String name = structureElementNode.get("name").asText();
                String type = structureElementNode.get("type").asText();
                metadataContentBuilder.jsonMapping(name, asDataTypeFormat(type));
            }

            MetadataContent metadataContent = metadataContentBuilder.build();

            // async buffer message
            CompletableFuture<RawdataMessageBuffer> future = offerMessage(new RawdataMessageBuffer(jsonParser, producer, position, bytes, metadataContent, encryptionClient, secretKey));
            if (!futures.offer(future)) {
                commitMessages();

                // re-offer message
                if (!futures.offer(future)) {
                    throw new IllegalStateException("Unable to offer future! Out of capacity: " + queueCapacity);
                }
            }
        });
    }

    String getString(JsonNode jsonNode, String fieldName) {
        return jsonNode.findValue(fieldName) != null ? jsonNode.findValue(fieldName).asText() : null;
    }

    private String asDataTypeFormat(String type) {
        return String.format("%s%s", type.substring(0, 1).toUpperCase(), type.substring(1).toLowerCase());
    }


    @Override
    public void close() {
        try {
            commitMessages();
            threadPool.shutdownAndAwaitTermination();
            client.close();
            LOG.info("Source - Published message Total-Count: {}", publishedMessageCount.get());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class RawdataMessageBuffer {
        private final JsonParser jsonParser;
        private final RawdataProducer producer;
        private final String position;
        private final byte[] data;
        private final EncryptionClient encryptionClient;
        private final byte[] secretKey;
        private final MetadataContent metadataContent;

        public RawdataMessageBuffer(JsonParser jsonParser, RawdataProducer producer, String position, byte[] data, MetadataContent manifest) {
            this(jsonParser, producer, position, data, manifest, null, null);
        }

        public RawdataMessageBuffer(JsonParser jsonParser, RawdataProducer producer, String position, byte[] data, MetadataContent metadataContent, EncryptionClient encryptionClient, byte[] secretKey) {
            this.jsonParser = jsonParser;
            this.metadataContent = metadataContent;
            Objects.requireNonNull(data);
            this.position = position;
            this.data = data;
            this.producer = producer;
            this.encryptionClient = encryptionClient;
            this.secretKey = secretKey;
        }

        private byte[] tryEncryptContent(byte[] content) {
            if (secretKey != null) {
                byte[] iv = encryptionClient.generateIV();
                return encryptionClient.encrypt(secretKey, iv, content);
            }
            return content;
        }

        public void produce() {
            RawdataMessage.Builder messageBuilder = producer.builder();
            messageBuilder.position(toPosition());
            byte[] manifestData = jsonParser.toJSON(metadataContent.getElementNode()).getBytes();
            messageBuilder.put("manifest.json", tryEncryptContent(manifestData));
            messageBuilder.put("entry", tryEncryptContent(data));
            producer.buffer(messageBuilder);
        }

        public String toPosition() {
            return position;
        }
    }
}
