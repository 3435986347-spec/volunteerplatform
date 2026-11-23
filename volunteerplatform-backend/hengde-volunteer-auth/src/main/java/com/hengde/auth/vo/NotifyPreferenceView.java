package com.hengde.auth.vo;

/**
 * 一个话题的订阅状态。
 *
 * @param topic      话题名（{@code NotifyTopic}）
 * @param label      给人看的名字
 * @param optional   能不能关
 * @param smsEnabled 是否接收短信（不可关闭的恒为 true）
 * @author hengde
 */
public record NotifyPreferenceView(String topic, String label, boolean optional, boolean smsEnabled) {
}
