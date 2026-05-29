package com.hengde.activity.dto;

import lombok.Data;

import java.util.List;

/**
 * 统一签退入参。{@code volunteerIds} 为空 = 对全体已签到未签退者签退；非空 = 仅对指定志愿者签退。
 *
 * @author hengde
 */
@Data
public class BulkCheckOutDTO {

    /** 指定签退的志愿者 id；null/空表示全体 */
    private List<Long> volunteerIds;
}
