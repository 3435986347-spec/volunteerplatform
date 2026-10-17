#!/usr/bin/env bash
# 把文档仓库的 知识库/ 发布到 Gitee 知识库仓库（henge-volunteerplatform-knowlegebase）。
#
# 发布方式是「快照提交」：每次发布在发布仓库的最新提交之上加一个提交，内容恰好是文档仓库 HEAD 里的 知识库/ 目录树。
# 不用 git subtree split——那会把 知识库/ 的全部历史一起推上去，而早期提交里可能还有后来才清掉的内容
# （2026-10-17 首次发布前就查到一条：拆页那一版的控制台页里写着线上服务器地址）。快照只发布通过了下面扫描的那一棵树。
#
# 源头永远是文档仓库的 知识库/：在发布仓库里直接改的内容会被下一次快照覆盖——要改回源头再发。
#
# 用法（在文档仓库任意位置运行）：
#   tools/publish_knowledge_base.sh            # 校验 → 敏感信息扫描 → 快照提交 → push
#   tools/publish_knowledge_base.sh --dry-run  # 只校验与扫描，打印将要推送的提交，不推
#
# 环境变量：KB_REMOTE_URL（默认 Gitee 知识库仓库）、KB_BRANCH（默认 master）、KB_TRAILER（追加到快照提交说明末尾，可空）
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
REMOTE_URL="${KB_REMOTE_URL:-https://gitee.com/huang-mingzhang/henge-volunteerplatform-knowlegebase.git}"
BRANCH="${KB_BRANCH:-master}"
PREFIX="知识库"
DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

# 1. 发布的是已提交的内容：知识库/ 有未提交改动时先提交
if ! git diff --quiet HEAD -- "$PREFIX" || [[ -n "$(git ls-files --others --exclude-standard -- "$PREFIX")" ]]; then
  echo "✗ $PREFIX/ 有未提交的改动——只发布已提交的内容，先提交再发布" >&2
  exit 1
fi

# 2. 生成物与代码一致、页里点名的东西都还在
python3 tools/gen_knowledge_base.py --check
python3 tools/verify_knowledge_base.py

# 3. 敏感信息扫描：服务器 IP、未打码的手机号、密钥形状的串。命中即停，不发布。
#    （127.0.0.1 / localhost 放行；手机号要的是 11 位连续数字且前后不是数字）
hits=$(perl -ne '
  next if /127\.0\.0\.1/ && !/(?<![\d.])(?!127\.)\d{1,3}(\.\d{1,3}){3}(?![\d.])/;
  print "$ARGV:$.: IP $&\n"        if /(?<![\d.])(?!127\.0\.0\.1)\d{1,3}(\.\d{1,3}){3}(?![\d.])/;
  print "$ARGV:$.: 手机号 $1\n"     while /(?<!\d)(1[3-9]\d{9})(?!\d)/g;
  print "$ARGV:$.: 疑似密钥 $&\n"   if /(AKLT[A-Za-z0-9]{16,}|LTAI[A-Za-z0-9]{12,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)/;
  close ARGV if eof;
' $(git ls-files -- "$PREFIX" | grep -E '\.(md|txt)$') || true)
if [[ -n "$hits" ]]; then
  echo "✗ 知识库里有不该发布的内容：" >&2
  echo "$hits" >&2
  exit 1
fi
echo "✓ 敏感信息扫描通过"

# 4. 快照提交：父提交是发布仓库当前的 $BRANCH（空仓库则没有父提交），树是 HEAD:知识库
tree=$(git rev-parse "HEAD:$PREFIX")
source_commit=$(git rev-parse --short HEAD)
parent_args=()
# ls-remote 失败（网络断、证书握手失败）要停下来，不能当成「空仓库」——那样会做出一个没有父提交的快照，推上去被拒成非快进
if ! remote_refs=$(git ls-remote "$REMOTE_URL" "refs/heads/$BRANCH"); then
  echo "✗ 连不上发布仓库 $REMOTE_URL（git ls-remote 失败），稍后重试" >&2
  exit 1
fi
remote_head=$(printf '%s' "$remote_refs" | cut -f1)
if [[ -n "$remote_head" ]]; then
  git fetch -q "$REMOTE_URL" "refs/heads/$BRANCH"
  if [[ "$(git rev-parse FETCH_HEAD^{tree})" == "$tree" ]]; then
    echo "✓ 发布仓库已经是这一版（文档仓库 $source_commit），不用再发"
    exit 0
  fi
  parent_args=(-p "$remote_head")
fi
msg="发布：文档仓库 $source_commit 的 知识库/

$(git log -1 --format=%s HEAD)"
[[ -n "${KB_TRAILER:-}" ]] && msg="$msg

$KB_TRAILER"
# ${arr[@]+…}：macOS 自带的 bash 3.2 在 set -u 下把空数组当未定义变量
snapshot=$(git commit-tree "$tree" ${parent_args[@]+"${parent_args[@]}"} -m "$msg")
echo "将发布 $snapshot（树 $tree，来自文档仓库 $source_commit）→ $REMOTE_URL ($BRANCH)"
if [[ $DRY_RUN -eq 1 ]]; then
  echo "（--dry-run：不推送）"
  exit 0
fi
git push "$REMOTE_URL" "$snapshot:refs/heads/$BRANCH"
echo "✓ 已发布"
