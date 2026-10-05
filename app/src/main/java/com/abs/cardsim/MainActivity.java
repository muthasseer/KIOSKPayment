package com.abs.cardsim;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements TerminalServer.Listener {

    private static final int PORT = 9100;
    private static final int BG = Color.parseColor("#08111f");
    private static final int GREEN = Color.parseColor("#1f9d55");
    private static final int RED = Color.parseColor("#d93636");
    private static final int ORANGE = Color.parseColor("#e07b00");
    private static final int PURPLE = Color.parseColor("#7a3fd1");
    private static final int GREY = Color.parseColor("#4b5a70");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<String> logLines = new ArrayList<String>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private TerminalServer server;
    private TextView tvAddr, tvState, tvAmount, tvRef, tvLog;
    private Button bOk, bReject, bExpired, bInsufficient, bCancel;

    private final Runnable addrTicker = new Runnable() {
        @Override
        public void run() {
            tvAddr.setText(addresses());
            ui.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        setButtons(false);

        server = new TerminalServer(PORT, this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    server.start();
                } catch (final IOException e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            tvState.setText("SERVER ERROR: " + e.getMessage());
                            tvState.setTextColor(RED);
                        }
                    });
                }
            }
        }).start();
        ui.post(addrTicker);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (server != null) server.stop();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private Button button(String text, int color, final int kind) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setButtons(false);
                server.respond(kind);
            }
        });
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(28), dp(18), dp(28));
        scroll.addView(root);

        root.addView(label("ABS CARD TERMINAL SIMULATOR", 18, Color.WHITE, true));
        root.addView(label("Enter this address in kiosk_config.json", 12, Color.parseColor("#9fb3c8"), false));
        tvAddr = label(addresses(), 20, Color.parseColor("#48e0a4"), true);
        root.addView(tvAddr);

        tvState = label("READY - waiting for a sale", 16, Color.parseColor("#9fb3c8"), true);
        root.addView(tvState);
        tvAmount = label("-", 38, Color.WHITE, true);
        root.addView(tvAmount);
        tvRef = label("", 12, Color.parseColor("#9fb3c8"), false);
        root.addView(tvRef);

        bOk = button("PAYMENT SUCCESSFUL", GREEN, TerminalServer.Result.SUCCESS);
        bReject = button("CARD REJECTED", RED, TerminalServer.Result.REJECTED);
        bExpired = button("CARD EXPIRED", ORANGE, TerminalServer.Result.EXPIRED);
        bInsufficient = button("INSUFFICIENT BALANCE", PURPLE, TerminalServer.Result.INSUFFICIENT);
        bCancel = button("CANCEL SALE", GREY, TerminalServer.Result.CANCELLED);
        root.addView(bOk);
        root.addView(bReject);
        root.addView(bExpired);
        root.addView(bInsufficient);
        root.addView(bCancel);

        Switch offline = new Switch(this);
        offline.setText("Simulate terminal OFFLINE");
        offline.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        offline.setLayoutParams(lp);
        offline.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (server != null) server.setSimulateOffline(isChecked);
            }
        });
        root.addView(offline);

        tvLog = label("", 12, Color.parseColor("#9fb3c8"), false);
        tvLog.setGravity(Gravity.START);
        root.addView(tvLog);

        setContentView(scroll);
    }

    private void setButtons(boolean enabled) {
        Button[] all = {bOk, bReject, bExpired, bInsufficient, bCancel};
        for (Button b : all) {
            b.setEnabled(enabled);
            b.setAlpha(enabled ? 1f : 0.35f);
        }
    }

    private String addresses() {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en != null) {
                for (NetworkInterface ni : Collections.list(en)) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(a.getHostAddress()).append(" : ").append(PORT);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return sb.length() == 0 ? "No network - connect to Wi-Fi" : sb.toString();
    }

    // ------------------------------------------------------------------ TerminalServer.Listener (called from server threads)

    @Override
    public void onSale(final TerminalServer.Sale sale) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                tvAmount.setText(String.format(Locale.US, "Rs. %.2f", sale.amount));
                tvRef.setText("Ref: " + sale.reference);
                tvState.setText("CARD PRESENTED - choose the result");
                tvState.setTextColor(Color.parseColor("#ffb020"));
                setButtons(true);
            }
        });
    }

    @Override
    public void onSaleEnded() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                setButtons(false);
                tvAmount.setText("-");
                tvRef.setText("");
                tvState.setText("READY - waiting for a sale");
                tvState.setTextColor(Color.parseColor("#9fb3c8"));
            }
        });
    }

    @Override
    public void onLog(final String line) {
        final String stamped;
        synchronized (clock) { stamped = clock.format(new Date()) + "  " + line; }
        ui.post(new Runnable() {
            @Override
            public void run() {
                logLines.add(0, stamped);
                while (logLines.size() > 12) logLines.remove(logLines.size() - 1);
                StringBuilder sb = new StringBuilder();
                for (String s : logLines) sb.append(s).append("\n");
                tvLog.setText(sb.toString());
            }
        });
    }
}
