package net.powermatcher.remote.websockets;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.powermatcher.api.MatcherEndpoint;
import net.powermatcher.api.messages.BidUpdate;
import net.powermatcher.api.messages.PriceUpdate;
import net.powermatcher.api.monitoring.ObservableAgent;
import net.powermatcher.core.BaseAgent;
import net.powermatcher.remote.websockets.data.ClusterInfoModel;
import net.powermatcher.remote.websockets.data.PmMessage;
import net.powermatcher.remote.websockets.data.PmMessage.PayloadType;
import net.powermatcher.remote.websockets.data.PriceUpdateModel;
import net.powermatcher.remote.websockets.json.ModelMapper;
import net.powermatcher.remote.websockets.json.PmJsonSerializer;

import org.eclipse.jetty.websocket.api.CloseStatus;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import aQute.bnd.annotation.component.Activate;
import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Deactivate;
import aQute.bnd.annotation.metatype.Configurable;
import aQute.bnd.annotation.metatype.Meta;

import com.google.gson.JsonSyntaxException;

/**
 * WebSocket implementation of an {@link MatcherEndpointProxy}. Enabled two agents to communicate via WebSockets and
 * JSON over a TCP connection.
 * 
 * @author FAN
 * @version 2.0
 */
@WebSocket()
@Component(designateFactory = MatcherEndpointProxyWebsocket.Config.class,
           immediate = true,
           provide = { ObservableAgent.class })
