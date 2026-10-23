package com.hengde.activity.service;

import com.hengde.activity.constant.PointSourceType;
import com.hengde.common.exception.BusinessException;
import com.hengde.organization.biz.constant.GroupFee;
import com.hengde.organization.biz.service.GroupCreationFee;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * {@link GroupCreationFee} 的实现：发起小组扣分、驳回退分，都记在积分账本上（来源 {@link PointSourceType#GROUP_CREATE}，消费类）。
 *
 * @author hengde
 */
@Service
public class GroupCreationFeeService implements GroupCreationFee {

    private PointService pointService;

    @Autowired
    public void setPointService(PointService pointService) {
        this.pointService = pointService;
    }

    @Override
    public String pointLockKey(Long volunteerId) {
        return PointService.LOCK_KEY_PREFIX + volunteerId;
    }

    @Override
    public void charge(Long volunteerId, Long groupId, String groupName) {
        // 积分锁在开事务之前就取到了，事务的读视图晚于其它扣减的提交，这里读到的就是最新余额（与 PointService.assertDeductible 同一口径）
        int balance = pointService.balanceOf(volunteerId);
        if (balance < GroupFee.CREATE_COST) {
            throw new BusinessException("积分不足：发起小组需要 " + GroupFee.CREATE_COST + " 积分，当前 " + balance);
        }
        pointService.record(volunteerId, -GroupFee.CREATE_COST, PointSourceType.GROUP_CREATE, groupId,
                "发起小组「" + groupName + "」", PointSourceType.OPERATOR_SYSTEM, null);
    }

    @Override
    public void refund(Long volunteerId, Long groupId, String groupName) {
        Integer charged = pointService.amountBySource(PointSourceType.GROUP_CREATE, groupId);
        if (charged == null || charged >= 0) {
            return;
        }
        pointService.record(volunteerId, -charged, PointSourceType.GROUP_CREATE, null,
                PointSourceType.GROUP_REFUND_REQUEST_PREFIX + groupId,
                "小组「" + groupName + "」未通过审核，退回发起积分", PointSourceType.OPERATOR_SYSTEM, null);
    }
}
