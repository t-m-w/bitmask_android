/**
 * Copyright (c) 2013 LEAP Encryption Access Project and contributers
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package se.leap.bitmaskclient.eip;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.blinkt.openvpn.LaunchVPN;
import de.blinkt.openvpn.core.IOpenVPNServiceInternal;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VpnStatus;
import se.leap.bitmaskclient.OnBootReceiver;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;
import static android.content.Intent.CATEGORY_DEFAULT;
import static se.leap.bitmaskclient.Constants.BROADCAST_EIP_EVENT;
import static se.leap.bitmaskclient.Constants.BROADCAST_RESULT_CODE;
import static se.leap.bitmaskclient.Constants.BROADCAST_RESULT_KEY;
import static se.leap.bitmaskclient.Constants.EIP_ACTION_CHECK_CERT_VALIDITY;
import static se.leap.bitmaskclient.Constants.EIP_ACTION_IS_RUNNING;
import static se.leap.bitmaskclient.Constants.EIP_ACTION_START;
import static se.leap.bitmaskclient.Constants.EIP_ACTION_START_ALWAYS_ON_VPN;
import static se.leap.bitmaskclient.Constants.EIP_ACTION_STOP;
import static se.leap.bitmaskclient.Constants.EIP_ACTION_STOP_BLOCKING_VPN;
import static se.leap.bitmaskclient.Constants.EIP_EARLY_ROUTES;
import static se.leap.bitmaskclient.Constants.EIP_RECEIVER;
import static se.leap.bitmaskclient.Constants.EIP_REQUEST;
import static se.leap.bitmaskclient.Constants.EIP_RESTART_ON_BOOT;
import static se.leap.bitmaskclient.Constants.PROVIDER_VPN_CERTIFICATE;
import static se.leap.bitmaskclient.Constants.SHARED_PREFERENCES;
import static se.leap.bitmaskclient.MainActivityErrorDialog.DOWNLOAD_ERRORS.ERROR_INVALID_VPN_CERTIFICATE;
import static se.leap.bitmaskclient.R.string.vpn_certificate_is_invalid;

/**
 * EIP is the abstract base class for interacting with and managing the Encrypted
 * Internet Proxy connection.  Connections are started, stopped, and queried through
 * this Service.
 * Contains logic for parsing eip-service.json from the provider, configuring and selecting
 * gateways, and controlling {@link de.blinkt.openvpn.core.OpenVPNService} connections.
 *
 * @author Sean Leonard <meanderingcode@aetherislands.net>
 * @author Parménides GV <parmegv@sdf.org>
 */
public final class EIP extends Service implements Observer {

    public final static String TAG = EIP.class.getSimpleName(),
            SERVICE_API_PATH = "config/eip-service.json",
            ERRORS = "errors",
            ERROR_ID = "errorID";

    private WeakReference<ResultReceiver> mReceiverRef = new WeakReference<>(null);
    private SharedPreferences preferences;
    private AtomicInteger processCounter;
    private EipStatus eipStatus;

    private AtomicBoolean disconnectOnConnect = new AtomicBoolean(false);

    private IOpenVPNServiceInternal mService;
    private ServiceConnection openVpnConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mService = IOpenVPNServiceInternal.Stub.asInterface(service);
            if (disconnectOnConnect.get()) {
                disconnect();
                disconnectOnConnect.set(false);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mService = null;
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        bindOpenVpnService();
        eipStatus = EipStatus.getInstance();
        eipStatus.addObserver(this);
        processCounter = new AtomicInteger(0);
        preferences = getSharedPreferences(SHARED_PREFERENCES, MODE_PRIVATE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        eipStatus.deleteObserver(this);
        unbindService(openVpnConnection);
    }

    /**
     * update eipStatus whenever it changes
     */
    @Override
    public void update(Observable observable, Object data) {
        if (observable instanceof EipStatus) {
            eipStatus = (EipStatus) observable;
        }
    }

    /**
     * register new process with processCounter and start it
     * @param intent the intent that started this EIP call
     */
    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        processCounter.incrementAndGet();
        final String action = intent.getAction();
        if (intent.getParcelableExtra(EIP_RECEIVER) != null) {
            mReceiverRef = new WeakReference<>((ResultReceiver) intent.getParcelableExtra(EIP_RECEIVER));
        }
        if (action != null) {
            new EipThread(this, action, intent).start();
        }
        return START_STICKY;
    }

