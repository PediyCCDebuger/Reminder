package cn.nnci.addintimer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 依据规则计算下一次（或后 N 次）触发时刻。纯 Java，无 Android 依赖。
 * 采用"逐日匹配"：从 after 当天开始最多扫描 1500 天，逻辑与
 * GSW/AddinTimer/bu.b(cv) 的六种模式分支一致，但实现更直白、可测。
 */
public final class Occurrence {
    private static final int HORIZON_DAYS = 1500;

    private Occurrence() {}

    /** AddinTimer 周几编码：0=周日 .. 6=周六；java.time DayOfWeek: 1=周一 .. 7=周日 */
    private static int weekIndex(LocalDate d) {
        return d.getDayOfWeek().getValue() % 7;
    }

    public static boolean matches(Rule r, LocalDate d) {
        switch (r.mode) {
            case Rule.MODE_WEEK:
                return has(r.weekdays, weekIndex(d));
            case Rule.MODE_ONCE:
                return d.getYear() == r.sy && d.getMonthValue() == r.sm && d.getDayOfMonth() == r.sd;
            case Rule.MODE_DAYS: {
                LocalDate base;
                try { base = LocalDate.of(r.by, Math.max(1, Math.min(12, r.bm)),
                        Math.max(1, Math.min(28, r.bd))); }
                catch (Exception e) { return false; }
                if (d.isBefore(base)) return false;
                long delta = java.time.temporal.ChronoUnit.DAYS.between(base, d);
                if (r.interval <= 0 || delta % r.interval != 0) return false;
                if (has(r.exclWeek, weekIndex(d))) return false;
                if (has(r.exclDays, d.getDayOfMonth() - 1)) return false;
                return true;
            }
            case Rule.MODE_YEAR_MONTH: {
                if (!has(r.months, d.getMonthValue() - 1)) return false;
                if (r.byWeek == 1) {
                    if (!has(r.nthWeekday, weekIndex(d))) return false;
                    int nth = (d.getDayOfMonth() - 1) / 7;   // 0-based 第几个该星期几
                    int lastDay = d.lengthOfMonth();
                    boolean isLast = (lastDay - d.getDayOfMonth()) / 7 == 0;
                    return has(r.nthWeek, nth) || (isLast && has(r.nthWeek, 4));
                }
                if (has(r.monthDays, d.getDayOfMonth() - 1)) return true;
                // 原版行为：选中"31号"(索引30)时，小月自动落在月末；新增"最后一天"(索引31)也落在月末
                if (d.getDayOfMonth() == d.lengthOfMonth()
                        && (has(r.monthDays, 30) || has(r.monthDays, 31))) return true;
                return false;
            }
            case Rule.MODE_LUNAR: {
                // 3.6.8：改用公历反算农历（fromSolar），避免旧实现把农历年等同于公历年、
                // 导致农历 11/12 月（公历落在次年）永远不匹配、永不触发。
                int[] ld = Lunar.fromSolar(d);
                if (ld == null) return false;
                if (ld[0] < Lunar.MIN_YEAR || ld[0] > Lunar.MAX_YEAR) return false;
                if (ld[1] != r.lunarMonth) return false;
                if (ld[2] != r.lunarDay) return false;
                if ((ld[3] == 1) != (r.lunarLeap == 1)) return false;
                return true;
            }
            case Rule.MODE_TARGET:
                return d.getYear() == r.ey && d.getMonthValue() == r.em && d.getDayOfMonth() == r.ed;
            default:
                return false;
        }
    }

    private static boolean has(String csv, int value) {
        for (int x : Rule.list(csv)) if (x == value) return true;
        return false;
    }

    /** 返回 after 之后（严格大于）的 count 个触发时刻；不足则有多少给多少 */
    public static List<LocalDateTime> next(Rule r, int hour, int minute, LocalDateTime after, int count) {
        List<LocalDateTime> out = new ArrayList<>();
        LocalDate d = after.toLocalDate();
        for (int k = 0; k < HORIZON_DAYS && out.size() < count; k++) {
            LocalDate day = d.plusDays(k);
            if (day.getYear() > Lunar.MAX_YEAR - 1) break;
            if (!matches(r, day)) continue;
            LocalDateTime t = day.atTime(Math.max(0, Math.min(23, hour)),
                    Math.max(0, Math.min(59, minute)));
            if (t.isAfter(after)) out.add(t);
        }
        return out;
    }

    public static LocalDateTime first(Rule r, int hour, int minute, LocalDateTime after) {
        List<LocalDateTime> l = next(r, hour, minute, after, 1);
        return l.isEmpty() ? null : l.get(0);
    }
}
