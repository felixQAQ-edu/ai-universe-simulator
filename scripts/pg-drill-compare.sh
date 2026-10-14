#!/bin/sh
# ADR-032 切库演练 · 第 6 步:比对「源副本目录」与「导出目录」(只读)。
#
# 用法:
#   ./scripts/pg-drill-compare.sh <源副本目录> <导出目录> [排除名单文件]
#
# 只比两个目录【直接下】的 *.json(与导入工具同口径,不递归)。逐个 cmp,不打印任何文件内容,
# 只打印计数与文件名。排除名单:每行一个文件名(如 abc.json),空行与 # 开头的行忽略;
# 排除项照样报状态,但不影响退出码(第 5 步走过一回合的那一局、月账等非存档放这里)。
#
# 退出码:
#   0  全部非排除项两侧都在且逐字节相同
#   1  有非排除项不同,或只在一侧
#   2  用法错误 / 目录不存在 / 目录在 git 仓库内(拒绝运行)
#
# POSIX sh;不写任何文件。真实存档只许放在仓库之外(ADR-031 W-11),故目录在仓库内一律拒绝。

set -u

usage() {
	echo "用法:$0 <源副本目录> <导出目录> [排除名单文件]" >&2
	exit 2
}

[ $# -ge 2 ] && [ $# -le 3 ] || usage
SRC=$1
EXP=$2
EXCL=${3:-}

for d in "$SRC" "$EXP"; do
	if [ ! -d "$d" ]; then
		echo "✗ 目录不存在:$d" >&2
		exit 2
	fi
	if (cd "$d" && git rev-parse --is-inside-work-tree >/dev/null 2>&1); then
		echo "✗ 拒绝运行:$d 在 git 仓库内(真实存档不得进仓库,W-11)" >&2
		exit 2
	fi
done
if [ -n "$EXCL" ] && [ ! -f "$EXCL" ]; then
	echo "✗ 排除名单文件不存在:$EXCL" >&2
	exit 2
fi

is_excluded() {
	[ -n "$EXCL" ] || return 1
	grep -v '^#' "$EXCL" | grep -Fxq -- "$1"
}

count_json() {
	n=0
	for f in "$1"/*.json; do
		[ -f "$f" ] && n=$((n + 1))
	done
	echo "$n"
}

same=0
diff_n=0
miss_n=0
bad=""
excl_report=""

# 源侧:逐个看导出侧
for f in "$SRC"/*.json; do
	[ -f "$f" ] || continue
	name=${f##*/}
	if [ -f "$EXP/$name" ]; then
		if cmp -s "$f" "$EXP/$name"; then st="相同"; else st="不同"; fi
	else
		st="只在源"
	fi
	if is_excluded "$name"; then
		excl_report="$excl_report  - $name:$st
"
		continue
	fi
	case $st in
		相同) same=$((same + 1)) ;;
		不同) diff_n=$((diff_n + 1)); bad="$bad  - 不同:$name
" ;;
		只在源) miss_n=$((miss_n + 1)); bad="$bad  - 只在源:$name
" ;;
	esac
done

# 导出侧:只在导出里的
for f in "$EXP"/*.json; do
	[ -f "$f" ] || continue
	name=${f##*/}
	[ -f "$SRC/$name" ] && continue
	if is_excluded "$name"; then
		excl_report="$excl_report  - $name:只在导出
"
		continue
	fi
	miss_n=$((miss_n + 1))
	bad="$bad  - 只在导出:$name
"
done

# 名单里两侧都没有的项也报出来(多半是名单写错了)
if [ -n "$EXCL" ]; then
	ghost=$(grep -v '^#' "$EXCL" | while IFS= read -r name; do
		[ -n "$name" ] || continue
		if [ ! -f "$SRC/$name" ] && [ ! -f "$EXP/$name" ]; then
			echo "  - $name:两侧都没有(名单写错?)"
		fi
	done)
	if [ -n "$ghost" ]; then
		excl_report="$excl_report$ghost
"
	fi
fi

echo "[pg-drill-compare] 源 *.json = $(count_json "$SRC") / 导出 *.json = $(count_json "$EXP")"
echo "非排除项:相同 = $same / 不同 = $diff_n / 一侧缺失 = $miss_n"
if [ -n "$bad" ]; then
	printf '%s' "$bad"
fi
if [ -n "$EXCL" ]; then
	echo "排除项状态:"
	printf '%s' "$excl_report"
fi

if [ "$diff_n" -eq 0 ] && [ "$miss_n" -eq 0 ]; then
	echo "退出码 = 0"
	exit 0
fi
echo "退出码 = 1"
exit 1
