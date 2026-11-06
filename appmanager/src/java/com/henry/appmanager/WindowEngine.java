package com.henry.appmanager;

/**
 * 桌面读写的传输层。
 *
 * 查找/定位功能原先直接调 shell（uiautomator dump + input），一次 dump 要 2~3 秒。
 * 把「读窗口、点窗口」抽出来之后，可以有多种实现：
 *
 *   - {@link DumpEngine}   uiautomator dump + input，永远可用，最慢
 *   - {@link AccessEngine} 内置无障碍服务，进程内读树，几十毫秒
 *
 * 三种实现共用上层的解析与索引代码：dump() 统一返回 uiautomator 格式的 XML，
 * 所以 findNode / isFolderNode / parsePager 这些一行都不用改。
 */
public interface WindowEngine {

    /** 引擎标识：dump / access */
    String name();

    /** 当前是否可用（无障碍服务被杀掉时会变 false） */
    boolean available();

    /** 读当前窗口，返回 uiautomator 兼容的 XML；失败返回空串 */
    String dump();

    void tap(int x, int y);

    /** 长按：实现必须保证有位移或时长，否则会被系统当成普通点击 */
    void longPress(int x, int y, int ms);

    void swipe(int x1, int y1, int x2, int y2, int ms);

    /** 3 = HOME，4 = BACK */
    void key(int code);
}
