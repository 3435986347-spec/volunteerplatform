package com.hengde.publicity.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.search.SearchItemVO;
import com.hengde.publicity.dao.AnnouncementMapper;
import com.hengde.publicity.dao.BannerMapper;
import com.hengde.publicity.dao.PublicityFileMapper;
import com.hengde.publicity.dto.AnnouncementDTO;
import com.hengde.publicity.dto.BannerDTO;
import com.hengde.publicity.dto.PublicityFileDTO;
import com.hengde.publicity.entity.Announcement;
import com.hengde.publicity.entity.Banner;
import com.hengde.publicity.entity.PublicityFile;
import com.hengde.publicity.vo.AnnouncementVO;
import com.hengde.publicity.vo.BannerVO;
import com.hengde.publicity.vo.PublicityFileVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PublicityService {

    private static final int STATUS_ENABLED = 1;
    private static final int DOWNLOADABLE = 1;

    private BannerMapper bannerMapper;
    private AnnouncementMapper announcementMapper;
    private PublicityFileMapper fileMapper;

    @Autowired
    public void setBannerMapper(BannerMapper bannerMapper) {
        this.bannerMapper = bannerMapper;
    }

    @Autowired
    public void setAnnouncementMapper(AnnouncementMapper announcementMapper) {
        this.announcementMapper = announcementMapper;
    }

    @Autowired
    public void setFileMapper(PublicityFileMapper fileMapper) {
        this.fileMapper = fileMapper;
    }

    public PageResult<BannerVO> banners(PageQuery query, boolean admin) {
        IPage<Banner> page = bannerMapper.selectPage(query.toPage(), Wrappers.<Banner>lambdaQuery()
                .eq(!admin, Banner::getStatus, STATUS_ENABLED)
                .orderByDesc(Banner::getSort)
                .orderByDesc(Banner::getId));
        return PageResult.of(page.convert(this::toBannerVO));
    }

    public Long createBanner(BannerDTO dto) {
        Banner banner = new Banner();
        copy(dto, banner);
        if (banner.getStatus() == null) {
            banner.setStatus(STATUS_ENABLED);
        }
        if (banner.getSort() == null) {
            banner.setSort(0);
        }
        bannerMapper.insert(banner);
        return banner.getId();
    }

    public void updateBanner(Long id, BannerDTO dto) {
        Banner banner = requireBanner(id);
        copy(dto, banner);
        bannerMapper.updateById(banner);
    }

    public void deleteBanner(Long id) {
        bannerMapper.deleteById(id);
    }

    public void sortBanner(Long id, Integer sort) {
        Banner banner = requireBanner(id);
        banner.setSort(sort);
        bannerMapper.updateById(banner);
    }

    public PageResult<AnnouncementVO> announcements(PageQuery query, boolean admin) {
        IPage<Announcement> page = announcementMapper.selectPage(query.toPage(), Wrappers.<Announcement>lambdaQuery()
                .eq(!admin, Announcement::getStatus, STATUS_ENABLED)
                .orderByDesc(Announcement::getPublishTime)
                .orderByDesc(Announcement::getId));
        return PageResult.of(page.convert(this::toAnnouncementVO));
    }

    /** 全局搜索：已发布公告按标题匹配的命中总数（供 api 聚合层算精确分页 total）。 */
    public long countSearch(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return 0;
        }
        Long c = announcementMapper.selectCount(Wrappers.<Announcement>lambdaQuery()
                .eq(Announcement::getStatus, STATUS_ENABLED)
                .like(Announcement::getTitle, keyword));
        return c == null ? 0 : c;
    }

    /** 全局搜索：已发布公告按标题匹配，取 [offset, offset+limit) 窗口（供 api 聚合层跨领域分页）。 */
    public List<SearchItemVO> search(String keyword, int offset, int limit) {
        if (!StringUtils.hasText(keyword) || limit <= 0) {
            return List.of();
        }
        List<Announcement> list = announcementMapper.selectList(Wrappers.<Announcement>lambdaQuery()
                .eq(Announcement::getStatus, STATUS_ENABLED)
                .like(Announcement::getTitle, keyword)
                .orderByDesc(Announcement::getId)
                .last("limit " + offset + "," + limit));
        return list.stream()
                .map(a -> new SearchItemVO("announcement", a.getId(), a.getTitle(), a.getSummary(), a.getCoverImageUrl()))
                .toList();
    }

    public AnnouncementVO announcementDetail(Long id) {
        // 志愿者端只允许查看已发布公告；草稿/下架公告即便猜到 id 也不可访问
        Announcement announcement = announcementMapper.selectOne(Wrappers.<Announcement>lambdaQuery()
                .eq(Announcement::getId, id)
                .eq(Announcement::getStatus, STATUS_ENABLED));
        if (announcement == null) {
            throw new BusinessException("公告不存在");
        }
        return toAnnouncementVO(announcement);
    }

    public Long createAnnouncement(AnnouncementDTO dto) {
        Announcement announcement = new Announcement();
        copy(dto, announcement);
        if (announcement.getStatus() == null) {
            announcement.setStatus(STATUS_ENABLED);
        }
        if (announcement.getPublishTime() == null && STATUS_ENABLED == announcement.getStatus()) {
            announcement.setPublishTime(LocalDateTime.now());
        }
        announcementMapper.insert(announcement);
        return announcement.getId();
    }

    public void updateAnnouncement(Long id, AnnouncementDTO dto) {
        Announcement announcement = announcementMapper.selectById(id);
        if (announcement == null) {
            throw new BusinessException("公告不存在");
        }
        copy(dto, announcement);
        if (STATUS_ENABLED == announcement.getStatus() && announcement.getPublishTime() == null) {
            announcement.setPublishTime(LocalDateTime.now());
        }
        announcementMapper.updateById(announcement);
    }

    public void deleteAnnouncement(Long id) {
        announcementMapper.deleteById(id);
    }

    public PageResult<PublicityFileVO> files(PageQuery query, boolean admin) {
        IPage<PublicityFile> page = fileMapper.selectPage(query.toPage(), Wrappers.<PublicityFile>lambdaQuery()
                .eq(!admin, PublicityFile::getDownloadable, DOWNLOADABLE)
                .orderByDesc(PublicityFile::getSort)
                .orderByDesc(PublicityFile::getId));
        return PageResult.of(page.convert(this::toFileVO));
    }

    public Long createFile(PublicityFileDTO dto) {
        PublicityFile file = new PublicityFile();
        copy(dto, file);
        if (file.getDownloadable() == null) {
            file.setDownloadable(0);
        }
        if (file.getSort() == null) {
            file.setSort(0);
        }
        fileMapper.insert(file);
        return file.getId();
    }

    public void deleteFile(Long id) {
        fileMapper.deleteById(id);
    }

    public void changeFileAccess(Long id, Boolean downloadable) {
        PublicityFile file = fileMapper.selectById(id);
        if (file == null) {
            throw new BusinessException("文件不存在");
        }
        file.setDownloadable(Boolean.TRUE.equals(downloadable) ? 1 : 0);
        fileMapper.updateById(file);
    }

    private Banner requireBanner(Long id) {
        Banner banner = bannerMapper.selectById(id);
        if (banner == null) {
            throw new BusinessException("轮播图不存在");
        }
        return banner;
    }

    private void copy(BannerDTO dto, Banner banner) {
        banner.setTitle(dto.getTitle());
        banner.setImageUrl(dto.getImageUrl());
        banner.setLinkType(dto.getLinkType() == null ? 0 : dto.getLinkType());
        banner.setLinkUrl(dto.getLinkUrl());
        banner.setSort(dto.getSort());
        banner.setStatus(dto.getStatus());
    }

    private void copy(AnnouncementDTO dto, Announcement announcement) {
        announcement.setTitle(dto.getTitle());
        announcement.setSummary(dto.getSummary());
        announcement.setContent(dto.getContent());
        announcement.setCoverImageUrl(dto.getCoverImageUrl());
        announcement.setLinkType(dto.getLinkType() == null ? 0 : dto.getLinkType());
        announcement.setLinkUrl(dto.getLinkUrl());
        announcement.setStatus(dto.getStatus());
    }

    private void copy(PublicityFileDTO dto, PublicityFile file) {
        file.setFileName(dto.getFileName());
        file.setFileUrl(dto.getFileUrl());
        file.setFileType(dto.getFileType());
        file.setFileSize(dto.getFileSize());
        file.setDownloadable(dto.getDownloadable());
        file.setSort(dto.getSort());
    }

    private BannerVO toBannerVO(Banner banner) {
        BannerVO vo = new BannerVO();
        vo.setId(banner.getId());
        vo.setTitle(banner.getTitle());
        vo.setImageUrl(banner.getImageUrl());
        vo.setLinkType(banner.getLinkType());
        vo.setLinkUrl(banner.getLinkUrl());
        vo.setSort(banner.getSort());
        vo.setStatus(banner.getStatus());
        return vo;
    }

    private AnnouncementVO toAnnouncementVO(Announcement announcement) {
        AnnouncementVO vo = new AnnouncementVO();
        vo.setId(announcement.getId());
        vo.setTitle(announcement.getTitle());
        vo.setSummary(announcement.getSummary());
        vo.setContent(announcement.getContent());
        vo.setCoverImageUrl(announcement.getCoverImageUrl());
        vo.setLinkType(announcement.getLinkType());
        vo.setLinkUrl(announcement.getLinkUrl());
        vo.setStatus(announcement.getStatus());
        vo.setPublishTime(announcement.getPublishTime());
        return vo;
    }

    private PublicityFileVO toFileVO(PublicityFile file) {
        PublicityFileVO vo = new PublicityFileVO();
        vo.setId(file.getId());
        vo.setFileName(file.getFileName());
        vo.setFileUrl(file.getFileUrl());
        vo.setFileType(file.getFileType());
        vo.setFileSize(file.getFileSize());
        vo.setDownloadable(file.getDownloadable());
        vo.setSort(file.getSort());
        return vo;
    }
}