    /**
     * Initiates an EIP connection by selecting a gateway and preparing and sending an
     * Intent to {@link de.blinkt.openvpn.LaunchVPN}.
     * It also sets up early routes.
     */
    private void startEIP(boolean earlyRoutes) {
        if (!EipStatus.getInstance().isBlockingVpnEstablished() && earlyRoutes)  {
            earlyRoutes();
        }

        Bundle result = new Bundle();
        if (!preferences.getBoolean(EIP_RESTART_ON_BOOT, false)){
            preferences.edit().putBoolean(EIP_RESTART_ON_BOOT, true).apply();
        }

        GatewaysManager gatewaysManager = gatewaysFromPreferences();
        if (!isVPNCertificateValid()){
            setErrorResult(result, vpn_certificate_is_invalid, ERROR_INVALID_VPN_CERTIFICATE.toString());
            tellToReceiverOrBroadcast(EIP_ACTION_START, RESULT_CANCELED, result);
            return;
        }

        Gateway gateway = gatewaysManager.select();
        if (gateway != null && gateway.getProfile() != null) {
            launchActiveGateway(gateway);
            tellToReceiverOrBroadcast(EIP_ACTION_START, RESULT_OK);
        } else
            tellToReceiverOrBroadcast(EIP_ACTION_START, RESULT_CANCELED);
    }

    /**
     * Tries to start the last used vpn profile when the OS was rebooted and always-on-VPN is enabled.
     * The {@link OnBootReceiver} will care if there is no profile.
     */
    private void startEIPAlwaysOnVpn() {
        Log.d(TAG, "startEIPAlwaysOnVpn vpn");

        GatewaysManager gatewaysManager = gatewaysFromPreferences();
        Gateway gateway = gatewaysManager.select();

        if (gateway != null && gateway.getProfile() != null) {
            Log.d(TAG, "startEIPAlwaysOnVpn eip launch avtive gateway vpn");
            launchActiveGateway(gateway);
        } else {
            Log.d(TAG, "startEIPAlwaysOnVpn no active profile available!");
        }
    }

    /**
     * Early routes are routes that block traffic until a new
     * VpnService is started properly.
     */
    private void earlyRoutes() {
        Intent voidVpnLauncher = new Intent(getApplicationContext(), VoidVpnLauncher.class);
        voidVpnLauncher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(voidVpnLauncher);
    }

