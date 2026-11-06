package com.henry.appmanager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * 常驻 root shell：su 只起一次，之后所有命令复用同一个进程。
 *
 * 为什么不做「每条命令 su -c」：Magisk 对每个新的 root 请求都会弹一次授权
 * 提示（取决于设置），而 App 每 3 秒轮询一次 meminfo，逐条 su 会变成
 * 每 3 秒弹一次窗。常驻一条 su，授权只发生一次。
 *
 * 协议：命令写进 su 的 stdin，随后写一行
 *     echo <唯一标记>$?
 * 从 stdout 读到这一行即认为命令结束，标记前的内容就是输出。
 * su 的 stdout 是管道而不是 PTY，所以没有回显、也没有 n_tty_write 卡死问题。
 */
public class RootShell {

    private static final String MARK = "__MINE_EOC__";

    private Process proc;
    private BufferedWriter stdin;

    /** 读线程写入、run() 消费；同一时刻只有一条命令。 */
    private final StringBuilder buf = new StringBuilder();
    private final Object lock = new Object();
    private boolean eof;

    public synchronized boolean isAlive() {
        return proc != null && proc.isAlive();
    }

    /**
     * 启动 su 并确认拿到的确实是 uid=0。
     *
     * @param timeoutMs 等待用户点「允许」的上限；超时会杀掉 su 进程
     */
    public synchronized boolean start(int timeoutMs) {
        // 不同 root 方案把 su 放在不同位置：Magisk 会在 PATH 里给 su，
        // KernelSU / APatch 也大致相同；再兜底几个常见绝对路径。
        String[] candidates = {"su", "/system/bin/su", "/sbin/su", "/debug_ramdisk/su"};
        for (String bin : candidates) {
            int r = startWith(bin, timeoutMs);
            // r > 0 成功；r == 0 su 存在但被拒绝（别再挨个路径重弹授权框）；r < 0 该路径没有 su
            if (r > 0) return true;
            close();
            if (r == 0) return false;
        }
        return false;
    }

    /** @return 1 = 拿到 root，0 = su 存在但没授权，-1 = 这个路径起不来 */
    private int startWith(String bin, int timeoutMs) {
        boolean launched = false;
        try {
            ProcessBuilder pb = new ProcessBuilder(bin);
            pb.redirectErrorStream(true);
            proc = pb.start();
            launched = true;
            stdin = new BufferedWriter(
                    new OutputStreamWriter(proc.getOutputStream(), "UTF-8"));

            final BufferedReader out = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), "UTF-8"), 1 << 16);
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String line;
                        while ((line = out.readLine()) != null) {
                            synchronized (lock) {
                                buf.append(line).append('\n');
                                lock.notifyAll();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    synchronized (lock) {
                        eof = true;
                        lock.notifyAll();
                    }
                }
            }, "root-shell-reader");
            reader.setDaemon(true);
            reader.start();

            String id = run("id", timeoutMs);
            return (id != null && id.contains("uid=0")) ? 1 : 0;
        } catch (IOException e) {
            // 进程根本没起来（路径不存在）才换下一个；超时/拒绝都算 0
            close();
            return launched ? 0 : -1;
        } catch (Throwable e) {
            close();
            return 0;
        }
    }

    /**
     * 执行一条命令，返回 stdout（含 stderr，已合并）。
     *
     * @param timeoutMs <=0 表示不限时（AOT 编译、建索引这类长任务）
     */
    public synchronized String run(String cmd, int timeoutMs) throws IOException {
        if (proc == null || !proc.isAlive()) throw new IOException("root shell 已退出");

        synchronized (lock) {
            buf.setLength(0);
            eof = false;
        }

        // 标记带纳秒，避免用户命令输出里恰好出现同样的字符串
        String mark = MARK + Long.toHexString(System.nanoTime());
        try {
            stdin.write(cmd);
            stdin.write("\n");
            stdin.write("echo " + mark + "$?\n");
            stdin.flush();
        } catch (IOException e) {
            close();
            throw e;
        }

        final long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : 0;
        synchronized (lock) {
            while (true) {
                String s = buf.toString();
                int idx = s.indexOf(mark);
                if (idx >= 0) {
                    int nl = s.indexOf('\n', idx);
                    if (nl > idx) {
                        // 标记行之前的内容就是命令输出
                        return s.substring(0, idx);
                    }
                }
                if (eof) throw new IOException("root shell 已断开");
                long wait = 500;
                if (deadline > 0) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        close();
                        throw new IOException("root 命令超时: " + head(cmd));
                    }
                    wait = Math.min(wait, left);
                }
                try {
                    lock.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("root 命令被中断");
                }
            }
        }
    }

    private static String head(String cmd) {
        return cmd.length() > 40 ? cmd.substring(0, 40) : cmd;
    }

    public synchronized void close() {
        try {
            if (stdin != null) stdin.close();
        } catch (Throwable ignored) {
        }
        try {
            if (proc != null) proc.destroy();
        } catch (Throwable ignored) {
        }
        stdin = null;
        proc = null;
    }
}
