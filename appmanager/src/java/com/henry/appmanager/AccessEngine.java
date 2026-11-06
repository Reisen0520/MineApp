package com.henry.appmanager;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileWriter;

/**
 * 无障碍引擎：在进程内直接读当前窗口的无障碍树，再用手势注入点击。
 *
 * 相比 uiautomator dump（每次都新起一个进程、连一次无障碍、等 idle，2~3 秒），
 * 这里读一次树只要几十毫秒，而且不需要 adb tcpip —— 手机重启后照样能用。
 *
 * 为了不改上层那套成熟的 XML 解析逻辑，这里把无障碍树**序列化成 uiautomator
 * 同款 XML**：每个节点一行，带 class / content-desc / clickable / bounds。
 * 上层的 findNode、parsePager、indexPage 完全感知不到差别。
 */
public class AccessEngine extends AccessibilityService implements WindowEngine {

    private static volatile AccessEngine instance;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 服务是否已连接（用户在系统设置里开启并绑定成功） */
    public static boolean ready() {
        return instance != null;
    }

    public static WindowEngine get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        android.util.Log.i("AccessEngine", "无障碍服务已连接");
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    // ---------- WindowEngine ----------

    @Override public String name() { return "access"; }

    @Override public boolean available() { return instance != null; }

    @Override
    public String dump() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "";
            // 🔴 必须带 <hierarchy> 根节点：上层的 dumpHome 用 xml.contains("<hierarchy")
            //    判断「这是不是一棵完整的树」。少了它，每一把 dump 都会被判失败，
            //    表现就是日志里长度 24K 却一直重试、最后报「桌面 dump 为空」。
            StringBuilder sb = new StringBuilder(64 * 1024);
            sb.append("<hierarchy rotation=\"0\">\n");
            walk(root, sb);
            sb.append("</hierarchy>\n");
            return sb.toString();
        } catch (Throwable e) {
            android.util.Log.w("AccessEngine", "dump 失败: " + e);
            return "";
        }
    }

    private static void walk(AccessibilityNodeInfo n, StringBuilder sb) {
        if (n == null) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        sb.append("<node class=\"");
        appendEscaped(sb, n.getClassName());
        sb.append("\" package=\"");
        appendEscaped(sb, n.getPackageName());
        sb.append("\" content-desc=\"");
        appendEscaped(sb, n.getContentDescription());
        sb.append("\" clickable=\"").append(n.isClickable() ? "true" : "false");
        sb.append("\" bounds=\"[")
          .append(r.left).append(',').append(r.top).append("][")
          .append(r.right).append(',').append(r.bottom).append("]\" />\n");
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            walk(c, sb);
            try { c.recycle(); } catch (Throwable ignored) { }
        }
    }

    /** XML 属性转义；引号必须处理，否则会截断 Bridge 的正则解析 */
    private static void appendEscaped(StringBuilder sb, CharSequence cs) {
        if (cs == null) return;
        String s = cs.toString();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("&quot;"); break;
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '\n': sb.append(' '); break;
                default: sb.append(ch);
            }
        }
    }

    @Override
    public void tap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatch(0, 60, p);
    }

    @Override
    public void longPress(int x, int y, int ms) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatch(0, Math.max(600, ms), p);
    }

    @Override
    public void swipe(int x1, int y1, int x2, int y2, int ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        boolean ok = dispatch(0, Math.max(50, ms), p);
        if (!ok) scrollFallback(x2 < x1);
    }

    @Override
    public void key(int code) {
        if (code == 3) performGlobalAction(GLOBAL_ACTION_HOME);
        else if (code == 4) performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /**
     * 手势必须在主线程派发：dispatchGesture 的返回值和实际执行都依赖
     * 服务的主线程消息队列，从工作线程直接调可能返回 true 但手势被丢弃。
     */
    private boolean dispatch(final long start, final long duration, final Path path) {
        final boolean[] ok = new boolean[1];
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    GestureDescription.StrokeDescription stroke =
                            new GestureDescription.StrokeDescription(path, start, duration);
                    GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
                    ok[0] = dispatchGesture(g, null, null);
                } catch (Throwable e) {
                    android.util.Log.w("AccessEngine", "手势异常: " + e);
                } finally {
                    latch.countDown();
                }
            }
        });
        try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { }
        return ok[0];
    }

    /** 手势派发失败时的兜底：让可滚动节点自己滚一屏 */
    private boolean scrollFallback(boolean forward) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            return root != null && scrollNode(root, forward);
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean scrollNode(AccessibilityNodeInfo n, boolean forward) {
        if (n == null) return false;
        if (n.isScrollable()) {
            boolean ok = n.performAction(forward
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            if (ok) return true;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            boolean ok = scrollNode(c, forward);
            try { c.recycle(); } catch (Throwable ignored) { }
            if (ok) return true;
        }
        return false;
    }
}
