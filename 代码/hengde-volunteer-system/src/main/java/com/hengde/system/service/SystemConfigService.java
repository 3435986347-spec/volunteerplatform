package com.hengde.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengde.auth.service.AdminQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.system.constant.SystemCodes;
import com.hengde.system.dao.SysConfigMapper;
import com.hengde.system.dao.SysSerialMapper;
import com.hengde.system.dto.SystemDTOs;
import com.hengde.system.entity.SysConfig;
import com.hengde.system.entity.SysSerial;
import com.hengde.system.vo.SystemVOs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台可配的小块配置：界面水印（Row 78）与后台菜单排序（Row 77），外加编号段一览（Row 75）。
 *
 * <p><b>水印文字由后端算</b>（{@link SystemVOs.Watermark#getText()}）：前端自己拼的话，手机尾号要先把手机号发到前端，
 * 而水印的用途正是「谁把这一屏拍出去了」——为了防泄露先泄露一次是说不通的。</p>
 *
 * <p>配置<b>整份覆盖</b>：这两样都没有查询维度，只有「读当前这份」和「保存这一份」。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class SystemConfigService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SysConfigMapper configMapper;
    private SysSerialMapper serialMapper;
    private AdminQueryService adminQueryService;

    @Autowired
    public void setConfigMapper(SysConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    @Autowired
    public void setSerialMapper(SysSerialMapper serialMapper) {
        this.serialMapper = serialMapper;
    }

    @Autowired
    public void setAdminQueryService(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    // ================= 水印 =================

    /** 读水印设置；给了 adminId 就连这个人的水印文字一起算好。 */
    public SystemVOs.Watermark watermark(Long adminId) {
        Map<String, Object> raw = readMap(SystemCodes.CONFIG_WATERMARK);
        SystemVOs.Watermark vo = new SystemVOs.Watermark();
        vo.setEnabled(bool(raw.get("enabled"), true));
        vo.setShowName(bool(raw.get("showName"), true));
        vo.setShowDepartment(bool(raw.get("showDepartment"), true));
        vo.setShowPhoneTail(bool(raw.get("showPhoneTail"), true));
        vo.setShowDate(bool(raw.get("showDate"), false));
        vo.setFontSize(intOf(raw.get("fontSize"), 14));
        vo.setOpacityPercent(intOf(raw.get("opacityPercent"), 8));
        vo.setRotateDegree(intOf(raw.get("rotateDegree"), -20));
        vo.setText(adminId == null ? null : textFor(vo, adminId));
        return vo;
    }

    public void saveWatermark(SystemDTOs.WatermarkSave dto, Long adminId) {
        requireOperator(adminId);
        SystemVOs.Watermark current = watermark(null);
        Map<String, Object> value = new HashMap<>();
        value.put("enabled", dto.getEnabled() == null ? current.isEnabled() : dto.getEnabled());
        value.put("showName", dto.getShowName() == null ? current.isShowName() : dto.getShowName());
        value.put("showDepartment", dto.getShowDepartment() == null ? current.isShowDepartment() : dto.getShowDepartment());
        value.put("showPhoneTail", dto.getShowPhoneTail() == null ? current.isShowPhoneTail() : dto.getShowPhoneTail());
        value.put("showDate", dto.getShowDate() == null ? current.isShowDate() : dto.getShowDate());
        value.put("fontSize", dto.getFontSize() == null ? current.getFontSize() : dto.getFontSize());
        value.put("opacityPercent", dto.getOpacityPercent() == null ? current.getOpacityPercent() : dto.getOpacityPercent());
        value.put("rotateDegree", dto.getRotateDegree() == null ? current.getRotateDegree() : dto.getRotateDegree());
        write(SystemCodes.CONFIG_WATERMARK, value, adminId);
    }

    /** 这个人的水印文字：姓名 · 部门 · 手机尾号 · 日期（按开关取）。 */
    private String textFor(SystemVOs.Watermark cfg, Long adminId) {
        if (!cfg.isEnabled()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (cfg.isShowName()) {
            String name = adminQueryService.listNamesByIds(List.of(adminId)).get(adminId);
            if (StringUtils.hasText(name)) {
                parts.add(name);
            }
        }
        if (cfg.isShowDepartment()) {
            String dept = adminQueryService.departmentOf(adminId);
            if (StringUtils.hasText(dept)) {
                parts.add(dept);
            }
        }
        if (cfg.isShowPhoneTail()) {
            String phone = adminQueryService.listPhonesByIds(List.of(adminId)).get(adminId);
            if (StringUtils.hasText(phone) && phone.length() >= 4) {
                // 只给后四位：水印是要能认出人，不是把号码贴到屏幕上
                parts.add(phone.substring(phone.length() - 4));
            }
        }
        if (cfg.isShowDate()) {
            parts.add(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    // ================= 菜单排序 =================

    public SystemVOs.MenuOrder menuOrder() {
        SystemVOs.MenuOrder vo = new SystemVOs.MenuOrder();
        List<Map<String, Object>> rows = readList(SystemCodes.CONFIG_MENU_ORDER);
        for (Map<String, Object> row : rows) {
            SystemVOs.MenuItem item = new SystemVOs.MenuItem();
            item.setKey(String.valueOf(row.get("key")));
            item.setName(row.get("name") == null ? null : String.valueOf(row.get("name")));
            item.setSort(intOf(row.get("sort"), 0));
            item.setVisible(bool(row.get("visible"), true));
            vo.getItems().add(item);
        }
        vo.getItems().sort(Comparator.comparing(SystemVOs.MenuItem::getSort, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SystemVOs.MenuItem::getKey, Comparator.nullsLast(Comparator.naturalOrder())));
        return vo;
    }

    public void saveMenuOrder(SystemDTOs.MenuOrderSave dto, Long adminId) {
        requireOperator(adminId);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (SystemDTOs.MenuOrderSave.Item item : dto.getItems()) {
            String key = item.getKey() == null ? "" : item.getKey().trim();
            if (key.isEmpty()) {
                throw new BusinessException("菜单键不能为空");
            }
            if (seen.contains(key)) {
                // 同一个键给两个次序，前端按谁渲染都说得通，那就是没定——直接拒绝
                throw new BusinessException("菜单键重复：" + key);
            }
            seen.add(key);
            Map<String, Object> row = new HashMap<>();
            row.put("key", key);
            row.put("name", item.getName());
            row.put("sort", item.getSort() == null ? 0 : item.getSort());
            row.put("visible", item.getVisible() == null || item.getVisible());
            rows.add(row);
        }
        write(SystemCodes.CONFIG_MENU_ORDER, rows, adminId);
    }

    // ================= 编号段 =================

    public List<SystemVOs.Serial> serials() {
        List<SystemVOs.Serial> out = new ArrayList<>();
        for (SysSerial s : serialMapper.selectList(null)) {
            SystemVOs.Serial vo = new SystemVOs.Serial();
            vo.setSegment(s.getSegment());
            vo.setName(s.getName());
            vo.setCurrentNo(s.getCurrentNo());
            out.add(vo);
        }
        out.sort(Comparator.comparing(SystemVOs.Serial::getSegment));
        return out;
    }

    // ================= 内部 =================

    private Map<String, Object> readMap(String key) {
        SysConfig c = configMapper.selectById(key);
        if (c == null || !StringUtils.hasText(c.getConfigValue())) {
            return Map.of();
        }
        try {
            return JSON.readValue(c.getConfigValue(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            // 配置读坏了就退回默认值：一段坏 JSON 不该让整个后台打不开
            log.warn("配置 {} 解析失败，按默认值处理", key, e);
            return Map.of();
        }
    }

    private List<Map<String, Object>> readList(String key) {
        SysConfig c = configMapper.selectById(key);
        if (c == null || !StringUtils.hasText(c.getConfigValue())) {
            return List.of();
        }
        try {
            return JSON.readValue(c.getConfigValue(), new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            log.warn("配置 {} 解析失败，按空处理", key, e);
            return List.of();
        }
    }

    private void write(String key, Object value, Long adminId) {
        try {
            configMapper.upsert(key, JSON.writeValueAsString(value), adminId);
        } catch (Exception e) {
            throw new BusinessException("配置保存失败");
        }
    }

    private static boolean bool(Object v, boolean def) {
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v));
    }

    private static Integer intOf(Object v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void requireOperator(Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
    }
}
