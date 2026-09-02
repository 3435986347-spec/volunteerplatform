package com.hengde.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.vo.VolunteerBackfillView;
import com.hengde.auth.vo.VolunteerDisplayView;
import com.hengde.auth.vo.VolunteerFlagInfoView;
import com.hengde.auth.vo.VolunteerGrantEligibilityView;
import com.hengde.auth.vo.VolunteerProfileView;
import com.hengde.common.constant.Gender;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 志愿者只读查询服务：供其他领域（如 activity 报名资格校验/管理端报名列表）跨模块取志愿者档案。
 *
 * <p>按 CLAUDE.md「领域间通过对方 service 接口调用」约定暴露，避免外部模块直接捅
 * {@link VolunteerMapper}；并以收敛视图（{@link VolunteerProfileView}/{@link VolunteerDisplayView}）出参，
 * 不外泄整个加密实体。手机号解密在本侧（持有 {@link CryptoUtil}）完成。</p>
 *
 * @author hengde
 */
@Service
public class VolunteerQueryService {

    private VolunteerMapper volunteerMapper;
    private CryptoUtil cryptoUtil;

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Autowired
    public void setCryptoUtil(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    /**
     * 取志愿者资格档案。
     *
     * @param volunteerId 志愿者 id
     * @return 资格视图；志愿者不存在时返回 null（由调用方决定如何处理）
     */
    public VolunteerProfileView getProfileForEligibility(Long volunteerId) {
        return toProfile(volunteerMapper.selectById(volunteerId));
    }

    /**
     * <b>当前读</b>版的资格档案（{@code FOR SHARE}）。<b>只能在事务内调用。</b>
     *
     * <p><b>凡是「读完就据此放行一个动作」的路径都必须用这条</b>，典型是报名三个入口的
     * 「账号状态是否正常」。{@link #getProfileForEligibility} 是普通 {@code selectById}：
     * REPEATABLE READ 下读视图在本事务第一次一致性读时就定死，此后无论过多久，
     * 别人提交的禁用/注销/逻辑删除都读不到。</p>
     *
     * <p><b>这个窗口在代报名上是敞开的</b>：{@code doProxyEnroll} 先跑同组校验（普通读）
     * 建立读视图，之后处置闸门虽然当前读并锁住了志愿者行，但资格判定若仍走快照读，
     * 就会拿着旧状态放行——闸门锁到了真相却没有使用它。<b>闸门之后不得再用快照读判定这个人的状态。</b></p>
     *
     * <p>取 S 与闸门同一把锁，重复获取在同一事务内是无操作，不产生升级。</p>
     *
     * @param volunteerId 志愿者 id
     * @return 资格视图；志愿者不存在或已逻辑删除返回 null
     */
    public VolunteerProfileView getProfileForEligibilityForShare(Long volunteerId) {
        if (volunteerId == null) {
            return null;
        }
        return toProfile(volunteerMapper.selectByIdForShare(volunteerId));
    }

    private VolunteerProfileView toProfile(Volunteer v) {
        if (v == null) {
            return null;
        }
        Integer genderCode = v.getGender() == null ? Gender.UNKNOWN.getCode() : v.getGender().getCode();
        Integer gradeCode = v.getGrade() == null ? null : v.getGrade().getCode();
        return new VolunteerProfileView(v.getId(), genderCode, v.getBirthday(), gradeCode, v.getStatus());
    }

    /**
     * 批量取志愿者展示信息（姓名/学校/年级/性别/手机号明文），按 id 映射返回，供管理端报名列表/导出 join。
     *
     * @param volunteerIds 志愿者 id 集合
     * @return id -> 展示视图；空集合返回空 Map
     */
    public Map<Long, VolunteerDisplayView> listDisplayByIds(Collection<Long> volunteerIds) {
        if (volunteerIds == null || volunteerIds.isEmpty()) {
            return Map.of();
        }
        List<Volunteer> list = volunteerMapper.selectList(
                Wrappers.<Volunteer>lambdaQuery().in(Volunteer::getId, volunteerIds));
        return list.stream().map(this::toDisplay)
                .collect(Collectors.toMap(VolunteerDisplayView::id, Function.identity()));
    }

    /**
     * 批量取<b>可接收通知的</b>手机号（id → 明文手机号），供通知短信统一入口使用。
     *
     * <p><b>为什么不复用 {@link #listDisplayByIds}</b>：那是「展示信息」，取多少字段由展示决定；
     * 这里要的是一个<b>口径</b>——谁还该收到我们发的短信。口径属于账号状态语义，归 auth，
     * 不该让 activity / honor 各自去判断（判断散开就会有人漏掉，而漏掉没有任何征兆）。</p>
     *
     * <p><b>已注销（{@link UserStatus#DELETED}）不发。</b>用户主动注销是要求我们停止打扰他，
     * 而通知短信正是打扰——他注销前报名的活动被取消、他早先的考勤被补发积分，都可能在注销之后
     * 触发一条短信。<b>禁用（{@link UserStatus#BANNED}）照常发</b>：协会 2026-08-11 的口径是
     * 给禁用账号开一个「只能看奖惩、提申诉」的小口子，而 7 天申诉期的起点正是那条通知——
     * 挡掉它等于把这个小口子又关上了。</p>
     *
     * <p>⚠️ 这条口径是<b>我们的推断</b>：协会没有就「注销之后还能不能收到短信」表过态。
     * 已记入《协会待确认清单》，若协会要求注销后仍告知，去掉这里的 status 过滤即可，一处改动。</p>
     *
     * @param volunteerIds 志愿者 id 集合
     * @return id -> 手机号明文；无手机号、已注销、行不存在的都不在返回值里
     */
    public Map<Long, String> listNotifiablePhones(Collection<Long> volunteerIds) {
        if (volunteerIds == null || volunteerIds.isEmpty()) {
            return Map.of();
        }
        List<Volunteer> list = volunteerMapper.selectList(Wrappers.<Volunteer>lambdaQuery()
                .select(Volunteer::getId, Volunteer::getPhone, Volunteer::getStatus)
                .in(Volunteer::getId, volunteerIds));
        Map<Long, String> result = new HashMap<>();
        for (Volunteer v : list) {
            if (UserStatus.DELETED.equals(v.getStatus())) {
                continue;
            }
            // 【hasText 而不是 != null】decrypt(null) 安全返回 null，但 decrypt("") 会走进
            // new byte[0 - IV_LENGTH] 抛 NegativeArraySizeException，被包成 BusinessException 抛出——
            // 而通知的调用方一律 try 住不让它影响业务，于是【整批通知静默丢失】：
            // 一个空字符串手机号就够让几百人的活动取消通知一条都发不出去，只留一行 ERROR。
            // 这道守卫是从 toDisplay 搬过来时漏掉的（那边一直有）；库里现在不该有 ''
            // （phone 可空、清空路径写的都是 null），但代价不对称——补一行 vs. 整批静默丢失。
            if (!StringUtils.hasText(v.getPhone())) {
                continue;
            }
            String phone = cryptoUtil.decrypt(v.getPhone());
            if (StringUtils.hasText(phone)) {
                result.put(v.getId(), phone.trim());
            }
        }
        return result;
    }

    /**
     * 批量取志愿者姓名（id → realName），仅 {@code select} 姓名列、<b>不解密手机号</b>。
     *
     * <p>供公开展示场景（如活动留言列表）只取姓名用，避免 {@link #listDisplayByIds} 把明文手机号
     * 解密带到调用方内存。无姓名（游客未实名）的不入 Map。</p>
     *
     * @param volunteerIds 志愿者 id 集合
     * @return id -> 姓名；空集合返回空 Map
     */
    public Map<Long, String> listNamesByIds(Collection<Long> volunteerIds) {
        if (volunteerIds == null || volunteerIds.isEmpty()) {
            return Map.of();
        }
        List<Volunteer> list = volunteerMapper.selectList(Wrappers.<Volunteer>lambdaQuery()
                .select(Volunteer::getId, Volunteer::getRealName)
                .in(Volunteer::getId, volunteerIds));
        return list.stream()
                .filter(v -> v.getRealName() != null)
                .collect(Collectors.toMap(Volunteer::getId, Volunteer::getRealName));
    }

    /**
     * 按手机号<b>精确</b>批量换 id（手机号 → volunteer.id）。
     *
     * <p><b>为什么必须由 auth 提供这个窄接口</b>：手机号在库里是密文 + {@code phone_hash} 索引，
     * 只有 auth 持有 {@link com.hengde.common.crypto.CryptoUtil}。若让调用方（如 honor 的证书批量上传）
     * 自己算 hash 或自己解密，敏感 PII 的处理面就从一个模块散到多个模块——
     * 项目契约是「PII 解密留在 auth，其它模块只消费窄接口输出」。</p>
     *
     * <p><b>只返回 id，不返回姓名手机号等任何 PII</b>。查不到的手机号不入 Map，由调用方按缺失处理。
     * 一个手机号对应多个账号时（脏数据）同样不入 Map——批量场景下宁可报「匹配不上」让人工核对，
     * 也不要随便挑一个绑上去。</p>
     *
     * @param phones 手机号明文集合
     * @return 手机号 -> volunteer.id；空集合返回空 Map
     */
    public Map<String, Long> findIdsByPhones(Collection<String> phones) {
        if (phones == null || phones.isEmpty()) {
            return Map.of();
        }
        Map<String, String> hashByPhone = new HashMap<>();
        for (String phone : phones) {
            if (phone != null && !phone.isBlank()) {
                hashByPhone.put(phone.trim(), cryptoUtil.hashPhone(phone.trim()));
            }
        }
        if (hashByPhone.isEmpty()) {
            return Map.of();
        }
        List<Volunteer> rows = volunteerMapper.selectList(Wrappers.<Volunteer>lambdaQuery()
                .select(Volunteer::getId, Volunteer::getPhoneHash)
                .in(Volunteer::getPhoneHash, hashByPhone.values()));
        // hash -> id；重复 hash（同号多账号）标记为 null 后剔除，不随便挑一个
        Map<String, Long> idByHash = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Volunteer v : rows) {
            if (idByHash.putIfAbsent(v.getPhoneHash(), v.getId()) != null) {
                ambiguous.add(v.getPhoneHash());
            }
        }
        ambiguous.forEach(idByHash::remove);

        Map<String, Long> result = new HashMap<>();
        hashByPhone.forEach((phone, hash) -> {
            Long id = idByHash.get(hash);
            if (id != null) {
                result.put(phone, id);
            }
        });
        return result;
    }

