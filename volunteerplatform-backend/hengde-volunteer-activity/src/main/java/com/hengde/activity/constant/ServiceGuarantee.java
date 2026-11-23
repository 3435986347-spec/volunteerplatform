package com.hengde.activity.constant;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 活动「服务保障」12 项的合法 key 与 CSV 互转（单一权威来源）。
 *
 * <p>发布活动时不定项选择（12 选 N），以逗号分隔 key 存 {@code activity.service_guarantees}。
 * key 与顺序须与小程序 {@code utils/service-guarantees.js}、详情页图标资产
 * （{@code assets/activity/guarantee/NN-key-{red,gray}.png}）完全一致。</p>
 *
 * @author hengde
 */
public final class ServiceGuarantee {

    /** 12 项合法 key，规范顺序（与前端 GUARANTEE_ORDER 完全一致） */
    public static final List<String> KEYS = List.of(
            "clothing", "water", "certificate", "training", "insurance", "traffic",
            "meal", "bus", "hotel", "tool", "checkup", "other");

    private static final Set<String> VALID = new LinkedHashSet<>(KEYS);

    private ServiceGuarantee() {
    }

    /**
     * {@code List<key>} → CSV：过滤未知 key、去重、按规范顺序排列后逗号拼接。
     * 入参 null / 空 / 全非法 → 返回 {@code null}（落库 NULL，不存空串）。
     */
    public static String toCsv(List<String> keys) {
        List<String> ordered = normalize(keys);
        return ordered.isEmpty() ? null : String.join(",", ordered);
    }

    /** CSV → {@code List<key>}：拆分、过滤未知、按规范顺序；null / 空 → 空列表。 */
    public static List<String> fromCsv(String csv) {
        if (!StringUtils.hasText(csv)) {
            return new ArrayList<>();
        }
        return normalize(List.of(csv.split(",")));
    }

    /** 过滤未知/空白 key、去重、按 {@link #KEYS} 规范顺序输出。 */
    private static List<String> normalize(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> picked = new LinkedHashSet<>();
        for (String k : keys) {
            if (k != null && VALID.contains(k.trim())) {
                picked.add(k.trim());
            }
        }
        List<String> ordered = new ArrayList<>();
        for (String k : KEYS) {
            if (picked.contains(k)) {
                ordered.add(k);
            }
        }
        return ordered;
    }
}
