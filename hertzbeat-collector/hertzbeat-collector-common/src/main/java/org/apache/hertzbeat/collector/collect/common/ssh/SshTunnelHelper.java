package org.apache.hertzbeat.collector.collect.common.ssh;


import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hertzbeat.common.entity.job.SshTunnel;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Ssh Tunnel Helper
 */
@Slf4j
public class SshTunnelHelper {

    private static final long DEFAULT_CACHE_TIMEOUT = 500 * 1000;

    private static final Cache<SshClientSessionWrapper, LocalPortForwardingWrapper> TRACKER_CACHE =
            Caffeine.newBuilder()
                    .initialCapacity(1)
                    .maximumSize(1000)
                    .expireAfterAccess(Duration.ofMillis(DEFAULT_CACHE_TIMEOUT))
                    .scheduler(Scheduler.systemScheduler())
                    .removalListener((key, value, cause) -> {
                        if (key != null && value != null) {
                            // 1. try close tunnel
                            SshClientSessionWrapper clientSessionWrapper = (SshClientSessionWrapper) key;
                            LocalPortForwardingWrapper wrapper = (LocalPortForwardingWrapper) value;
                            List<LocalPortForwardingWrapper> select = wrapper.select(clientSessionWrapper.getClientSession(), null);
                            for (LocalPortForwardingWrapper forwardWrapper : select) {
                                try {
                                    forwardWrapper.close();
                                    log.info("[SSH Tunnel] discarded ssh local port forwarding, {}", forwardWrapper);
                                } catch (IOException e) {
                                    log.error("[SSH Tunnel] discarded ssh session local port forwarding  error", e);
                                }
                            }
                            // 2. try close session
                            if (!clientSessionWrapper.isShareConnection()) {
                                try {
                                    clientSessionWrapper.close();
                                    log.info("[SSH Tunnel] close unshared ssh connection, {}", clientSessionWrapper);
                                } catch (IOException e) {
                                    log.error("[SSH Tunnel] close unshared ssh connection error", e);
                                }
                            }
                        }
                    })
                    .build();


    /**
     * create ssh tunnel
     *
     * @param sshTunnel  ssh tunnel param
     * @param remoteHost remote host
     * @param remotePort remote port
     * @return local port
     */
    public static int localPortForward(SshTunnel sshTunnel, String remoteHost, String remotePort) throws GeneralSecurityException, IOException {
        boolean shareConnection = Boolean.parseBoolean(sshTunnel.getShareConnection());
        // 1. get ssh session
        ClientSession session = SshHelper.getConnectSession(sshTunnel.getHost(), sshTunnel.getPort(),
                sshTunnel.getUsername(), sshTunnel.getPassword(), sshTunnel.getPrivateKey(), sshTunnel.getPrivateKeyPassphrase(),
                Integer.parseInt(sshTunnel.getTimeout()), shareConnection);
        SshClientSessionWrapper sessionWrapper = new SshClientSessionWrapper(session, shareConnection);

        // 2. get local port
        int localPort = sshTunnel.getLocalPort() != null ? Integer.parseInt(sshTunnel.getLocalPort()) : 0;

        // 3. get tunnel
        LocalPortForwardingWrapper forwardingWrapper = selectWrapper(
                TRACKER_CACHE.getIfPresent(sessionWrapper), sessionWrapper, remoteHost, remotePort, localPort);
        if (forwardingWrapper == null) {
            if (localPort <= 0) {
                localPort = getRandomPort();
            }
            LocalPortForwardingWrapper newForwardingWrapper = sessionWrapper
                    .createLocalPortForwardingTracker(localPort, remoteHost, Integer.parseInt(remotePort));
            TRACKER_CACHE.put(sessionWrapper, newForwardingWrapper);
            log.info("[SSH Tunnel] created ssh forwarding tracker ssh:{}, remote:{}, localPort:{}",
                    sshTunnel.getHost() + ":" + sshTunnel.getPort(), remoteHost + ":" + remotePort, localPort);
        } else {
            localPort = forwardingWrapper.getTracker().getLocalAddress().getPort();
        }

        return localPort;
    }

    public static void checkTunnelParam(SshTunnel sshTunnel) {
        if (sshTunnel == null || !Boolean.parseBoolean(sshTunnel.getEnable())) {
            return;
        }
        if (!StringUtils.hasText(sshTunnel.getHost())) {
            throw new IllegalArgumentException("ssh tunnel must has ssh host param");
        }
        if (!StringUtils.hasText(sshTunnel.getPort())){
            throw new IllegalArgumentException("ssh tunnel must has ssh port param");
        }
        if (!StringUtils.hasText(sshTunnel.getUsername())){
            throw new IllegalArgumentException("ssh tunnel must has ssh username param");
        }

    }