    /**
     * 志愿者账号是否处于可用状态。供志愿者端 RBAC 鉴权：停用/注销/不存在的志愿者不应携带任何权限点
     * （即便 token 未过期，杜绝「停用但 token 仍在」越权窗口）。
     *
     * @param volunteerId 志愿者 id
     * @return true=存在且未被禁用/注销；status 为 null 按正常处理（与 DB 默认 0 对齐）
     */
    public boolean isActive(Long volunteerId) {
        if (volunteerId == null) {
            return false;
        }
        Volunteer v = volunteerMapper.selectById(volunteerId);
        if (v == null) {
            return false;
        }
        Integer status = v.getStatus();
        return status == null || UserStatus.NORMAL.equals(status);
    }

    /**
     * 志愿者是否「活跃 + 已标记管理团队」（一次查库）。供志愿者端 RBAC <b>读取路径</b>用——
     * 取消 manager_flag（降级）后立即失效，与授权写入门槛 {@code manager_flag=1} 一致，避免 stale 授权继续生效。
     *
     * @param volunteerId 志愿者 id
     * @return true=存在、未停用且 {@code manager_flag=1}
     */
    public boolean isActiveManager(Long volunteerId) {
        if (volunteerId == null) {
            return false;
        }
        Volunteer v = volunteerMapper.selectById(volunteerId);
        if (v == null) {
            return false;
        }
        Integer status = v.getStatus();
        boolean active = status == null || UserStatus.NORMAL.equals(status);
        return active && Integer.valueOf(1).equals(v.getManagerFlag());
    }

