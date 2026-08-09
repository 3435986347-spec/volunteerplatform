package com.hengde.honor.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.auth.entity.VolunteerSanction;
import com.hengde.auth.service.SanctionQueryService;
import com.hengde.common.result.Result;
import com.hengde.honor.dto.AppealSubmitDTO;
import com.hengde.honor.service.RewardPunishService;
import com.hengde.honor.vo.RewardPunishVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-奖惩中心（{@code /v/honor/reward-punishes}）。
 *
 * <p>xlsx Row 41「奖惩中心：各类违规记录和奖励」；原型 P109「奖惩记录」。</p>
 *
 * <p><b>本组接口刻意不受「拒绝使用本程序」处置的拦截</b>：Row 41 F 给了志愿者 7 天申诉期，
 * 而申诉就在这里提交。若最重的那条处置把这里也挡掉，被罚得最重的人恰恰成了唯一无法申诉的人。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-奖惩中心")
@RestController
@RequestMapping("/v/honor")
public class RewardPunishController {

    private RewardPunishService rewardPunishService;
    private SanctionQueryService sanctionQueryService;

    @Autowired
    public void setRewardPunishService(RewardPunishService rewardPunishService) {
        this.rewardPunishService = rewardPunishService;
    }

    @Autowired
    public void setSanctionQueryService(SanctionQueryService sanctionQueryService) {
        this.sanctionQueryService = sanctionQueryService;
    }

    @Operation(summary = "我的奖惩记录（只返回已通过组织部审核的）")
    @GetMapping("/reward-punishes")
    public Result<List<RewardPunishVO>> myRecords() {
        return Result.ok(rewardPunishService.myRecords(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "我当前生效中的处置（到期即自动消失，不依赖定时任务）")
    @GetMapping("/sanctions")
    public Result<List<VolunteerSanction>> mySanctions() {
        return Result.ok(sanctionQueryService.activeSanctions(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "对处罚提交申诉（自处罚生效起 7 天内；奖励无需申诉）")
    @PostMapping("/reward-punishes/{id}/appeal")
    public Result<Void> appeal(@PathVariable Long id, @RequestBody @Valid AppealSubmitDTO dto) {
        rewardPunishService.appeal(id, StpUtil.getLoginIdAsLong(), dto);
        return Result.ok();
    }
}
