package com.coupenet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
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
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int REQ_SHIZUKU = 4242;
    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private Button statusButton;
    private EditText search;
    private ListView list;
    private ProgressBar progress;
    private TextView counter;

    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private Set<String> blocked;
    private AppAdapter adapter;
    private Firewall.Backend backend = Firewall.Backend.NONE;

    static final class AppEntry {
        String pkg;
        String label;
        Drawable icon;
        boolean system;
    }

    // ---------- Écouteurs Shizuku ----------

    private final Shizuku.OnBinderReceivedListener binderReceived =
            () -> ui.post(() -> refreshStatus(true));
    private final Shizuku.OnBinderDeadListener binderDead =
            () -> ui.post(() -> refreshStatus(false));
    private final Shizuku.OnRequestPermissionResultListener permResult =
            (code, result) -> ui.post(() -> refreshStatus(true));

    // ---------- Cycle de vie ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        statusButton = findViewById(R.id.status_button);
        search = findViewById(R.id.search);
        list = findViewById(R.id.list);
        progress = findViewById(R.id.progress);
        counter = findViewById(R.id.counter);

        blocked = Store.blocked(this);
        adapter = new AppAdapter();
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceived);
            Shizuku.addBinderDeadListener(binderDead);
            Shizuku.addRequestPermissionResultListener(permResult);
        } catch (Throwable ignored) {
        }

        loadApps();
        refreshStatus(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus(false);
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceived);
            Shizuku.removeBinderDeadListener(binderDead);
            Shizuku.removeRequestPermissionResultListener(permResult);
        } catch (Throwable ignored) {
        }
        worker.shutdown();
        super.onDestroy();
    }

    // ---------- Menu ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        menu.findItem(R.id.menu_system).setChecked(Store.showSystem(this));
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
            return true;
        } else if (id == R.id.menu_reapply) {
            reapply(true);
            return true;
        } else if (id == R.id.menu_unblock_all) {
            confirmUnblockAll();
            return true;
        } else if (id == R.id.menu_help) {
            showHelp();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---------- Statut du moteur (Shizuku / root) ----------

    private void refreshStatus(boolean reapplyAfter) {
        worker.execute(() -> {
            final Firewall.Backend b = Firewall.backend();
            final boolean running = Firewall.shizukuRunning();
            ui.post(() -> {
                Firewall.Backend previous = backend;
                backend = b;
                renderStatus(running);
                if (b != Firewall.Backend.NONE && (reapplyAfter || previous == Firewall.Backend.NONE)) {
                    reapply(false);
                }
            });
        });
    }

    private void renderStatus(boolean shizukuRunning) {
        statusButton.setVisibility(View.VISIBLE);
        if (backend == Firewall.Backend.SHIZUKU) {
            statusText.setText(R.string.status_shizuku_ok);
            statusText.setBackgroundColor(0xFFE3F5E6);
            statusButton.setVisibility(View.GONE);
        } else if (backend == Firewall.Backend.ROOT) {
            statusText.setText(R.string.status_root_ok);
            statusText.setBackgroundColor(0xFFE3F5E6);
            statusButton.setVisibility(View.GONE);
        } else if (shizukuRunning) {
            statusText.setText(R.string.status_need_permission);
            statusText.setBackgroundColor(0xFFFFF4D6);
            statusButton.setText(R.string.btn_grant);
            statusButton.setOnClickListener(v -> {
                try {
                    Shizuku.requestPermission(REQ_SHIZUKU);
                } catch (Throwable t) {
                    toast(t.toString());
                }
            });
        } else {
            statusText.setText(R.string.status_no_shizuku);
            statusText.setBackgroundColor(0xFFFDE2E1);
            statusButton.setText(R.string.btn_open_shizuku);
            statusButton.setOnClickListener(v -> openShizuku());
        }
    }

    private void openShizuku() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
        if (launch != null) {
            startActivity(launch);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + SHIZUKU_PKG)));
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://shizuku.rikka.app/download/")));
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
                if (pm.checkPermission("android.permission.INTERNET", ai.packageName)
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
        if (backend == Firewall.Backend.NONE) {
            toast(getString(R.string.toast_no_backend));
            adapter.notifyDataSetChanged();
            return;
        }
        final boolean lastOne = !block && blocked.size() == 1 && blocked.contains(e.pkg);
        worker.execute(() -> {
            Firewall.Result r = block ? Firewall.block(e.pkg) : Firewall.unblock(e.pkg, lastOne);
            ui.post(() -> {
                if (r.ok) {
                    Store.setBlocked(this, e.pkg, block);
                    blocked = Store.blocked(this);
                    toast(getString(block ? R.string.toast_blocked : R.string.toast_unblocked, e.label));
                } else {
                    showError(r.output);
                }
                applyFilter();
            });
        });
    }

    private void reapply(boolean verbose) {
        if (backend == Firewall.Backend.NONE) {
            if (verbose) toast(getString(R.string.toast_no_backend));
            return;
        }
        worker.execute(() -> {
            Firewall.Result r = Firewall.applyAll(this);
            ui.post(() -> {
                if (!r.ok) showError(r.output);
                else if (verbose) toast(getString(R.string.toast_reapplied));
            });
        });
    }

    private void confirmUnblockAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_unblock_all)
                .setMessage(R.string.confirm_unblock_all)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (backend == Firewall.Backend.NONE) {
                        toast(getString(R.string.toast_no_backend));
                        return;
                    }
                    worker.execute(() -> {
                        Firewall.Result r = Firewall.unblockAll(this);
                        ui.post(() -> {
                            if (r.ok) {
                                Store.clear(this);
                                blocked = Store.blocked(this);
                                applyFilter();
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

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_help)
                .setMessage(R.string.help_text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
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
