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

import android.app.Activity;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import de.blinkt.openvpn.LaunchVPN;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.Connection;
import de.blinkt.openvpn.core.ProfileManager;
import se.leap.bitmaskclient.Dashboard;
import se.leap.bitmaskclient.EipFragment;

import static se.leap.bitmaskclient.eip.Constants.ACTION_CHECK_CERT_VALIDITY;
import static se.leap.bitmaskclient.eip.Constants.ACTION_IS_EIP_RUNNING;
import static se.leap.bitmaskclient.eip.Constants.ACTION_START_EIP;
import static se.leap.bitmaskclient.eip.Constants.ACTION_STOP_EIP;
import static se.leap.bitmaskclient.eip.Constants.ACTION_UPDATE_EIP_SERVICE;
import static se.leap.bitmaskclient.eip.Constants.CERTIFICATE;
import static se.leap.bitmaskclient.eip.Constants.KEY;
import static se.leap.bitmaskclient.eip.Constants.RECEIVER_TAG;
import static se.leap.bitmaskclient.eip.Constants.REQUEST_TAG;

/**
 * EIP is the abstract base class for interacting with and managing the Encrypted
 * Internet Proxy connection.  Connections are started, stopped, and queried through
 * this IntentService.
 * Contains logic for parsing eip-service.json from the provider, configuring and selecting
 * gateways, and controlling {@link de.blinkt.openvpn.core.OpenVPNService} connections.
 * 
 * @author Sean Leonard <meanderingcode@aetherislands.net>
 * @author Parménides GV <parmegv@sdf.org>
 */
public final class EIP extends IntentService {

    public final static String TAG = EIP.class.getSimpleName();
    public final static String SERVICE_API_PATH = "config/eip-service.json";

    public static final int DISCONNECT = 15;
    
    private static Context context;
    private static ResultReceiver mReceiver;
    private static SharedPreferences preferences;
	
    private static JSONObject eip_definition;
    private static List<Gateway> gateways = new ArrayList<>();
    private static ProfileManager profile_manager;
    private static Gateway gateway;
    
	public EIP(){
		super(TAG);
	}
	
	@Override
	public void onCreate() {
		super.onCreate();
		
		context = getApplicationContext();
		profile_manager = ProfileManager.getInstance(context);

		preferences = getSharedPreferences(Dashboard.SHARED_PREFERENCES, MODE_PRIVATE);
		refreshEipDefinition();
	}
	
    @Override
    protected void onHandleIntent(Intent intent) {
	String action = intent.getAction();
	mReceiver = intent.getParcelableExtra(RECEIVER_TAG);
	
	if ( action.equals(ACTION_START_EIP))
	    startEIP();
	else if (action.equals(ACTION_STOP_EIP))
	    stopEIP();
	else if (action.equals(ACTION_IS_EIP_RUNNING))
	    isRunning();
        else if (action.equals(ACTION_UPDATE_EIP_SERVICE))
	    updateEIPService();
	else if (action.equals(ACTION_CHECK_CERT_VALIDITY))
	    checkCertValidity();
    }
	
    /**
     * Initiates an EIP connection by selecting a gateway and preparing and sending an
     * Intent to {@link de.blinkt.openvpn.LaunchVPN}.
     * It also sets up early routes.
     */
    private void startEIP() {
	if(gateways.isEmpty())
	    updateEIPService();
        earlyRoutes();

        GatewaySelector gateway_selector = new GatewaySelector(gateways);
	gateway = gateway_selector.select();
	if(gateway != null && gateway.getProfile() != null) {
	    mReceiver = EipFragment.getReceiver();
	    launchActiveGateway();
	}
	tellToReceiver(ACTION_START_EIP, Activity.RESULT_OK);
    }

    /**
     * Early routes are routes that block traffic until a new
     * VpnService is started properly.
     */
    private void earlyRoutes() {
	Intent void_vpn_launcher = new Intent(context, VoidVpnLauncher.class);
	void_vpn_launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
	startActivity(void_vpn_launcher);
    }
    
    private void launchActiveGateway() {
	Intent intent = new Intent(this,LaunchVPN.class);
	intent.setAction(Intent.ACTION_MAIN);
	intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
	intent.putExtra(LaunchVPN.EXTRA_NAME, gateway.getProfile().getName());
	intent.putExtra(LaunchVPN.EXTRA_HIDELOG, true);
	startActivity(intent);
    }

    private void stopEIP() {
	EipStatus eip_status = EipStatus.getInstance();
	Log.d(TAG, "stopEip(): eip is connected? " + eip_status.isConnected());
	int result_code = Activity.RESULT_CANCELED;
	if(eip_status.isConnected() || eip_status.isConnecting())
	    result_code = Activity.RESULT_OK;

	tellToReceiver(ACTION_STOP_EIP, result_code);
    }
	
