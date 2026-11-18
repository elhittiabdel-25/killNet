package com.coupenet;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.coupenet.adb.AdbManager;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_VPN = 4243;
    private static final int REQ_NOTIF = 4244;

    private static final int GREEN = 0xFFE3F5E6, YELLOW = 0xFFFFF4D6, RED = 0xFFFDE2E1, GREY = 0xFFF1F3F4;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private RadioButton modeVpn, modeSystem;
    private TextView statusText;
    private Button btn1, btn2, btn3;
    private EditText search;
    private ProgressBar progress;
    private TextView counter;

    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private Set<String> blocked;
    private AppAdapter adapter;
    private Firewall.Backend backend = Firewall.Backend.NONE;
    private boolean pairingMessageShown = false;
    private boolean busy = false;
    private boolean tcpOpen = false;

    static final class AppEntry {
        String pkg;
        String label;
        Drawable icon;
        boolean system;
    }

    // ---------- Cycle de vie ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.init(this);

        modeVpn = findViewById(R.id.mode_vpn);
        modeSystem = findViewById(R.id.mode_system);
        statusText = findViewById(R.id.status_text);
        btn1 = findViewById(R.id.btn1);
        btn2 = findViewById(R.id.btn2);
        btn3 = findViewById(R.id.btn3);
        search = findViewById(R.id.search);
        ListView list = findViewById(R.id.list);
        progress = findViewById(R.id.progress);
        counter = findViewById(R.id.counter);

        blocked = Store.blocked(this);
        adapter = new AppAdapter();
        list.setAdapter(adapter);

        (Store.systemMode(this) ? modeSystem : modeVpn).setChecked(true);
        ((RadioGroup) findViewById(R.id.mode_group)).setOnCheckedChangeListener((g, id) ->
                switchMode(id == R.id.mode_system ? Store.MODE_SYSTEM : Store.MODE_VPN));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        FirewallService.setListener(this::onPairingState);
        refreshStatus();
    }

    @Override
    protected void onPause() {
        FirewallService.setListener(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                if (!Store.systemMode(this)) BlockVpnService.refresh(this);
            } else {
                toast(getString(R.string.toast_vpn_refused));
            }
            ui.postDelayed(this::refreshStatus, 400);
        }
    }

    // ---------- Menu ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        menu.findItem(R.id.menu_system).setChecked(Store.showSystem(this));
        menu.findItem(R.id.menu_auto_disable).setChecked(Store.autoDisableWifiDebug(this));
        menu.findItem(R.id.menu_fallback).setChecked(Store.vpnFallback(this));
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean sys = Store.systemMode(this);
        menu.findItem(R.id.menu_auto_disable).setVisible(sys);
        menu.findItem(R.id.menu_fallback).setVisible(sys);
        menu.findItem(R.id.menu_repair).setVisible(sys && AdbManager.supported());
        menu.findItem(R.id.menu_reapply).setVisible(sys);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_system) {
            boolean v = !item.isChecked();
            item.setChecked(v);
            Store.setShowSystem(this, v);
            applyFilter();
        } else if (id == R.id.menu_reapply) {
            reapply(true);
        } else if (id == R.id.menu_unblock_all) {
            confirmUnblockAll();
        } else if (id == R.id.menu_auto_disable) {
            boolean v = !item.isChecked();
            item.setChecked(v);
            Store.setAutoDisableWifiDebug(this, v);
        } else if (id == R.id.menu_fallback) {
            boolean v = !item.isChecked();
            item.setChecked(v);
            Store.setVpnFallback(this, v);
            if (v && !otherVpnActive()) askVpnPermissionIfNeeded();
        } else if (id == R.id.menu_repair) {
            startPairing();
        } else if (id == R.id.menu_log) {
            showLog();
        } else if (id == R.id.menu_help) {
            showText(R.string.menu_help, getString(R.string.help_text));
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    // ---------- Mode ----------

    private void switchMode(String mode) {
        if (mode.equals(Store.mode(this))) return;
        final Set<String> current = Store.blocked(this);
        Store.setMode(this, mode);
        invalidateOptionsMenu();
        if (Store.MODE_VPN.equals(mode)) {
            // On retire les règles du pare-feu système, le VPN prend le relais.
            worker.execute(() -> {
                if (!current.isEmpty() && Firewall.backend(this) != Firewall.Backend.NONE) {
                    Firewall.unblockAll(this);
                }
            });
            if (!current.isEmpty() && !askVpnPermissionIfNeeded()) BlockVpnService.refresh(this);
        } else {
            BlockVpnService.stop(this);
        }
        refreshStatus();
    }

    // ---------- Statut ----------

    private void refreshStatus() {
        blocked = Store.blocked(this);
        applyFilter();
        if (!Store.systemMode(this)) {
            renderVpnStatus();
            return;
        }
        worker.execute(() -> {
            final Firewall.Backend b = Firewall.backend(this);
            final boolean tcp = b == Firewall.Backend.ADB && AdbManager.tcpPortOpen();
            ui.post(() -> {
                Firewall.Backend previous = backend;
                backend = b;
                tcpOpen = tcp;
                if (Store.systemMode(this)) renderSystemStatus();
                if (b != Firewall.Backend.NONE && previous == Firewall.Backend.NONE) reapply(false);
            });
        });
    }

    private void renderVpnStatus() {
        hideButtons();
        boolean needPermission = VpnService.prepare(this) != null;
        if (blocked.isEmpty()) {
            setStatus(getString(R.string.status_vpn_empty), GREY);
        } else if (!BlockVpnService.isRunning() && otherVpnActive()) {
            setStatus(getString(R.string.status_vpn_conflict), RED);
            showButton(btn1, R.string.btn_switch_system, v -> modeSystem.setChecked(true));
        } else if (needPermission) {
            setStatus(getString(R.string.status_vpn_permission), YELLOW);
            showButton(btn1, R.string.btn_allow_vpn, v -> askVpnPermissionIfNeeded());
        } else if (BlockVpnService.isRunning()) {
            setStatus(getString(R.string.status_vpn_on, blocked.size()), GREEN);
            showButton(btn2, R.string.btn_always_on, v -> openVpnSettings());
        } else {
            setStatus(getString(R.string.status_vpn_off), RED);
            showButton(btn1, R.string.btn_start_vpn, v -> {
                BlockVpnService.refresh(this);
                ui.postDelayed(this::refreshStatus, 600);
            });
        }
    }

    private void renderSystemStatus() {
        hideButtons();
        if (backend == Firewall.Backend.ROOT) {
            setStatus(getString(R.string.status_root_ok), GREEN);
        } else if (!AdbManager.supported()) {
            setStatus(getString(R.string.status_old_android), RED);
        } else if (backend == Firewall.Backend.ADB) {
            boolean auto = AdbManager.canToggleWirelessDebugging(this);
            String st = getString(auto ? R.string.status_adb_ok : R.string.status_adb_ok_manual)
                    + "\n\n" + getString(tcpOpen ? R.string.status_tcp_on : R.string.status_tcp_off);
            setStatus(st, tcpOpen ? GREEN : YELLOW);
            if (!tcpOpen) showButton(btn1, R.string.btn_enable_tcp, v -> enableTcp());
            showButton(btn2, R.string.btn_test, v -> reapply(true));
            if (!auto) showButton(btn3, R.string.btn_open_wireless, v -> openWirelessDebugging());
        } else {
            setStatus(getString(R.string.status_need_pairing), YELLOW);
            showButton(btn1, R.string.btn_pair, v -> startPairing());
            showButton(btn2, R.string.btn_pair_manual, v -> manualPairDialog());
            showButton(btn3, R.string.btn_pair_help, v -> showText(R.string.btn_pair_help, getString(R.string.pair_help_text)));
        }
    }

    private void setStatus(String text, int color) {
        statusText.setText(text);
        statusText.setBackgroundColor(color);
    }

    private void hideButtons() {
        btn1.setVisibility(View.GONE);
        btn2.setVisibility(View.GONE);
        btn3.setVisibility(View.GONE);
    }

    private void showButton(Button b, int text, View.OnClickListener l) {
        b.setText(text);
        b.setOnClickListener(l);
        b.setVisibility(View.VISIBLE);
    }

    // ---------- Mode 4G (port adb TCP local) ----------

    private void enableTcp() {
        if (busy) { toast(getString(R.string.toast_busy)); return; }
        busy = true;
        progress.setVisibility(View.VISIBLE);
        setStatus(getString(R.string.tcp_in_progress), YELLOW);
        worker.execute(() -> {
            String err = null;
            try {
                AdbManager.enableTcpMode(this);
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String error = err;
            ui.post(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                if (error == null) {
                    showText(R.string.tcp_ok_title, getString(R.string.tcp_ok_text));
                } else {
                    showError(error);
                }
                refreshStatus();
            });
        });
    }

    // ---------- Appairage ----------

    private void startPairing() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        pairingMessageShown = false;
        FirewallService.startPairing(this);
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_pair)
                .setMessage(R.string.pair_steps)
                .setPositiveButton(R.string.btn_open_wireless, (d, w) -> openWirelessDebugging())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIF) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startPairing();
            } else {
                toast(getString(R.string.toast_notif_needed));
                manualPairDialog();
            }
        }
    }

    private void manualPairDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView info = new TextView(this);
        info.setText(R.string.manual_pair_info);
        final EditText port = new EditText(this);
        port.setHint(R.string.manual_pair_port);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText code = new EditText(this);
        code.setHint(R.string.manual_pair_code);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(info);
        box.addView(port);
        box.addView(code);
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_pair_manual)
                .setView(box)
                .setNeutralButton(R.string.btn_open_wireless, (d, w) -> openWirelessDebugging())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.btn_pair_go, (d, w) -> {
                    String p = port.getText().toString().trim();
                    // On accepte aussi « 192.168.1.20:37123 ».
                    if (p.contains(":")) p = p.substring(p.lastIndexOf(':') + 1);
                    int portNum;
                    try { portNum = Integer.parseInt(p); } catch (Exception e) { portNum = -1; }
                    String c = code.getText().toString().trim();
                    if (c.isEmpty()) { toast(getString(R.string.manual_pair_missing)); return; }
                    pairingMessageShown = false;
                    FirewallService.submitCode(this, portNum, c);
                    setStatus(getString(R.string.pair_in_progress), YELLOW);
                })
                .show();
    }

    private void onPairingState(String message, boolean finished, boolean success) {
        if (!Store.systemMode(this)) return;
        if (finished && success) {
            backend = Firewall.Backend.NONE;
            refreshStatus();
            if (!pairingMessageShown) {
                pairingMessageShown = true;
                showText(R.string.pair_success_title, getString(R.string.pair_success_long));
            }
        } else if (backend == Firewall.Backend.NONE) {
            setStatus(message, YELLOW);
        }
    }

    private void openWirelessDebugging() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", "toggle_adb_wireless");
        i.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
        i.putExtra(":settings:show_fragment_args", args);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            toast(getString(R.string.toast_find_wireless));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
                toast(getString(R.string.toast_enable_dev_options));
            } catch (Exception ignored) {
                toast(getString(R.string.toast_enable_dev_options));
            }
        }
    }

    private void openVpnSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
            toast(getString(R.string.toast_always_on));
        } catch (Exception ignored) {
        }
    }

    /** Un autre VPN (Proton, etc.) est-il connecté en ce moment ? */
    private boolean otherVpnActive() {
        if (BlockVpnService.isRunning()) return false;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Renvoie true si une demande d'autorisation VPN (ou un avertissement) a été affichée. */
    private boolean askVpnPermissionIfNeeded() {
        Intent i = VpnService.prepare(this);
        if (i == null) return false;
        if (otherVpnActive()) {
            // Ne jamais couper le VPN de l'utilisateur sans prévenir.
            new AlertDialog.Builder(this)
                    .setTitle(R.string.vpn_conflict_title)
                    .setMessage(R.string.vpn_conflict_text)
                    .setPositiveButton(R.string.btn_switch_system, (d, w) -> modeSystem.setChecked(true))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> refreshStatus())
                    .setNeutralButton(R.string.btn_replace_vpn, (d, w) -> {
                        try { startActivityForResult(i, REQ_VPN); } catch (Exception ignored) {}
                    })
                    .show();
            return true;
        }
        try {
            startActivityForResult(i, REQ_VPN);
            return true;
        } catch (Exception e) {
            toast(e.toString());
            return false;
        }
    }

    // ---------- Liste des applications ----------

    private void loadApps() {
        progress.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> infos = pm.getInstalledApplications(0);
            List<AppEntry> result = new ArrayList<>();
            for (ApplicationInfo ai : infos) {
                if (ai.packageName.equals(getPackageName())) continue;
                // On ne garde que les apps qui peuvent aller sur internet.
                if (pm.checkPermission(Manifest.permission.INTERNET, ai.packageName)
                        != PackageManager.PERMISSION_GRANTED) continue;
                AppEntry e = new AppEntry();
                e.pkg = ai.packageName;
                e.label = String.valueOf(ai.loadLabel(pm));
                try { e.icon = ai.loadIcon(pm); } catch (Throwable ignored) {}
                e.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                result.add(e);
            }
            ui.post(() -> {
                allApps.clear();
                allApps.addAll(result);
                progress.setVisibility(View.GONE);
                applyFilter();
            });
        });
    }

    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean showSystem = Store.showSystem(this);
        shown.clear();
        for (AppEntry e : allApps) {
            boolean isBlocked = blocked.contains(e.pkg);
            if (e.system && !showSystem && !isBlocked) continue;
            if (!q.isEmpty()
                    && !e.label.toLowerCase(Locale.ROOT).contains(q)
                    && !e.pkg.toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(e);
        }
        final Collator col = Collator.getInstance();
        shown.sort((a, b) -> {
            boolean ba = blocked.contains(a.pkg), bb = blocked.contains(b.pkg);
            if (ba != bb) return ba ? -1 : 1;
            return col.compare(a.label, b.label);
        });
        adapter.notifyDataSetChanged();
        counter.setText(getString(R.string.counter, blocked.size()));
    }

    // ---------- Blocage ----------

    private void toggle(AppEntry e, boolean block) {
        if (!Store.systemMode(this)) {
            Store.setBlocked(this, e.pkg, block);
            blocked = Store.blocked(this);
            if (!askVpnPermissionIfNeeded()) BlockVpnService.refresh(this);
            toast(getString(block ? R.string.toast_blocked : R.string.toast_unblocked, e.label));
            applyFilter();
            ui.postDelayed(this::refreshStatus, 600);
            return;
        }
        if (backend == Firewall.Backend.NONE) {
            toast(getString(R.string.err_no_backend));
            adapter.notifyDataSetChanged();
            return;
        }
        if (busy) {
            toast(getString(R.string.toast_busy));
            adapter.notifyDataSetChanged();
            return;
        }
        busy = true;
        progress.setVisibility(View.VISIBLE);
        final boolean lastOne = !block && blocked.size() == 1 && blocked.contains(e.pkg);
        worker.execute(() -> {
            Firewall.Result r = block ? Firewall.block(this, e.pkg) : Firewall.unblock(this, e.pkg, lastOne);
            ui.post(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                if (r.ok) {
                    Store.setBlocked(this, e.pkg, block);
                    blocked = Store.blocked(this);
                    if (BlockVpnService.isRunning()) BlockVpnService.refresh(this);
                    toast(getString(block ? R.string.toast_blocked : R.string.toast_unblocked, e.label));
                } else {
                    showError(r.output);
                }
                applyFilter();
            });
        });
    }

    private void reapply(boolean verbose) {
        if (!Store.systemMode(this)) return;
        if (backend == Firewall.Backend.NONE) {
            if (verbose) toast(getString(R.string.err_no_backend));
            return;
        }
        if (verbose) {
            progress.setVisibility(View.VISIBLE);
            toast(getString(R.string.toast_applying));
        }
        worker.execute(() -> {
            Firewall.Result r = Store.blocked(this).isEmpty()
                    ? Firewall.unblockAll(this) : Firewall.applyAll(this);
            ui.post(() -> {
                progress.setVisibility(View.GONE);
                if (r.ok) {
                    BlockVpnService.stop(this);
                    if (verbose) toast(getString(R.string.toast_reapplied));
                } else if (verbose) {
                    showError(r.output);
                }
            });
        });
    }

    private void confirmUnblockAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_unblock_all)
                .setMessage(R.string.confirm_unblock_all)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (!Store.systemMode(this)) {
                        Store.clear(this);
                        BlockVpnService.stop(this);
                        refreshStatus();
                        return;
                    }
                    if (backend == Firewall.Backend.NONE) {
                        Store.clear(this);
                        BlockVpnService.stop(this);
                        refreshStatus();
                        return;
                    }
                    progress.setVisibility(View.VISIBLE);
                    worker.execute(() -> {
                        Firewall.Result r = Firewall.unblockAll(this);
                        ui.post(() -> {
                            progress.setVisibility(View.GONE);
                            if (r.ok) {
                                Store.clear(this);
                                BlockVpnService.stop(this);
                                refreshStatus();
                            } else {
                                showError(r.output);
                            }
                        });
                    });
                })
                .show();
    }

    private void showError(String details) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.error_title)
                .setMessage(getString(R.string.error_body, details))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showLog() {
        String text = Log.read();
        final String full = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL + "\n" + (text.isEmpty() ? "(vide)" : text);
        TextView tv = new TextView(this);
        tv.setText(full);
        tv.setTextIsSelectable(true);
        tv.setTextSize(11);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_log)
                .setView(sv)
                .setPositiveButton(R.string.btn_copy, (d, w) -> {
                    android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Coupe-Net", full));
                    toast(getString(R.string.toast_copied));
                })
                .setNeutralButton(R.string.btn_clear, (d, w) -> Log.clear())
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private void showText(int title, String text) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---------- Adaptateur ----------

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View v = convert != null ? convert
                    : LayoutInflater.from(MainActivity.this).inflate(R.layout.item_app, parent, false);
            AppEntry e = shown.get(position);
            ImageView icon = v.findViewById(R.id.icon);
            TextView label = v.findViewById(R.id.label);
            TextView pkg = v.findViewById(R.id.pkg);
            Switch sw = v.findViewById(R.id.toggle);

            boolean isBlocked = blocked.contains(e.pkg);
            icon.setImageDrawable(e.icon);
            label.setText(e.label);
            pkg.setText(isBlocked ? getString(R.string.state_blocked, e.pkg) : e.pkg);
            pkg.setTextColor(isBlocked ? 0xFFC62828 : 0xFF757575);

            sw.setOnCheckedChangeListener(null);
            sw.setChecked(isBlocked);
            sw.setOnCheckedChangeListener((b, checked) -> toggle(e, checked));
            v.setOnClickListener(x -> sw.toggle());
            return v;
        }
    }
}
