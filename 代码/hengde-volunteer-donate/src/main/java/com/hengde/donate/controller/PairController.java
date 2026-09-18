package com.hengde.donate.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.dto.ShipmentRegisterDTO;
import com.hengde.donate.vo.DonateFlowVOs;
import com.hengde.donate.service.CrowdfundService;
import com.hengde.donate.service.PairProjectService;
import com.hengde.donate.service.PairService;
import com.hengde.donate.vo.PairVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-助学助困结对与项目众筹（{@code /v/donate}，V3 结对批）。
 *
 * <p>仅需登录；志愿者 id 一律取自登录态。结对登记另要求已实名（服务层判定）。</p>
 *
 * <p><b>本批不含支付</b>：登记的是「我认捐多少」，由协会确认结对成立；捐款接 trade 在捐款批。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-结对与众筹")
@RestController
@RequestMapping("/v/donate")
public class PairController {

    private PairProjectService projectService;
    private PairService pairService;
    private CrowdfundService crowdfundService;

    @Autowired
    public void setProjectService(PairProjectService projectService) {
        this.projectService = projectService;
    }

    @Autowired
    public void setPairService(PairService pairService) {
        this.pairService = pairService;
    }

    @Autowired
    public void setCrowdfundService(CrowdfundService crowdfundService) {
        this.crowdfundService = crowdfundService;
    }

    @Operation(summary = "结对项目列表：tab 1助学 / 2助困 / 3助残 / 4结对成功（Row 10 四个页签）")
    @GetMapping("/pair-projects")
    public Result<PageResult<PairVOs.Project>> projects(@RequestParam(required = false) Integer tab,
                                                        PageQuery query) {
        return Result.ok(projectService.listForVolunteer(query, tab, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "结对中心详情（Row 34：这条结对 + 为它付过的每一笔款，含发票字段）")
    @GetMapping("/pairs/mine/{id}")
    public Result<PairVOs.PairCenter> myPairDetail(@PathVariable Long id) {
        return Result.ok(pairService.myPairDetail(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "我的结对记录（结对中心，Row 34）")
    @GetMapping("/pairs/mine")
    public Result<PageResult<PairVOs.PairRecord>> myPairs(PageQuery query) {
        return Result.ok(pairService.myPairs(StpUtil.getLoginIdAsLong(), query));
    }

    @Operation(summary = "结对项目详情（含我的登记与能否登记）")
    @GetMapping("/pair-projects/{id}")
    public Result<PairVOs.Project> projectDetail(@PathVariable Long id) {
        return Result.ok(projectService.detailForVolunteer(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "受助方来信（项目公开信 + 写给我的那些）")
    @GetMapping("/pair-projects/{id}/letters")
    public Result<PageResult<PairVOs.Letter>> letters(@PathVariable Long id, PageQuery query) {
        return Result.ok(projectService.lettersForVolunteer(id, StpUtil.getLoginIdAsLong(), query));
    }

    @Operation(summary = "结对登记（指定金额或全款；须已实名）")
    @PostMapping("/pair-projects/{id}/pairs")
    public Result<PairVOs.PairRecord> register(@PathVariable Long id, @Valid @RequestBody PairDTOs.Register dto) {
        return Result.ok(pairService.register(id, StpUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "撤回结对登记（仅「待确认」可撤；已成立请联系协会）")
    @DeleteMapping("/pair-projects/{id}/pairs")
    public Result<Void> withdraw(@PathVariable Long id) {
        pairService.withdraw(id, StpUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "众筹项目列表：tab 0全部 / 1进行中 / 2已结束（Row 16）")
    @GetMapping("/crowdfunds")
    public Result<PageResult<PairVOs.Crowdfund>> crowdfunds(@RequestParam(required = false) Integer tab,
                                                            PageQuery query) {
        return Result.ok(crowdfundService.listForVolunteer(query, tab));
    }

    @Operation(summary = "众筹项目详情与进度（已筹＝已到账捐款之和；接受捐物的项目带物资需求与收件信息）")
    @GetMapping("/crowdfunds/{id}")
    public Result<PairVOs.Crowdfund> crowdfundDetail(@PathVariable Long id) {
        return Result.ok(crowdfundService.detailForVolunteer(id));
    }

    @Operation(summary = "众筹捐物：登记寄出的物资 + 快递公司 + 单号（Row 16；须已实名，项目在募集中且接受捐物）")
    @PostMapping("/crowdfunds/{id}/goods-donations")
    public Result<DonateFlowVOs.Shipment> donateGoods(@PathVariable Long id,
                                                      @Valid @RequestBody ShipmentRegisterDTO dto) {
        return Result.ok(crowdfundService.registerGoods(id, StpUtil.getLoginIdAsLong(), dto));
    }
}