    /**
     * get tunnel
     *
     * @param wrapper        LocalPortForwardingWrapper
     * @param sessionWrapper SshClientSessionWrapper
     * @param remoteHost     remote host
     * @param remotePort     remote port
     * @param localPort      local port
     * @return LocalPortForwardingWrapper
     */
    private static LocalPortForwardingWrapper selectWrapper(LocalPortForwardingWrapper wrapper, SshClientSessionWrapper sessionWrapper,
                                                            String remoteHost, String remotePort, Integer localPort) {
        if (wrapper == null) {
            return null;
        }
        List<LocalPortForwardingWrapper> selectList = wrapper.select(sessionWrapper.getClientSession(), localPortForwardWrapper -> {
            if (!localPortForwardWrapper.isOpen()) {
                return false;
            }
            ExplicitPortForwardingTracker tracker = localPortForwardWrapper.getTracker();
            SshdSocketAddress localAddress = tracker.getLocalAddress();
            SshdSocketAddress remoteAddress = tracker.getRemoteAddress();
            if (localPort <= 0) {
                return Objects.equals(remoteAddress.getHostName(), remoteHost)
                        && Objects.equals(remoteAddress.getPort(), Integer.parseInt(remotePort));
            }
            return Objects.equals(localAddress.getPort(), localPort)
                    && Objects.equals(remoteAddress.getHostName(), remoteHost)
                    && Objects.equals(remoteAddress.getPort(), Integer.parseInt(remotePort));
        });

        if (selectList.isEmpty()) {
            return null;
        }
        LocalPortForwardingWrapper selected;
        if (selectList.size() == 1) {
            selected = selectList.get(0);
        } else {
            selected = selectList.stream().min(Comparator.comparing(LocalPortForwardingWrapper::getLastAccessTime)).get();
        }
        selected.setLastAccessTime(System.currentTimeMillis());
        return selected;
    }


    private static int getRandomPort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0);) {
            return serverSocket.getLocalPort();
        }
    }

    @Getter
    @Setter
    @EqualsAndHashCode
    private static class SshClientSessionWrapper {
        private ClientSession clientSession;
        private boolean shareConnection;

        public SshClientSessionWrapper(ClientSession clientSession, boolean shareConnection) {
            this.clientSession = clientSession;
            this.shareConnection = shareConnection;
        }

        public LocalPortForwardingWrapper createLocalPortForwardingTracker(Integer localPort, String remoteHost, Integer remotePort) throws IOException {
            SshdSocketAddress remoteAddress = new SshdSocketAddress(remoteHost, remotePort);
            SshdSocketAddress localAddress = new SshdSocketAddress("localhost", localPort);
            ExplicitPortForwardingTracker tracker = clientSession.createLocalPortForwardingTracker(localAddress, remoteAddress);
            return new LocalPortForwardingWrapper(tracker);
        }

        public void close() throws IOException {
            clientSession.close();
        }

        @Override
        public String toString() {
            return "{ ssh:%s, shareConnection:%b}".formatted(clientSession, shareConnection);
        }
    }

    @Getter
    @Setter
    @EqualsAndHashCode
    private static class LocalPortForwardingWrapper {
        private static Map<ClientSession, List<LocalPortForwardingWrapper>> map = new ConcurrentHashMap<>();

        private ExplicitPortForwardingTracker tracker;
        private Long lastAccessTime;

        public LocalPortForwardingWrapper(ExplicitPortForwardingTracker tracker) {
            this.tracker = tracker;
            this.lastAccessTime = System.currentTimeMillis();
            map.computeIfAbsent(tracker.getClientSession(), (key) -> new ArrayList<>()).add(this);
        }

        /**
         * @param session   ssh client session
         * @param predicate condition
         * @return LocalPortForwardWrapper
         */
        public List<LocalPortForwardingWrapper> select(ClientSession session, Predicate<LocalPortForwardingWrapper> predicate) {
            List<LocalPortForwardingWrapper> trackerList = map.get(session);
            if (CollectionUtils.isEmpty(trackerList)) {
                return trackerList;
            }
            List<LocalPortForwardingWrapper> list = new ArrayList<>();
            long currentTimeMillis = System.currentTimeMillis();
            Iterator<LocalPortForwardingWrapper> iterator = trackerList.iterator();
            while (iterator.hasNext()) {
                LocalPortForwardingWrapper wrapper = iterator.next();
                // lazy remove
                if (currentTimeMillis - wrapper.getLastAccessTime() > DEFAULT_CACHE_TIMEOUT) {
                    try {
                        wrapper.getTracker().close();
                        iterator.remove();
                        log.info("[SSH Tunnel] Remove ssh local port forwarding {}", wrapper);
                    } catch (IOException e) {
                        log.warn("[SSH Tunnel] Close ssh local port forwarding  Error", e);
                    }
                } else if (predicate == null || predicate.test(wrapper)) {
                    list.add(wrapper);
                }
            }
            return list;
        }

        public void close() throws IOException {
            tracker.close();
        }

        public boolean isOpen() {
            return tracker.isOpen();
        }

        @Override
        public String toString() {
            return "{ ssh:%s, remote:%s, localPort:%d }".formatted(
                    tracker.getSession().getConnectAddress(),
                    tracker.getRemoteAddress(),
                    tracker.getLocalAddress().getPort()
            );
        }
    }

}
