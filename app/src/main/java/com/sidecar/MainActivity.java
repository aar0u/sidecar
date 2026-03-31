package com.sidecar;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.sidecar.core.ConfigManager;
import com.sidecar.core.ProcessManager;
import com.sidecar.model.ServiceConfig;
import com.sidecar.ui.WebViewActivity;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private LinearLayout servicesContainer;
    private ProgressBar loadingIndicator;
    private Button btnRefreshConfig;

    private List<ServiceConfig> currentServices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        servicesContainer = findViewById(R.id.servicesContainer);
        loadingIndicator = findViewById(R.id.loadingIndicator);
        btnRefreshConfig = findViewById(R.id.btnRefreshConfig);

        btnRefreshConfig.setOnClickListener(v -> refreshRemoteConfig(true));

        // 1. Initial display with cached or default config
        currentServices = ConfigManager.getCachedOrFallback(this);
        renderServices();

        // 2. Fetch remote update
        refreshRemoteConfig(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderServices();
    }

    private void refreshRemoteConfig(boolean force) {
        loadingIndicator.setVisibility(View.VISIBLE);
        ConfigManager.loadServices(this, force, new ConfigManager.Callback() {
            @Override
            public void onSuccess(List<ServiceConfig> services) {
                loadingIndicator.setVisibility(View.GONE);
                currentServices = services;
                renderServices();
                if (force) {
                    Toast.makeText(MainActivity.this, "Config updated (" + services.size() + " services)", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message, List<ServiceConfig> fallbackServices) {
                loadingIndicator.setVisibility(View.GONE);
                currentServices = fallbackServices;
                renderServices();
                if (force) {
                    Toast.makeText(MainActivity.this, "Update failed: " + message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void renderServices() {
        servicesContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (ServiceConfig service : currentServices) {
            View card = inflater.inflate(R.layout.item_service_card, servicesContainer, false);
            bindServiceCard(card, service);
            servicesContainer.addView(card);
        }
    }

    private void bindServiceCard(View card, ServiceConfig service) {
        TextView tvName = card.findViewById(R.id.tvName);
        TextView tvStatus = card.findViewById(R.id.tvStatus);
        TextView tvDesc = card.findViewById(R.id.tvDesc);
        TextView tvMeta = card.findViewById(R.id.tvMeta);
        Button btnStart = card.findViewById(R.id.btnStart);
        Button btnOpen = card.findViewById(R.id.btnOpen);
        Button btnStop = card.findViewById(R.id.btnStop);

        tvName.setText(service.getName());
        tvDesc.setText(service.getDescription() != null && !service.getDescription().isEmpty()
                ? service.getDescription()
                : "No description");
        tvMeta.setText("Port: " + service.getPort() + " | Binary: " + service.getBinaryName());

        boolean isRunning = ProcessManager.isRunning(service.getId());

        if (isRunning) {
            tvStatus.setText("RUNNING");
            tvStatus.setBackgroundResource(R.drawable.bg_status_running);
            tvStatus.setTextColor(Color.parseColor("#34C759"));

            btnStart.setVisibility(View.GONE);
            btnOpen.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.VISIBLE);
        } else {
            tvStatus.setText("STOPPED");
            tvStatus.setBackgroundResource(R.drawable.bg_status_stopped);
            tvStatus.setTextColor(Color.parseColor("#8E8E93"));

            btnStart.setVisibility(View.VISIBLE);
            btnStart.setEnabled(true);
            btnStart.setText("Launch");
            btnOpen.setVisibility(View.GONE);
            btnStop.setVisibility(View.GONE);
        }

        btnOpen.setOnClickListener(v -> {
            startActivity(WebViewActivity.createIntent(
                    MainActivity.this,
                    service.getUrl(),
                    service.getName(),
                    service.isKeepScreenOn()
            ));
        });

        btnStop.setOnClickListener(v -> {
            ProcessManager.stop(service.getId());
            bindServiceCard(card, service);
        });

        btnStart.setOnClickListener(v -> {
            tvStatus.setText("STARTING…");
            tvStatus.setBackgroundResource(R.drawable.bg_status_starting);
            tvStatus.setTextColor(Color.parseColor("#FF9500"));
            btnStart.setEnabled(false);

            ProcessManager.start(MainActivity.this, service, new ProcessManager.Callback() {
                @Override
                public void onProgress(String message) {
                    tvStatus.setText(message);
                }

                @Override
                public void onReady(String url) {
                    bindServiceCard(card, service);
                    startActivity(WebViewActivity.createIntent(
                            MainActivity.this,
                            url,
                            service.getName(),
                            service.isKeepScreenOn()
                    ));
                }

                @Override
                public void onError(String message) {
                    Toast.makeText(MainActivity.this, "Error: " + message, Toast.LENGTH_LONG).show();
                    bindServiceCard(card, service);
                }

                @Override
                public void onStopped() {
                    bindServiceCard(card, service);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
