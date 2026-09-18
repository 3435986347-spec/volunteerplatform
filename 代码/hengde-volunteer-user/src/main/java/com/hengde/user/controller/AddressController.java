package com.hengde.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.result.Result;
import com.hengde.user.dto.AddressSaveDTO;
import com.hengde.user.service.AddressService;
import com.hengde.user.vo.AddressVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-地址管理（{@code /v/user/addresses}，Row 40）。一律只动本人的地址。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-地址管理")
@RestController
@RequestMapping("/v/user/addresses")
public class AddressController {

    private AddressService addressService;

    @Autowired
    public void setAddressService(AddressService addressService) {
        this.addressService = addressService;
    }

    @Operation(summary = "我的地址（置顶在最前）")
    @GetMapping
    public Result<List<AddressVO>> list() {
        return Result.ok(addressService.list(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "新增地址（最多 20 个）")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody AddressSaveDTO dto) {
        return Result.ok(addressService.create(StpUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "修改地址")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AddressSaveDTO dto) {
        addressService.update(StpUtil.getLoginIdAsLong(), id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除地址")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        addressService.delete(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }

    @Operation(summary = "置顶（一人至多一条，置顶新的会取消旧的）")
    @PostMapping("/{id}/top")
    public Result<Void> top(@PathVariable Long id) {
        addressService.top(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }

    @Operation(summary = "取消置顶")
    @DeleteMapping("/{id}/top")
    public Result<Void> untop(@PathVariable Long id) {
        addressService.untop(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }
}
