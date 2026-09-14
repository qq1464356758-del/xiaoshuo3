package com.novelgithub.exporter;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class GitHubExporter {
    public interface Listener { void onProgress(int percent, String message); }

    public static final class Result {
        public Uri uri;
        public String fileName;
        public String branch;
        public int repositoryFileCount;
        public int lockCount;
    }

    private static final Pattern REPO_PATTERN = Pattern.compile("^https?://github\\.com/([^/]+)/([^/#?]+?)(?:\\.git)?/?(?:[?#].*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCK_PATTERN = Pattern.compile("^定稿/CH(\\d{3})/LOCK-V(\\d+)\\.txt$");
    private static final int BUFFER = 64 * 1024;

    private GitHubExporter() {}

    public static Result export(Context context, String repoUrl, Listener listener) throws Exception {
        listener.onProgress(2, "解析 GitHub 仓库链接");
        Repo repo = parseRepo(repoUrl);

        listener.onProgress(6, "读取仓库信息");
        JSONObject metadata = getJson("https://api.github.com/repos/" + repo.owner + "/" + repo.name);
        String branch = metadata.optString("default_branch", "main");

        File work = new File(context.getCacheDir(), "novel_export_" + System.currentTimeMillis());
        File downloadZip = new File(work, "repo.zip");
        File unzipDir = new File(work, "repo_unzip");
        File outputDir = new File(work, "export");
        ensureDir(work); ensureDir(unzipDir); ensureDir(outputDir);

        try {
            listener.onProgress(12, "下载 GitHub 官方仓库 ZIP 快照");
            String encodedBranch = encodeBranch(branch);
            download("https://codeload.github.com/" + repo.owner + "/" + repo.name + "/zip/refs/heads/" + encodedBranch, downloadZip);

            listener.onProgress(32, "解压仓库到手机临时目录");
            unzip(downloadZip, unzipDir);
            File sourceRoot = singleRoot(unzipDir);
            if (sourceRoot == null || !sourceRoot.isDirectory()) throw new Exception("仓库 ZIP 结构异常");

            listener.onProgress(44, "复制完整仓库");
            File fullRepo = new File(outputDir, "01_完整仓库");
            int fileCount = copyDirectory(sourceRoot, fullRepo);

            listener.onProgress(55, "识别正式 LOCK 章节");
            State state = readState(sourceRoot);
            List<LockItem> locks = findLocks(sourceRoot, state);

            listener.onProgress(63, "原样合并 LOCK 正文");
            File mergedDir = new File(outputDir, "02_锁定正文合并");
            ensureDir(mergedDir);
            File merged = new File(mergedDir, "全部锁定正文_严格原样合并.txt");
            mergeExactBytes(locks, merged);

            listener.onProgress(70, "复制每章 LOCK 原文件");
            File lockDir = new File(outputDir, "03_锁定章节单独文件");
            ensureDir(lockDir);
            for (LockItem item : locks) {
                copyFile(item.file, new File(lockDir, String.format(Locale.US, "CH%03d_%s", item.chapter, item.file.getName())));
            }

            listener.onProgress(76, "整理关键入口和系统规则");
            File keyDir = new File(outputDir, "04_项目关键入口");
            ensureDir(keyDir);
            String[] keys = {"数据库入口.json", "运行状态.json", "当前进度.md", "README.md"};
            for (String key : keys) {
                File f = new File(sourceRoot, key);
                if (f.isFile()) copyFile(f, new File(keyDir, key));
            }
            File systemDir = new File(sourceRoot, "系统");
            if (systemDir.isDirectory()) copyDirectory(systemDir, new File(outputDir, "05_系统规则与角色指令"));

            listener.onProgress(82, "生成导出清单和校验值");
            File manifest = new File(outputDir, "00_导出清单.txt");
            writeManifest(manifest, repoUrl, branch, state, locks, fileCount, merged, sourceRoot);

            String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = safe(repo.name) + "_小说完整备份_" + time + ".zip";
            File finalZip = new File(work, fileName);

            listener.onProgress(88, "打包最终 ZIP");
            zipDirectory(outputDir, finalZip);

            listener.onProgress(95, "保存到手机 Downloads/小说GitHub导出");
            Uri uri = saveToDownloads(context, finalZip, fileName);

            Result result = new Result();
            result.uri = uri;
            result.fileName = fileName;
            result.branch = branch;
            result.repositoryFileCount = fileCount;
            result.lockCount = locks.size();
            listener.onProgress(100, "完成");
            return result;
        } finally {
            deleteRecursively(work);
        }
    }

    private static Repo parseRepo(String url) throws Exception {
        String normalized = url.trim();
        Matcher m = REPO_PATTERN.matcher(normalized);
        if (!m.matches()) throw new Exception("链接格式不正确。请粘贴仓库首页链接，例如 https://github.com/账号/仓库");
        String owner = m.group(1);
        String name = m.group(2);
        if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
        if (owner.isEmpty() || name.isEmpty()) throw new Exception("无法识别仓库账号或名称");
        return new Repo(owner, name);
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "NovelGitHubExporter-Android/1.0");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code == 404) throw new Exception("仓库不存在，或仓库不是公开仓库");
        if (code < 200 || code >= 300) throw new Exception("GitHub 返回 HTTP " + code);
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } finally { c.disconnect(); }
    }

    private static void download(String url, File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "NovelGitHubExporter-Android/1.0");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("下载仓库失败，HTTP " + code);
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            pipe(in, os);
        } finally { c.disconnect(); }
    }

    private static String encodeBranch(String branch) throws Exception {
        String[] parts = branch.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
        }
        return sb.toString();
    }

    private static void unzip(File zip, File dest) throws Exception {
        String canonicalDest = dest.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File target = new File(dest, entry.getName());
                String canonicalTarget = target.getCanonicalPath();
                if (!canonicalTarget.startsWith(canonicalDest)) throw new Exception("ZIP 包含非法路径");
                if (entry.isDirectory()) {
                    ensureDir(target);
                } else {
                    ensureDir(target.getParentFile());
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(target))) { pipe(zis, os); }
                }
                zis.closeEntry();
            }
        }
    }

    private static File singleRoot(File dir) {
        File[] items = dir.listFiles();
        if (items == null) return null;
        for (File f : items) if (f.isDirectory()) return f;
        return dir;
    }

    private static State readState(File root) {
        State state = new State();
        File f = new File(root, "运行状态.json");
        if (!f.isFile()) return state;
        try {
            JSONObject j = new JSONObject(new String(readFileBytes(f), StandardCharsets.UTF_8));
            state.status = j.optString("状态", "");
            state.currentChapter = j.optInt("当前章", 0);
            JSONArray arr = j.optJSONArray("有效定稿");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    int ch = o.optInt("章号", 0);
                    String path = o.optString("正文路径", "");
                    if (ch > 0 && !path.isEmpty()) state.effective.put(ch, path);
                }
            }
        } catch (Exception ignored) {}
        return state;
    }

    private static List<LockItem> findLocks(File root, State state) throws Exception {
        List<LockItem> out = new ArrayList<>();
        if (!state.effective.isEmpty()) {
            List<Integer> chapters = new ArrayList<>(state.effective.keySet());
            Collections.sort(chapters);
            for (int ch : chapters) {
                File f = new File(root, state.effective.get(ch));
                if (f.isFile()) out.add(new LockItem(ch, f));
            }
            return out;
        }

        Map<Integer, LockItem> best = new HashMap<>();
        scanLocks(root, root, best);
        int maxChapter = Integer.MAX_VALUE;
        if (state.currentChapter > 0 && !"已完成".equals(state.status)) maxChapter = state.currentChapter - 1;
        for (LockItem item : best.values()) if (item.chapter <= maxChapter) out.add(item);
        out.sort(Comparator.comparingInt(a -> a.chapter));
        return out;
    }

    private static void scanLocks(File root, File f, Map<Integer, LockItem> best) throws Exception {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) scanLocks(root, c, best);
            return;
        }
        String rel = relative(root, f).replace(File.separatorChar, '/');
        Matcher m = LOCK_PATTERN.matcher(rel);
        if (!m.matches()) return;
        int ch = Integer.parseInt(m.group(1));
        int version = Integer.parseInt(m.group(2));
        LockItem old = best.get(ch);
        if (old == null || version > old.version) best.put(ch, new LockItem(ch, version, f));
    }

    private static void mergeExactBytes(List<LockItem> locks, File out) throws Exception {
        ensureDir(out.getParentFile());
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            for (LockItem item : locks) {
                try (InputStream in = new BufferedInputStream(new FileInputStream(item.file))) { pipe(in, os); }
            }
        }
    }

    private static void writeManifest(File f, String repoUrl, String branch, State state, List<LockItem> locks, int fileCount, File merged, File sourceRoot) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("小说GitHub一键导出清单\n");
        sb.append("生成时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n");
        sb.append("仓库：").append(repoUrl).append("\n");
        sb.append("分支：").append(branch).append("\n");
        sb.append("仓库文件数：").append(fileCount).append("\n");
        sb.append("运行状态：").append(state.status).append("\n");
        sb.append("当前章：").append(state.currentChapter).append("\n");
        sb.append("正式LOCK数量：").append(locks.size()).append("\n");
        sb.append("LOCK章节：");
        for (int i = 0; i < locks.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format(Locale.US, "CH%03d", locks.get(i).chapter));
        }
        sb.append("\n");
        sb.append("合并正文SHA256：").append(sha256(merged)).append("\n\n");
        sb.append("重要说明：\n");
        sb.append("1. 01_完整仓库 是本次默认分支的完整快照。\n");
        sb.append("2. 02_锁定正文合并/全部锁定正文_严格原样合并.txt 由正式LOCK文件按章号顺序直接拼接。\n");
        sb.append("3. 合并过程中不添加章节标题、分隔符、空行或任何额外字节。\n");
        sb.append("4. 若运行状态处于归档中且有效定稿列表缺失，当前正在归档章不会被推定为正式LOCK。\n\n");
        sb.append("LOCK来源：\n");
        for (LockItem item : locks) {
            sb.append(String.format(Locale.US, "CH%03d", item.chapter)).append(" | ")
              .append(relative(sourceRoot, item.file).replace(File.separatorChar, '/'))
              .append(" | SHA256=").append(sha256(item.file)).append("\n");
        }
        writeBytes(f, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Uri saveToDownloads(Context context, File zip, String fileName) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/小说GitHub导出");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("无法在下载目录创建文件");
        try {
            try (InputStream in = new BufferedInputStream(new FileInputStream(zip));
                 OutputStream raw = resolver.openOutputStream(uri, "w")) {
                if (raw == null) throw new Exception("无法写入下载目录");
                try (OutputStream out = new BufferedOutputStream(raw)) { pipe(in, out); }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return uri;
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            throw e;
        }
    }

    private static int copyDirectory(File src, File dst) throws Exception {
        if (src.isFile()) { copyFile(src, dst); return 1; }
        ensureDir(dst);
        int count = 0;
        File[] items = src.listFiles();
        if (items != null) for (File f : items) count += copyDirectory(f, new File(dst, f.getName()));
        return count;
    }

    private static void copyFile(File src, File dst) throws Exception {
        ensureDir(dst.getParentFile());
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) { pipe(in, out); }
    }

    private static void zipDirectory(File root, File zip) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)), StandardCharsets.UTF_8)) {
            zipWalk(root, root, zos);
        }
    }

    private static void zipWalk(File root, File f, ZipOutputStream zos) throws Exception {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) zipWalk(root, c, zos);
            return;
        }
        String name = relative(root, f).replace(File.separatorChar, '/');
        ZipEntry e = new ZipEntry(name);
        e.setTime(f.lastModified());
        zos.putNextEntry(e);
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) { pipe(in, zos); }
        zos.closeEntry();
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[BUFFER]; int n;
            while ((n = in.read(buf)) >= 0) if (n > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static byte[] readFileBytes(File f) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) { return readAll(in); }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pipe(in, out);
        return out.toByteArray();
    }

    private static void pipe(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[BUFFER]; int n;
        while ((n = in.read(buf)) >= 0) if (n > 0) out.write(buf, 0, n);
        out.flush();
    }

    private static void writeBytes(File f, byte[] data) throws Exception {
        ensureDir(f.getParentFile());
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) { out.write(data); }
    }

    private static void ensureDir(File f) throws Exception {
        if (f == null) return;
        if (!f.exists() && !f.mkdirs()) throw new Exception("无法创建目录：" + f.getAbsolutePath());
    }

    private static String relative(File root, File child) throws Exception {
        String rp = root.getCanonicalPath();
        String cp = child.getCanonicalPath();
        if (cp.equals(rp)) return "";
        if (!cp.startsWith(rp + File.separator)) return child.getName();
        return cp.substring(rp.length() + 1);
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }

    private static final class Repo {
        final String owner, name;
        Repo(String owner, String name) { this.owner = owner; this.name = name; }
    }

    private static final class State {
        String status = "";
        int currentChapter = 0;
        final Map<Integer, String> effective = new HashMap<>();
    }

    private static final class LockItem {
        final int chapter;
        final int version;
        final File file;
        LockItem(int chapter, File file) { this(chapter, 1, file); }
        LockItem(int chapter, int version, File file) { this.chapter = chapter; this.version = version; this.file = file; }
    }
}
