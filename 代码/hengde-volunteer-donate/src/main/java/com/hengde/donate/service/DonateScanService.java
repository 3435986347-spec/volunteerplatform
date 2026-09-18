package com.hengde.donate.service;

import com.hengde.common.exception.BusinessException;
import com.hengde.donate.constant.DonateCodes;
import com.hengde.donate.entity.DonateBarcodeCatalog;
import com.hengde.donate.vo.DonateFlowVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 扫码识别：扫码页只有一个输入框，扫到的可能是物品专属码、箱码、快递单号、商品条码中的任何一种。
 * 先告诉前端「这是什么」，再由人确认要做哪个动作——到货、装箱、送达都是改不回来的事，
 * 值得多一眼确认（这也是动作接口按 id 而识别单独一个接口的原因）。
 *
 * <p>我们生成的两种码靠前缀不查库就能分流（{@link DonateCodes#kindOf}）；
 * 其余的先当快递单号查运单，查不到再查商品条码库。</p>
 *
 * @author hengde
 */
@Service
public class DonateScanService {

    private DonateItemService itemService;
    private DonateBoxService boxService;
    private DonateShipmentService shipmentService;
    private DonateMasterDataService masterDataService;

    @Autowired
    public void setItemService(DonateItemService itemService) {
        this.itemService = itemService;
    }

    @Autowired
    public void setBoxService(DonateBoxService boxService) {
        this.boxService = boxService;
    }

    @Autowired
    public void setShipmentService(DonateShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @Autowired
    public void setMasterDataService(DonateMasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    public DonateFlowVOs.ScanResult resolve(String raw) {
        String code = DonateCodes.normalize(raw);
        if (!StringUtils.hasText(code)) {
            throw new BusinessException("码不能为空");
        }
        DonateFlowVOs.ScanResult r = new DonateFlowVOs.ScanResult();
        switch (DonateCodes.kindOf(code)) {
            case ITEM -> {
                DonateFlowVOs.Item item = itemService.findByCode(code);
                r.setKind(item == null ? "UNKNOWN" : "ITEM");
                if (item != null) {
                    r.setId(item.getId());
                    r.setItem(item);
                    r.setSummary("物品：" + item.getName() + "（" + item.getStatusLabel() + "）");
                } else {
                    r.setSummary("查无此物品码");
                }
            }
            case BOX -> {
                DonateFlowVOs.Box box = boxService.findByCode(code);
                r.setKind(box == null ? "UNKNOWN" : "BOX");
                if (box != null) {
                    r.setId(box.getId());
                    r.setBox(box);
                    r.setSummary("箱子：" + box.getBoxCode() + "（" + box.getStatusLabel() + "，" + box.getItemCount() + " 件）");
                } else {
                    r.setSummary("查无此箱码");
                }
            }
            default -> {
                List<DonateFlowVOs.Shipment> shipments = shipmentService.findByExpressNo(code);
                if (!shipments.isEmpty()) {
                    r.setKind("SHIPMENT");
                    r.setShipments(shipments);
                    r.setId(shipments.size() == 1 ? shipments.get(0).getId() : null);
                    r.setSummary(shipments.size() == 1
                            ? "包裹：" + shipments.get(0).getDonorName() + " 寄出（" + shipments.get(0).getStatusLabel() + "）"
                            : "该单号对应 " + shipments.size() + " 个包裹，请选择");
                } else {
                    DonateBarcodeCatalog c = masterDataService.findCatalog(raw);
                    if (c != null) {
                        r.setKind("CATALOG");
                        r.setId(c.getId());
                        r.setCatalogName(c.getName());
                        r.setSummary("商品条码：" + c.getName());
                    } else {
                        r.setKind("UNKNOWN");
                        r.setSummary("不认识这个码");
                    }
                }
            }
        }
        return r;
    }
}
