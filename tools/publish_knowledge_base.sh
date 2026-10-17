#!/usr/bin/env bash
# 把文档仓库的 知识库/ 以 git subtree 发布到 Gitee 知识库仓库（henge-volunteerplatform-knowlegebase）。
#
# 源头永远是文档仓库的 知识库/：发布仓库的历史只含这个目录（subtree split 出来的），
# 在发布仓库里直接改的内容下次发布会被拒（非快进）——要改回源头再发。
#
# 用法（在文档仓库任意位置运行）：
#   tools/publish_knowledge_base.sh            # 校验 → 敏感信息扫描 → split → push
#   tools/publish_knowledge_base.sh --dry-run  # 只校验与扫描，打印将要推送的提交，不推
#
# 环境变量：KB_REMOTE_URL（默认 Gitee 知识库仓库）、KB_BRANCH（默认 master）
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
REMOTE_URL="${KB_REMOTE_URL:-https://gitee.com/huang-mingzhang/henge-volunteerplatform-knowlegebase.git}"
BRANCH="${KB_BRANCH:-master}"
PREFIX="知识库"
DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

# 1. 发布的是已提交的内容：知识库/ 有未提交改动时先提交
if ! git diff --quiet HEAD -- "$PREFIX" || [[ -n "$(git ls-files --others --exclude-standard -- "$PREFIX")" ]]; then
  echo "✗ $PREFIX/ 有未提交的改动——subtree 只发布已提交的内容，先提交再发布" >&2
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

# 4. split 出只含 知识库/ 历史的提交，推到发布仓库
split=$(git subtree split --prefix="$PREFIX" HEAD)
echo "将发布 $split → $REMOTE_URL ($BRANCH)"
if [[ $DRY_RUN -eq 1 ]]; then
  echo "（--dry-run：不推送）"
  exit 0
fi
git push "$REMOTE_URL" "$split:refs/heads/$BRANCH"
echo "✓ 已发布"
