package com.hengde.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.user.dao.UserAddressMapper;
import com.hengde.user.dto.AddressSaveDTO;
import com.hengde.user.entity.UserAddress;
import com.hengde.user.vo.AddressVO;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 收货地址（Row 40「地址新增、删除、修改、置顶」，V4 个人中心补全批）。
 *
 * <p><b>「最多 20 条」是先数再插，靠按人上锁</b>（{@code lock:address:volunteer:}，锁在事务外）——变异验证去掉锁时
 * 并发新增越过了上限。</p>
 *
 * <p><b>一人至多一条置顶</b>由生成列唯一键 {@code uk_active_top} 兜底。置顶是「先清掉旧的、再置新的」两条语句，
 * ⚠️ 它的串行化<b>其实由第一条 UPDATE 本身提供</b>：按 {@code idx_volunteer} 扫到并锁住这个人的全部地址行，并发置顶在行锁上排队——
 * 变异验证去掉锁时置顶用例照样绿。置顶仍在同一把锁里（与新增、删除串行，不引入第二种加锁顺序），但别把那把锁当成置顶正确性的依据。</p>
 *
 * <p>收件电话密文存储（与商城快递单同口径），只给本人解密下发。不是本人的地址与不存在同一句话。</p>
 *
 * @author hengde
 */
@Service
public class AddressService {

    public static final int MAX_PER_VOLUNTEER = 20;
    private static final String LOCK_PREFIX = "lock:address:volunteer:";

    private UserAddressMapper addressMapper;
    private CryptoUtil cryptoUtil;
    private RedissonClient redissonClient;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setAddressMapper(UserAddressMapper addressMapper) {
        this.addressMapper = addressMapper;
    }

    @Autowired
    public void setCryptoUtil(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 我的地址：置顶的在最前，其余按最后修改时间倒序。 */
    public List<AddressVO> list(Long volunteerId) {
        return addressMapper.selectList(Wrappers.<UserAddress>lambdaQuery()
                        .eq(UserAddress::getVolunteerId, volunteerId)
                        .orderByDesc(UserAddress::getIsTop)
                        .orderByDesc(UserAddress::getUpdateTime)
                        .orderByDesc(UserAddress::getId))
                .stream().map(this::toVO).toList();
    }

    public Long create(Long volunteerId, AddressSaveDTO dto) {
        UserAddress a = fill(new UserAddress(), dto);
        return locked(volunteerId, () -> {
            Long count = addressMapper.selectCount(Wrappers.<UserAddress>lambdaQuery()
                    .eq(UserAddress::getVolunteerId, volunteerId));
            if (count != null && count >= MAX_PER_VOLUNTEER) {
                throw new BusinessException("最多保存 " + MAX_PER_VOLUNTEER + " 个地址");
            }
            a.setVolunteerId(volunteerId);
            a.setIsTop(0);
            addressMapper.insert(a);
            return a.getId();
        });
    }

    public void update(Long volunteerId, Long id, AddressSaveDTO dto) {
        UserAddress a = fill(new UserAddress(), dto);
        int rows = addressMapper.update(null, Wrappers.<UserAddress>lambdaUpdate()
                .eq(UserAddress::getId, id)
                .eq(UserAddress::getVolunteerId, volunteerId)
                .set(UserAddress::getRecvName, a.getRecvName())
                .set(UserAddress::getRecvPhone, a.getRecvPhone())
                .set(UserAddress::getRegion, a.getRegion())
                .set(UserAddress::getDetail, a.getDetail())
                .set(UserAddress::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("地址不存在");
        }
    }

    public void delete(Long volunteerId, Long id) {
        int rows = addressMapper.delete(Wrappers.<UserAddress>lambdaQuery()
                .eq(UserAddress::getId, id)
                .eq(UserAddress::getVolunteerId, volunteerId));
        if (rows != 1) {
            throw new BusinessException("地址不存在");
        }
    }

    /** 置顶：按人上锁，先清旧的再置新的（同一事务）。已经是置顶的再点一次不报错。 */
    public void top(Long volunteerId, Long id) {
        locked(volunteerId, () -> {
            UserAddress a = requireOwn(volunteerId, id);
            if (Objects.equals(a.getIsTop(), 1)) {
                return null;
            }
            LocalDateTime now = LocalDateTime.now();
            addressMapper.update(null, Wrappers.<UserAddress>lambdaUpdate()
                    .eq(UserAddress::getVolunteerId, volunteerId)
                    .eq(UserAddress::getIsTop, 1)
                    .set(UserAddress::getIsTop, 0)
                    .set(UserAddress::getTopTime, null));
            int rows = addressMapper.update(null, Wrappers.<UserAddress>lambdaUpdate()
                    .eq(UserAddress::getId, id)
                    .eq(UserAddress::getVolunteerId, volunteerId)
                    .set(UserAddress::getIsTop, 1)
                    .set(UserAddress::getTopTime, now));
            if (rows != 1) {
                throw new BusinessException("地址不存在");
            }
            return null;
        });
    }

    /** 取消置顶。 */
    public void untop(Long volunteerId, Long id) {
        locked(volunteerId, () -> {
            requireOwn(volunteerId, id);
            addressMapper.update(null, Wrappers.<UserAddress>lambdaUpdate()
                    .eq(UserAddress::getId, id)
                    .eq(UserAddress::getVolunteerId, volunteerId)
                    .set(UserAddress::getIsTop, 0)
                    .set(UserAddress::getTopTime, null));
            return null;
        });
    }

    private <T> T locked(Long volunteerId, java.util.function.Supplier<T> action) {
        if (volunteerId == null) {
            throw new BusinessException("未登录");
        }
        return DistributedLockSupport.runLocked(redissonClient, LOCK_PREFIX + volunteerId,
                () -> transactionTemplate.execute(s -> action.get()));
    }

    private UserAddress requireOwn(Long volunteerId, Long id) {
        UserAddress a = id == null ? null : addressMapper.selectById(id);
        if (a == null || !Objects.equals(a.getVolunteerId(), volunteerId)) {
            throw new BusinessException("地址不存在");
        }
        return a;
    }

    private UserAddress fill(UserAddress a, AddressSaveDTO dto) {
        if (dto == null) {
            throw new BusinessException("请填写地址");
        }
        String name = trim(dto.getRecvName());
        String phone = trim(dto.getRecvPhone());
        String region = trim(dto.getRegion());
        String detail = trim(dto.getDetail());
        if (name.isEmpty() || region.isEmpty() || detail.isEmpty()) {
            throw new BusinessException("请把收件人、所在地区与详细地址填写完整");
        }
        if (!phone.matches("^1[3-9]\\d{9}$")) {
            throw new BusinessException("收件电话格式不正确");
        }
        a.setRecvName(name);
        a.setRecvPhone(cryptoUtil.encrypt(phone));
        a.setRegion(region);
        a.setDetail(detail);
        return a;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private AddressVO toVO(UserAddress a) {
        AddressVO vo = new AddressVO();
        vo.setId(a.getId());
        vo.setRecvName(a.getRecvName());
        vo.setRecvPhone(cryptoUtil.decrypt(a.getRecvPhone()));
        vo.setRegion(a.getRegion());
        vo.setDetail(a.getDetail());
        vo.setTop(Objects.equals(a.getIsTop(), 1));
        vo.setUpdateTime(a.getUpdateTime());
        return vo;
    }
}
