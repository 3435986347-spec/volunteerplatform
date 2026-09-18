package com.hengde.activity.album.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.album.dao.ActivityAlbumBatchMapper;
import com.hengde.activity.album.dao.ActivityAlbumMapper;
import com.hengde.activity.album.dao.ActivityAlbumPhotoMapper;
import com.hengde.activity.album.dao.ActivityAlbumRuleMapper;
import com.hengde.activity.album.dto.AlbumDTOs;
import com.hengde.activity.album.entity.ActivityAlbum;
import com.hengde.activity.album.entity.ActivityAlbumBatch;
import com.hengde.activity.album.entity.ActivityAlbumPhoto;
import com.hengde.activity.album.entity.ActivityAlbumRule;
import com.hengde.activity.album.vo.AlbumVOs;
import com.hengde.activity.constant.ActivityStatus;
import com.hengde.activity.constant.EnrollmentStatus;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.event.AlbumPhotosUploadedEvent;
import com.hengde.activity.service.ActivityLeaderService;
import com.hengde.activity.service.PointService;
import com.hengde.auth.service.AdminQueryService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.oss.FileStorageService;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 活动相册（V4 活动相册批，xlsx Row 11 / Row 30，V4规划 D10）。
 *
 * <p><b>自动建册</b>：活动相册在第一次被打开或上传时建（标题「编号 活动名称」），生成列唯一键保证一个活动一个；并发建撞键就读回赢家那一个。
 * 草稿 / 审核中 / 已取消的活动不建。</p>
 *
 * <p><b>权限</b>（Row 11 D）：看与单张下载——全体已实名志愿者；往<b>这个活动的</b>相册传——报名已通过的志愿者与本活动志愿者负责人；往<b>任何</b>相册传——管理团队；
 * 删相册 / 删照片 / 批量下载 / 管理——后台各自的权限点。</p>
 *
 * <p><b>积分</b>（D10）：新来源 {@link PointSourceType#ALBUM}（非消费、计入累计获得）。每一批上传是一张审核单，<b>审核通过那一刻</b>按
 * 「这一批现在还没删的照片数」与规则算分，个人在同一相册累计不超过上限；上限的复核在当前读锁住这个人在这个相册的全部批次行之后做，
 * 否则两批同时审核会各自以为还差几分而越过上限。0 分不入账（账本口径）。流水 {@code source_id}＝批次 id，{@code uk_source} 保幂等。</p>
 *
 * <p><b>同步社区</b>（Row 30「默认勾选发送到交流平台」）：上传事务提交后发 {@link AlbumPhotosUploadedEvent}，social 监听发帖。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class AlbumService {

    public static final String UPLOAD_DIR = "album";
    public static final int MAX_PHOTOS_PER_BATCH = 50;
    public static final int UPLOADER_VOLUNTEER = 1;
    public static final int UPLOADER_ADMIN = 2;
    public static final int POINTS_PENDING = 0;
    public static final int POINTS_APPROVED = 1;
    public static final int POINTS_REJECTED = 2;
    public static final int POINTS_NONE = 9;

    private ActivityAlbumMapper albumMapper;
    private ActivityAlbumBatchMapper batchMapper;
    private ActivityAlbumPhotoMapper photoMapper;
    private ActivityAlbumRuleMapper ruleMapper;
    private ActivityMapper activityMapper;
    private ActivityEnrollmentMapper enrollmentMapper;
    private ActivityLeaderService leaderService;
    private PointService pointService;
    private VolunteerQueryService volunteerQueryService;
    private AdminQueryService adminQueryService;
    private FileStorageService fileStorageService;
    private ApplicationEventPublisher eventPublisher;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setAlbumMapper(ActivityAlbumMapper albumMapper) {
        this.albumMapper = albumMapper;
    }

    @Autowired
    public void setBatchMapper(ActivityAlbumBatchMapper batchMapper) {
        this.batchMapper = batchMapper;
    }

    @Autowired
    public void setPhotoMapper(ActivityAlbumPhotoMapper photoMapper) {
        this.photoMapper = photoMapper;
    }

    @Autowired
    public void setRuleMapper(ActivityAlbumRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setEnrollmentMapper(ActivityEnrollmentMapper enrollmentMapper) {
        this.enrollmentMapper = enrollmentMapper;
    }

    @Autowired
    public void setLeaderService(ActivityLeaderService leaderService) {
        this.leaderService = leaderService;
    }

    @Autowired
    public void setPointService(PointService pointService) {
        this.pointService = pointService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
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
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ================= 相册 =================

    /** 活动的相册（没有就建）。 */
    public ActivityAlbum albumOfActivity(Long activityId) {
        ActivityAlbum existing = findActivityAlbum(activityId);
        if (existing != null) {
            return existing;
        }
        Activity a = activityId == null ? null : activityMapper.selectById(activityId);
        if (a == null || !(Integer.valueOf(ActivityStatus.PUBLISHED).equals(a.getStatus())
                || Integer.valueOf(ActivityStatus.FINISHED).equals(a.getStatus()))) {
            throw new BusinessException("活动不存在，或还没发布");
        }
        ActivityAlbum album = new ActivityAlbum();
        album.setActivityId(activityId);
        String title = (a.getSerialNo() == null ? "" : a.getSerialNo() + " ") + a.getTitle();
        album.setTitle(title.length() > 160 ? title.substring(0, 160) : title);
        album.setCreatedByType(0);
        try {
            albumMapper.insert(album);
            return album;
        } catch (DuplicateKeyException e) {
            ActivityAlbum winner = findActivityAlbum(activityId);
            if (winner == null) {
                throw e;
            }
            return winner;
        }
    }

    /** 后台新增：给了活动就是那个活动的相册（已有直接返回），否则按标题建一个不挂活动的。 */
    public Long create(Long adminId, AlbumDTOs.Create dto) {
        if (dto != null && dto.getActivityId() != null) {
            return albumOfActivity(dto.getActivityId()).getId();
        }
        String title = dto == null || dto.getTitle() == null ? "" : dto.getTitle().trim();
        if (title.isEmpty() || title.length() > 160) {
            throw new BusinessException("请填写相册标题（不超过 160 字）");
        }
        ActivityAlbum album = new ActivityAlbum();
        album.setTitle(title);
        album.setCreatedByType(2);
        album.setCreatedBy(adminId);
        albumMapper.insert(album);
        return album.getId();
    }

    public void deleteAlbum(Long adminId, Long albumId) {
        if (albumMapper.softDelete(albumId, adminId) == 0) {
            throw new BusinessException("相册不存在");
        }
    }

    public PageResult<AlbumVOs.Album> list(String keyword, Long viewerVolunteerId, PageQuery query) {
        var wrapper = Wrappers.<ActivityAlbum>lambdaQuery()
                .like(StringUtils.hasText(keyword), ActivityAlbum::getTitle, keyword == null ? null : keyword.trim())
                .orderByDesc(ActivityAlbum::getId);
        Long total = albumMapper.selectCount(wrapper);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<ActivityAlbum> rows = albumMapper.selectList(wrapper.last("LIMIT " + offset + ", " + query.getSize()));
        return PageResult.of(toAlbumVOs(rows, viewerVolunteerId), total == null ? 0 : total, query.getPage(), query.getSize());
    }

    public AlbumVOs.Album detail(Long albumId, Long viewerVolunteerId) {
        return toAlbumVOs(List.of(requireAlbum(albumId)), viewerVolunteerId).get(0);
    }

    public PageResult<AlbumVOs.Photo> photos(Long albumId, PageQuery query) {
        requireAlbum(albumId);
        var wrapper = Wrappers.<ActivityAlbumPhoto>lambdaQuery()
                .eq(ActivityAlbumPhoto::getAlbumId, albumId)
                .orderByDesc(ActivityAlbumPhoto::getId);
        Long total = photoMapper.selectCount(wrapper);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<ActivityAlbumPhoto> rows = photoMapper.selectList(wrapper.last("LIMIT " + offset + ", " + query.getSize()));
        Map<String, String> names = uploaderNames(rows.stream().map(p -> new long[]{p.getUploaderType(), p.getUploaderId()}).toList());
        List<AlbumVOs.Photo> out = rows.stream().map(p -> {
            AlbumVOs.Photo vo = new AlbumVOs.Photo();
            vo.setId(p.getId());
            vo.setBatchId(p.getBatchId());
            vo.setUrl(p.getUrl());
            vo.setUploaderName(names.get(p.getUploaderType() + ":" + p.getUploaderId()));
            vo.setCreateTime(p.getCreateTime());
            return vo;
        }).toList();
        return PageResult.of(out, total == null ? 0 : total, query.getPage(), query.getSize());
    }

    /** 上传记录（谁传的、传了多少张），新的在前。 */
    public PageResult<AlbumVOs.Batch> batches(Long albumId, PageQuery query) {
        requireAlbum(albumId);
        return batchPage(Wrappers.<ActivityAlbumBatch>lambdaQuery()
                .eq(ActivityAlbumBatch::getAlbumId, albumId)
                .orderByDesc(ActivityAlbumBatch::getId), query);
    }

    /** 批量下载（后台）：返回全部没删照片的地址与建议文件名。 */
    public List<Map<String, String>> downloadList(Long albumId) {
        ActivityAlbum album = requireAlbum(albumId);
        List<ActivityAlbumPhoto> rows = photoMapper.selectList(Wrappers.<ActivityAlbumPhoto>lambdaQuery()
                .eq(ActivityAlbumPhoto::getAlbumId, albumId).orderByAsc(ActivityAlbumPhoto::getId));
        List<Map<String, String>> out = new ArrayList<>();
        int i = 1;
        for (ActivityAlbumPhoto p : rows) {
            String ext = p.getUrl().contains(".") ? p.getUrl().substring(p.getUrl().lastIndexOf('.')) : "";
            out.add(Map.of("url", p.getUrl(), "filename", album.getTitle().replaceAll("[\\\\/:*?\"<>|\\s]+", "_") + "_" + (i++) + ext));
        }
        return out;
    }

    public void deletePhoto(Long adminId, Long photoId) {
        if (photoMapper.softDelete(photoId, adminId) == 0) {
            throw new BusinessException("照片不存在");
        }
    }

    // ================= 上传 =================

    /** 这个志愿者能不能往这个相册传（Row 11 D）。 */
    public boolean canUpload(Long volunteerId, ActivityAlbum album) {
        if (volunteerId == null || !volunteerQueryService.filterActiveRegistered(List.of(volunteerId)).contains(volunteerId)) {
            return false;
        }
        if (volunteerQueryService.isActiveManager(volunteerId)) {
            return true;
        }
        if (album.getActivityId() == null) {
            return false;
        }
        Long approved = enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, album.getActivityId())
                .eq(ActivityEnrollment::getVolunteerId, volunteerId)
                .eq(ActivityEnrollment::getStatus, EnrollmentStatus.APPROVED));
        return (approved != null && approved > 0) || leaderService.isVolunteerLeader(album.getActivityId(), volunteerId);
    }

    public Long upload(Long volunteerId, Long albumId, AlbumDTOs.Upload dto) {
        ActivityAlbum album = requireAlbum(albumId);
        if (!canUpload(volunteerId, album)) {
            throw new BusinessException("只有这个活动的志愿者、活动负责人和管理团队能往这个相册传照片");
        }
        List<String> urls = requirePhotos(dto);
        ActivityAlbumRule rule = rule();
        boolean sync = dto.getSyncSocial() == null || dto.getSyncSocial();
        Long batchId = insertBatch(album, UPLOADER_VOLUNTEER, volunteerId, urls, dto.getComment(), sync,
                Integer.valueOf(1).equals(rule.getEnabled()) ? POINTS_PENDING : POINTS_NONE);
        if (sync) {
            try {
                eventPublisher.publishEvent(new AlbumPhotosUploadedEvent(batchId, volunteerId, album.getTitle(), trim(dto.getComment()), urls));
            } catch (Exception e) {
                log.error("[ALBUM] 同步社区事件处理失败 batchId={}（照片已入相册）", batchId, e);
            }
        }
        return batchId;
    }

    public Long adminUpload(Long adminId, Long albumId, AlbumDTOs.Upload dto) {
        ActivityAlbum album = requireAlbum(albumId);
        return insertBatch(album, UPLOADER_ADMIN, adminId, requirePhotos(dto), dto.getComment(), false, POINTS_NONE);
    }

    /** social 同步发帖成功后回写帖子 id。 */
    public void markSocialPost(Long batchId, Long postId) {
        batchMapper.update(null, Wrappers.<ActivityAlbumBatch>lambdaUpdate()
                .eq(ActivityAlbumBatch::getId, batchId)
                .set(ActivityAlbumBatch::getSocialPostId, postId));
    }

    // ================= 积分审核 =================

    public PageResult<AlbumVOs.Batch> pointsQueue(Integer status, PageQuery query) {
        int s = status == null ? POINTS_PENDING : status;
        return batchPage(Wrappers.<ActivityAlbumBatch>lambdaQuery()
                .eq(ActivityAlbumBatch::getPointsStatus, s)
                .orderBy(true, s == POINTS_PENDING, ActivityAlbumBatch::getId), query);
    }

    /** @return 这一批实际发了几分 */
    public int approvePoints(Long adminId, Long batchId) {
        Integer awarded = transactionTemplate.execute(tx -> {
            ActivityAlbumBatch b = requirePendingBatch(batchId);
            ActivityAlbumRule rule = rule();
            int already = batchMapper.sumAwardedForUpdate(b.getAlbumId(), b.getUploaderId());
            Long remaining = photoMapper.selectCount(Wrappers.<ActivityAlbumPhoto>lambdaQuery()
                    .eq(ActivityAlbumPhoto::getBatchId, batchId));
            int points = 0;
            if (Integer.valueOf(1).equals(rule.getEnabled()) && remaining != null) {
                int byPhotos = (int) (remaining / rule.getPhotosPerUnit()) * rule.getPointsPerUnit();
                points = Math.max(0, Math.min(byPhotos, rule.getMaxPointsPerAlbum() - already));
            }
            int n = batchMapper.update(null, Wrappers.<ActivityAlbumBatch>lambdaUpdate()
                    .eq(ActivityAlbumBatch::getId, batchId)
                    .eq(ActivityAlbumBatch::getPointsStatus, POINTS_PENDING)
                    .set(ActivityAlbumBatch::getPointsStatus, POINTS_APPROVED)
                    .set(ActivityAlbumBatch::getAwardedPoints, points)
                    .set(ActivityAlbumBatch::getReviewedBy, adminId)
                    .set(ActivityAlbumBatch::getReviewedTime, LocalDateTime.now()));
            if (n == 0) {
                throw new BusinessException("这一批刚被别人审过了，请刷新");
            }
            if (points > 0) {
                ActivityAlbum album = albumMapper.selectById(b.getAlbumId());
                pointService.record(b.getUploaderId(), points, PointSourceType.ALBUM, batchId,
                        "相册上传：" + (album == null ? "" : album.getTitle()), PointSourceType.OPERATOR_ADMIN, adminId);
            }
            return points;
        });
        return awarded == null ? 0 : awarded;
    }

    public void rejectPoints(Long adminId, Long batchId, String reason) {
        String r = trim(reason);
        if (r == null || r.length() > 255) {
            throw new BusinessException("请填写驳回原因（不超过 255 字）");
        }
        requirePendingBatch(batchId);
        if (batchMapper.update(null, Wrappers.<ActivityAlbumBatch>lambdaUpdate()
                .eq(ActivityAlbumBatch::getId, batchId)
                .eq(ActivityAlbumBatch::getPointsStatus, POINTS_PENDING)
                .set(ActivityAlbumBatch::getPointsStatus, POINTS_REJECTED)
                .set(ActivityAlbumBatch::getRejectReason, r)
                .set(ActivityAlbumBatch::getReviewedBy, adminId)
                .set(ActivityAlbumBatch::getReviewedTime, LocalDateTime.now())) == 0) {
            throw new BusinessException("这一批刚被别人审过了，请刷新");
        }
    }

    public ActivityAlbumRule rule() {
        ActivityAlbumRule r = ruleMapper.selectById(1L);
        if (r == null) {
            r = new ActivityAlbumRule();
            r.setEnabled(0);
            r.setPhotosPerUnit(1);
            r.setPointsPerUnit(0);
            r.setMaxPointsPerAlbum(0);
        }
        return r;
    }

    public void saveRule(Long adminId, AlbumDTOs.Rule dto) {
        if (dto.getPhotosPerUnit() == null || dto.getPhotosPerUnit() < 1 || dto.getPhotosPerUnit() > 100
                || dto.getPointsPerUnit() == null || dto.getPointsPerUnit() < 1 || dto.getPointsPerUnit() > 100
                || dto.getMaxPointsPerAlbum() == null || dto.getMaxPointsPerAlbum() < 0 || dto.getMaxPointsPerAlbum() > 1000) {
            throw new BusinessException("每多少张 1~100、给多少分 1~100、每人每相册上限 0~1000");
        }
        ruleMapper.update(null, Wrappers.<ActivityAlbumRule>lambdaUpdate()
                .eq(ActivityAlbumRule::getId, 1L)
                .set(ActivityAlbumRule::getEnabled, Boolean.TRUE.equals(dto.getEnabled()) ? 1 : 0)
                .set(ActivityAlbumRule::getPhotosPerUnit, dto.getPhotosPerUnit())
                .set(ActivityAlbumRule::getPointsPerUnit, dto.getPointsPerUnit())
                .set(ActivityAlbumRule::getMaxPointsPerAlbum, dto.getMaxPointsPerAlbum())
                .set(ActivityAlbumRule::getUpdatedBy, adminId)
                .set(ActivityAlbumRule::getUpdateTime, LocalDateTime.now()));
    }

    // ================= 内部 =================

    private Long insertBatch(ActivityAlbum album, int uploaderType, Long uploaderId, List<String> urls, String comment,
                             boolean sync, int pointsStatus) {
        return transactionTemplate.execute(tx -> {
            LocalDateTime now = LocalDateTime.now();
            ActivityAlbumBatch b = new ActivityAlbumBatch();
            b.setAlbumId(album.getId());
            b.setUploaderType(uploaderType);
            b.setUploaderId(uploaderId);
            b.setPhotoCount(urls.size());
            b.setComment(trim(comment));
            b.setSyncSocial(sync ? 1 : 0);
            b.setPointsStatus(pointsStatus);
            b.setCreateTime(now);
            batchMapper.insert(b);
            for (String url : urls) {
                ActivityAlbumPhoto p = new ActivityAlbumPhoto();
                p.setAlbumId(album.getId());
                p.setBatchId(b.getId());
                p.setUploaderType(uploaderType);
                p.setUploaderId(uploaderId);
                p.setUrl(url);
                photoMapper.insert(p);
            }
            return b.getId();
        });
    }

    private List<String> requirePhotos(AlbumDTOs.Upload dto) {
        List<String> urls = dto == null || dto.getPhotoUrls() == null ? List.of()
                : dto.getPhotoUrls().stream().filter(Objects::nonNull).map(String::trim).filter(StringUtils::hasText).distinct().toList();
        if (urls.isEmpty()) {
            throw new BusinessException("请至少选一张照片");
        }
        if (urls.size() > MAX_PHOTOS_PER_BATCH) {
            throw new BusinessException("一次最多传 " + MAX_PHOTOS_PER_BATCH + " 张");
        }
        for (String url : urls) {
            if (!fileStorageService.isOwnUpload(url, UPLOAD_DIR)) {
                throw new BusinessException("照片请先通过小程序上传");
            }
        }
        String c = trim(dto.getComment());
        if (c != null && c.length() > 500) {
            throw new BusinessException("评论不超过 500 字");
        }
        return urls;
    }

    private ActivityAlbum findActivityAlbum(Long activityId) {
        return activityId == null ? null : albumMapper.selectOne(Wrappers.<ActivityAlbum>lambdaQuery()
                .eq(ActivityAlbum::getActivityId, activityId));
    }

    private ActivityAlbum requireAlbum(Long albumId) {
        ActivityAlbum a = albumId == null ? null : albumMapper.selectById(albumId);
        if (a == null) {
            throw new BusinessException("相册不存在");
        }
        return a;
    }

    private ActivityAlbumBatch requirePendingBatch(Long batchId) {
        ActivityAlbumBatch b = batchId == null ? null : batchMapper.selectById(batchId);
        if (b == null) {
            throw new BusinessException("上传记录不存在");
        }
        if (!Integer.valueOf(POINTS_PENDING).equals(b.getPointsStatus())) {
            throw new BusinessException("这一批不在待审核状态");
        }
        return b;
    }

    private List<AlbumVOs.Album> toAlbumVOs(List<ActivityAlbum> rows, Long viewerVolunteerId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Map<String, Object>> stats = new HashMap<>();
        photoMapper.selectStats(rows.stream().map(ActivityAlbum::getId).toList())
                .forEach(m -> stats.put(((Number) m.get("albumId")).longValue(), m));
        List<Long> coverIds = stats.values().stream().map(m -> ((Number) m.get("lastId")).longValue()).toList();
        Map<Long, String> covers = new HashMap<>();
        if (!coverIds.isEmpty()) {
            photoMapper.selectBatchIds(coverIds).forEach(p -> covers.put(p.getAlbumId(), p.getUrl()));
        }
        return rows.stream().map(a -> {
            AlbumVOs.Album vo = new AlbumVOs.Album();
            vo.setId(a.getId());
            vo.setActivityId(a.getActivityId());
            vo.setTitle(a.getTitle());
            Map<String, Object> st = stats.get(a.getId());
            vo.setPhotoCount(st == null ? 0 : ((Number) st.get("cnt")).longValue());
            vo.setLastUploadTime(st == null ? null : toTime(st.get("lastTime")));
            vo.setCoverUrl(covers.get(a.getId()));
            vo.setCreateTime(a.getCreateTime());
            if (viewerVolunteerId != null) {
                vo.setUploadable(canUpload(viewerVolunteerId, a));
            }
            return vo;
        }).toList();
    }

    private PageResult<AlbumVOs.Batch> batchPage(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ActivityAlbumBatch> wrapper,
                                                 PageQuery query) {
        Long total = batchMapper.selectCount(wrapper);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<ActivityAlbumBatch> rows = batchMapper.selectList(wrapper.last("LIMIT " + offset + ", " + query.getSize()));
        Map<Long, Long> remaining = new HashMap<>();
        Map<Long, List<String>> previews = new HashMap<>();
        Map<Long, String> titles = new HashMap<>();
        if (!rows.isEmpty()) {
            List<Long> ids = rows.stream().map(ActivityAlbumBatch::getId).toList();
            photoMapper.selectBatchRemaining(ids).forEach(m -> remaining.put(((Number) m.get("batchId")).longValue(), ((Number) m.get("cnt")).longValue()));
            photoMapper.selectList(Wrappers.<ActivityAlbumPhoto>lambdaQuery().in(ActivityAlbumPhoto::getBatchId, ids).orderByAsc(ActivityAlbumPhoto::getId))
                    .forEach(p -> {
                        List<String> l = previews.computeIfAbsent(p.getBatchId(), k -> new ArrayList<>());
                        if (l.size() < 9) {
                            l.add(p.getUrl());
                        }
                    });
            albumMapper.selectBatchIds(rows.stream().map(ActivityAlbumBatch::getAlbumId).distinct().toList())
                    .forEach(a -> titles.put(a.getId(), a.getTitle()));
        }
        Map<String, String> names = uploaderNames(rows.stream().map(b -> new long[]{b.getUploaderType(), b.getUploaderId()}).toList());
        List<AlbumVOs.Batch> out = rows.stream().map(b -> {
            AlbumVOs.Batch vo = new AlbumVOs.Batch();
            vo.setId(b.getId());
            vo.setAlbumId(b.getAlbumId());
            vo.setAlbumTitle(titles.get(b.getAlbumId()));
            vo.setUploaderType(b.getUploaderType());
            vo.setUploaderId(b.getUploaderId());
            vo.setUploaderName(names.get(b.getUploaderType() + ":" + b.getUploaderId()));
            vo.setPhotoCount(b.getPhotoCount());
            vo.setRemainingCount(remaining.getOrDefault(b.getId(), 0L));
            vo.setComment(b.getComment());
            vo.setSocialPostId(b.getSocialPostId());
            vo.setPointsStatus(b.getPointsStatus());
            vo.setAwardedPoints(b.getAwardedPoints());
            vo.setRejectReason(b.getRejectReason());
            vo.setReviewedTime(b.getReviewedTime());
            vo.setCreateTime(b.getCreateTime());
            vo.setPreviewUrls(previews.getOrDefault(b.getId(), List.of()));
            return vo;
        }).toList();
        return PageResult.of(out, total == null ? 0 : total, query.getPage(), query.getSize());
    }

    /** key「类型:id」→ 名字（志愿者取姓名，后台账号取姓名或登录名）。 */
    private Map<String, String> uploaderNames(List<long[]> uploaders) {
        List<Long> volunteers = uploaders.stream().filter(u -> u[0] == UPLOADER_VOLUNTEER).map(u -> u[1]).distinct().toList();
        List<Long> admins = uploaders.stream().filter(u -> u[0] == UPLOADER_ADMIN).map(u -> u[1]).distinct().toList();
        Map<String, String> out = new HashMap<>();
        if (!volunteers.isEmpty()) {
            volunteerQueryService.listNamesByIds(volunteers).forEach((id, name) -> out.put(UPLOADER_VOLUNTEER + ":" + id, name));
        }
        if (!admins.isEmpty()) {
            adminQueryService.listNamesByIds(admins).forEach((id, name) -> out.put(UPLOADER_ADMIN + ":" + id, name));
        }
        return out;
    }

    private static LocalDateTime toTime(Object o) {
        if (o instanceof LocalDateTime t) {
            return t;
        }
        if (o instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
