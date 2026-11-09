package cn.nnci.addintimer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AddinTimer 定时规则的 28 项 "#" 分隔串。
 *
 * 依据原 APK 反汇编：
 *   GSW/AddinTimer/ko.a()        : "%d#%s#%d#%d#%d#%d#%d#%d#%d#%s#%s#%d#%s#%s#%s#%d#%d#%d#%d#%d#%d#%d#%d#%s#%d#%d#%d#%d"
 *   GSW/AddinTimer/ko.a(String)  : 逐项反序列化
 *   GSW/AddinTimer/bu.b(cv)      : 模式 0..5 的下次触发日期计算
 *   TimerRulePropAct 的 DatePicker 回调把 month+1 入库，故日期字段的月份为 1-based
 */
public class Rule {
    public static final int MODE_WEEK = 0;      // 按周循环
    public static final int MODE_ONCE = 1;      // 指定日期
    public static final int MODE_DAYS = 2;      // 按天循环
    public static final int MODE_YEAR_MONTH = 3;// 按年月循环
    public static final int MODE_LUNAR = 4;     // 按阴历循环
    public static final int MODE_TARGET = 5;    // 倒计时 / 目标日期

    public static final String[] MODE_NAMES = {
            "按周循环", "指定日期", "按天循环", "按年月循环", "按阴历循环", "倒计时/目标日期"};
    public static final String[] WEEK_CN = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    public int mode;
    public String weekdays = "1,2,3,4,5";        // 1  b  周几 0=周日..6=周六
    public int sy, sm, sd;                       // 2,3,4  指定日期（月 1-based）
    public int interval = 1;                     // 5  f  按天循环间隔
    public int by, bm, bd;                       // 6,7,8 循环基准日（月 1-based）
    public String exclWeek = "";                 // 9  j  排除周几
    public String months = "0,1,2";              // 10 l  月份 0=1月..11=12月
    public int byWeek;                           // 11 m  0=按天计算 1=指周计算
    public String monthDays = "0,1,2";           // 12 n  日期 0=1号..30=31号
    public String nthWeek = "0";                 // 13 o  0..3=第1~4个，4=最后一个
    public String nthWeekday = "0";              // 14 p  周几
    public int lunarMonth = 1, lunarDay = 1;     // 15,16 q,r 农历月/日（1-based）
    public int lunarLeap;                        // 17 s  闰月标志
    public int ey, em, ed;                       // 18,19,20 t,u,v 结束/目标日期
    public int cdMinutes = 30;                   // 21 w
    public int hourCycle;                        // 22 x
    public String exclDays = "";                 // 23 k  排除的日期（0-based）
    public int endEnable;                        // 24 y
    public int cycleMinutes = 60;                // 25 z
    public int cycleEndH = 23, cycleEndM = 59;   // 26,27 A,B

    public Rule() {
        java.time.LocalDate n = java.time.LocalDate.now();
        sy = ey = n.getYear(); sm = em = n.getMonthValue(); sd = ed = n.getDayOfMonth();
        by = n.getYear(); bm = n.getMonthValue(); bd = n.getDayOfMonth();
    }

    public static Rule parse(String s) {
        String[] p = (s == null ? "" : s).split("#", -1);
        String[] v = new String[28];
        for (int i = 0; i < 28; i++) v[i] = i < p.length ? p[i] : "";
        Rule r = new Rule();
        r.mode = i(v[0]);
        r.weekdays = v[1];
        r.sy = i(v[2]); r.sm = i(v[3]); r.sd = i(v[4]);
        r.interval = i(v[5]);
        r.by = i(v[6]); r.bm = i(v[7]); r.bd = i(v[8]);
        r.exclWeek = v[9];
        r.months = v[10];
        r.byWeek = i(v[11]);
        r.monthDays = v[12];
        r.nthWeek = v[13];
        r.nthWeekday = v[14];
        r.lunarMonth = i(v[15]); r.lunarDay = i(v[16]); r.lunarLeap = i(v[17]);
        r.ey = i(v[18]); r.em = i(v[19]); r.ed = i(v[20]);
        r.cdMinutes = i(v[21]); r.hourCycle = i(v[22]);
        r.exclDays = v[23];
        r.endEnable = i(v[24]); r.cycleMinutes = i(v[25]);
        r.cycleEndH = i(v[26]); r.cycleEndM = i(v[27]);
        if (r.interval <= 0) r.interval = 1;
        if (r.lunarMonth < 1) r.lunarMonth = 1;
        if (r.lunarDay < 1) r.lunarDay = 1;
        return r;
    }