    /**
     * <b>当前读</b>版的资格视图：按 id 取志愿者并加共享行锁，返回「已实名 + 账号正常」两个判定。
     * <b>只能在事务内调用。</b>
     *
     * <p>供「授予权益的那一刻再确认一次资格」用。{@link #getFlagInfo} 与 {@link #isActive}
     * 都是快照读：在 REPEATABLE READ 下，调用方事务里第一条 SELECT 就把读视图定死，
     * 之后别人把志愿者禁用/注销<b>并提交</b>，这两个方法照样返回「正常」。
     * 发起与审核之间往往隔着好几天，这个窗口不是理论值。</p>
     *
     * <p>返回 null 表示<b>志愿者行已不存在</b>（含逻辑删除），与「存在但状态异常」区分开，
     * 便于调用方给出不同的提示。</p>
     *
     * @param volunteerId 志愿者 id
     * @return 资格视图；志愿者不存在或已删除返回 null
     */
    public VolunteerGrantEligibilityView getGrantEligibilityForShare(Long volunteerId) {
        if (volunteerId == null) {
            return null;
        }
        Volunteer v = volunteerMapper.selectByIdForShare(volunteerId);
        if (v == null) {
            return null;
        }
        Integer status = v.getStatus();
        // status 为 null 按正常处理，与 DB 默认 0 及 isActive 的口径一致
        boolean active = status == null || UserStatus.NORMAL.equals(status);
        return new VolunteerGrantEligibilityView(v.getId(), v.getRealName(),
                v.getRegisterTime() != null, active, status);
    }

