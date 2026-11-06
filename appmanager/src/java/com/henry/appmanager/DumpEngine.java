package com.henry.appmanager;

/**
 * 兼容引擎：走 shell 的 uiautomator dump + input。
 *
 * 没有无障碍服务、也没有别的通道时用它，行为与 3.8.5 完全一致，
 * 只是把命令收拢到一个类里，方便上层无感切换。
 */
public class DumpEngine implements WindowEngine {

    private final AdbService service;

    public DumpEngine(AdbService service) {
        this.service = service;
    }

    @Override public String name() { return "dump"; }

    @Override public boolean available() { return true; }

    @Override public String dump() {
        try {
            service.shellLong("uiautomator dump --compressed /sdcard/mineapp_loc.xml");
            String xml = service.shellLong("cat /sdcard/mineapp_loc.xml");
            return xml == null || !xml.contains("<hierarchy") ? "" : xml;
        } catch (Throwable e) {
            return "";
        }
    }

    @Override public void tap(int x, int y) {
        service.shellLong("input tap " + x + " " + y);
    }

    @Override public void longPress(int x, int y, int ms) {
        // 零距离的 swipe 会被当成点击，必须带 2~4px 位移
        service.shellLong("input swipe " + x + " " + y + " " + (x + 4) + " " + y + " " + ms);
    }

    @Override public void swipe(int x1, int y1, int x2, int y2, int ms) {
        service.shellLong("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms);
    }

    @Override public void key(int code) {
        service.shellLong("input keyevent " + code);
    }
}
