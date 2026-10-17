<!-- 生成物：由 tools/gen_knowledge_base.py 从代码生成，不要手改；改了代码重跑脚本。 -->

# donate 模块的接口

163 个端点。模块说明见 [`知识库/模块/donate.md`](../../模块/donate.md)。

## 志愿者端 `/v`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/v/donate/barcode-catalog/{barcode}` |  | 按商品条码查条码库（查不到返回 null，手填即可） | `BookDonationController` |
| GET | `/v/donate/book-campaigns` |  | 捐书活动列表（含本次活动数据） | `BookDonationController` |
| GET | `/v/donate/book-campaigns/{id}` |  | 捐书活动详情（含收件信息：收件人=我的姓名，地址=预留地址+我的姓名） | `BookDonationController` |
| POST | `/v/donate/book-campaigns/{id}/shipments` |  | 报名并登记寄出（录入物资 + 快递公司 + 单号，一次提交） | `BookDonationController` |
| GET | `/v/donate/coupons/mine` |  | 我的卷（status：0可用/1已使用/2已作废/3已过期，不传=全部） | `MallController` |
| GET | `/v/donate/coupons/usable` |  | 这件规格我此刻能用的卷（先到期的排前面） | `MallController` |
| GET | `/v/donate/crowdfunds` |  | 众筹项目列表：tab 0全部 / 1进行中 / 2已结束（Row 16） | `PairController` |
| GET | `/v/donate/crowdfunds/{id}` |  | 众筹项目详情与进度（已筹＝已到账捐款之和；接受捐物的项目带物资需求与收件信息） | `PairController` |
| GET | `/v/donate/crowdfunds/{id}/donations` |  | 众筹项目捐赠记录（公开；只列已到账的，姓名打码） | `DonationController` |
| POST | `/v/donate/crowdfunds/{id}/donations` |  | 众筹捐款（金额自填；传 wx.login 的 code；返回捐款记录与唤起支付的参数） | `DonationController` |
| POST | `/v/donate/crowdfunds/{id}/goods-donations` |  | 众筹捐物：登记寄出的物资 + 快递公司 + 单号（Row 16；须已实名，项目在募集中且接受捐物） | `PairController` |
| GET | `/v/donate/donations/mine` |  | 我的捐赠记录（Row 33：众筹的捐款与捐物合成一条时间线；kind 0全部 / 1捐款 / 2捐物） | `DonationController` |
| DELETE | `/v/donate/donations/{id}` |  | 取消待支付的捐款（先关交易单；刚付款的不能取消） | `DonationController` |
| GET | `/v/donate/donations/{id}` |  | 我的一笔捐款详情 | `DonationController` |
| GET | `/v/donate/donations/{id}/invoice-file` |  | 下载我的发票（短期签名地址，约 2 分钟有效；还没开出时报「发票还没有开出」） | `DonationController` |
| POST | `/v/donate/donations/{id}/pay` |  | 重新发起付款（仅待支付；复用活的交易单） | `DonationController` |
| GET | `/v/donate/exchange-records` |  | 全部兑换记录（Row 8 C「全部人的」；只放姓名/商品/时间，不含单号与取货码） | `MallController` |
| GET | `/v/donate/exchange-rules` |  | 兑换规则（文字 + 图片，Row 8 C） | `MallController` |
| GET | `/v/donate/express-companies` |  | 可选的快递公司 | `BookDonationController` |
| GET | `/v/donate/goods` |  | 商品列表（仅已上架且未隐藏） | `MallController` |
| GET | `/v/donate/goods/{id}` |  | 商品详情（含规格、赞助方、评价摘要、是否需持卷兑换） | `MallController` |
| GET | `/v/donate/goods/{id}/reviews` |  | 该商品的全部评价 | `MallController` |
| GET | `/v/donate/items/mine` |  | 我的捐书记录（Row 38：编号、名称、编码、受捐学校、审核状态、物资类型、借阅次数） | `BookDonationController` |
| GET | `/v/donate/orders` |  | 我的兑换（status 可筛选 0待审核/1待领取·待发货/2已驳回/3已领取·已签收/4已取消/5待支付/6已发货） | `MallController` |
| POST | `/v/donate/orders` |  | 下单兑换（下单即扣分并占库存；可选用一张卷，一单一卷；可选快递——快递费现金或积分抵扣；有现金要付的落「待支付」） | `MallController` |
| POST | `/v/donate/orders/verify` |  | 核销员扫码核销取货码（CAS，一次性；不能核销自己的单） | `MallController` |
| DELETE | `/v/donate/orders/{id}` |  | 取消兑换（只有待支付的单能自己取消，先关交易单；兑换成功之后须联系协会取消） | `MallController` |
| GET | `/v/donate/orders/{id}` |  | 我的兑换详情（待领取时带取货码与条码图） | `MallController` |
| POST | `/v/donate/orders/{id}/pay` |  | 发起付款（仅待支付的单；传 wx.login 的 code，返回唤起小程序支付的参数） | `MallController` |
| POST | `/v/donate/orders/{id}/receive` |  | 确认收货（仅快递且已发货的单） | `MallController` |
| POST | `/v/donate/orders/{id}/reviews` |  | 评价（须是本人已领取的兑换单，一单一评） | `MallController` |
| GET | `/v/donate/pair-projects` |  | 结对项目列表：tab 1助学 / 2助困 / 3助残 / 4结对成功（Row 10 四个页签） | `PairController` |
| GET | `/v/donate/pair-projects/{id}` |  | 结对项目详情（含我的登记与能否登记） | `PairController` |
| GET | `/v/donate/pair-projects/{id}/donations` |  | 结对项目捐赠记录（Row 10「项目捐赠记录」；只列已到账的，姓名打码） | `DonationController` |
| POST | `/v/donate/pair-projects/{id}/donations` |  | 结对捐款（付「认捐额 − 已付」，须已登记结对；一条结对至多一笔待支付） | `DonationController` |
| GET | `/v/donate/pair-projects/{id}/letters` |  | 受助方来信（项目公开信 + 写给我的那些） | `PairController` |
| DELETE | `/v/donate/pair-projects/{id}/pairs` |  | 撤回结对登记（仅「待确认」可撤；已成立请联系协会） | `PairController` |
| POST | `/v/donate/pair-projects/{id}/pairs` |  | 结对登记（指定金额或全款；须已实名） | `PairController` |
| GET | `/v/donate/pair-projects/{id}/updates` |  | 活动动态（协会的发放凭证，一律公开；时间倒序） | `PairController` |
| GET | `/v/donate/pairs/mine` |  | 我的结对记录（结对中心，Row 34） | `PairController` |
| GET | `/v/donate/pairs/mine/{id}` |  | 结对中心详情（Row 34：这条结对 + 为它付过的每一笔款，含发票字段） | `PairController` |
| GET | `/v/donate/reviews/mine` |  | 我的评价 | `MallController` |
| GET | `/v/donate/shipments/mine` |  | 我的运单（含物资明细与流转轨迹，Row 38 运单管理） | `BookDonationController` |
| DELETE | `/v/donate/shipments/{id}` |  | 取消寄送（仅机构确认到货之前） | `BookDonationController` |
| GET | `/v/donate/shipments/{id}` |  | 我的运单详情 | `BookDonationController` |
| POST | `/v/donate/shipments/{id}/return-address` |  | 提交不合格物资的退回收件信息 | `BookDonationController` |
| GET | `/v/donate/shipments/{id}/track` |  | 运单物流轨迹（快递100 快照；未开通时 available=false） | `BookDonationController` |
| GET | `/v/donate/verifiers/me` |  | 我是不是核销员 | `MallController` |
| GET | `/v/donate/wishes` |  | 心愿列表：tab 0 心愿池 / 1 已认领 / 2 已实现（姓名、学校打 *；需已验手机号） | `WishController` |
| GET | `/v/donate/wishes/mine` |  | 微心愿中心：我的认领 + 寄出的包裹、物资与流转轨迹（Row 35） | `WishController` |
| GET | `/v/donate/wishes/{id}` |  | 心愿详情（认领人返回全部资料与物资接收地址，其他人打 *） | `WishController` |
| DELETE | `/v/donate/wishes/{id}/claim` |  | 取消认领（物资已寄出且未取消时不可取消） | `WishController` |
| POST | `/v/donate/wishes/{id}/claim` |  | 认领（须已实名志愿者） | `WishController` |
| POST | `/v/donate/wishes/{id}/shipments` |  | 为认领的心愿登记寄出物资（录入物资 + 快递单号，一次提交） | `WishController` |

