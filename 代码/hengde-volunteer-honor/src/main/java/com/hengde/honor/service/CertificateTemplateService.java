package com.hengde.honor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.oss.FileStorageService;
import com.hengde.common.oss.FileValidator;
import com.hengde.honor.dao.HonorCertificateTemplateMapper;
import com.hengde.honor.dto.CertificateTemplateSaveDTO;
import com.hengde.honor.entity.HonorCertificateTemplate;
import com.hengde.honor.vo.CertificateTemplateVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 证书电子样本管理（Row 36 F 列第 ③ 项「设置某个活动的电子样本」）。
 *
 * @author hengde
 */
@Service
public class CertificateTemplateService {

    /** 样本 PDF 上限 20MB——比证书本身宽松些，样本常含高分辨率底图与公章 */
    private static final long MAX_TEMPLATE_BYTES = 20L * 1024 * 1024;

    /** 样本私有对象的目录前缀；{@link #uploadTemplateFile} 产出、create/update 校验，两处必须一致 */
    private static final String TEMPLATE_KEY_PREFIX = "certificate-template/";

    private HonorCertificateTemplateMapper templateMapper;
    private FileStorageService fileStorageService;

    @Autowired
    public void setTemplateMapper(HonorCertificateTemplateMapper templateMapper) {
        this.templateMapper = templateMapper;
    }

    @Autowired
    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /** 样本列表（按作用域键排序，global 排在最后便于一眼看清哪些活动有专属样本）。 */
    public List<CertificateTemplateVO> list() {
        return templateMapper.selectList(Wrappers.<HonorCertificateTemplate>lambdaQuery()
                        .orderByAsc(HonorCertificateTemplate::getScopeKey))
                .stream().map(CertificateTemplateService::toVo).toList();
    }

    /** 新增样本。 */
    @Transactional(rollbackFor = Exception.class)
    public Long create(CertificateTemplateSaveDTO dto, Long operatorId) {
        requireTemplateObjectKey(dto.getFileKey());
        HonorCertificateTemplate t = new HonorCertificateTemplate();
        t.setScopeKey(resolveScopeKey(dto));
        t.setName(dto.getName());
        t.setFileKey(dto.getFileKey());
        t.setLayout(dto.getLayout());
        t.setEnabled(dto.getEnabled() == null ? 1 : dto.getEnabled());
        t.setCreateBy(operatorId);
        try {
            templateMapper.insert(t);
        } catch (DuplicateKeyException e) {
            // uk_template_active_scope 只约束【未删】的行（生成列 active_scope_key），
            // 故这里撞键必然是「确实存在一个在用的样本」，而不是「删掉的那条还占着位子」。
            // V31 之前用的是裸 UNIQUE(scope_key)，软删行继续占键，管理员删掉再建会收到这句话，
            // 可列表里又看不到那条（已软删）——既建不了也改不了，界面上死锁。
            throw new BusinessException("该作用域已存在电子样本，请直接修改它");
        }
        return t.getId();
    }

    /**
     * 上传样本文件到<b>私有</b>对象存储，返回可填进 {@code fileKey} 的 key。
     *
     * <p><b>为什么必须单开这个入口</b>：既有的 {@code POST /a/files/upload} 走
     * {@code FileStorageService#upload}，会按 {@code hengde.oss.public-read} 给对象打<b>公共读 ACL</b>
     * 并返回 URL；而样本要作为证书底图被服务端回读，且不该对全网可取。
     * 没有这个入口，管理员<b>根本造不出一个合法的 {@code fileKey}</b>——
     * 样本管理的四个接口就全是摆设，证书也永远生成不出来。</p>
     */
    public String uploadTemplateFile(MultipartFile file) {
        FileValidator.validate(file, Set.of("pdf"), MAX_TEMPLATE_BYTES);
        String objectKey = TEMPLATE_KEY_PREFIX + UUID.randomUUID() + ".pdf";
        try {
            fileStorageService.uploadPrivate(file.getBytes(), objectKey, "application/pdf");
        } catch (IOException e) {
            throw new BusinessException("读取上传文件失败：" + e.getMessage());
        }
        return objectKey;
    }