    private static int i(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    public static int[] list(String s) {
        if (s == null || s.length() == 0) return new int[0];
        String[] a = s.split(",");
        List<Integer> out = new ArrayList<>();
        for (String x : a) {
            try { out.add(Integer.parseInt(x.trim())); } catch (Exception ignored) {}
        }
        int[] r = new int[out.size()];
        for (int k = 0; k < r.length; k++) r[k] = out.get(k);
        return r;
    }

    private static boolean has(String s, int value) {
        for (int x : list(s)) if (x == value) return true;
        return false;
    }

    private static String join(String s) {
        return String.join("、", labels(s, null));
    }

    private static String[] labels(String csv, final String[] names) {
        int[] a = list(csv);
        String[] out = new String[a.length];
        for (int k = 0; k < a.length; k++) {
            out[k] = names == null ? String.valueOf(a[k])
                    : (a[k] >= 0 && a[k] < names.length ? names[a[k]] : String.valueOf(a[k]));
        }
        return out;
    }

    /** 生成与原版完全兼容的 28 项规则串 */
    public String serialize() {
        Object[] a = new Object[28];
        a[0] = mode; a[1] = weekdays;
        a[2] = sy; a[3] = sm; a[4] = sd; a[5] = interval;
        a[6] = by; a[7] = bm; a[8] = bd;
        a[9] = exclWeek; a[10] = months; a[11] = byWeek;
        a[12] = monthDays; a[13] = nthWeek; a[14] = nthWeekday;
        a[15] = lunarMonth; a[16] = lunarDay; a[17] = lunarLeap;
        a[18] = ey; a[19] = em; a[20] = ed;
        a[21] = cdMinutes; a[22] = hourCycle; a[23] = exclDays;
        a[24] = endEnable; a[25] = cycleMinutes; a[26] = cycleEndH; a[27] = cycleEndM;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 28; k++) {
            if (k > 0) sb.append('#');
            sb.append(a[k]);
        }
        return sb.toString();
    }

    public String describe() {
        switch (mode) {
            case MODE_WEEK:
                return "按周循环：" + String.join("、", labels(weekdays, WEEK_CN));
            case MODE_ONCE:
                return String.format(Locale.CHINA, "指定日期：%04d-%02d-%02d（一次性）", sy, sm, sd);
            case MODE_DAYS: {
                String s = String.format(Locale.CHINA, "按天循环：每 %d 天（基准 %04d-%02d-%02d）",
                        interval, by, bm, bd);
                if (exclWeek.length() > 0) s += "，排除" + String.join("、", labels(exclWeek, WEEK_CN));
                if (exclDays.length() > 0) s += "，排除每月 " + exclDays + " 号(0基)";
                return s;
            }
            case MODE_YEAR_MONTH: {
                int[] ms = list(months);
                StringBuilder mons = new StringBuilder();
                for (int k = 0; k < ms.length; k++) {
                    if (k > 0) mons.append("、");
                    mons.append(ms[k] + 1).append("月");
                }
                if (byWeek == 1) {
                    StringBuilder nth = new StringBuilder();
                    int[] ns = list(nthWeek);
                    for (int k = 0; k < ns.length; k++) {
                        if (k > 0) nth.append("、");
                        nth.append(ns[k] == 4 ? "最后" : "第" + (ns[k] + 1));
                    }
                    return "按年月循环（指周计算）：" + mons + " 的 " + nth + "个 "
                            + String.join("、", labels(nthWeekday, WEEK_CN));
                }
                StringBuilder days = new StringBuilder();
                int[] ds = list(monthDays);
                for (int k = 0; k < ds.length; k++) {
                    if (k > 0) days.append("、");
                    days.append(ds[k] + 1);
                }
                return "按年月循环（按天计算）：" + mons + " 的 " + days + " 号";
            }
            case MODE_LUNAR:
                return "按阴历循环：每年 " + (lunarLeap == 1 ? "闰" : "")
                        + Lunar.monthName(lunarMonth) + Lunar.dayName(lunarDay);
            case MODE_TARGET:
                return String.format(Locale.CHINA, "目标日期：%04d-%02d-%02d（一次性）", ey, em, ed);
            default:
                return "未知模式 " + mode;
        }
    }

    /** 编辑界面用的模式参数提示 */
    public String paramHint() {
        switch (mode) {
            case MODE_WEEK: return "周几，逗号分隔：0=周日 1=周一 … 6=周六";
            case MODE_ONCE: return "年月日，如 2027-05-08";
            case MODE_DAYS: return "间隔天数，如 1 表示每天；可选追加排除项";
            case MODE_YEAR_MONTH: return "按天计算：月份(0基)/日期(0基)；指周计算：月份/第几个/周几";
            case MODE_LUNAR: return "农历月,日，如 8,15；闰月请勾选";
            case MODE_TARGET: return "目标年月日，如 2027-01-01";
            default: return "";
        }
    }
}
