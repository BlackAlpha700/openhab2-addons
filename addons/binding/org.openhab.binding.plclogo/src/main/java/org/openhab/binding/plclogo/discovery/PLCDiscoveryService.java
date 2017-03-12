/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.plclogo.discovery;

import static org.openhab.binding.plclogo.PLCLogoBindingConstants.*;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.net.util.SubnetUtils;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.UID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link PLCDiscoveryService} is responsible for discovering devices on
 * the current Network. It uses every Network Interface which is connected to a network.
 *
 * @author Alexander Falkenstern - Initial contribution
 */
public class PLCDiscoveryService extends AbstractDiscoveryService {

    private final Logger logger = LoggerFactory.getLogger(PLCDiscoveryService.class);

    private static final int DISCOVERY_TIMEOUT = 30;
    private static final Set<ThingTypeUID> DISCOVERABLE_THING_TYPES_UIDS = Collections.singleton(THING_TYPE_DEVICE);

    private static final int CONNECTION_TIMEOUT = 500;
    private TreeSet<String> addresses = new TreeSet<String>();

    private ReentrantLock lock = new ReentrantLock();
    private ExecutorService executor = null;

    private class Runner implements Runnable {
        final String address;

        public Runner(final String address) {
            this.address = address;
            if (address == null) {
                throw new RuntimeException("IP may not be null!");
            }
        }

        @Override
        public void run() {
            // gets every ip which can be assigned on the given network
            final InetSocketAddress endpoint = new InetSocketAddress(address, 102);
            if (!endpoint.isUnresolved()) {
                Socket socket = new Socket();
                try {
                    socket.connect(endpoint, CONNECTION_TIMEOUT);

                    logger.info("LOGO! device found at: {}.", address);

                    String hostname = InetAddress.getByName(address).getHostName();
                    if (!hostname.matches(UID.SEGMENT_PATTERN)) {
                        // Replace invalid char's, since UID has no method for this.
                        hostname = hostname.replaceAll("[^A-Za-z0-9_-]", "_");
                    }

                    ThingUID thingUID = new ThingUID(THING_TYPE_DEVICE, hostname);
                    DiscoveryResultBuilder builder = DiscoveryResultBuilder.create(thingUID);

                    builder = builder.withProperty(LOGO_HOST, address);
                    builder = builder.withLabel(hostname);

                    lock.lock();
                    try {
                        thingDiscovered(builder.build());
                    } finally {
                        lock.unlock();
                    }
                } catch (IOException exception) {
                    logger.debug("LOGO! device not found at: {}.", address);
                } finally {
                    try {
                        socket.close();
                    } catch (IOException exception) {
                        logger.error("LOGO! bridge discovering: {}.", exception.toString());
                    }
                }
            }
        }
    }

    public PLCDiscoveryService() {
        super(DISCOVERABLE_THING_TYPES_UIDS, DISCOVERY_TIMEOUT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void startScan() {
        if (executor != null) {
            stopScan();
        }

        try {
            Enumeration<NetworkInterface> devices = NetworkInterface.getNetworkInterfaces();
            while (devices.hasMoreElements()) {
                NetworkInterface device = devices.nextElement();
                if (device.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress iface : device.getInterfaceAddresses()) {
                    InetAddress address = iface.getAddress();
                    if (address instanceof Inet4Address) {
                        final String prefix = String.valueOf(iface.getNetworkPrefixLength());
                        SubnetUtils utilities = new SubnetUtils(address.getHostAddress() + "/" + prefix);
                        addresses.addAll(Arrays.asList(utilities.getInfo().getAllAddresses()));
                    }
                }
            }
        } catch (SocketException exception) {
            addresses.clear();
            logger.error("LOGO! bridge discovering: {}.", exception.toString());
        }

        executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 1);
        for (String address : addresses) {
            executor.execute(new Runner(address));
        }
        stopScan();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected synchronized void stopScan() {
        logger.debug("Stop scan for LOGO! bridge");
        super.stopScan();

        if (executor != null) {
            try {
                final long size = (long) Math.ceil(1.5 * addresses.size());
                executor.awaitTermination(CONNECTION_TIMEOUT * size, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                logger.error("LOGO! bridge discovering: {}.", exception.toString());
            }
            executor.shutdown();
            executor = null;
        }
        addresses.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void startBackgroundDiscovery() {
        logger.debug("Start background scan for LOGO! bridge");
        startScan();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void stopBackgroundDiscovery() {
        logger.debug("Stop background scan for LOGO! bridge");
        stopScan();
    }

}