    /** 修改样本。作用域键不允许改——改了等于把这份样本挪给另一个活动，应当新建。 */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, CertificateTemplateSaveDTO dto) {
        HonorCertificateTemplate exist = templateMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException("电子样本不存在");
        }
        // 【不能静默忽略 activityId】DTO 里带着这个字段，前端改了它却毫无反应，
        // 管理员会以为「已经把样本挪到另一个活动了」，实际半点没动——
        // 而这种错要等到那个活动发不出证书时才暴露。要么明确拒绝，要么支持；这里选择明确拒绝。
        String requested = resolveScopeKey(dto);
        if (!requested.equals(exist.getScopeKey())) {
            throw new BusinessException("电子样本的作用域不可修改（当前：" + exist.getScopeKey()
                    + "），请为新作用域另建一份");
        }
        requireTemplateObjectKey(dto.getFileKey());
        // 【用 UpdateWrapper 而不是 updateById】updateById 会跳过值为 null 的字段，
        // 于是 layout 一经设置就再也清不掉——前端把坐标配置清空、保存后毫无反应。
        // 这正是 CLAUDE.md 里记着的那条（MedalService 的同款，当时被测试当场抓到）。
        // enabled 是 NOT NULL 列，不传时保持原值，故只在有值时才 set。
        int rows = templateMapper.update(null, Wrappers.<HonorCertificateTemplate>lambdaUpdate()
                .set(HonorCertificateTemplate::getName, dto.getName())
                .set(HonorCertificateTemplate::getFileKey, dto.getFileKey())
                .set(HonorCertificateTemplate::getLayout, dto.getLayout())
                .set(dto.getEnabled() != null, HonorCertificateTemplate::getEnabled, dto.getEnabled())
                // 走 UpdateWrapper 时 MetaObjectHandler 不会触发，update_time 得自己写
                .set(HonorCertificateTemplate::getUpdateTime, java.time.LocalDateTime.now())
                .eq(HonorCertificateTemplate::getId, id));
        // 【必须查影响行数】UPDATE 经 @TableLogic 会带上 is_deleted = 0，而开头那次 selectById
        // 读的是快照，两者之间隔着别人的一次提交：并发删除时这里 0 行、什么也没写，
        // 接口却报「修改成功」。与批量上传里刚修掉的那处是同一形状，只是在这个类里。
        if (rows != 1) {
            throw new BusinessException("电子样本不存在或已被删除，本次修改未生效");
        }
    }

    /** 删除样本（逻辑删除）。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (templateMapper.deleteById(id) != 1) {
            throw new BusinessException("电子样本不存在");
        }
    }

    /**
     * 校验 {@code fileKey} 确实来自 {@link #uploadTemplateFile}。
     *
     * <p><b>理由与 {@link #resolveScopeKey} 那条完全一样</b>：不收前端能随手写出的自由字符串，
     * 否则填错一个 key 要等到<b>那个活动第一次出证</b>时才报「文件读取失败」——
     * 而那时是志愿者在点下载，不是管理员在配置界面。上传入口是产出合法 key 的唯一途径，
     * 故这里以它的前缀为准。</p>
     *
     * <p><b>为什么不顺手试读一次对象</b>：试读只能证明「此刻可读」。样本文件在保存之后
     * 一样可能被从桶里删掉，所以它给不了保证，真正的保证是渲染时读不到就拒绝出证
     * （已有用例 {@code download_whenTemplateFileMissing_isRejected} 压着）。
     * 用一次可能拉取 20MB 的读取去换一个假的确定性，不划算。</p>
     */
    private static void requireTemplateObjectKey(String fileKey) {
        if (fileKey == null || !fileKey.startsWith(TEMPLATE_KEY_PREFIX)) {
            throw new BusinessException("样本文件 key 非法：请先调用「上传样本 PDF」接口，"
                    + "并原样使用它返回的 key（应以 " + TEMPLATE_KEY_PREFIX + " 开头）");
        }
    }

    /**
     * 作用域键：传了 activityId 就是 {@code activity:{id}}，否则是全局默认。
     *
     * <p>由服务端组装、<b>不收前端直接传来的 scopeKey</b>：那样前端可以写出任意字符串，
     * 造出一堆永远匹配不上的作用域，而错误要到「生成证书时找不到样本」才暴露。</p>
     */
    private static String resolveScopeKey(CertificateTemplateSaveDTO dto) {
        return dto.getActivityId() == null
                ? HonorCertificateTemplate.SCOPE_GLOBAL
                : HonorCertificateTemplate.activityScope(dto.getActivityId());
    }

    private static CertificateTemplateVO toVo(HonorCertificateTemplate t) {
        CertificateTemplateVO vo = new CertificateTemplateVO();
        vo.setId(t.getId());
        vo.setScopeKey(t.getScopeKey());
        vo.setName(t.getName());
        vo.setFileKey(t.getFileKey());
        vo.setLayout(t.getLayout());
        vo.setEnabled(t.getEnabled());
        vo.setCreateTime(t.getCreateTime());
        String prefix = HonorCertificateTemplate.SCOPE_ACTIVITY_PREFIX;
        if (t.getScopeKey() != null && t.getScopeKey().startsWith(prefix)) {
            vo.setActivityId(Long.valueOf(t.getScopeKey().substring(prefix.length())));
        }
        return vo;
    }
}
