package com.hengde.organization.biz.service;

/**
 * 发起小组的积分扣与退（费用见 {@link com.hengde.organization.biz.constant.GroupFee}）。
 *
 * <p>积分账本在 activity 模块，而 activity 依赖 organization——这里反过来定一个接口，由 activity 实现，
 * 免得两个模块互相依赖。organization 自己的测试上下文里没有实现，{@link GroupService} 那时不扣分。</p>
 *
 * @author hengde
 */
public interface GroupCreationFee {

    /** 扣分要持的积分锁（与手工扣分、商城兑换同一把），{@link GroupService} 在开事务之前取。 */
    String pointLockKey(Long volunteerId);

    /** 在调用方事务内扣分；余额不够抛业务异常（整笔建组回滚）。调用方须已持有 {@link #pointLockKey}。 */
    void charge(Long volunteerId, Long groupId, String groupName);

    /** 在调用方事务内退回当初扣的分；没扣过（改造前发起的、后台导入的）就什么都不做。幂等。 */
    void refund(Long volunteerId, Long groupId, String groupName);
}
