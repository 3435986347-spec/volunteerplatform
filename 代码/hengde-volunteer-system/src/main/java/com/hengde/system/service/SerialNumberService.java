package com.hengde.system.service;

import com.hengde.common.exception.BusinessException;
import com.hengde.system.dao.SysSerialMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 十位编号（V4 系统治理批，Row 75「由10位数字组成，每一个功能一个编号段，前三个数字为功能编号段区别」）。
 *
 * <p><b>取号是一条语句</b>（{@code INSERT … ON DUPLICATE KEY UPDATE current_no = LAST_INSERT_ID(current_no + 1)}），
 * 随后 {@code SELECT LAST_INSERT_ID()} 取回<b>本连接</b>刚写的值——「先读当前值再写回加一」在并发下会发出两个一样的号，
 * 而这个号是要印在文件上给人对的。</p>
 *
 * <p>⚠️ <b>这两条语句必须走同一条连接</b>，所以整段包在事务里：{@code LAST_INSERT_ID()} 是<b>连接级</b>的，
 * 不包事务时两条语句各自从连接池借一条连接，读回来的是别人刚写的值——16 个线程同时取号只取到 2 个不同的号
 * （并发用例当场撞出来的）。调用方本就在事务里时这里会加入它，同样是同一条连接。</p>
 *
 * <p><b>只对 V4 新对象启用</b>（V4规划 Q12）：活动的 {@code serial_no}、兑换单号那些存量编号不迁——
 * 改已经发出去的编号，等于让此前所有的截图、回执与对话记录对不上。</p>
 *
 * @author hengde
 */
@Service
public class SerialNumberService {

    /** 后七位的上限：一段最多一千万个号 */
    private static final long MAX_NO = 9_999_999L;

    private SysSerialMapper serialMapper;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setSerialMapper(SysSerialMapper serialMapper) {
        this.serialMapper = serialMapper;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 取下一个十位编号。
     *
     * @param segment 三位功能段（如 101 网盘文件）
     * @param name    段名（段不存在时顺带建出来，免得新功能上线还要先手工插一行）
     */
    public String next(String segment, String name) {
        if (segment == null || !segment.matches("\\d{3}")) {
            throw new BusinessException("功能编号段必须是三位数字");
        }
        Long taken = transactionTemplate.execute(status -> {
            serialMapper.bumpNo(segment, name == null ? segment : name);
            return serialMapper.lastNo();
        });
        long no = taken == null ? 0L : taken;
        if (no > MAX_NO) {
            throw new BusinessException("编号段 " + segment + " 已用尽");
        }
        return segment + String.format("%07d", no);
    }
}
