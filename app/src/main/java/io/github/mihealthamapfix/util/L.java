package io.github.mihealthamapfix.util;

import java.util.Locale;

/**
 * 本地化日志工具 / Localized logging utility.
 * 中文系统使用简体中文，其余使用英语。
 */
public final class L {
    private L() {}

    private static final boolean ZH;
    static {
        String lang = Locale.getDefault().getLanguage();
        ZH = "zh".equals(lang);
    }

    /** 根据系统语言返回对应的字符串 / Returns string based on system language. */
    public static String s(String zh, String en) {
        return ZH ? zh : en;
    }
}
