package com.hengde.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.AdminQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.oss.FileStorageService;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.system.constant.SystemCodes;
import com.hengde.system.dao.SysFileMapper;
import com.hengde.system.dao.SysFileShareMapper;
import com.hengde.system.dao.SysFolderGrantMapper;
import com.hengde.system.dao.SysFolderMapper;
import com.hengde.system.dto.SystemDTOs;
import com.hengde.system.entity.SysFile;
import com.hengde.system.entity.SysFileShare;
import com.hengde.system.entity.SysFolder;
import com.hengde.system.entity.SysFolderGrant;
import com.hengde.system.support.SystemProperties;
import com.hengde.system.vo.SystemVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件网盘（V4 系统治理批，Row 71「类似网盘 / 可以设权限 / 超级管理员有最高权限 / 可以分享、下载 /
 * 后台可以设置是否登录管理员账号才能打开 / 可以选择特定文件公开至小程序文件下载板块」+ Row 19 的文件开放时间与下载开关）。
 *
 * <p><b>授权沿着文件夹往下继承</b>：授在上级的权限对下级也算数——否则每建一个子文件夹都要重授一遍，
 * 漏授的那一个没有任何征兆（点进去是空的，看着像「里面没东西」）。超管不看授权表。</p>
 *
 * <p><b>「现在开不开放」按时间现算</b>（{@link SysFile#openAt}）：不靠定时任务把到点的文件改成开放——
 * 漏跑一次，该开的文件就一直关着，而这条纪律本项目已经在名单公示与处置到期上用过两次。</p>
 *
 * <p><b>分享链接是随机令牌不是 id</b>：拿 id 当链接等于把整个网盘按序号公开；能不能打开另有「要不要登录」开关（Row 71 原文），
 * 而下载计数那条 UPDATE 的影响行数是<b>撤销之后的最后一道闸</b>（同电子证书签发前那一下）。</p>
 *
 * @author hengde
 */
@Service
public class FileVaultService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SysFolderMapper folderMapper;
    private SysFileMapper fileMapper;
    private SysFolderGrantMapper grantMapper;
    private SysFileShareMapper shareMapper;
    private SerialNumberService serialNumberService;
    private SystemProperties properties;
    private AdminQueryService adminQueryService;
    private FileStorageService fileStorageService;

    @Autowired
    public void setFolderMapper(SysFolderMapper folderMapper) {
        this.folderMapper = folderMapper;
    }

    @Autowired
    public void setFileMapper(SysFileMapper fileMapper) {
        this.fileMapper = fileMapper;
    }

    @Autowired
    public void setGrantMapper(SysFolderGrantMapper grantMapper) {
        this.grantMapper = grantMapper;
    }

    @Autowired
    public void setShareMapper(SysFileShareMapper shareMapper) {
        this.shareMapper = shareMapper;
    }

    @Autowired
    public void setSerialNumberService(SerialNumberService serialNumberService) {
        this.serialNumberService = serialNumberService;
    }

    @Autowired
    public void setAdminQueryService(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    @Autowired
    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Autowired
    public void setProperties(SystemProperties properties) {
        this.properties = properties;
    }

    // ================= 文件夹 =================

    /** 我看得到的文件夹树（超管看全部）。 */
    public List<SystemVOs.Folder> tree(Long adminId) {
        List<SysFolder> all = folderMapper.selectAll();
        Map<Long, List<SysFolder>> byParent = new HashMap<>();
        all.forEach(f -> byParent.computeIfAbsent(f.getParentId(), k -> new ArrayList<>()).add(f));
        return buildTree(byParent, null, adminId, false, false);
    }

    public Long createFolder(SystemDTOs.FolderSave dto, Long adminId) {
        requireOperator(adminId);
        if (dto.getParentId() != null) {
            requireFolder(dto.getParentId());
            requireAccess(adminId, dto.getParentId(), true);
        } else if (!isSuperAdmin(adminId)) {
            // 根目录是全站结构，只有超管能加——不然每个人都能在顶层堆一个自己的文件夹
            throw new BusinessException("只有超级管理员能在根目录新建文件夹");
        }
        SysFolder folder = new SysFolder();
        folder.setParentId(dto.getParentId());
        folder.setName(dto.getName().trim());
        folder.setSort(dto.getSort() == null ? 0 : dto.getSort());
        folder.setCreateBy(adminId);
        folder.setCreateTime(LocalDateTime.now().withNano(0));
        folderMapper.insert(folder);
        return folder.getId();
    }

    public void renameFolder(Long id, SystemDTOs.FolderSave dto, Long adminId) {
        requireOperator(adminId);
        requireFolder(id);
        requireAccess(adminId, id, true);
        int rows = folderMapper.update(null, Wrappers.<SysFolder>lambdaUpdate()
                .eq(SysFolder::getId, id)
                .set(SysFolder::getName, dto.getName().trim())
                .set(dto.getSort() != null, SysFolder::getSort, dto.getSort())
                .set(SysFolder::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("文件夹不存在");
        }
    }

    /** 删文件夹：**有下级或有文件就不让删**，不连带删（同组织架构那一课：连带删是不可逆的）。 */
    public void deleteFolder(Long id, Long adminId) {
        requireOperator(adminId);
        requireFolder(id);
        requireAccess(adminId, id, true);
        if (folderMapper.countChildren(id) > 0) {
            throw new BusinessException("请先删除或移走下级文件夹");
        }
        if (fileMapper.countByFolder(id) > 0) {
            throw new BusinessException("文件夹里还有文件");
        }
        if (folderMapper.deleteById(id) != 1) {
            throw new BusinessException("文件夹不存在");
        }
    }

    // ================= 文件 =================

    public PageResult<SystemVOs.VaultFile> files(Long folderId, String keyword, PageQuery query, Long adminId) {
        requireFolder(folderId);
        requireAccess(adminId, folderId, false);
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        long total = fileMapper.countInFolder(folderId, kw);
        List<SysFile> rows = fileMapper.selectInFolder(folderId, kw,
                (long) (query.getPage() - 1) * query.getSize(), query.getSize());
        LocalDateTime now = LocalDateTime.now();
        List<SystemVOs.VaultFile> out = new ArrayList<>(rows.size());
        rows.forEach(f -> out.add(toVaultFile(f, now)));
        return PageResult.of(out, total, query.getPage(), query.getSize());
    }

    /** 把一个已上传的文件登记进网盘（上传本身走 api 的 `POST /a/files/upload?dir=vault`）。 */
    public Long addFile(SystemDTOs.FileSave dto, Long adminId) {
        requireOperator(adminId);
        requireFolder(dto.getFolderId());
        requireAccess(adminId, dto.getFolderId(), true);
        String url = dto.getFileUrl().trim();
        if (!fileStorageService.isOwnUpload(url, SystemCodes.DIR_VAULT)) {
            // 不校验的话网盘里能挂任意外链，而点开它的人以为那是协会的文件
            throw new BusinessException("文件请先通过后台上传");
        }
        SysFile file = new SysFile();
        file.setFolderId(dto.getFolderId());
        file.setSerialNo(serialNumberService.next(SystemCodes.SERIAL_SEGMENT_FILE, "文件网盘文件"));
        file.setName(dto.getName().trim());
        file.setFileUrl(url);
        file.setFileExt(extOf(dto.getName(), url));
        file.setFileSize(dto.getFileSize());
        file.setUploadBy(adminId);
        file.setPublished(0);
        file.setAllowDownload(1);
        file.setCreateTime(LocalDateTime.now().withNano(0));
        fileMapper.insert(file);
        return file.getId();
    }

    public void renameFile(Long id, String name, Long adminId) {
        requireOperator(adminId);
        SysFile file = requireFile(id);
        requireAccess(adminId, file.getFolderId(), true);
        if (!StringUtils.hasText(name)) {
            throw new BusinessException("请填写文件名");
        }
        fileMapper.update(null, Wrappers.<SysFile>lambdaUpdate()
                .eq(SysFile::getId, id)
                .set(SysFile::getName, name.trim())
                .set(SysFile::getUpdateTime, LocalDateTime.now()));
    }

    /** 移到别的文件夹：两边都要有写权限（只看目标的话，等于允许把别人文件夹里的东西搬走）。 */
    public void moveFile(Long id, Long targetFolderId, Long adminId) {
        requireOperator(adminId);
        SysFile file = requireFile(id);
        requireAccess(adminId, file.getFolderId(), true);
        requireFolder(targetFolderId);
        requireAccess(adminId, targetFolderId, true);
        fileMapper.update(null, Wrappers.<SysFile>lambdaUpdate()
                .eq(SysFile::getId, id)
                .set(SysFile::getFolderId, targetFolderId)
                .set(SysFile::getUpdateTime, LocalDateTime.now()));
    }

    public void deleteFile(Long id, Long adminId) {
        requireOperator(adminId);
        SysFile file = requireFile(id);
        requireAccess(adminId, file.getFolderId(), true);
        if (fileMapper.softDelete(id) != 1) {
            throw new BusinessException("文件不存在");
        }
    }

    /** 公开到小程序 + 开放时间窗口（Row 19）。 */
    public void publish(Long id, SystemDTOs.PublishSave dto, Long adminId) {
        requireOperator(adminId);
        SysFile file = requireFile(id);
        requireAccess(adminId, file.getFolderId(), true);
        boolean published = Boolean.TRUE.equals(dto.getPublished());
        if (published && dto.getPublishStart() != null && dto.getPublishEnd() != null
                && !dto.getPublishEnd().isAfter(dto.getPublishStart())) {
            throw new BusinessException("开放结束时间要晚于开始时间");
        }
        fileMapper.update(null, Wrappers.<SysFile>lambdaUpdate()
                .eq(SysFile::getId, id)
                .set(SysFile::getPublished, published ? 1 : 0)
                // 显式 set：撤销公开时把窗口一起清掉，否则下次再公开会悄悄套用上次的时间
                .set(SysFile::getPublishStart, published ? dto.getPublishStart() : null)
                .set(SysFile::getPublishEnd, published ? dto.getPublishEnd() : null)
                .set(SysFile::getAllowDownload, dto.getAllowDownload() == null || dto.getAllowDownload() ? 1 : 0)
                .set(SysFile::getUpdateTime, LocalDateTime.now()));
    }

    /** 志愿者端的「内置文件」（Row 19）：此刻在开放窗口里的；关掉下载的只给名字不给地址。 */
    public List<SystemVOs.OpenFile> openFiles() {
        LocalDateTime now = LocalDateTime.now();
        List<SystemVOs.OpenFile> out = new ArrayList<>();
        for (SysFile f : fileMapper.selectOpenNow(now)) {
            SystemVOs.OpenFile vo = new SystemVOs.OpenFile();
            vo.setId(f.getId());
            vo.setSerialNo(f.getSerialNo());
            vo.setName(f.getName());
            vo.setFileExt(f.getFileExt());
            vo.setFileSize(f.getFileSize());
            boolean download = Integer.valueOf(1).equals(f.getAllowDownload());
            vo.setAllowDownload(download);
            // 关掉下载就不下发地址：只在前端隐藏按钮的话，地址已经在响应里了
            vo.setFileUrl(download ? f.getFileUrl() : null);
            vo.setPublishStart(f.getPublishStart());
            vo.setPublishEnd(f.getPublishEnd());
            out.add(vo);
        }
        return out;
    }

    // ================= 授权 =================

    public List<SystemVOs.FolderGrant> grants(Long folderId, Long adminId) {
        requireFolder(folderId);
        requireAccess(adminId, folderId, false);
        List<SystemVOs.FolderGrant> out = new ArrayList<>();
        for (SysFolderGrant g : grantMapper.selectByFolder(folderId)) {
            SystemVOs.FolderGrant vo = new SystemVOs.FolderGrant();
            vo.setId(g.getId());
            vo.setFolderId(g.getFolderId());
            vo.setGranteeType(g.getGranteeType());
            vo.setGranteeLabel(Integer.valueOf(SystemCodes.GRANTEE_DEPARTMENT).equals(g.getGranteeType())
                    ? g.getDepartment() : adminQueryService.listNamesByIds(List.of(g.getAdminId())).get(g.getAdminId()));
            vo.setAdminId(g.getAdminId());
            vo.setDepartment(g.getDepartment());
            vo.setCanWrite(Integer.valueOf(1).equals(g.getCanWrite()));
            vo.setCreateTime(g.getCreateTime());
            out.add(vo);
        }
        return out;
    }

    /** 授权（Row 71「可以设权限」）：**只有超管能改授权**，否则被授了写权限的人能把自己提成别的文件夹的管理者。 */
    public Long grant(Long folderId, SystemDTOs.GrantSave dto, Long adminId) {
        requireSuperAdmin(adminId);
        requireFolder(folderId);
        SysFolderGrant g = new SysFolderGrant();
        g.setFolderId(folderId);
        g.setGranteeType(dto.getGranteeType());
        if (Integer.valueOf(SystemCodes.GRANTEE_ADMIN).equals(dto.getGranteeType())) {
            if (dto.getAdminId() == null) {
                throw new BusinessException("请选择后台账号");
            }
            g.setAdminId(dto.getAdminId());
        } else {
            if (!StringUtils.hasText(dto.getDepartment())) {
                throw new BusinessException("请填写部门");
            }
            if (!adminQueryService.activeDepartments().contains(dto.getDepartment().trim())) {
                // 授给一个没有启用账号的部门＝授了个谁也用不上的权限，和投诉流转同一条口径
                throw new BusinessException("这个部门当前没有启用的账号");
            }
            g.setDepartment(dto.getDepartment().trim());
        }
        g.setCanWrite(Boolean.TRUE.equals(dto.getCanWrite()) ? 1 : 0);
        g.setCreateTime(LocalDateTime.now().withNano(0));
        try {
            grantMapper.insert(g);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("这个对象在这个文件夹上已经有授权了");
        }
        return g.getId();
    }

    public void revokeGrant(Long grantId, Long adminId) {
        requireSuperAdmin(adminId);
        if (grantId == null || grantMapper.deleteRow(grantId) != 1) {
            throw new BusinessException("授权不存在");
        }
    }

    // ================= 分享 =================

    public List<SystemVOs.FileShare> shares(Long fileId, Long adminId) {
        SysFile file = requireFile(fileId);
        requireAccess(adminId, file.getFolderId(), false);
        LocalDateTime now = LocalDateTime.now();
        List<SystemVOs.FileShare> out = new ArrayList<>();
        for (SysFileShare s : shareMapper.selectByFile(fileId)) {
            out.add(toShare(s, file.getName(), now));
        }
        return out;
    }

    public SystemVOs.FileShare share(Long fileId, SystemDTOs.ShareSave dto, Long adminId) {
        requireOperator(adminId);
        SysFile file = requireFile(fileId);
        requireAccess(adminId, file.getFolderId(), false);
        int hours = dto == null || dto.getHours() == null ? properties.getShareDefaultHours() : dto.getHours();
        SysFileShare s = new SysFileShare();
        s.setFileId(fileId);
        s.setToken(newToken());
        s.setRequireLogin(dto == null || dto.getRequireLogin() == null || dto.getRequireLogin() ? 1 : 0);
        s.setExpireTime(hours <= 0 ? null : LocalDateTime.now().plusHours(hours).withNano(0));
        s.setDownloadCount(0);
        s.setCreateBy(adminId);
        s.setCreateTime(LocalDateTime.now().withNano(0));
        shareMapper.insert(s);
        return toShare(s, file.getName(), LocalDateTime.now());
    }

    public void revokeShare(Long shareId, Long adminId) {
        requireOperator(adminId);
        SysFileShare s = shareId == null ? null : shareMapper.selectById(shareId);
        if (s == null) {
            throw new BusinessException("分享不存在");
        }
        SysFile file = requireFile(s.getFileId());
        requireAccess(adminId, file.getFolderId(), false);
        if (shareMapper.revoke(shareId) != 1) {
            throw new BusinessException("分享不存在或已撤销");
        }
    }

    /**
     * 打开一条分享。
     *
     * @param loggedInAdminId 打开的人（没登录传 null）——要登录的分享交给调用方先判断，这里再核一次
     */
    public SystemVOs.SharedFile openShare(String token, Long loggedInAdminId) {
        SysFileShare s = StringUtils.hasText(token) ? shareMapper.selectByToken(token.trim()) : null;
        // 不存在、已撤销、已过期报同一句话：分开报等于告诉人「这个令牌是真的，只是过期了」
        if (s == null || !s.usableAt(LocalDateTime.now())) {
            throw new BusinessException("分享链接无效或已过期");
        }
        if (Integer.valueOf(1).equals(s.getRequireLogin()) && loggedInAdminId == null) {
            throw new BusinessException("这份分享需要登录后台账号才能打开");
        }
        SysFile file = fileMapper.selectById(s.getFileId());
        if (file == null) {
            throw new BusinessException("分享链接无效或已过期");
        }
        // 计数那条 UPDATE 带着「没撤销」，是撤销与打开之间的最后一道闸
        if (shareMapper.countDownload(s.getId()) != 1) {
            throw new BusinessException("分享链接无效或已过期");
        }
        SystemVOs.SharedFile vo = new SystemVOs.SharedFile();
        vo.setName(file.getName());
        vo.setFileExt(file.getFileExt());
        vo.setFileSize(file.getFileSize());
        vo.setFileUrl(file.getFileUrl());
        return vo;
    }

    // ================= 内部 =================

    private List<SystemVOs.Folder> buildTree(Map<Long, List<SysFolder>> byParent, Long parentId, Long adminId,
                                             boolean inheritedRead, boolean inheritedWrite) {
        List<SystemVOs.Folder> out = new ArrayList<>();
        for (SysFolder f : byParent.getOrDefault(parentId, List.of())) {
            boolean[] here = accessOf(adminId, f.getId(), inheritedRead, inheritedWrite);
            List<SystemVOs.Folder> children = buildTree(byParent, f.getId(), adminId, here[0], here[1]);
            if (!here[0] && children.isEmpty()) {
                // 自己没权限、下面也没有能看的：整枝不出现（露出名字就等于露出了协会的目录结构）
                continue;
            }
            SystemVOs.Folder vo = new SystemVOs.Folder();
            vo.setId(f.getId());
            vo.setParentId(f.getParentId());
            vo.setName(f.getName());
            vo.setSort(f.getSort());
            vo.setFileCount(here[0] ? fileMapper.countByFolder(f.getId()) : 0L);
            vo.setCanWrite(here[1]);
            vo.setChildren(children);
            out.add(vo);
        }
        return out;
    }

    /** 这个人在这个文件夹上的读写权限（含从上级继承来的）。 */
    private boolean[] accessOf(Long adminId, Long folderId, boolean inheritedRead, boolean inheritedWrite) {
        if (isSuperAdmin(adminId)) {
            return new boolean[]{true, true};
        }
        boolean read = inheritedRead;
        boolean write = inheritedWrite;
        String department = adminQueryService.departmentOf(adminId);
        for (SysFolderGrant g : grantMapper.selectForAdmin(folderId, adminId, department)) {
            read = true;
            write = write || Integer.valueOf(1).equals(g.getCanWrite());
        }
        return new boolean[]{read, write};
    }

    /** 从根一路往下算到这个文件夹（授权继承）。 */
    private void requireAccess(Long adminId, Long folderId, boolean needWrite) {
        requireOperator(adminId);
        if (isSuperAdmin(adminId)) {
            return;
        }
        boolean read = false;
        boolean write = false;
        for (Long id : pathOf(folderId)) {
            boolean[] here = accessOf(adminId, id, read, write);
            read = here[0];
            write = here[1];
        }
        if (!read || (needWrite && !write)) {
            throw new BusinessException(needWrite ? "你在这个文件夹里没有写权限" : "你没有这个文件夹的权限");
        }
    }

    /** 根 → … → 自己 的 id 路径。 */
    private List<Long> pathOf(Long folderId) {
        List<Long> path = new ArrayList<>();
        Long cursor = folderId;
        int guard = 0;
        while (cursor != null && guard++ < 32) {
            SysFolder f = folderMapper.selectById(cursor);
            if (f == null) {
                break;
            }
            path.add(0, f.getId());
            cursor = f.getParentId();
        }
        return path;
    }

    private SysFolder requireFolder(Long id) {
        SysFolder f = id == null ? null : folderMapper.selectById(id);
        if (f == null) {
            throw new BusinessException("文件夹不存在");
        }
        return f;
    }

    private SysFile requireFile(Long id) {
        SysFile f = id == null ? null : fileMapper.selectById(id);
        if (f == null) {
            throw new BusinessException("文件不存在");
        }
        return f;
    }

    private boolean isSuperAdmin(Long adminId) {
        return adminId != null && adminQueryService.isSuperAdmin(adminId);
    }

    private void requireSuperAdmin(Long adminId) {
        requireOperator(adminId);
        if (!isSuperAdmin(adminId)) {
            throw new BusinessException("只有超级管理员能改文件夹权限");
        }
    }

    private static void requireOperator(Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
    }

    private SystemVOs.VaultFile toVaultFile(SysFile f, LocalDateTime now) {
        SystemVOs.VaultFile vo = new SystemVOs.VaultFile();
        vo.setId(f.getId());
        vo.setFolderId(f.getFolderId());
        vo.setSerialNo(f.getSerialNo());
        vo.setName(f.getName());
        vo.setFileUrl(f.getFileUrl());
        vo.setFileExt(f.getFileExt());
        vo.setFileSize(f.getFileSize());
        vo.setPublished(Integer.valueOf(1).equals(f.getPublished()));
        vo.setPublishStart(f.getPublishStart());
        vo.setPublishEnd(f.getPublishEnd());
        vo.setAllowDownload(Integer.valueOf(1).equals(f.getAllowDownload()));
        vo.setOpenNow(f.openAt(now));
        vo.setCreateTime(f.getCreateTime());
        return vo;
    }

    private static SystemVOs.FileShare toShare(SysFileShare s, String fileName, LocalDateTime now) {
        SystemVOs.FileShare vo = new SystemVOs.FileShare();
        vo.setId(s.getId());
        vo.setFileId(s.getFileId());
        vo.setFileName(fileName);
        vo.setToken(s.getToken());
        vo.setRequireLogin(Integer.valueOf(1).equals(s.getRequireLogin()));
        vo.setExpireTime(s.getExpireTime());
        vo.setDownloadCount(s.getDownloadCount());
        vo.setCreateTime(s.getCreateTime());
        vo.setUsable(!Integer.valueOf(1).equals(s.getIsDeleted()) && s.usableAt(now));
        return vo;
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String extOf(String name, String url) {
        String source = name != null && name.contains(".") ? name : url;
        int i = source == null ? -1 : source.lastIndexOf('.');
        if (i < 0 || i == source.length() - 1) {
            return null;
        }
        String ext = source.substring(i + 1).toLowerCase();
        return ext.length() > 16 ? ext.substring(0, 16) : ext;
    }

    /** 给用例用的：这个人对这个文件夹是读还是写（不抛异常）。 */
    public boolean canWrite(Long adminId, Long folderId) {
        try {
            requireAccess(adminId, folderId, true);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }
}
