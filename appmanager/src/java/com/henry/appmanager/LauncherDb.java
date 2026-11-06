package com.henry.appmanager;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * root 快路径：直接读桌面自己的数据库，拿到「应用 → 第几页 / 哪个文件夹」。
 *
 * 不 root 时只能靠无障碍树一页页扫，慢且受桌面差异影响；有 root 时这张表
 * 就是权威数据：Launcher3 系（含 ColorOS / MIUI）的 favorites 表里，
 * screen 是页号（0 基），container=-100 表示直接在桌面、>0 表示在某个文件夹，
 * itemType=2 是文件夹本身，intent 里带 component=包名/Activity。
 */
public class LauncherDb {

    public static class Entry {
        public String title;
        public String pkg;
        public int screen;
        public String folder;
    }

    private static final String[] CANDIDATES = {
            "com.android.launcher",            // ColorOS / OPPO
            "com.miui.home",                   // MIUI / HyperOS
            "com.google.android.apps.nexuslauncher",
            "com.oppo.launcher",
            "com.coloros.launcher",
            "com.huawei.android.launcher",
            "com.hihonor.android.launcher"
    };

    /** 最近一次失败原因，供上层写日志 */
    public static volatile String diag = "";

    private static final Pattern COMPONENT =
            Pattern.compile("component=([^/;]+)/([^;\\]]+)");