    /**
     * Checks the last stored status notified by ics-openvpn
     * Sends <code>Activity.RESULT_CANCELED</code> to the ResultReceiver that made the
     * request if it's not connected, <code>Activity.RESULT_OK</code> otherwise.
     */
    private void isRunning() {
	EipStatus eip_status = EipStatus.getInstance();
	int resultCode = (eip_status.isConnected()) ?
	    Activity.RESULT_OK :
	    Activity.RESULT_CANCELED;
	tellToReceiver(ACTION_IS_EIP_RUNNING, resultCode);
    }

    /**
     * Loads eip-service.json from SharedPreferences, delete previous vpn profiles and add new gateways.
     * TODO Implement API call to refresh eip-service.json from the provider
     */
    private void updateEIPService() {
	refreshEipDefinition();
        if(eip_definition != null)
            updateGateways();
	tellToReceiver(ACTION_UPDATE_EIP_SERVICE, Activity.RESULT_OK);
    }

    private void refreshEipDefinition() {
	try {
	    String eip_definition_string = preferences.getString(KEY, "");
	    if(!eip_definition_string.isEmpty()) {
		eip_definition = new JSONObject(eip_definition_string);
	    }
	} catch (JSONException e) {
	    // TODO Auto-generated catch block
	    e.printStackTrace();
	}
    }
	
    /**
     * Walk the list of gateways defined in eip-service.json and parse them into
     * Gateway objects.
     * TODO Store the Gateways (as Serializable) in SharedPreferences
     */
    private void updateGateways(){
	try {
            JSONArray gatewaysDefined = eip_definition.getJSONArray("gateways");
            for (int i = 0; i < gatewaysDefined.length(); i++) {
                JSONObject gw = gatewaysDefined.getJSONObject(i);
                if (isOpenVpnGateway(gw)) {
                    Gateway gateway = new Gateway(eip_definition, context, gw);
                    if(!gateways.contains(gateway)) {
                        addGateway(gateway);
                    }
                }
            }
	} catch (JSONException e) {
	    // TODO Auto-generated catch block
	    e.printStackTrace();
	}
    }

    private boolean isOpenVpnGateway(JSONObject gateway) {
	try {
	    String transport = gateway.getJSONObject("capabilities").getJSONArray("transport").toString();
	    return transport.contains("openvpn");
	} catch (JSONException e) {
	    return false;
	}
    }

    private void addGateway(Gateway gateway) {
        VpnProfile profile = gateway.getProfile();
        removeGateway(gateway);

	profile_manager.addProfile(profile);
        profile_manager.saveProfile(context, profile);
        profile_manager.saveProfileList(context);

	gateways.add(gateway);
        Log.d(TAG, "Gateway added: " + gateway.getProfile().getUUIDString());
    }

    private void removeGateway(Gateway gateway) {
        VpnProfile profile = gateway.getProfile();
        removeDuplicatedProfile(profile);
        removeDuplicatedGateway(profile);
    }

    private void removeDuplicatedProfile(VpnProfile remove) {
        if(containsProfile(remove))
            profile_manager.removeProfile(context, duplicatedProfile(remove));
        if(containsProfile(remove)) removeDuplicatedProfile(remove);
    }

    private boolean containsProfile(VpnProfile profile) {
        Collection<VpnProfile> profiles = profile_manager.getProfiles();
        for(VpnProfile aux : profiles) {
            if (sameConnections(profile.mConnections, aux.mConnections)) {
                return true;
            }
        }
        return false;
    }

    private VpnProfile duplicatedProfile(VpnProfile profile) {
        VpnProfile duplicated = null;
        Collection<VpnProfile> profiles = profile_manager.getProfiles();
        for(VpnProfile aux : profiles) {
            if (sameConnections(profile.mConnections, aux.mConnections)) {
                duplicated = aux;
            }
        }
        if(duplicated != null) return duplicated;
        else throw new NoSuchElementException(profile.getName());
    }

    private boolean sameConnections(Connection[] c1, Connection[] c2) {
        int same_connections = 0;
        for(Connection c1_aux : c1) {
            for(Connection c2_aux : c2)
                if(c2_aux.mServerName.equals(c1_aux.mServerName)) {
                    same_connections++;
                    break;
                }
        }
        return c1.length == c2.length && c1.length == same_connections;

    }

    private void removeDuplicatedGateway(VpnProfile profile) {
        Iterator<Gateway> it = gateways.iterator();
        List<Gateway> gateways_to_remove = new ArrayList<>();
        while(it.hasNext()) {
            Gateway aux = it.next();
            if(aux.getProfile().mConnections == profile.mConnections)
                gateways_to_remove.add(aux);
        }
        gateways.removeAll(gateways_to_remove);
    }

    private void checkCertValidity() {
	VpnCertificateValidator validator = new VpnCertificateValidator();
	int resultCode = validator.isValid(preferences.getString(CERTIFICATE, "")) ?
	    Activity.RESULT_OK :
	    Activity.RESULT_CANCELED;
	tellToReceiver(ACTION_CHECK_CERT_VALIDITY, resultCode);
    }

    private void tellToReceiver(String action, int resultCode) {
        if (mReceiver != null){
            Bundle resultData = new Bundle();
            resultData.putString(REQUEST_TAG, action);
            mReceiver.send(resultCode, resultData);
        }
    }
}