public class MatcherEndpointProxyWebsocket
    extends BaseAgent
    implements MatcherEndpoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(MatcherEndpointProxyWebsocket.class);

    @Meta.OCD
    public static interface Config {
        @Meta.AD(deflt = "", description = "remote agent endpoint proxy to connect to.")
        String desiredConnectionId();

        @Meta.AD(deflt = "matcherendpointproxy", description = "The unique identifier of the agent")
        String agentId();

        @Meta.AD(deflt = "ws://localhost:8080/powermatcher/websockets/agentendpoint",
                 description = "URL of powermatcher websocket endpoint.")
        String powermatcherUrl();

        @Meta.AD(deflt = "30", description = "reconnect timeout keeping the connection alive.")
        int reconnectTimeout();

        @Meta.AD(deflt = "60", description = "connect timeout to wait for remote server to respond.")
        int connectTimeout();
    }

    private URI powermatcherUrl;

    private Session remoteSession;

    private WebSocketClient client;

    private int reconnectDelay, connectTimeout;

    private BundleContext bundleContext;

    private ServiceRegistration<MatcherEndpoint> matcherEndpointServiceRegistration;

    private final ScheduledThreadPoolExecutor executorService = new ScheduledThreadPoolExecutor(1);

    private ScheduledFuture<?> scheduledFuture;

    /**
     * OSGi calls this method to activate a managed service.
     * 
     * @param properties
     *            the configuration properties
     */
    @Activate
    public synchronized void activate(BundleContext bundleContext, Map<String, Object> properties) {
        // Read configuration properties
        Config config = Configurable.createConfigurable(Config.class, properties);
        init(config.agentId());

        try {
            powermatcherUrl = new URI(config.powermatcherUrl()
                                      + "?agentId=" + getAgentId()
                                      + "&desiredConnectionId="
                                      + config.desiredConnectionId());
        } catch (URISyntaxException e) {
            LOGGER.error("Malformed URL for powermatcher websocket endpoint. Reason {}", e);
            return;
        }

        reconnectDelay = config.reconnectTimeout();
        connectTimeout = config.connectTimeout();

        this.bundleContext = bundleContext;

        Runnable reconnectJob = new Runnable() {
            @Override
            public void run() {
                connectRemote();
            }
        };

        scheduledFuture = executorService.scheduleAtFixedRate(reconnectJob,
                                                              1,
                                                              reconnectDelay,
                                                              TimeUnit.SECONDS);
    }

    /**
     * OSGi calls this method to deactivate a managed service.
     */
    @Deactivate
    public synchronized void deactivate() {
        scheduledFuture.cancel(true);
        disconnectRemote();
        unregisterAgentEndpoint();
    }

    /**
     * {@inheritDoc}
     * 
     * This specific implementation opens a websocket.
     */
    public synchronized void connectRemote() {
        if (!isRemoteConnected()) {
            // Try to setup a new websocket connection.
            client = new WebSocketClient();
            ClientUpgradeRequest request = new ClientUpgradeRequest();

            try {
                client.start();
                Future<Session> connectFuture = client.connect(this, powermatcherUrl, request);
                LOGGER.info("Connecting to : {}", request);

                // Wait configurable time for remote to respond
                Session newRemoteSession = connectFuture.get(connectTimeout, TimeUnit.SECONDS);

                remoteSession = newRemoteSession;
            } catch (Exception e) {
                LOGGER.error("Unable to connect to remote agent. Reason {}", e);
                remoteSession = null;
            }
        }
    }

    /**
     * {@inheritDoc}
     * 
     * This specific implementation closes the open websocket.
     */
    public synchronized boolean disconnectRemote() {
        // Terminate remote session (if any)
        if (isRemoteConnected()) {
            remoteSession.close(new CloseStatus(0, "Normal disconnect"));
        }

        remoteSession = null;

        // Stop the client
        if (client != null && !client.isStopped()) {
            try {
                client.stop();
            } catch (Exception e) {
                LOGGER.warn("Unable to disconnect, reason: [{}]", e);
                return false;
            }
        }

        // Unregister the MatcherEndpoint with the OSGI runtime, to disable connections locally
        unregisterAgentEndpoint();

        return true;
    }

    /**
     * {@inheritDoc}
     * 
     * This specific implementation checks to see if the websocket is open.
     */
    public boolean isRemoteConnected() {
        return remoteSession != null && remoteSession.isOpen();
    }

    @OnWebSocketClose
    public void onDisconnect(int statusCode, String reason) {
        LOGGER.info("Connection closed: {} - {}", statusCode, reason);
        remoteSession = null;
    }

    @OnWebSocketMessage
    public void onMessage(String message) {
        LOGGER.debug("Received message from remote agent {}", message);

        try {
            // Decode the JSON data
            PmJsonSerializer serializer = new PmJsonSerializer();
            PmMessage pmMessage = serializer.deserialize(message);

            // Handle specific message
            if (pmMessage.getPayloadType() == PayloadType.PRICE_UPDATE) {
                // Relay price update to local agent
                PriceUpdate newPriceUpdate = ModelMapper.mapPriceUpdate((PriceUpdateModel) pmMessage.getPayload());
                if (localSession != null) {
                    localSession.updatePrice(newPriceUpdate);
                }
            }

            if (pmMessage.getPayloadType() == PayloadType.CLUSTERINFO) {
                // Sync marketbasis and clusterid with local session, for new
                // connections
                ClusterInfoModel clusterInfo = (ClusterInfoModel) pmMessage.getPayload();
                configure(ModelMapper.convertMarketBasis(clusterInfo.getMarketBasis()), clusterInfo.getClusterId());

                // Register the MatcherEndpoint with the OSGI runtime, to make it available for connections
                registerAgentEndpoint();
            }
        } catch (JsonSyntaxException e) {
            LOGGER.warn("Unable to understand message from remote agent: {}", message);
        }
    }

    // The local session to the agent, handling local stuff

    private net.powermatcher.api.Session localSession;

    @Override
    public void connectToAgent(net.powermatcher.api.Session session) {
        if (!isConnected()) {
            throw new IllegalStateException("This matcher is not yet connected");
        } else if (localSession == null) {
            localSession = session;
            session.setMarketBasis(getMarketBasis());
        } else {
            throw new IllegalStateException("The MatcherEndpointProxy may only be connected to 1 agent");
        }
    }

    @Override
    public void agentEndpointDisconnected(net.powermatcher.api.Session session) {
        if (session.equals(localSession)) {
            localSession = null;
            disconnectRemote();
        }
    }

    @Override
    public void handleBidUpdate(net.powermatcher.api.Session session, BidUpdate bidUpdate) {
        if (session == localSession && isRemoteConnected()) {
            try {
                PmJsonSerializer serializer = new PmJsonSerializer();
                String message = serializer.serializeBidUpdate(bidUpdate);
                remoteSession.getRemote().sendString(message);
            } catch (IOException e) {
                LOGGER.error("Unable to send new bid to remote agent. Reason {}", e);
            }
        }
    }

    private void registerAgentEndpoint() {
        if (matcherEndpointServiceRegistration == null) {
            matcherEndpointServiceRegistration = bundleContext.registerService(MatcherEndpoint.class, this, null);
        }
    }

    private void unregisterAgentEndpoint() {
        if (matcherEndpointServiceRegistration != null) {
            matcherEndpointServiceRegistration.unregister();
            matcherEndpointServiceRegistration = null;
        }
    }
}
