package se.leap.bitmaskclient.firewall;

interface FirewallCallback {
    void onFirewallStarted(boolean success);
    void onFirewallStopped(boolean success);
    void onSuRequested(boolean success);
}