    /**
     * <b>排他锁</b>版的资格视图，语义与 {@link #getGrantEligibilityForShare} 完全相同，
     * 只是把 {@code FOR SHARE} 换成 {@code FOR UPDATE}。<b>只能在事务内调用。</b>
     *
     * <p>给「复核完资格之后，同一个事务里还要写这个志愿者的处置」用（奖惩审核通过）。
     * 先取 S、再由 {@code SanctionService.impose} 取 X 是一次锁升级，两名管理员同时审同一个人
     * 就会死锁；一开始取 X 则没有升级，第二次取同一把锁在本事务内是无操作。
     * 这把锁的两个用途与选择依据见 {@link com.hengde.auth.dao.VolunteerMapper#selectByIdForUpdate}。</p>
     *
     * <p>只读不写的复核（如勋章发放审核）仍应当用 {@code ForShare} 那条，多个审核可以并行。</p>
     *
     * @param volunteerId 志愿者 id
     * @return 资格视图；志愿者不存在或已删除返回 null
     */
    public VolunteerGrantEligibilityView getGrantEligibilityForUpdate(Long volunteerId) {
        if (volunteerId == null) {
            return null;
        }
        Volunteer v = volunteerMapper.selectByIdForUpdate(volunteerId);
        if (v == null) {
            return null;
        }
        Integer status = v.getStatus();
        // status 为 null 按正常处理，与 DB 默认 0 及 isActive 的口径一致
        boolean active = status == null || UserStatus.NORMAL.equals(status);
        return new VolunteerGrantEligibilityView(v.getId(), v.getRealName(),
                v.getRegisterTime() != null, active, status);
    }

    /**
     * 志愿者标记/授权页基础信息（按 id 定位）：姓名 + 管理团队标记 + 是否已实名。
     *
     * @param volunteerId 志愿者 id
     * @return 信息视图；志愿者不存在返回 null
     */
    public VolunteerFlagInfoView getFlagInfo(Long volunteerId) {
        Volunteer v = volunteerMapper.selectById(volunteerId);
        if (v == null) {
            return null;
        }
        return new VolunteerFlagInfoView(v.getId(), v.getRealName(),
                v.getManagerFlag() == null ? 0 : v.getManagerFlag(), v.getRegisterTime() != null);
    }