    /**
     * 读取桌面库；成功返回条目列表，失败返回 null（调用方回落老路径）。
     *
     * @param srcOut 回传「包名/库文件名」，方便写日志
     */
    public static List<Entry> load(AdbService service, Context ctx, String[] srcOut) {
        StringBuilder log = new StringBuilder();
        // ⓪ 先用本进程的 ContentResolver 直接查（provider 若导出，root 都不需要）
        for (String uri : new String[]{
                "content://com.android.launcher.settings/favorites",
                "content://com.android.launcher.OplusFavoritesProvider/favorites"}) {
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(Uri.parse(uri), null, null, null, null);
                if (c == null) { log.append("resolver:null;"); continue; }
                List<Entry> list = readCursor(c, log);
                if (!list.isEmpty()) {
                    if (srcOut != null) srcOut[0] = uri;
                    return list;
                }
            } catch (Throwable e) {
                log.append("resolver:").append(e.getClass().getSimpleName()).append(";");
            } finally {
                try { if (c != null) c.close(); } catch (Throwable ignored) { }
            }
        }
        // ① 再试 ContentProvider：root shell 跑 content query，绕开文件权限/SELinux
        for (String uri : new String[]{
                "content://com.android.launcher.settings/favorites",
                "content://com.android.launcher.OplusFavoritesProvider/favorites",
                "content://com.android.launcher.provider.outward/favorites"}) {
            try {
                String out = service.shellLong("content query --uri " + uri);
                if (out == null) continue;
                out = out.trim();
                if (out.isEmpty() || out.contains("Exception") || out.contains("Error")) {
                    log.append(uri.substring(uri.lastIndexOf('/') + 1)).append(":q=")
                       .append(out.isEmpty() ? "空" : out.substring(0, Math.min(60, out.length())))
                       .append(";");
                    continue;
                }
                List<Entry> list = parseRows(out, log);
                if (!list.isEmpty()) {
                    if (srcOut != null) srcOut[0] = uri;
                    return list;
                }
            } catch (Throwable e) {
                log.append("provider异常=").append(e).append(";");
            }
        }
        for (String pkg : CANDIDATES) {
            String[][] dirs = {
                    {"/data/data/" + pkg + "/databases", pkg},
                    {"/data/user/0/" + pkg + "/databases", pkg},
                    {"/data/user_de/0/" + pkg + "/databases", pkg}
            };
            for (String[] d : dirs) {
                String dir = d[0];
                try {
                    String ls = service.shellLong("ls " + dir);
                    ls = ls == null ? "" : ls.trim();
                    String name = null;
                    for (String line : ls.split("\n")) {
                        String n = line.trim();
                        if (!n.startsWith("launcher") || !n.endsWith(".db")) continue;
                        if (name == null || n.length() < name.length()) name = n;
                    }
                    if (name == null) {
                        // 目录里没有可用的 db：记下 ls 原文，再用 find 兜底找一次
                        String raw = ls.replace("\n", "\\n");
                        if (raw.length() > 90) raw = raw.substring(0, 90);
                        log.append(pkg).append(":ls=[").append(raw).append("];");
                        String found = service.shellLong(
                                "find /data/data/" + pkg + " -maxdepth 3 -name launcher*.db");
                        found = found == null ? "" : found.trim();
                        if (!found.isEmpty() && !found.contains("No such")) {
                            String first = found.split("\n")[0].trim();
                            int slash = first.lastIndexOf('/');
                            if (slash > 0) {
                                dir = first.substring(0, slash);
                                name = first.substring(slash + 1);
                                log.append("find=").append(first).append(";");
                            }
                        }
                    }
                    if (name == null) { log.append(pkg).append(":无db;"); continue; }

                    // 🔴 先让 App 自己建空文件：这样文件属主和 SELinux 标签都是
                    //    untrusted_app，root 再 cp 覆盖内容即可，避免 chown/context 问题。
                    File out = new File(ctx.getFilesDir(), "ldb.sqlite");
                    File wal = new File(ctx.getFilesDir(), "ldb.sqlite-wal");
                    try { if (!out.exists()) out.createNewFile(); } catch (Throwable ignored) { }
                    try { if (!wal.exists()) wal.createNewFile(); } catch (Throwable ignored) { }

                    String src = dir + "/" + name;
                    service.shellLong("cp " + src + " " + out.getAbsolutePath());
                    // 带上 -wal（桌面进程在跑时最新数据可能在 WAL 里）
                    service.shellLong("cp " + src + "-wal " + wal.getAbsolutePath());
                    long len = out.length();
                    log.append(pkg).append(":").append(name).append(" len=").append(len).append(";");
                    if (len <= 0) continue;

                    List<Entry> list = read(out, log);
                    if (!list.isEmpty()) {
                        if (srcOut != null) srcOut[0] = pkg + "/" + name;
                        return list;
                    }
                } catch (Throwable e) {
                    log.append(pkg).append(":异常=").append(e).append(";");
                }
            }
        }
        diag = log.toString();
        return null;
    }

    /**
     * 解析 `content query` 的输出。每行形如：
     * Row: 0 _id=12, title=微信, intent=Intent { ... component=com.tencent.mm/.ui.LauncherUI },
     *        container=-100, screen=0, cellX=0, cellY=0, itemType=0
     */
    private static List<Entry> parseRows(String out, StringBuilder log) {
        List<Entry> list = new ArrayList<Entry>();
        Map<String, String> folderTitles = new HashMap<String, String>();
        List<String[]> rows = new ArrayList<String[]>();
        for (String line : out.split("\n")) {
            if (!line.startsWith("Row:")) continue;
            String id = between(line, "_id=", ",");
            String title = between(line, "title=", ",");
            String container = between(line, "container=", ",");
            String screen = between(line, "screen=", ",");
            String type = between(line, "itemType=", ",");
            if (type == null) type = between(line, "itemType=", "}");
            Matcher m = COMPONENT.matcher(line);
            String pkg = m.find() ? m.group(1) : null;
            if ("2".equals(trim(type))) folderTitles.put(trim(id), trim(title));
            rows.add(new String[]{id, title, pkg, container, screen, type});
        }
        for (String[] r : rows) {
            if (!"0".equals(trim(r[5]))) continue;
            if (r[2] == null) continue;
            Entry e = new Entry();
            e.pkg = r[2];
            e.title = r[1] == null ? "" : trim(r[1]);
            e.screen = parseInt(trim(r[4]), 0);
            long c = parseInt(trim(r[3]), -100);
            e.folder = c > 0 ? folderTitles.get(String.valueOf(c)) : "";
            if (e.folder == null) e.folder = "";
            list.add(e);
        }
        if (list.isEmpty()) log.append("rows=").append(rows.size()).append(";");
        return list;
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) return null;
        i += a.length();
        int j = s.indexOf(b, i);
        return j < 0 ? s.substring(i) : s.substring(i, j);
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private static List<Entry> readCursor(Cursor c, StringBuilder log) {
        List<Entry> list = new ArrayList<Entry>();
        Map<String, String> folders = new HashMap<String, String>();
        List<String[]> rows = new ArrayList<String[]>();
        try {
            while (c.moveToNext()) {
                String id = col(c, "_id");
                String title = col(c, "title");
                String intent = col(c, "intent");
                String container = col(c, "container");
                String screen = col(c, "screen");
                String type = col(c, "itemType");
                if ("2".equals(type)) folders.put(id, title);
                rows.add(new String[]{id, title, intent, container, screen, type});
            }
        } catch (Throwable e) {
            log.append("cursor=").append(e).append(";");
        }
        for (String[] r : rows) {
            if (!"0".equals(r[5]) || r[2] == null) continue;
            Matcher m = COMPONENT.matcher(r[2]);
            if (!m.find()) continue;
            Entry e = new Entry();
            e.pkg = m.group(1);
            e.title = r[1] == null ? "" : r[1];
            e.screen = parseInt(r[4], 0);
            int cont = parseInt(r[3], -100);
            e.folder = cont > 0 ? folders.get(String.valueOf(cont)) : "";
            if (e.folder == null) e.folder = "";
            list.add(e);
        }
        return list;
    }

    private static String col(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        return i < 0 ? null : c.getString(i);
    }

    private static List<Entry> read(File f, StringBuilder log) {
        List<Entry> out = new ArrayList<Entry>();
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            c = db.rawQuery(
                    "select _id,title,intent,container,screen,itemType from favorites", null);
            Map<Long, String> folderNames = new HashMap<Long, String>();
            List<String[]> rows = new ArrayList<String[]>();
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String title = c.getString(1);
                String intent = c.getString(2);
                long container = c.getLong(3);
                int screen = c.getInt(4);
                int type = c.getInt(5);
                if (type == 2) folderNames.put(id, title == null ? "" : title);
                rows.add(new String[]{String.valueOf(id), title, intent,
                        String.valueOf(container), String.valueOf(screen), String.valueOf(type)});
            }
            for (String[] r : rows) {
                if (!"0".equals(r[5])) continue;           // 只要应用图标
                String intent = r[2];
                if (intent == null || intent.isEmpty()) continue;
                Matcher m = COMPONENT.matcher(intent);
                if (!m.find()) continue;
                Entry e = new Entry();
                e.pkg = m.group(1);
                e.title = r[1] == null ? "" : r[1];
                e.screen = Integer.parseInt(r[4]);
                long container = Long.parseLong(r[3]);
                e.folder = container > 0 ? folderNames.get(container) : "";
                if (e.folder == null) e.folder = "";
                out.add(e);
            }
        } catch (Throwable e) {
            log.append("read异常=").append(e).append(";");
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) { }
            try { if (db != null) db.close(); } catch (Throwable ignored) { }
        }
        return out;
    }
}
