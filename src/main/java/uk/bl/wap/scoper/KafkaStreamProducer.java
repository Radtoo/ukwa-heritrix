/**
 * 
 */
package uk.bl.wap.scoper;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Logger;

import org.apache.commons.httpclient.URIException;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.archive.crawler.frontier.BdbFrontier;
import org.archive.modules.CrawlURI;
import org.archive.modules.Processor;
import org.archive.modules.net.ServerCache;
import org.archive.modules.postprocessor.CrawlLogJsonBuilder;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.Lifecycle;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import uk.bl.wap.crawler.postprocessor.KafkaKeyedCrawlLogFeed;

/**
 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
 *
 */
public class KafkaStreamProducer extends Processor implements Lifecycle {

    protected static final Logger logger = Logger
            .getLogger(KafkaKeyedCrawlLogFeed.class.getName());

    protected ServerCache serverCache;

    public ServerCache getServerCache() {
        return this.serverCache;
    }

    @Autowired
    public void setServerCache(ServerCache serverCache) {
        this.serverCache = serverCache;
    }

    protected Map<String, String> extraFields = new HashMap<String, String>();

    public Map<String, String> getExtraFields() {
        return extraFields;
    }

    public void setExtraFields(Map<String, String> extraFields) {
        this.extraFields = extraFields;
    }

    protected boolean dumpPendingAtClose = false;

    public boolean getDumpPendingAtClose() {
        return dumpPendingAtClose;
    }

    /**
     * If true, publish all pending urls (i.e. queued urls still in the
     * frontier) when crawl job is stopping. They are recognizable by the status
     * field which has the value 0.
     *
     * @see BdbFrontier#setDumpPendingAtClose(boolean)
     */
    public void setDumpPendingAtClose(boolean dumpPendingAtClose) {
        this.dumpPendingAtClose = dumpPendingAtClose;
    }

    protected String brokerList = "localhost:9092";

    /** Kafka broker list (kafka property "metadata.broker.list"). */
    public void setBrokerList(String brokerList) {
        this.brokerList = brokerList;
    }

    public String getBrokerList() {
        return brokerList;
    }

    protected String topic = "heritrix-crawl-log";

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getTopic() {
        return topic;
    }

    private int acks = 1;

    public int getAcks() {
        return acks;
    }

    public void setAcks(int acks) {
        this.acks = acks;
    }

    protected byte[] buildMessage(CrawlURI curi) {
        JSONObject jo = CrawlLogJsonBuilder.buildJson(curi, getExtraFields(),
                getServerCache());
        try {
            return jo.toString().getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected boolean shouldProcess(CrawlURI curi) {
        return true;
    }

    private transient long pendingDumpedCount = 0l;

    @Override
    public synchronized void stop() {
        if (!isRunning) {
            return;
        }

        String rateStr = String.format("%1.1f",
                0.01 * stats.errors / stats.total);
        logger.info("final error count: " + stats.errors + "/" + stats.total
                + " (" + rateStr + "%)");

        if (kafkaProducer != null) {
            kafkaProducer.close();
            kafkaProducer = null;
        }
        if (kafkaProducerThreads != null) {
            kafkaProducerThreads.destroy();
            kafkaProducerThreads = null;
        }

        super.stop();
    }

    private transient ThreadGroup kafkaProducerThreads;

    transient protected KafkaProducer<String, byte[]> kafkaProducer;

    protected KafkaProducer<String, byte[]> kafkaProducer() {
        if (kafkaProducer == null) {
            synchronized (this) {
                if (kafkaProducer == null) {
                    final Properties props = new Properties();
                    props.put("bootstrap.servers", getBrokerList());
                    props.put("acks", Integer.toString(getAcks()));
                    props.put("key.serializer",
                            StringSerializer.class.getName());
                    props.put("value.serializer",
                            ByteArraySerializer.class.getName());

                    /*
                     * XXX This mess here exists so that the kafka producer
                     * thread is in a thread group that is not the ToePool, so
                     * that it doesn't get interrupted at the end of the crawl
                     * in ToePool.cleanup().
                     */
                    kafkaProducerThreads = new ThreadGroup(
                            Thread.currentThread().getThreadGroup().getParent(),
                            "KafkaProducerThreads");
                    ThreadFactory threadFactory = new ThreadFactory() {
                        public Thread newThread(Runnable r) {
                            return new Thread(kafkaProducerThreads, r);
                        }
                    };
                    Callable<KafkaProducer<String, byte[]>> task = new Callable<KafkaProducer<String, byte[]>>() {
                        public KafkaProducer<String, byte[]> call()
                                throws InterruptedException {
                            return new KafkaProducer<String, byte[]>(props);
                        }
                    };
                    ExecutorService executorService = Executors
                            .newFixedThreadPool(1, threadFactory);
                    Future<KafkaProducer<String, byte[]>> future = executorService
                            .submit(task);
                    try {
                        kafkaProducer = future.get();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    } catch (ExecutionException e) {
                        throw new RuntimeException(e);
                    } finally {
                        executorService.shutdown();
                    }
                }
            }
        }
        return kafkaProducer;
    }

    protected final class StatsCallback implements Callback {
        public long errors = 0l;
        public long total = 0l;

        @Override
        public void onCompletion(RecordMetadata metadata, Exception exception) {
            total++;
            if (exception != null) {
                errors++;
            }

            if (total % 10000 == 0) {
                String rateStr = String.format("%1.1f", 0.01 * errors / total);
                logger.info("error count so far: " + errors + "/" + total + " ("
                        + rateStr + "%)");
            }
        }
    }

    protected StatsCallback stats = new StatsCallback();

    /**
     * Create a hashed key from the host, to distribute crawling consistently.
     * 
     * We could also use the default 'SURT of the Authority' class key here, but
     * this can be overridden in Crawler Beans, and is not yet set at this
     * point.
     * 
     * @see org.archive.crawler.frontier.SurtAuthorityQueueAssignmentPolicy
     * 
     * @param curi
     * @return
     */
    protected String getKeyForCrawlURI(CrawlURI curi) {
        // Hash the key to ensure uniform distribution:
        String queueKey;
        try {
            queueKey = curi.getUURI().getAuthority();
        } catch (URIException e) {
            queueKey = "unparseable_key";
        }
        if (queueKey == null) {
            // Mostly DNS records:
            if (curi.getURI().startsWith("dns:")) {
                // return the hostname without the 'dns:' prefix:
                queueKey = curi.getURI().substring(4);
            } else {
                queueKey = "null_key";
            }
        }
        // Hash it to make the key:
        HashCode hash = hf.hashBytes(queueKey.getBytes());
        return hash.toString();
    }

    // Hash function used to generate the partition key:
    private HashFunction hf = Hashing.murmur3_32();

    protected boolean shouldEmit(CrawlURI candidate) {
        // Drop data/mailto URIs
        if (candidate.getURI().startsWith("data:")
                || candidate.getURI().startsWith("mailto:")) {
            return false;
        }
        return true;
    }

    @Override
    protected void innerProcess(CrawlURI curi) throws InterruptedException {
        if (this.shouldEmit(curi)) {
            byte[] message = buildMessage(curi);
            ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<String, byte[]>(
                    getTopic(), getKeyForCrawlURI(curi), message);
            kafkaProducer().send(producerRecord, stats);
        }
    }

}
