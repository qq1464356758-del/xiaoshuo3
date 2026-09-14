package com.novelgithub.exporter;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText repoInput;
    private Button exportButton;
    private Button pasteButton;
    private Button openDownloadsButton;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView logText;
    private Uri lastExportUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(22), pad, dp(18));
        root.setBackgroundColor(0xFFFDFBFF);

        TextView title = new TextView(this);
        title.setText("小说 GitHub 一键导出器");
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1D1B20);
        root.addView(title, matchWrap());

        TextView sub = new TextView(this);
        sub.setText("粘贴公开 GitHub 小说仓库链接，一键备份完整仓库 + LOCK 原样合并正文。\n导出位置：下载/小说GitHub导出/");
        sub.setTextSize(14);
        sub.setTextColor(0xFF49454F);
        sub.setPadding(0, dp(8), 0, dp(18));
        root.addView(sub, matchWrap());

        repoInput = new EditText(this);
        repoInput.setHint("https://github.com/你的账号/你的仓库");
        repoInput.setSingleLine(true);
        repoInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        repoInput.setText("https://github.com/qq1464356758-del/xiaoshuo3");
        repoInput.setTextSize(15);
        root.addView(repoInput, new LinearLayout.LayoutParams(-1, dp(58)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(8));

        pasteButton = new Button(this);
        pasteButton.setText("粘贴链接");
        row.addView(pasteButton, new LinearLayout.LayoutParams(0, dp(52), 1));

        exportButton = new Button(this);
        exportButton.setText("一键导出 ZIP");
        LinearLayout.LayoutParams exportLp = new LinearLayout.LayoutParams(0, dp(52), 2);
        exportLp.setMargins(dp(8), 0, 0, 0);
        row.addView(exportButton, exportLp);
        root.addView(row, matchWrap());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        root.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(8)));

        statusText = new TextView(this);
        statusText.setText("准备就绪");
        statusText.setTextSize(15);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setTextColor(0xFF1D1B20);
        statusText.setPadding(0, dp(14), 0, dp(6));
        root.addView(statusText, matchWrap());

        openDownloadsButton = new Button(this);
        openDownloadsButton.setText("分享已导出的 ZIP");
        openDownloadsButton.setEnabled(false);
        root.addView(openDownloadsButton, new LinearLayout.LayoutParams(-1, dp(50)));

        TextView logTitle = new TextView(this);
        logTitle.setText("处理记录");
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setTextSize(14);
        logTitle.setPadding(0, dp(14), 0, dp(4));
        root.addView(logTitle, matchWrap());

        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTextColor(0xFF49454F);
        logText.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logText, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView foot = new TextView(this);
        foot.setText("仅读取公开仓库，不上传、不修改 GitHub。LOCK 合并不添加标题、分隔符或额外换行。\n私有仓库暂不支持。仅 Android 10 及以上。");
        foot.setTextSize(11);
        foot.setTextColor(0xFF79747E);
        foot.setGravity(Gravity.CENTER_HORIZONTAL);
        foot.setPadding(0, dp(10), 0, 0);
        root.addView(foot, matchWrap());

        setContentView(root);

        pasteButton.setOnClickListener(v -> pasteClipboard());
        exportButton.setOnClickListener(v -> startExport());
        openDownloadsButton.setOnClickListener(v -> shareLastExport());
    }

    private void pasteClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData data = cm.getPrimaryClip();
            if (data != null && data.getItemCount() > 0) {
                CharSequence text = data.getItemAt(0).coerceToText(this);
                if (text != null) repoInput.setText(text.toString().trim());
            }
        }
    }

    private void startExport() {
        String url = repoInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "请先粘贴 GitHub 仓库链接", Toast.LENGTH_SHORT).show();
            return;
        }
        exportButton.setEnabled(false);
        pasteButton.setEnabled(false);
        openDownloadsButton.setEnabled(false);
        progressBar.setProgress(0);
        statusText.setText("正在准备……");
        logText.setText("");
        lastExportUri = null;

        executor.execute(() -> {
            try {
                GitHubExporter.Result result = GitHubExporter.export(this, url, (percent, message) -> runOnUiThread(() -> {
                    progressBar.setProgress(percent);
                    statusText.setText(message);
                    logText.append("• " + message + "\n");
                }));
                runOnUiThread(() -> {
                    lastExportUri = result.uri;
                    progressBar.setProgress(100);
                    statusText.setText("导出完成 ✅");
                    logText.append("\n文件：" + result.fileName + "\n");
                    logText.append("分支：" + result.branch + "\n");
                    logText.append("仓库文件：" + result.repositoryFileCount + "\n");
                    logText.append("正式 LOCK：" + result.lockCount + " 章\n");
                    logText.append("保存：下载/小说GitHub导出/\n");
                    exportButton.setEnabled(true);
                    pasteButton.setEnabled(true);
                    openDownloadsButton.setEnabled(true);
                    Toast.makeText(this, "ZIP 已保存到下载目录", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setProgress(0);
                    statusText.setText("导出失败");
                    logText.append("\n错误：" + e.getMessage() + "\n");
                    exportButton.setEnabled(true);
                    pasteButton.setEnabled(true);
                    openDownloadsButton.setEnabled(false);
                });
            }
        });
    }

    private void shareLastExport() {
        if (lastExportUri == null) {
            Intent downloads = new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
            startActivity(downloads);
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/zip");
        share.putExtra(Intent.EXTRA_STREAM, lastExportUri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "分享导出的 ZIP"));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