    /**
     * starts the VPN and connects to the given gateway
     * @param gateway to connect to
     */
    private void launchActiveGateway(Gateway gateway) {
        Intent intent = new Intent(this, LaunchVPN.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(LaunchVPN.EXTRA_HIDELOG, true);
        intent.putExtra(LaunchVPN.EXTRA_TEMP_VPN_PROFILE, gateway.getProfile());
        startActivity(intent);
    }

    /**
     * Stop VPN
     * First checks if the OpenVpnConnection is open then
     * terminates EIP if currently connected or connecting
     */
    private void stopEIP() {
        // if the OpenVpnConnection doesn't exist remember to disconnect after connecting
        int resultCode = RESULT_CANCELED;
        if (eipStatus.isConnected() || eipStatus.isConnecting()) {
            stop();
            resultCode = RESULT_OK;
        }
        tellToReceiverOrBroadcast(EIP_ACTION_STOP, resultCode);
    }

    /**
     * Checks the last stored status notified by ics-openvpn
     * Sends <code>Activity.RESULT_CANCELED</code> to the ResultReceiver that made the
     * request if it's not connected, <code>Activity.RESULT_OK</code> otherwise.
     */
    private void isRunning() {
        EipStatus eipStatus = EipStatus.getInstance();
        int resultCode = (eipStatus.isConnected()) ?
                RESULT_OK :
                RESULT_CANCELED;
        tellToReceiverOrBroadcast(EIP_ACTION_IS_RUNNING, resultCode);
    }

    /**
     * read eipServiceJson from preferences and parse Gateways
     * @return GatewaysManager
     */
    private GatewaysManager gatewaysFromPreferences() {
        GatewaysManager gatewaysManager = new GatewaysManager(this, preferences);
        gatewaysManager.configureFromPreferences();
        return gatewaysManager;
    }

    /**
     * read VPN certificate from preferences and check it
     * broadcast result
     */
    private void checkVPNCertificateValidity() {
        int resultCode = isVPNCertificateValid() ?
                RESULT_OK :
                RESULT_CANCELED;
        tellToReceiverOrBroadcast(EIP_ACTION_CHECK_CERT_VALIDITY, resultCode);
    }

    /**
     * read VPN certificate from preferences and check it
     * @return true if VPN certificate is valid false otherwise
     */
    private boolean isVPNCertificateValid() {
        VpnCertificateValidator validator = new VpnCertificateValidator(preferences.getString(PROVIDER_VPN_CERTIFICATE, ""));
        return validator.isValid();
    }

    /**
     * send resultCode and resultData to receiver or
     * broadcast the result if no receiver is defined
     * @param action the action that has been performed
     * @param resultCode RESULT_OK if action was successful RESULT_CANCELED otherwise
     * @param resultData other data to broadcast or return to receiver
     */
    private void tellToReceiverOrBroadcast(String action, int resultCode, Bundle resultData) {
        resultData.putString(EIP_REQUEST, action);
        if (mReceiverRef.get() != null) {
            mReceiverRef.get().send(resultCode, resultData);
        } else {
            broadcastEvent(resultCode, resultData);
        }
    }

    /**
     * send resultCode and resultData to receiver or
     * broadcast the result if no receiver is defined
     * @param action the action that has been performed
     * @param resultCode RESULT_OK if action was successful RESULT_CANCELED otherwise
     */
    private void tellToReceiverOrBroadcast(String action, int resultCode) {
        tellToReceiverOrBroadcast(action, resultCode, new Bundle());
    }

    /**
     * broadcast resul
     * @param resultCode RESULT_OK if action was successful RESULT_CANCELED otherwise
     * @param resultData other data to broadcast or return to receiver
     */
    private void broadcastEvent(int resultCode , Bundle resultData) {
        Intent intentUpdate = new Intent(BROADCAST_EIP_EVENT);
        intentUpdate.addCategory(CATEGORY_DEFAULT);
        intentUpdate.putExtra(BROADCAST_RESULT_CODE, resultCode);
        intentUpdate.putExtra(BROADCAST_RESULT_KEY, resultData);
        Log.d(TAG, "sending broadcast");
        LocalBroadcastManager.getInstance(this).sendBroadcast(intentUpdate);
    }


    /**
     * helper function to add error to result bundle
     * @param result - result of an action
     * @param errorMessageId - id of string resource describing the error
     * @param errorId - MainActivityErrorDialog DownloadError id
     */
    void setErrorResult(Bundle result, @StringRes int errorMessageId, String errorId) {
        JSONObject errorJson = new JSONObject();
        try {
            errorJson.put(ERRORS, getResources().getString(errorMessageId));
            errorJson.put(ERROR_ID, errorId);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        result.putString(ERRORS, errorJson.toString());
        result.putBoolean(BROADCAST_RESULT_KEY, false);
    }


    /**
     * disable startup on restart
     * then stop VPN
     */
    private void stop() {
        preferences.edit().putBoolean(EIP_RESTART_ON_BOOT, false).apply();
        if (eipStatus.isBlockingVpnEstablished()) {
            stopBlockingVpn();
        }
        disconnect();
    }

    /**
     * stop void vpn from blocking internet
     */
    private void stopBlockingVpn() {
        Log.d(TAG, "stop VoidVpn!");
        Intent stopVoidVpnIntent = new Intent(this, VoidVpnService.class);
        stopVoidVpnIntent.setAction(EIP_ACTION_STOP_BLOCKING_VPN);
        startService(stopVoidVpnIntent);
    }


    /**
     * check if OpenVPNConnection is connected
     * then terminate VPN connection
     */
    private void disconnect() {
        // if OpenVPNConnection is not connected remember to disconnect
        // after connection is established
        if (mService == null) {
            disconnectOnConnect.set(true);
            return;
        }

        ProfileManager.setConntectedVpnProfileDisconnected(this);
        if (mService != null) {
            try {
                mService.stopVPN(false);
            } catch (RemoteException e) {
                VpnStatus.logException(e);
            }
        }
    }

    /**
     * bind OpenVPNService to openVPNConnection
     */
    private void bindOpenVpnService() {
        Intent intent = new Intent(this, OpenVPNService.class);
        intent.setAction(OpenVPNService.START_SERVICE);
        bindService(intent, openVpnConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * Thread to handle the requests to this service
     */
    class EipThread extends Thread {
        EipThread(EIP eipReference, String action, Intent startIntent) {
            super(new EipRunnable(eipReference, action, startIntent));
        }
    }

    /**
     * Runnable to be used in the EipThread class
     */
    class EipRunnable implements Runnable {
        private String action;
        private EIP eipReference;
        private Intent intent;

        EipRunnable(EIP eipReference, String action, Intent startIntent){
            super();
            this.action = action;
            this.eipReference = eipReference;
            this.intent = startIntent;
        }

        /**
         * select correct function to call based upon transmitted action
         * after completing check if no other thread is running
         * if no other thread is running terminate the EIP service
         */
        @Override
        public void run() {
            switch (action) {
                case EIP_ACTION_START:
                    boolean earlyRoutes = intent.getBooleanExtra(EIP_EARLY_ROUTES, true);
                    startEIP(earlyRoutes);
                    break;
                case EIP_ACTION_START_ALWAYS_ON_VPN:
                    startEIPAlwaysOnVpn();
                    break;
                case EIP_ACTION_STOP:
                    stopEIP();
                    break;
                case EIP_ACTION_IS_RUNNING:
                    isRunning();
                    break;
                case EIP_ACTION_CHECK_CERT_VALIDITY:
                    checkVPNCertificateValidity();
                    break;
            }
            if (processCounter.decrementAndGet() == 0) {
                eipReference.stopSelf();
            }
        }
    }
}