    /**
     * 已实名志愿者人数（{@code register_time} 非空）。供 data 域数据看板「注册志愿者人数」。
     * 逻辑删除由 {@code @TableLogic} 自动排除。
     */
    public long countRegistered() {
        return volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery()
                .isNotNull(Volunteer::getRegisterTime));
    }

    /**
     * 「管理团队」志愿者人数：{@code manager_flag=1} 且<b>已实名、未停用/注销</b>，与 {@link #isActiveManager}
     * 读取口径一致——游客态/禁用/注销上的管理团队标记不计入。供 data 域数据看板「管理团队人数」。
     */
    public long countManagers() {
        return volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery()
                .eq(Volunteer::getManagerFlag, 1)
                .isNotNull(Volunteer::getRegisterTime)
                .eq(Volunteer::getStatus, UserStatus.NORMAL));
    }

    /**
     * 该志愿者是否被标记为「管理团队」（V11 manager_flag）。供 activity 积分发放判定 ×1.2 倍率。
     *
     * @param volunteerId 志愿者 id
     * @return true=管理团队成员；志愿者不存在或未标记返回 false
     */
    public boolean isManager(Long volunteerId) {
        Volunteer v = volunteerMapper.selectById(volunteerId);
        return v != null && Integer.valueOf(1).equals(v.getManagerFlag());
    }

    /**
     * 活动补录定位志愿者：按身份证或手机号<b>精确匹配</b>唯一志愿者（身份证优先），姓名仅作交叉校验。
     *
     * <p>身份证/手机号经 {@link CryptoUtil#hashIdCard}/{@link CryptoUtil#hashPhone} 算哈希查询，不解密。
     * 必须提供身份证或手机号之一（避免重名歧义）；命中多人或姓名不符直接拒；无匹配返回 null。</p>
     *
     * @param idCard 身份证号（明文，可空）
     * @param phone  手机号（明文，可空）
     * @param name   姓名（可空，提供则须与命中人一致）
     * @return 匹配到的志愿者定位视图；无匹配返回 null
     */
    public VolunteerBackfillView findForBackfill(String idCard, String phone, String name) {
        boolean hasIdCard = StringUtils.hasText(idCard);
        boolean hasPhone = StringUtils.hasText(phone);
        if (!hasIdCard && !hasPhone) {
            throw new BusinessException("请提供手机号或身份证以精确匹配志愿者");
        }
        Volunteer byIdCard = hasIdCard ? selectSingleByHash(true, cryptoUtil.hashIdCard(idCard)) : null;
        Volunteer byPhone = hasPhone ? selectSingleByHash(false, cryptoUtil.hashPhone(phone)) : null;

        Volunteer v;
        if (hasIdCard && hasPhone) {
            // 两者都传：须命中同一志愿者，否则说明录入冲突（身份证属 A、手机号属 B）——直接拒，避免误补
            if (byIdCard == null && byPhone == null) {
                return null;
            }
            if (byIdCard == null || byPhone == null || !byIdCard.getId().equals(byPhone.getId())) {
                throw new BusinessException("身份证与手机号未匹配到同一志愿者，请核对");
            }
            v = byIdCard;
        } else {
            v = hasIdCard ? byIdCard : byPhone;
            if (v == null) {
                return null;
            }
        }
        if (StringUtils.hasText(name) && !name.trim().equals(v.getRealName())) {
            throw new BusinessException("姓名与手机号/身份证不匹配");
        }
        return new VolunteerBackfillView(v.getId(), v.getRealName(), v.getSchool());
    }

    /** 按身份证/手机号哈希取唯一志愿者；命中多人抛异常，无命中返回 null。 */
    private Volunteer selectSingleByHash(boolean byIdCard, String hash) {
        var wrapper = Wrappers.<Volunteer>lambdaQuery();
        if (byIdCard) {
            wrapper.eq(Volunteer::getIdCardHash, hash);
        } else {
            wrapper.eq(Volunteer::getPhoneHash, hash);
        }
        List<Volunteer> list = volunteerMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return null;
        }
        if (list.size() > 1) {
            throw new BusinessException("命中多名志愿者，请用更精确的条件");
        }
        return list.get(0);
    }

    private VolunteerDisplayView toDisplay(Volunteer v) {
        Integer genderCode = v.getGender() == null ? Gender.UNKNOWN.getCode() : v.getGender().getCode();
        Integer gradeCode = v.getGrade() == null ? null : v.getGrade().getCode();
        String phone = StringUtils.hasText(v.getPhone()) ? cryptoUtil.decrypt(v.getPhone()) : null;
        return new VolunteerDisplayView(v.getId(), v.getRealName(), v.getSchool(), gradeCode, genderCode, phone);
    }
}
