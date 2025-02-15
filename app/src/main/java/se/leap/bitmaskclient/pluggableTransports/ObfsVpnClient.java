package se.leap.bitmaskclient.pluggableTransports;

import android.util.Log;

import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import client.Client_;
import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.VpnStatus;
import se.leap.bitmaskclient.eip.EipStatus;

public class ObfsVpnClient implements Observer, client.EventLogger {

    public static final AtomicInteger SOCKS_PORT = new AtomicInteger(4430);
    public static final String SOCKS_IP = "127.0.0.1";
    private static final String ERR_BIND = "bind: address already in use";

    private static final String TAG = ObfsVpnClient.class.getSimpleName();
    private volatile boolean noNetwork;
    private final AtomicBoolean pendingNetworkErrorHandling = new AtomicBoolean(false);
    private final AtomicInteger reconnectRetry = new AtomicInteger(0);
    private static final int MAX_RETRY = 5;

    private final client.Client_ obfsVpnClient;
    private final Object LOCK = new Object();

    public ObfsVpnClient(Obfs4Options options) {
        obfsVpnClient = new Client_(options.udp, SOCKS_IP+":"+SOCKS_PORT.get(), options.cert);
        obfsVpnClient.setEventLogger(this);
    }

    /**
     * starts the client
     * @return the port ObfsVpn is running on
     */
    public int start() {
        synchronized (LOCK) {
            Log.d(TAG, "aquired LOCK");
            new Thread(this::startSync).start();
            waitUntilStarted();
            Log.d(TAG, "returning LOCK after " + (reconnectRetry.get() + 1) * 200 +" ms");
        }
        return SOCKS_PORT.get();
    }

    private void waitUntilStarted() {
        int count = -1;
        try {
            while (count < reconnectRetry.get() && reconnectRetry.get() < MAX_RETRY) {
                Thread.sleep(200);
                count++;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void startSync() {
        try {
            obfsVpnClient.start();
        } catch (Exception e) {
            Log.e(TAG, "[obfsvpn] exception: " + e.getLocalizedMessage());
            VpnStatus.logError("[obfsvpn] " + e.getLocalizedMessage());
            if (e.getLocalizedMessage() != null && e.getLocalizedMessage().contains(ERR_BIND) && reconnectRetry.get() < MAX_RETRY) {
                reconnectRetry.addAndGet(1);
                SOCKS_PORT.addAndGet(1);
                obfsVpnClient.setSocksAddr(SOCKS_IP+":"+SOCKS_PORT.get());
                Log.d(TAG, "[obfsvpn] reconnecting on different port... " + SOCKS_PORT.get());
                VpnStatus.logDebug("[obfsvpn] reconnecting on different port... " + SOCKS_PORT.get());
                startSync();
            } else if (noNetwork) {
                pendingNetworkErrorHandling.set(true);
            }
        }
    }

    public void stop() {
        synchronized (LOCK) {
            Log.d(TAG, "stopping obfsVpnClient...");
            try {
                obfsVpnClient.stop();
                reconnectRetry.set(0);
                SOCKS_PORT.set(4430);
                Thread.sleep(100);
            } catch (Exception e) {
                e.printStackTrace();
                VpnStatus.logError("[obfsvpn] " + e.getLocalizedMessage());
            }
            pendingNetworkErrorHandling.set(false);
            Log.d(TAG, "stopping obfsVpnClient releasing LOCK ...");
        }
    }

    public boolean isStarted() {
        return obfsVpnClient.isStarted();
    }

    @Override
    public void update(Observable observable, Object arg) {
        if (observable instanceof EipStatus) {
            EipStatus status = (EipStatus) observable;
            if (status.getLevel() == ConnectionStatus.LEVEL_NONETWORK) {
                noNetwork = true;
            } else {
                noNetwork = false;
                if (pendingNetworkErrorHandling.getAndSet(false)) {
                    stop();
                    start();
                }
            }
        }
    }

    @Override
    public void error(String s) {
        VpnStatus.logError("[obfsvpn] " + s);
    }

    @Override
    public void log(String state, String message) {
        VpnStatus.logDebug("[obfsvpn] " + state + " " + message);
    }

}