## 后台 `/a`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/a/donate/barcode-catalog` | `donate:item` | 商品条码库列表（keyword 精确匹配条码或模糊匹配名称） | `AdminBookDonationController` |
| POST | `/a/donate/barcode-catalog` | `donate:item` | 新增条码库条目 | `AdminBookDonationController` |
| DELETE | `/a/donate/barcode-catalog/{id}` | `donate:item` | 删除条码库条目 | `AdminBookDonationController` |
| PUT | `/a/donate/barcode-catalog/{id}` | `donate:item` | 修改条码库条目 | `AdminBookDonationController` |
| GET | `/a/donate/book-campaigns` | `donate:item` | 捐书活动列表（含本次活动数据） | `AdminBookDonationController` |
| POST | `/a/donate/book-campaigns` | `donate:item` | 新建捐书活动（落草稿） | `AdminBookDonationController` |
| DELETE | `/a/donate/book-campaigns/{id}` | `donate:item` | 删除（仅草稿） | `AdminBookDonationController` |
| GET | `/a/donate/book-campaigns/{id}` | `donate:item` | 捐书活动详情 | `AdminBookDonationController` |
| PUT | `/a/donate/book-campaigns/{id}` | `donate:item` | 修改捐书活动 | `AdminBookDonationController` |
| POST | `/a/donate/book-campaigns/{id}/end` | `donate:item` | 手动结束 | `AdminBookDonationController` |
| POST | `/a/donate/book-campaigns/{id}/publish` | `donate:item` | 发布（须先填收件电话与地址） | `AdminBookDonationController` |
| GET | `/a/donate/boxes` | `donate:item` | 箱子列表 | `AdminBookDonationController` |
| POST | `/a/donate/boxes` | `donate:item` | 新建箱子（生成箱码与条码图） | `AdminBookDonationController` |
| GET | `/a/donate/boxes/{id}` | `donate:item` | 箱子详情（含箱码条码图与箱内物资） | `AdminBookDonationController` |
| POST | `/a/donate/boxes/{id}/deliver` | `donate:item` | 送达受赠单位（Row 17 第 10 步；空箱不能送达） | `AdminBookDonationController` |
| POST | `/a/donate/boxes/{id}/pack` | `donate:item` | 扫物品专属码装箱（一次一件） | `AdminBookDonationController` |
| POST | `/a/donate/boxes/{id}/unpack` | `donate:item` | 扫物品专属码出箱（仅装箱中的箱子） | `AdminBookDonationController` |
| POST | `/a/donate/coupon-grants/{id}/revoke` | `donate:coupon` | 作废一张已发出的卷（仅未使用的可作废） | `AdminMallCouponController` |
| GET | `/a/donate/coupons` | `donate:coupon` | 卷列表（带已发放 / 已使用张数） | `AdminMallCouponController` |
| POST | `/a/donate/coupons` | `donate:coupon` | 新建卷（默认启用） | `AdminMallCouponController` |
| GET | `/a/donate/coupons/{id}` | `donate:coupon` | 卷详情 | `AdminMallCouponController` |
| PUT | `/a/donate/coupons/{id}` | `donate:coupon` | 修改卷（只影响之后发出的卷，已发出的有条款快照） | `AdminMallCouponController` |
| GET | `/a/donate/coupons/{id}/grants` | `donate:coupon` | 某张卷的发放记录（status：0可用/1已使用/2已作废/3已过期） | `AdminMallCouponController` |
| POST | `/a/donate/coupons/{id}/grants` | `donate:coupon` | 发卷 / 批量发卷（按志愿者 id 或手机号；requestId 保幂等） | `AdminMallCouponController` |
| PATCH | `/a/donate/coupons/{id}/status` | `donate:coupon` | 启用 / 停用（停用只挡新发放） | `AdminMallCouponController` |
| GET | `/a/donate/crowdfunds` | `donate:project` | 众筹项目列表 | `AdminPairController` |
| POST | `/a/donate/crowdfunds` | `donate:project` | 新建众筹项目（落草稿） | `AdminPairController` |
| DELETE | `/a/donate/crowdfunds/{id}` | `donate:project` | 删除众筹项目（仅草稿） | `AdminPairController` |
| GET | `/a/donate/crowdfunds/{id}` | `donate:project` | 众筹项目详情 | `AdminPairController` |
| PUT | `/a/donate/crowdfunds/{id}` | `donate:project` | 修改众筹项目（已结束的不能改） | `AdminPairController` |
| POST | `/a/donate/crowdfunds/{id}/end` | `donate:project` | 结束众筹项目 | `AdminPairController` |
| POST | `/a/donate/crowdfunds/{id}/publish` | `donate:project` | 上架众筹项目 | `AdminPairController` |
| GET | `/a/donate/donations` | `donate:project` | 捐款列表（按类型 / 项目 / 状态 / 开票状态；keyword 试单号、项目名、捐款人姓名或手机号） | `AdminDonationController` |
| POST | `/a/donate/donations/{id}/invoice` | `donate:project` | 登记开票（清单⑦只预留：发票在系统外开，这里记发票号；仅已到账且需要发票的） | `AdminDonationController` |
| GET | `/a/donate/donations/{id}/invoice-file` | `donate:project` | 查看发票文件（短期签名地址） | `AdminDonationController` |
| POST | `/a/donate/donations/{id}/invoice-file` | `donate:project` | 上传发票文件（协会 2026-10-16 丁-5：财务传开好的发票，PDF 或图片 ≤10MB；传上即已开票，再传是替换； | `AdminDonationController` |
| POST | `/a/donate/donations/{id}/refund` | `donate:project` + `trade:refund` | 退一笔已到账的捐款（先减回已筹、提交后原路退款；已开票的须先作废发票） | `AdminDonationController` |
| GET | `/a/donate/exchange-rules` | `donate:goods` | 兑换规则（当前值；与志愿者端同一份） | `AdminMallGoodsController` |
| PUT | `/a/donate/exchange-rules` | `donate:goods` | 保存兑换规则（覆盖式，无版本；图片走 /a/files/upload?dir=exchange-rule） | `AdminMallGoodsController` |
| GET | `/a/donate/goods` | `donate:goods` 或 `donate:goods-audit` | 商品列表（含草稿/待审/停用/隐藏，status 可筛选） | `AdminMallGoodsController` |
| POST | `/a/donate/goods` | `donate:goods` | 新增商品（落草稿，图片走 /a/files/upload?dir=goods） | `AdminMallGoodsController` |
| DELETE | `/a/donate/goods/{id}` | `donate:goods` | 删除商品（逻辑删除，连同规格；历史订单有快照不受影响） | `AdminMallGoodsController` |
| GET | `/a/donate/goods/{id}` | `donate:goods` 或 `donate:goods-audit` | 商品详情（不限状态） | `AdminMallGoodsController` |
| PUT | `/a/donate/goods/{id}` | `donate:goods` | 修改商品（已过审的改完退回待审；审核中的不可改） | `AdminMallGoodsController` |
| POST | `/a/donate/goods/{id}/approve` | `donate:goods-audit` | 审核通过 → 已上架 | `AdminMallGoodsController` |
| PATCH | `/a/donate/goods/{id}/display` | `donate:goods` | 排序 / 隐藏（纯展示，不触发重审） | `AdminMallGoodsController` |
| POST | `/a/donate/goods/{id}/reject` | `donate:goods-audit` | 审核驳回 | `AdminMallGoodsController` |
| GET | `/a/donate/goods/{id}/reviews` | `donate:goods` 或 `donate:goods-audit` | 某商品的评价（仅正常项；下架用 DELETE /a/donate/reviews/{id}） | `AdminMallGoodsController` |
| POST | `/a/donate/goods/{id}/submit` | `donate:goods` | 提交审核（仅草稿与驳回稿可提交） | `AdminMallGoodsController` |
| GET | `/a/donate/items` | `donate:item` | 物资 10 维搜索（箱码 / 专属码 / 物品条码 / 捐赠人名字·电话·单位 / 名称 / 类型 / 快递单号 / 进度） | `AdminBookDonationController` |
| GET | `/a/donate/items/export` | `donate:item-export` | 物资批量导出（每物资一行，Excel；上限 20000 行） | `AdminBookDonationController` |
| DELETE | `/a/donate/items/{id}` | `donate:item` | 单独减少物资（已装箱 / 已送达 / 已寄回的不能删） | `AdminBookDonationController` |
| POST | `/a/donate/items/{id}/barcode` | `donate:item` | 生成物品专属码并返回标签（幂等：已有码返回原码供重印） | `AdminBookDonationController` |
| DELETE | `/a/donate/letters/{id}` | `donate:project` | 删除来信 | `AdminPairController` |
| GET | `/a/donate/orders` | `donate:order` 或 `donate:order-audit` | 兑换单列表（keyword 试订单号 / 商品名 / 兑换人姓名或手机号） | `AdminMallOrderController` |
| POST | `/a/donate/orders/verify` | `donate:verify` | 现场核销取货码（CAS，一次性；返回该发什么） | `AdminMallOrderController` |
| POST | `/a/donate/orders/{id}/approve` | `donate:order-audit` | 兑换审核通过（自提：生成取货码并快照自提点；快递：进入待发货） | `AdminMallOrderController` |
| POST | `/a/donate/orders/{id}/cancel` | `donate:order-audit` | 协会取消已成功的兑换单（待领取 / 待发货；退分 + 还库存 + 还卷，付过现金的原路退款，短信告知） | `AdminMallOrderController` |
| POST | `/a/donate/orders/{id}/reject` | `donate:order-audit` | 兑换审核驳回（退分 + 还库存 + 还卷；已付款的原路退款，发起失败记在单上） | `AdminMallOrderController` |
| POST | `/a/donate/orders/{id}/ship` | `donate:verify` | 快递单登记发货（仅快递且待发货的单；CAS 一次性） | `AdminMallOrderController` |
| GET | `/a/donate/pair-projects` | `donate:project` | 结对项目列表（状态 / 类型 / 关键词） | `AdminPairController` |
| POST | `/a/donate/pair-projects` | `donate:project` | 新建结对项目（落草稿） | `AdminPairController` |
| DELETE | `/a/donate/pair-projects/{id}` | `donate:project` | 删除结对项目（仅草稿且无人登记） | `AdminPairController` |
| GET | `/a/donate/pair-projects/{id}` | `donate:project` | 结对项目详情 | `AdminPairController` |
| PUT | `/a/donate/pair-projects/{id}` | `donate:project` | 修改结对项目（已结束的不能改） | `AdminPairController` |
| POST | `/a/donate/pair-projects/{id}/end` | `donate:project` | 结束项目（不再接受新的结对登记） | `AdminPairController` |
| GET | `/a/donate/pair-projects/{id}/letters` | `donate:project` | 来信列表（后台看全部，含写给某个结对人的） | `AdminPairController` |
| POST | `/a/donate/pair-projects/{id}/letters` | `donate:project` | 录入受助方来信（图文；不填收信人=项目公开信） | `AdminPairController` |
| POST | `/a/donate/pair-projects/{id}/publish` | `donate:project` | 上架（草稿 → 进行中，志愿者端从此可见） | `AdminPairController` |
| GET | `/a/donate/pair-projects/{id}/updates` | `donate:project` | 活动动态列表（发放凭证，一律公开） | `AdminPairController` |
| POST | `/a/donate/pair-projects/{id}/updates` | `donate:project` | 录入一条活动动态（图文，最多 9 张图；不填时间取录入时间） | `AdminPairController` |
| DELETE | `/a/donate/pair-updates/{id}` | `donate:project` | 删除活动动态 | `AdminPairController` |
| GET | `/a/donate/pairs` | `donate:project` | 结对登记列表（按项目 / 状态；含结对人姓名与电话） | `AdminPairController` |
| POST | `/a/donate/pairs/{id}/cancel` | `donate:project` | 取消结对登记（原因必填；已成立的会退回认捐额并撤销证书） | `AdminPairController` |
| POST | `/a/donate/pairs/{id}/establish` | `donate:project` | 确认结对成立（认捐额累加；**出证的触发点**） | `AdminPairController` |
| GET | `/a/donate/pickup-points` | `donate:goods` 或 `donate:goods-audit` | 自提点列表（含已停用的） | `AdminMallGoodsController` |
| POST | `/a/donate/pickup-points` | `donate:goods` | 新增自提点 | `AdminMallGoodsController` |
| DELETE | `/a/donate/pickup-points/{id}` | `donate:goods` | 删除自提点（逻辑删除；商品上残留的引用在展示与兑换时自动滤掉） | `AdminMallGoodsController` |
| PUT | `/a/donate/pickup-points/{id}` | `donate:goods` | 修改自提点（全量；停用后不能再被选，已兑换的单有快照不受影响） | `AdminMallGoodsController` |
| GET | `/a/donate/recipient-orgs` | `donate:item` | 受赠单位列表 | `AdminBookDonationController` |
| POST | `/a/donate/recipient-orgs` | `donate:item` | 新建受赠单位 | `AdminBookDonationController` |
| DELETE | `/a/donate/recipient-orgs/{id}` | `donate:item` | 删除受赠单位（已送达记录存的是名称快照，不受影响） | `AdminBookDonationController` |
| PUT | `/a/donate/recipient-orgs/{id}` | `donate:item` | 修改受赠单位 | `AdminBookDonationController` |
| DELETE | `/a/donate/reviews/{id}` | `donate:goods` | 下架不当评价（逻辑删除，不新增权限点） | `AdminMallGoodsController` |
| GET | `/a/donate/scan` | `donate:item` | 扫码识别：物品专属码 / 箱码 / 快递单号 / 商品条码 | `AdminBookDonationController` |
| GET | `/a/donate/shipments` | `donate:item` | 运单列表（bizType 不传＝捐书活动；2 微心愿 / 3 众筹捐物，bizId 为对应的认领 / 众筹项目 id） | `AdminBookDonationController` |
| GET | `/a/donate/shipments/{id}` | `donate:item` | 运单详情（含物资、轨迹、退回收件信息明文） | `AdminBookDonationController` |
| POST | `/a/donate/shipments/{id}/arrive` | `donate:item` | 扫码确认到货（Row 17 第 5 步） | `AdminBookDonationController` |
| POST | `/a/donate/shipments/{id}/check` | `donate:item` | 核对捐赠单据（逐件判定，须覆盖全部待核对物资，Row 17 第 6 步） | `AdminBookDonationController` |
| POST | `/a/donate/shipments/{id}/items` | `donate:item` | 单独添加物资（仅已到货 / 已核对的包裹） | `AdminBookDonationController` |
| POST | `/a/donate/shipments/{id}/return` | `donate:item` | 登记不合格物资寄回（快递公司 + 单号；按运单退） | `AdminBookDonationController` |
| POST | `/a/donate/shipments/{id}/subscribe` | `donate:item` | 重新订阅物流推送（仅「被中止 / 已放弃」且仍在途的运单；由定时任务下一轮发起，订阅是付费项） | `AdminBookDonationController` |
| GET | `/a/donate/shipments/{id}/track` | `donate:item` | 运单物流轨迹（refresh=true 强制重查，仍受终态约束） | `AdminBookDonationController` |
| GET | `/a/donate/verifiers` | `donate:verify` | 核销员列表 | `AdminMallVerifierController` |
| POST | `/a/donate/verifiers` | `donate:verify` | 指派核销员（须已实名且账号正常） | `AdminMallVerifierController` |
| DELETE | `/a/donate/verifiers/{id}` | `donate:verify` | 撤销核销员资格 | `AdminMallVerifierController` |
| GET | `/a/donate/wishes` | `donate:wish` | 心愿列表（搜索框：标题 / 编号 / 上报单位；状态下拉 0待认领/1已认领/2已实现/3已下架） | `AdminWishController` |
| POST | `/a/donate/wishes` | `donate:wish` | 单独上传一个心愿（编号留空由系统生成） | `AdminWishController` |
| GET | `/a/donate/wishes/export` | `donate:wish` | 批量下载心愿（含未成年人资料） | `AdminWishController` |
| POST | `/a/donate/wishes/import` | `donate:wish` | 批量导入心愿（xlsx，全成或全不成；失败时逐行给出原因） | `AdminWishController` |
| GET | `/a/donate/wishes/import-template` | `donate:wish` | 下载导入模板（表头即列名） | `AdminWishController` |
| GET | `/a/donate/wishes/{id}` | `donate:wish` | 心愿详情：资料全文 + 历次认领（认领人、寄来的包裹、物资与轨迹） | `AdminWishController` |
| PUT | `/a/donate/wishes/{id}` | `donate:wish` | 修改心愿资料（仅待认领 / 已下架；编号留空表示不改） | `AdminWishController` |
| POST | `/a/donate/wishes/{id}/realize` | `donate:wish` | 心愿实现：合格物资发放 + 上传发放图片（1~9 张），认领人收到站内提示 | `AdminWishController` |
| POST | `/a/donate/wishes/{id}/restore` | `donate:wish` | 重新上架（回到心愿池） | `AdminWishController` |
| POST | `/a/donate/wishes/{id}/revoke-claim` | `donate:wish` | 后台取消认领（原因必填，认领人收到站内提示；物资已在流转中则不可撤） | `AdminWishController` |
| POST | `/a/donate/wishes/{id}/take-down` | `donate:wish` | 下架（仅待认领） | `AdminWishController` |
| GET | `/a/donate/wishes/{id}/viewers` | `donate:wish` | 看过这个心愿详情的人与他们当时的手机号（协会 2026-10-16 丁-7；最近看的在前） | `AdminWishController` |

## 其他（回调 / 分享 / WebSocket 等）

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| POST | `/callback/logistics/kuaidi100` |  | 快递100 订阅推送（MD5(param + salt) 验签，每张运单一个 salt） | `LogisticsCallbackController` |
