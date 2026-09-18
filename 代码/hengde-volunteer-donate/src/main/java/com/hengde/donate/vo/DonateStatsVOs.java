package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 后台「数据汇总」里属捐赠的部分（Row 79，V3 收尾批）。口径见 {@code DonateStatsMapper}。
 *
 * @author hengde
 */
public final class DonateStatsVOs {

    private DonateStatsVOs() {
    }

    @Data
    @Schema(description = "捐赠数据汇总")
    public static class Summary {
        private Wish wish;
        private Pair pair;
        private Book book;
    }

    @Data
    @Schema(description = "微心愿数据")
    public static class Wish {
        @Schema(description = "发布总数（含已下架）")
        private long published;
        @Schema(description = "认领成功（此刻已认领 / 已实现的心愿）")
        private long claimed;
        @Schema(description = "其中已实现")
        private long realized;
        @Schema(description = "参与人数（认领过的去重人数，不含被撤销的认领）")
        private long participants;
        @Schema(description = "参与人次（认领次数，不含被撤销的）")
        private long participations;
        @Schema(description = "收到包裹（已确认到货）")
        private long parcels;
    }

    @Data
    @Schema(description = "结对数据")
    public static class Pair {
        @Schema(description = "发布总数（不含草稿）")
        private long published;
        @Schema(description = "结对成功（此刻结对成立的登记）")
        private long established;
        @Schema(description = "参与人次（全部登记次数）")
        private long participations;
        @Schema(description = "结对金额·认捐额（协会确认成立时累加）")
        private BigDecimal pledgedAmount;
        @Schema(description = "结对金额·到账额（捐款批起真正收到的钱）")
        private BigDecimal raisedAmount;
    }

    @Data
    @Schema(description = "捐书活动数据（与单个活动「本次活动数据」同口径，跨全部活动汇总）")
    public static class Book {
        @Schema(description = "参加人数（去重）")
        private long participants;
        @Schema(description = "收到包裹")
        private long parcels;
        @Schema(description = "收到课外书籍（件）")
        private long books;
        @Schema(description = "收到学习用品（件）")
        private long stationery;
        @Schema(description = "收到运动器材（件）")
        private long sports;
        @Schema(description = "修建书屋数：系统里没有「书屋」这个概念，恒为 null（见协会待确认清单㉒）")
        private Long bookHouses;
    }
}
