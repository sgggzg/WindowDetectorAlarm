package com.sgggzg.qmzg;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AboutFragment extends Fragment {

    private static final String REPO = "sgggzg/WindowDetectorAlarm";
    private static final String API_LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String HTML_LATEST = "https://github.com/" + REPO + "/releases/latest";
    private static final String RELEASE_PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final String USER_AGENT = "WindowDetector-Updater";
    private static final String PREF_NAME = "update_state";

    private static final String[] MIRROR_PREFIXES = {
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://githubproxy.cc/",
        "https://ghproxy.homeboyc.cn/"
    };

    private TextView tvCurrentVersion;
    private Button btnCheckUpdate;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean downloadPaused = false;
    private volatile boolean downloadCancelled = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_about, container, false);

        setupFold(view, R.id.tvTitle1, R.id.tvContent1);
        setupFold(view, R.id.tvTitle2, R.id.tvContent2);
        setupFold(view, R.id.tvTitle3, R.id.tvContent3);
        setupFold(view, R.id.tvTitle4, R.id.tvContent4);
        setupFold(view, R.id.tvTitle5, R.id.tvContent5);
        setupFold(view, R.id.tvTitle6, R.id.tvContent6);
        setupFold(view, R.id.tvTitle7, R.id.tvContent7);

        tvCurrentVersion = view.findViewById(R.id.tvCurrentVersion);
        btnCheckUpdate = view.findViewById(R.id.btnCheckUpdate);

        tvCurrentVersion.setText("当前版本：" + getCurrentVersionName());
        btnCheckUpdate.setOnClickListener(v -> checkUpdate());

        return view;
    }

    private String getCurrentVersionName() {
        try {
            PackageInfo info = requireActivity().getPackageManager()
                .getPackageInfo(requireActivity().getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) {
            return "未知";
        }
    }

    // ==================== 源结构 ====================
    private static class Source {
        String prefix;
        String name;

        Source(String prefix, String name) {
            this.prefix = prefix;
            this.name = name;
        }

        String buildUrl(String originalUrl) {
            return prefix + originalUrl;
        }
    }

    private Source[] getAllSources() {
        Source[] sources = new Source[1 + MIRROR_PREFIXES.length];
        sources[0] = new Source("", "官方源");
        for (int i = 0; i < MIRROR_PREFIXES.length; i++) {
            sources[i + 1] = new Source(MIRROR_PREFIXES[i],
                                        "镜像" + (i + 1) + " (" + getMirrorHost(MIRROR_PREFIXES[i]) + ")");
        }
        return sources;
    }

    // 【核心】续传时，把暂停所在的源放到列表最前
    private Source[] getSourcesForResume(String pausedPrefix) {
        Source[] all = getAllSources();
        if (pausedPrefix == null || pausedPrefix.isEmpty()) {
            return all;
        }

        Source preferred = null;
        List<Source> rest = new ArrayList<>();
        for (Source s : all) {
            if (s.prefix.equals(pausedPrefix)) {
                preferred = s;
            } else {
                rest.add(s);
            }
        }
        if (preferred == null) return all;

        Source[] result = new Source[all.length];
        result[0] = preferred;
        for (int i = 0; i < rest.size(); i++) {
            result[i + 1] = rest.get(i);
        }
        return result;
    }

    private String getSourceNameByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return "官方源";
        for (int i = 0; i < MIRROR_PREFIXES.length; i++) {
            if (MIRROR_PREFIXES[i].equals(prefix)) {
                return "镜像" + (i + 1) + " (" + getMirrorHost(prefix) + ")";
            }
        }
        return "未知源";
    }

    private String getMirrorHost(String prefix) {
        try {
            URL u = new URL(prefix);
            return u.getHost();
        } catch (Exception e) {
            return prefix;
        }
    }

    // ==================== 文件目录 ====================
    private File getUpdateDir() {
        File dir = requireActivity().getExternalFilesDir("update");
        if (dir == null) dir = new File(requireActivity().getFilesDir(), "update");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void cleanOldFiles(String keepFileName) {
        try {
            File dir = getUpdateDir();
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String name = f.getName();
                boolean isApk = name.endsWith(".apk");
                boolean isPart = name.endsWith(".apk.part");
                if (!isApk && !isPart) continue;
                if (name.equals(keepFileName)) continue;
                if (name.equals(keepFileName + ".part")) continue;
                try { f.delete(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    // ==================== 更新信息 ====================
    private static class UpdateInfo {
        String tagName;
        String version;
        String body;
        String apkUrl;
    }

    // ==================== 检查更新 ====================
    private void checkUpdate() {
        final ProgressDialog dialog = new ProgressDialog(requireActivity());
        dialog.setMessage("正在检查更新...");
        dialog.setCancelable(false);
        dialog.show();

        new Thread(new Runnable() {
                @Override
                public void run() {
                    UpdateInfo info = null;
                    String errMsg = null;

                    try {
                        info = tryApi();
                    } catch (Exception e) {
                        errMsg = "API: " + e.getMessage();
                    }

                    if (info == null) {
                        try {
                            info = tryHtml();
                        } catch (Exception e) {
                            errMsg = (errMsg == null ? "" : errMsg + " / ") + "HTML: " + e.getMessage();
                        }
                    }

                    final UpdateInfo finalInfo = info;
                    final String finalErr = errMsg;

                    mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                dialog.dismiss();
                                if (finalInfo == null) {
                                    new AlertDialog.Builder(requireActivity())
                                        .setTitle("检查失败")
                                        .setMessage("无法获取最新版本信息。\n\n" +
                                                    (finalErr == null ? "" : finalErr + "\n\n") +
                                                    "可能是网络问题或代理 IP 被限流。\n你可以手动前往 GitHub 查看。")
                                        .setPositiveButton("前往 GitHub", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int w) {
                                                try {
                                                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE)));
                                                } catch (Exception ignored) {}
                                            }
                                        })
                                        .setNegativeButton("关闭", null)
                                        .show();
                                    return;
                                }
                                showUpdateDialog(finalInfo);
                            }
                        });
                }
            }).start();
    }

    private UpdateInfo tryApi() throws Exception {
        HttpURLConnection conn = open(API_LATEST);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new Exception("HTTP " + code);
        }
        String json = readAll(conn);
        conn.disconnect();

        JSONObject obj = new JSONObject(json);
        UpdateInfo info = new UpdateInfo();
        info.tagName = obj.getString("tag_name");
        info.version = info.tagName.startsWith("v") ? info.tagName.substring(1) : info.tagName;
        info.body = obj.optString("body", "");

        JSONArray assets = obj.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.getString("name");
                if (name.toLowerCase().endsWith(".apk")) {
                    info.apkUrl = a.getString("browser_download_url");
                    break;
                }
            }
        }
        return info;
    }

    private UpdateInfo tryHtml() throws Exception {
        HttpURLConnection conn1 = open(HTML_LATEST);
        conn1.setInstanceFollowRedirects(false);
        int code1 = conn1.getResponseCode();
        String location = conn1.getHeaderField("Location");
        conn1.disconnect();

        if (location == null) {
            if (code1 == 200) {
                location = HTML_LATEST;
            } else {
                throw new Exception("未获取到重定向地址");
            }
        }

        String tag = null;
        int idx = location.lastIndexOf("/tag/");
        if (idx >= 0) {
            tag = location.substring(idx + 5);
            int q = tag.indexOf('?');
            if (q >= 0) tag = tag.substring(0, q);
            int h = tag.indexOf('#');
            if (h >= 0) tag = tag.substring(0, h);
        }
        if (tag == null || tag.isEmpty()) {
            throw new Exception("无法从 URL 中解析 tag");
        }

        UpdateInfo info = new UpdateInfo();
        info.tagName = tag;
        info.version = tag.startsWith("v") ? tag.substring(1) : tag;
        info.body = "(详细更新内容请访问 GitHub Release 页面)";

        String assetsUrl = "https://github.com/" + REPO + "/releases/expanded_assets/" + tag;
        HttpURLConnection conn2 = open(assetsUrl);
        conn2.setRequestProperty("Referer", "https://github.com/" + REPO + "/releases/tag/" + tag);
        int code2 = conn2.getResponseCode();
        if (code2 != 200) {
            conn2.disconnect();
            return info;
        }
        String html = readAll(conn2);
        conn2.disconnect();

        Pattern p = Pattern.compile("href=\"([^\"]+\\.apk)\"");
        Matcher m = p.matcher(html);
        if (m.find()) {
            String href = m.group(1);
            if (href.startsWith("/")) {
                href = "https://github.com" + href;
            } else if (!href.startsWith("http")) {
                href = "https://github.com/" + href;
            }
            info.apkUrl = href;
        }
        return info;
    }

    private void showUpdateDialog(UpdateInfo info) {
        final String currentVersion = getCurrentVersionName();
        final boolean hasUpdate = compareVersions(info.version, currentVersion) > 0;

        if (!hasUpdate) {
            new AlertDialog.Builder(requireActivity())
                .setTitle("已是最新版本")
                .setMessage("当前版本：" + currentVersion + "\n最新版本：" + info.version)
                .setPositiveButton("确定", null)
                .show();
            return;
        }

        String body = info.body == null ? "" : info.body;
        if (body.length() > 800) body = body.substring(0, 800) + "...";

        String msg = "当前版本：" + currentVersion
            + "\n最新版本：" + info.version
            + "\n\n更新内容：\n" + body;

        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity())
            .setTitle("发现新版本")
            .setMessage(msg)
            .setNegativeButton("稍后", null);

        if (info.apkUrl != null) {
            builder.setPositiveButton("立即下载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        downloadApk(info.apkUrl);
                    }
                });
        } else {
            builder.setPositiveButton("前往 GitHub", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE)));
                        } catch (Exception ignored) {}
                    }
                });
        }
        builder.show();
    }

    // ==================== 下载确认 ====================
    private void downloadApk(final String url) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!requireActivity().getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(requireActivity())
                    .setTitle("需要安装权限")
                    .setMessage("请允许本应用安装未知来源的应用，然后再次点击下载")
                    .setPositiveButton("前往设置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            try {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                           Uri.parse("package:" + requireActivity().getPackageName()));
                                startActivity(intent);
                            } catch (Exception ignored) {}
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
                return;
            }
        }

        String fileName = "update.apk";
        try {
            int slash = url.lastIndexOf('/');
            if (slash >= 0 && slash < url.length() - 1) {
                fileName = url.substring(slash + 1);
                int q = fileName.indexOf('?');
                if (q >= 0) fileName = fileName.substring(0, q);
            }
        } catch (Exception ignored) {}

        final String finalUrl = url;
        final String finalFileName = fileName;

        File partFile = new File(getUpdateDir(), fileName + ".part");
        SharedPreferences prefs = requireActivity().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String pausedPrefix = prefs.getString("paused_source_prefix", "");

        String msg = "文件名：" + fileName + "\n\n来源：\n" + url;
        if (partFile.exists() && partFile.length() > 0) {
            String resumeSourceName = getSourceNameByPrefix(pausedPrefix);
            msg = "检测到未完成的下载\n已下载：" + (partFile.length() / 1024) + " KB"
                + "\n续传源：" + resumeSourceName
                + "\n\n" + msg;
        }

        new AlertDialog.Builder(requireActivity())
            .setTitle("准备下载")
            .setMessage(msg)
            .setPositiveButton("开始下载", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    startDownload(finalUrl, finalFileName);
                }
            })
            .setNeutralButton("复制链接", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    try {
                        ClipboardManager cm = (ClipboardManager)
                            requireActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("apk_url", finalUrl));
                        Toast.makeText(requireActivity(), "链接已复制", Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ==================== 下载主流程 ====================
    private void startDownload(final String url, final String fileName) {
        downloadPaused = false;
        downloadCancelled = false;

        cleanOldFiles(fileName);

        final ProgressDialog dialog = new ProgressDialog(requireActivity());
        dialog.setTitle("正在下载新版本");
        dialog.setMessage("准备下载：" + fileName);
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setMax(100);
        dialog.setProgress(0);
        dialog.setCancelable(false);
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, "暂停", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    downloadPaused = true;
                }
            });
        dialog.setButton(DialogInterface.BUTTON_NEUTRAL, "取消", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    downloadCancelled = true;
                }
            });
        dialog.show();

        new Thread(new Runnable() {
                @Override
                public void run() {
                    File apkFile = downloadApkWithMirrors(url, fileName, dialog);

                    if (apkFile == null) {
                        final boolean wasPaused = downloadPaused;
                        final boolean wasCancelled = downloadCancelled;
                        mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    dialog.dismiss();
                                    String tip;
                                    if (wasPaused) {
                                        tip = "已暂停，下次点\"立即下载\"可从断点继续";
                                    } else if (wasCancelled) {
                                        tip = "已取消，已删除未完成的文件";
                                    } else {
                                        tip = "所有下载源均失败，请检查网络或稍后重试";
                                    }
                                    Toast.makeText(requireActivity(), tip, Toast.LENGTH_LONG).show();
                                }
                            });
                        return;
                    }

                    final long finalTotal = apkFile.length();

                    String apkPackage = null;
                    String apkVersion = null;
                    try {
                        PackageManager pm = requireActivity().getPackageManager();
                        PackageInfo apkInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
                        if (apkInfo != null) {
                            apkPackage = apkInfo.packageName;
                            apkVersion = apkInfo.versionName;
                        }
                    } catch (Exception ignored) {}

                    final String finalPkg = apkPackage;
                    final String finalVer = apkVersion;
                    final File finalApkFile = apkFile;

                    mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                dialog.dismiss();

                                if (finalPkg == null) {
                                    new AlertDialog.Builder(requireActivity())
                                        .setTitle("下载完成，但校验失败")
                                        .setMessage("已下载：" + (finalTotal / 1024) + " KB\n"
                                                    + "文件路径：" + finalApkFile.getAbsolutePath() + "\n\n"
                                                    + "无法解析 APK 包名，文件可能损坏或不是 APK。\n是否仍要尝试安装？")
                                        .setPositiveButton("仍然安装", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int w) {
                                                installApk(finalApkFile);
                                            }
                                        })
                                        .setNegativeButton("取消并删除", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int w) {
                                                try { finalApkFile.delete(); } catch (Exception ignored) {}
                                            }
                                        })
                                        .show();
                                    return;
                                }

                                String currentPkg = requireActivity().getPackageName();
                                if (!finalPkg.equals(currentPkg)) {
                                    new AlertDialog.Builder(requireActivity())
                                        .setTitle("⚠️ 包名不匹配")
                                        .setMessage("下载的 APK 包名：\n" + finalPkg + "\n\n"
                                                    + "当前应用包名：\n" + currentPkg + "\n\n"
                                                    + "版本号：" + (finalVer == null ? "未知" : finalVer) + "\n"
                                                    + "文件大小：" + (finalTotal / 1024) + " KB\n\n"
                                                    + "这不是本应用的安装包，安装后将成为一个独立的应用。建议取消并检查更新源。")
                                        .setPositiveButton("仍然安装", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int w) {
                                                installApk(finalApkFile);
                                            }
                                        })
                                        .setNegativeButton("取消并删除", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int w) {
                                                try { finalApkFile.delete(); } catch (Exception ignored) {}
                                            }
                                        })
                                        .show();
                                    return;
                                }

                                new AlertDialog.Builder(requireActivity())
                                    .setTitle("下载完成")
                                    .setMessage("包名：" + finalPkg + "\n"
                                                + "版本：" + (finalVer == null ? "未知" : finalVer) + "\n"
                                                + "大小：" + (finalTotal / 1024) + " KB\n\n"
                                                + "是否立即安装？")
                                    .setPositiveButton("安装", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, int w) {
                                            installApk(finalApkFile);
                                        }
                                    })
                                    .setNegativeButton("稍后", null)
                                    .show();
                            }
                        });
                }
            }).start();
    }

    // ==================== 多源下载 ====================
    private File downloadApkWithMirrors(String originalUrl, String fileName, ProgressDialog dialog) {
        // 判断是否续传：.part 存在且有内容
        File partFile = new File(getUpdateDir(), fileName + ".part");
        boolean isResume = partFile.exists() && partFile.length() > 0;

        SharedPreferences prefs = requireActivity().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Source[] sources;

        if (isResume) {
            // 【核心】续传时，暂停所在的源放第一位
            String pausedPrefix = prefs.getString("paused_source_prefix", "");
            sources = getSourcesForResume(pausedPrefix);
        } else {
            // 全新下载，正常顺序
            sources = getAllSources();
            // 全新下载时清掉残留的暂停标记
            prefs.edit().remove("paused_source_prefix").apply();
        }

        for (Source source : sources) {
            if (downloadPaused || downloadCancelled) return null;

            // 【核心】进入每个源之前，先记录当前用的是哪个源
            // 这样如果这个源下载一半暂停，下次就知道从这个源续传
            prefs.edit().putString("paused_source_prefix", source.prefix).apply();

            String fullUrl = source.buildUrl(originalUrl);
            File result = tryDownload(fullUrl, originalUrl, fileName, source, dialog);
            if (result != null) {
                // 成功，清掉暂停标记
                prefs.edit().remove("paused_source_prefix").apply();
                return result;
            }

            if (downloadPaused || downloadCancelled) return null;
        }
        return null;
    }

    // ==================== 单源下载 ====================
    private File tryDownload(final String url,
                             final String originalUrl,
                             final String fileName,
                             final Source source,
                             final ProgressDialog dialog) {

        File dir = getUpdateDir();
        File partFile = new File(dir, fileName + ".part");
        File apkFile = new File(dir, fileName);

        if (apkFile.exists()) {
            try { apkFile.delete(); } catch (Exception ignored) {}
        }

        long existingBytes = partFile.exists() ? partFile.length() : 0;

        HttpURLConnection conn = null;
        InputStream is = null;
        FileOutputStream fos = null;

        try {
            final long initialExisting = existingBytes;
            final String sourceName = source.name;
            mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (dialog.isShowing()) {
                            dialog.setMessage("来源：" + sourceName
                                              + "\n文件：" + fileName
                                              + (initialExisting > 0
                                              ? "\n从 " + (initialExisting / 1024) + " KB 处继续"
                                              : "")
                                              + "\n\n正在连接...");
                        }
                    }
                });

            conn = open(url);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            if (existingBytes > 0) {
                conn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
            }
            conn.connect();

            int code = conn.getResponseCode();

            if (code == 302 || code == 301) {
                String redirect = conn.getHeaderField("Location");
                conn.disconnect();
                conn = open(redirect);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                if (existingBytes > 0) {
                    conn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
                }
                conn.connect();
                code = conn.getResponseCode();
            }

            long totalBytes = -1;
            long downloadedStart = 0;
            boolean appendMode = false;

            if (code == 206) {
                appendMode = true;
                downloadedStart = existingBytes;
                totalBytes = parseContentRangeTotal(conn.getHeaderField("Content-Range"));
                is = conn.getInputStream();
            } else if (code == 200) {
                long fullLength = conn.getContentLengthLong();
                if (existingBytes > 0) {
                    // 服务器不支持 Range，尝试 skip 到断点
                    is = conn.getInputStream();
                    long toSkip = existingBytes;
                    boolean skipOk = true;
                    while (toSkip > 0) {
                        long skipped = is.skip(toSkip);
                        if (skipped <= 0) {
                            skipOk = false;
                            break;
                        }
                        toSkip -= skipped;
                    }
                    if (!skipOk) {
                        try { is.close(); } catch (Exception ignored) {}
                        is = null;
                        // skip 失败，保留 .part，返回 null，让后续源处理
                        return null;
                    }
                    appendMode = true;
                    downloadedStart = existingBytes;
                    totalBytes = fullLength > 0 ? fullLength : -1;
                } else {
                    if (partFile.exists()) {
                        try { partFile.delete(); } catch (Exception ignored) {}
                    }
                    appendMode = false;
                    downloadedStart = 0;
                    totalBytes = fullLength;
                    is = conn.getInputStream();
                }
            } else if (code == 416) {
                try { partFile.delete(); } catch (Exception ignored) {}
                return null;
            } else {
                return null;
            }

            fos = new FileOutputStream(partFile, appendMode);

            long downloaded = downloadedStart;
            byte[] buf = new byte[8192];
            int len;
            long lastUpdate = 0;
            final long totalFinal = totalBytes;

            while ((len = is.read(buf)) != -1) {
                if (downloadCancelled) {
                    try { fos.close(); } catch (Exception ignored) {}
                    fos = null;
                    try { is.close(); } catch (Exception ignored) {}
                    is = null;
                    try { partFile.delete(); } catch (Exception ignored) {}
                    return null;
                }
                if (downloadPaused) {
                    try { fos.close(); } catch (Exception ignored) {}
                    fos = null;
                    try { is.close(); } catch (Exception ignored) {}
                    is = null;
                    // 保留 .part
                    return null;
                }

                fos.write(buf, 0, len);
                downloaded += len;

                long now = System.currentTimeMillis();
                if (now - lastUpdate > 300) {
                    lastUpdate = now;
                    final long dl = downloaded;
                    mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (dialog.isShowing()) {
                                    if (totalFinal > 0) {
                                        int percent = (int) (dl * 100 / totalFinal);
                                        dialog.setProgress(percent);
                                        dialog.setMessage("来源：" + sourceName
                                                          + "\n文件：" + fileName
                                                          + "\n进度：" + percent + "%"
                                                          + "\n已下载：" + (dl / 1024) + " KB / " + (totalFinal / 1024) + " KB");
                                    } else {
                                        dialog.setMessage("来源：" + sourceName
                                                          + "\n文件：" + fileName
                                                          + "\n已下载：" + (dl / 1024) + " KB");
                                    }
                                }
                            }
                        });
                }
            }

            try { fos.close(); } catch (Exception ignored) {}
            fos = null;
            try { is.close(); } catch (Exception ignored) {}
            is = null;
            try { conn.disconnect(); } catch (Exception ignored) {}
            conn = null;

            if (partFile.renameTo(apkFile)) {
                return apkFile;
            }
            return null;

        } catch (Exception e) {
            return null;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private long parseContentRangeTotal(String contentRange) {
        if (contentRange == null) return -1;
        try {
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0) {
                String total = contentRange.substring(slash + 1).trim();
                if (!total.equals("*")) {
                    return Long.parseLong(total);
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    // ==================== 安装 ====================
    private void installApk(File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(requireActivity(),
                                                    requireActivity().getPackageName() + ".fileprovider", apkFile);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireActivity(), "打开安装器失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 网络工具 ====================
    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        return conn;
    }

    private String readAll(HttpURLConnection conn) throws Exception {
        InputStream is = conn.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
        is.close();
        return baos.toString("UTF-8");
    }

    // ==================== 版本比较 ====================
    private int compareVersions(String v1, String v2) {
        try {
            String[] a = v1.split("\\.");
            String[] b = v2.split("\\.");
            int n = Math.max(a.length, b.length);
            for (int i = 0; i < n; i++) {
                int x = i < a.length ? safeInt(a[i]) : 0;
                int y = i < b.length ? safeInt(b[i]) : 0;
                if (x > y) return 1;
                if (x < y) return -1;
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private int safeInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 折叠卡片 ====================
    private void setupFold(View view, int titleId, int contentId) {
        TextView tvTitle = view.findViewById(titleId);
        final TextView tvContent = view.findViewById(contentId);
        tvTitle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (tvContent.getVisibility() == View.GONE) {
                        tvContent.setVisibility(View.VISIBLE);
                    } else {
                        tvContent.setVisibility(View.GONE);
                    }
                }
            });
    }
}
