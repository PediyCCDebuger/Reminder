package cn.nnci.addintimer;

import java.time.LocalDate;

/**
 * 农历（1900-2099）与公历互转，纯 Java，无 Android 依赖。
 *
 * 数据表每个 int 的位含义（与 lunardate / 通用 lunarInfo 表一致）：
 *   bit16..bit4 : 1..12 月是否为大月（1=30 天，0=29 天）
 *   bit3..bit0  : 闰月月份（0 表示无闰月）
 *   bit16(0x10000) : 闰月是否为大月
 * 基准：公历 1900-01-31 == 农历 1900 年正月初一
 */
public final class Lunar {
    private static final int[] INFO = { 
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0,
        0x09ad0, 0x055d2, 0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540,
        0x0d6a0, 0x0ada2, 0x095b0, 0x14977, 0x04970, 0x0a4b0, 0x0b4b5, 0x06a50,
        0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970, 0x06566, 0x0d4a0,
        0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2,
        0x0a950, 0x0b557, 0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5d0, 0x14573,
        0x052b0, 0x0a9a8, 0x0e950, 0x06aa0, 0x0aea6, 0x0ab50, 0x04b60, 0x0aae4,
        0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0, 0x096d0, 0x04dd5,
        0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b5a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46,
        0x0ab60, 0x09570, 0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58,
        0x05ac0, 0x0ab60, 0x096d5, 0x092e0, 0x0c960, 0x0d954, 0x0d4a0, 0x0da50,
        0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5, 0x0a950, 0x0b4a0,
        0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260,
        0x0ea65, 0x0d530, 0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0,
        0x1d0b6, 0x0d250, 0x0d520, 0x0dd45, 0x0b5a0, 0x056d0, 0x055b2, 0x049b0,
        0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0, 0x14b63, 0x09370,
        0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06aa0, 0x1a6c4, 0x0aae0,
        0x092e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0,
        0x0a6d0, 0x055d4, 0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50,
        0x055a0, 0x0aba4, 0x0a5b0, 0x052b0, 0x0b273, 0x06930, 0x07337, 0x06aa0,
        0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160, 0x0e968, 0x0d520,
        0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252, };
    private static final LocalDate BASE = LocalDate.of(1900, 1, 31);
    public static final int MIN_YEAR = 1900;
    public static final int MAX_YEAR = 2099;

    private Lunar() {}

    private static int info(int y) { return INFO[y - 1900]; }

    public static int leapMonth(int y) { return info(y) & 0x0f; }

    public static int leapDays(int y) {
        int lm = leapMonth(y);
        if (lm == 0) return 0;
        return (info(y) & 0x10000) != 0 ? 30 : 29;
    }

    public static int monthDays(int y, int m) {
        return (info(y) & (0x10000 >> m)) != 0 ? 30 : 29;
    }

    /** 指定农历月的天数；leap=true 表示闰 month 月 */
    public static int monthDays(int y, int m, boolean leap) {
        if (leap) {
            if (leapMonth(y) != m) return 0;
            return leapDays(y);
        }
        return monthDays(y, m);
    }

    public static int yearDays(int y) {
        int s = 0;
        for (int m = 1; m <= 12; m++) s += monthDays(y, m);
        return s + leapDays(y);
    }

    /** 农历 -> 公历；该月不存在这一天（如小月三十）返回 null */
    public static LocalDate toSolar(int y, int m, int d, boolean leap) {
        if (y < MIN_YEAR || y > MAX_YEAR || m < 1 || m > 12 || d < 1 || d > 30) return null;
        if (leap && leapMonth(y) != m) return null;
        if (d > monthDays(y, m, leap)) return null;
        long offset = 0;
        for (int k = MIN_YEAR; k < y; k++) offset += yearDays(k);
        for (int mm = 1; mm < m; mm++) offset += monthDays(y, mm);
        int lm = leapMonth(y);
        if (lm != 0 && (leap || lm < m)) offset += leapDays(y);
        offset += d - 1;
        return BASE.plusDays(offset);
    }

    /** 公历 -> 农历，返回 {年, 月, 日, 是否闰月(0/1)} */
    public static int[] fromSolar(LocalDate solar) {
        long offset = java.time.temporal.ChronoUnit.DAYS.between(BASE, solar);
        if (offset < 0) return null;
        int y = MIN_YEAR;
        while (y <= MAX_YEAR) {
            int yd = yearDays(y);
            if (offset < yd) break;
            offset -= yd;
            y++;
        }
        if (y > MAX_YEAR) return null;
        int lm = leapMonth(y);
        int m = 1;
        boolean leap = false;
        while (m <= 12) {
            int md = monthDays(y, m);
            if (offset < md) break;
            offset -= md;
            if (lm == m) {
                int ld = leapDays(y);
                if (offset < ld) { leap = true; break; }
                offset -= ld;
            }
            m++;
        }
        return new int[]{y, m, (int) offset + 1, leap ? 1 : 0};
    }

    /** 中文月名 */
    public static String monthName(int m) {
        String[] n = {"", "正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊"};
        return m >= 1 && m <= 12 ? n[m] + "月" : m + "月";
    }

    public static String dayName(int d) {
        String[] n1 = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        if (d <= 10) return n1[d];
        if (d == 20) return "二十";
        if (d == 30) return "三十";
        if (d < 20) return "十" + n1[d - 10];
        return "廿" + n1[d - 20];
    }
}
