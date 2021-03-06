package uk.bl.wap.crawler.processor;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.archive.modules.CrawlURI;
import org.archive.modules.Processor;
import org.archive.modules.net.CrawlHost;
import org.archive.modules.net.ServerCache;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Annotates a CrawlURI with its IP address.
 * 
 * @author rcoram
 */

public class IpAnnotator extends Processor {
    private final static Logger LOGGER = Logger.getLogger(IpAnnotator.class
            .getName());

    protected ServerCache serverCache;

    @Autowired
    public void setServerCache(ServerCache serverCache) {
        this.serverCache = serverCache;
    }

    @Override
    protected boolean shouldProcess(CrawlURI curi) {
        return curi.isSuccess();
    }

    @Override
    protected void innerProcess(CrawlURI curi) {
        try {
            CrawlHost host = serverCache.getHostFor(curi.getUURI());
            if (host != null && host.getIP() != null) {
                curi.getAnnotations()
                        .add("ip:" + host.getIP().getHostAddress());
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Problem adding IP: " + curi.getURI(), e);
            curi.getNonFatalFailures().add(e);
        }
    }
}
