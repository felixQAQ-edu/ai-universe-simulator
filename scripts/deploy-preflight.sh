#!/bin/sh
# deploy-preflight.sh —— `fly deploy` 之前跑,只读,不部署。
#
# 为什么有它:runbook §3.1.4「部署前确认本地工作副本不落后」是一条纪律,
# 而这条纪律已经两次没防住(2026-09-17 打出 e6d6ee2;2026-09-28 本地 [behind 32]
# 打出 6d49a15)。两次 §3.1.5 都是绿的 —— 它比的是「线上 == 本地 HEAD」,
# 本地 HEAD 本身旧,就相等而放行。故把那条纪律改成机器拦:非零退出就停。
#
# 它检查三件事,全部只读:
#   1. git fetch origin 成功(拿不到远端就无从比较,不放行);
#   2. 工作区干净(含未跟踪文件 —— Docker 构建上下文会把它们一并打进镜像);
#   3. HEAD == origin/main。
# 全部通过 → 打印「期望线上 SHA」,§3.1.5 的本地期望值就读这一行。
#
# ⚠️ 本脚本不调用 fly,也不给部署命令 —— 部署仍是 Felix 亲手(runbook 头部分工红线)。

set -u

REMOTE_BRANCH="origin/main"

fail() {
  printf '✗ preflight 未通过:%s\n' "$1" >&2
  shift
  for line in "$@"; do
    printf '  %s\n' "$line" >&2
  done
  printf '  → 不要部署。处理完再跑一遍本脚本。\n' >&2
  exit 1
}

git rev-parse --git-dir >/dev/null 2>&1 \
  || fail "当前目录不是 git 仓库"

printf '· git fetch origin ...\n'
git fetch origin \
  || fail "git fetch origin 失败" "拿不到远端就无从判断本地是否落后。"

git rev-parse --verify --quiet "$REMOTE_BRANCH" >/dev/null \
  || fail "找不到 $REMOTE_BRANCH"

DIRTY="$(git status --porcelain)"
if [ -n "$DIRTY" ]; then
  printf '%s\n' "$DIRTY" | sed 's/^/    /' >&2
  fail "工作区不干净(含未跟踪文件,见上)" \
    "部署会把这些改动打进镜像,而线上 SHA 指向的 commit 不含它们。" \
    "先提交 / 丢弃 / 移走,再重跑。"
fi

HEAD_SHA="$(git rev-parse --short HEAD)"
REMOTE_SHA="$(git rev-parse --short "$REMOTE_BRANCH")"

if [ "$HEAD_SHA" != "$REMOTE_SHA" ]; then
  # 左 = 本地独有(ahead),右 = 远端独有(behind)
  COUNTS="$(git rev-list --left-right --count HEAD..."$REMOTE_BRANCH")"
  AHEAD="$(printf '%s' "$COUNTS" | awk '{print $1}')"
  BEHIND="$(printf '%s' "$COUNTS" | awk '{print $2}')"
  if [ "$AHEAD" -eq 0 ]; then
    fail "本地落后 $REMOTE_BRANCH $BEHIND 个 commit(本地 $HEAD_SHA,远端 $REMOTE_SHA)" \
      "先 git pull --ff-only。"
  elif [ "$BEHIND" -eq 0 ]; then
    fail "本地领先 $REMOTE_BRANCH $AHEAD 个 commit(本地 $HEAD_SHA,远端 $REMOTE_SHA)" \
      "要部署的东西不在 main 上。先合并并 push,或切回 main 后 git pull --ff-only。"
  else
    fail "本地与 $REMOTE_BRANCH 分叉(本地独有 $AHEAD / 远端独有 $BEHIND;本地 $HEAD_SHA,远端 $REMOTE_SHA)" \
      "先切回 main 并 git pull --ff-only(或处理分叉)。"
  fi
fi

printf '✓ preflight 通过:工作区干净,HEAD == %s\n' "$REMOTE_BRANCH"
printf '期望线上 SHA = %s\n' "$REMOTE_SHA"
exit 0
