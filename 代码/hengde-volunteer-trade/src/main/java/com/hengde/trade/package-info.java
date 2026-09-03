/**
 * 平台级收付能力（微信支付）。首个使用方是 V3 的积分商城与捐款，
 * 纸质证书（honor 第 4C 批）、会员费（xlsx Row 5）、SaaS 增值收费（Row 84）日后共用。
 *
 * <p><b>本模块只依赖 common，这是承重约定（V3规划 D1），不是巧合。</b></p>
 *
 * <p><b>两条不许破的规矩</b>：</p>
 * <ol>
 *   <li><b>本模块不放 controller。</b> {@code /a/trade/*} 与两条 webhook 一律落在 api 模块，
 *       本模块的 service 收 {@code operatorId} 参数并<b>硬校验非空</b>
 *       （边界口径同 {@code ActivityChangeService.requestChange}）。
 *       <br>破坏它的典型路径：有人在这里建了 controller，为了取操作人 id 顺手加 auth 依赖——
 *       而 <b>Row 5 会员费是 auth 域的事</b>，日后 auth 要查缴费状态就会与本模块<b>成环</b>。
 *       api 里已有先例（{@code FileUploadController} 用 {@code StpAdminUtil}），但注意
 *       那个先例只覆盖「api 控制器做管理端鉴权」这半，「供给操作人 id」是新增用法。</li>
 *   <li><b>验签与解密必须写在本模块的 service 内</b>，api 控制器只做「读原始报文 + 转发」。
 *       否则支付逻辑会一点点长到 api 里去——api 的定位是启动类 + 全局配置 + 聚合接口。</li>
 * </ol>
 *
 * <p><b>业务状态回写有四道，缺一不可</b>（V3规划 D2）：回调、主动查单、分钟级扫描
 * <b>三条路走同一个方法</b>且幂等；每日对账是第四道、不是唯一那道。
 * 理由是 {@code CertificateReconcileJob} 那一课——Spring 事件进程内、不持久；
 * 证书丢一次事件只是少一张证书，<b>支付丢一次事件是钱收了、单没发货</b>。</p>
 *
 * @author hengde
 */
package com.hengde.trade;
