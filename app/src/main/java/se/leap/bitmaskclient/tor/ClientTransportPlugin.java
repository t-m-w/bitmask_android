package se.leap.bitmaskclient.tor;

import android.content.Context;
import android.os.FileObserver;
import android.util.Log;

import androidx.annotation.Nullable;

import org.torproject.jni.ClientTransportPluginInterface;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.HashMap;
import java.util.Scanner;
import java.util.Vector;

import IPtProxy.IPtProxy;

public class ClientTransportPlugin implements ClientTransportPluginInterface {
    public static String TAG = ClientTransportPlugin.class.getSimpleName();

    private HashMap<String, String> mFronts;
    private final WeakReference<Context> contextRef;
    private long snowflakePort = -1;
    private FileObserver logFileObserver;

    public ClientTransportPlugin(Context context) {
        this.contextRef = new WeakReference<>(context);
        loadCdnFronts(context);
    }

    @Override
    public void start() {
        Context context = contextRef.get();
        if (context == null) {
            return;
        }
        File logfile = new File(context.getApplicationContext().getCacheDir(), "snowflake.log");
        Log.d(TAG, "logfile at " + logfile.getAbsolutePath());
        try {
            if (logfile.exists()) {
                logfile.delete();
            }
            logfile.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }

        //this is using the current, default Tor snowflake infrastructure
        String target = getCdnFront("snowflake-target");
        String front = getCdnFront("snowflake-front");
        String stunServer = getCdnFront("snowflake-stun");
        Log.d(TAG, "startSnowflake. target: " + target + ", front:" + front + ", stunServer" + stunServer);
        snowflakePort = IPtProxy.startSnowflake( stunServer, target, front, logfile.getAbsolutePath(), false, false, true, 5);
        Log.d(TAG, "startSnowflake running on port: " + snowflakePort);
        watchLogFile(logfile);
    }

    private void watchLogFile(File logfile) {
        final Vector<String> lastBuffer = new Vector<>();
        logFileObserver = new FileObserver(logfile) {
            @Override
            public void onEvent(int event, @Nullable String name) {
                if (FileObserver.MODIFY == event) {
                    try (Scanner scanner = new Scanner(logfile)) {
                        Vector<String> currentBuffer = new Vector<>();
                        while (scanner.hasNextLine()) {
                            currentBuffer.add(scanner.nextLine());
                        }
                        if (lastBuffer.size() < currentBuffer.size()) {
                            int startIndex =  lastBuffer.size() > 0 ? lastBuffer.size() - 1 : 0;
                            int endIndex = currentBuffer.size() - 1;
                            Collection<String> newMessages = currentBuffer.subList(startIndex, endIndex);
                            for (String message : newMessages) {
                                Log.d("[SNOWFLAKE]",  message);
                            }
                            lastBuffer.addAll(newMessages);
                        }
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        logFileObserver.startWatching();
    }

    @Override
    public void stop() {
        IPtProxy.stopSnowflake();
        snowflakePort = -1;
        logFileObserver.stopWatching();
    }

    @Override
    public String getTorrc() {
        return "UseBridges 1\n" +
                "ClientTransportPlugin snowflake socks5 127.0.0.1:" + snowflakePort + "\n" +
                "Bridge snowflake 192.0.2.3:1";
    }

    private void loadCdnFronts(Context context) {
        if (mFronts == null) {
            mFronts = new HashMap<>();
        }
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("fronts")));
            String line;
            while (true) {
                line = reader.readLine();
                if (line == null) break;
                String[] front = line.split(" ");
                mFronts.put(front[0], front[1]);
                Log.d(TAG, "front: " + front[0] + ", " + front[1]);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Nullable
    private String getCdnFront(String service) {
        if (mFronts != null) {
            return mFronts.get(service);
        }
        return null;
    }
}
