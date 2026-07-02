// 活动「服务保障」12 项的单一权威常量：key/顺序与后端 ServiceGuarantee 常量、
// 图标资产 assets/activity/guarantee/NN-key-{red,gray}.png 一一对应。
// 详情页（展示红/灰）与发布页（不定项选择）共用此表，避免「发布选了、详情不亮」的 key 漂移。
const GUARANTEE_ORDER = [
  { key: "clothing", label: "志愿者服装", asset: "01-clothing" },
  { key: "water", label: "提供饮水", asset: "02-water" },
  { key: "certificate", label: "志愿服务证书", asset: "03-certificate" },
  { key: "training", label: "专项培训", asset: "04-training" },
  { key: "insurance", label: "志愿者保险", asset: "05-insurance" },
  { key: "traffic", label: "交通补贴", asset: "06-traffic" },
  { key: "meal", label: "餐饮或食物", asset: "07-meal" },
  { key: "bus", label: "集中乘车", asset: "08-bus" },
  { key: "hotel", label: "提供住宿", asset: "09-hotel" },
  { key: "tool", label: "志愿服务工具", asset: "10-tool" },
  { key: "checkup", label: "免费体检", asset: "11-checkup" },
  { key: "other", label: "其他", asset: "12-other" }
];

// 图标路径：选中=红、未选=灰
function guaranteeIcon(asset, enabled) {
  return `/assets/activity/guarantee/${asset}-${enabled ? "red" : "gray"}.png`;
}

module.exports = { GUARANTEE_ORDER, guaranteeIcon };
