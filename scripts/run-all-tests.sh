#!/usr/bin/env bash
# 全量回归驱动：顺序跑 8 个套件（共用同一库），统计每个套件的 PASS/FAIL 与退出码
cd "D:/Work/Hotel Management/hotel-management-system" || exit 1
export HMS_BASE=http://127.0.0.1:8081
LOG=/tmp/regress-full.log
: > "$LOG"
TOTAL_P=0; TOTAL_F=0
printf "%-14s %8s %8s %6s\n" "SUITE" "PASS" "FAIL" "EXIT" | tee -a "$LOG"
for s in func-test opt1-test opt2-test opt3-test opt4-test opt5-test opt6-test opt7-test; do
  bash "scripts/$s.sh" > "/tmp/suite-$s.log" 2>&1
  ec=$?
  p=$(grep -c '\[PASS\]' "/tmp/suite-$s.log")
  f=$(grep -c '\[FAIL\]' "/tmp/suite-$s.log")
  TOTAL_P=$((TOTAL_P + p)); TOTAL_F=$((TOTAL_F + f))
  printf "%-14s %8d %8d %6d\n" "$s" "$p" "$f" "$ec" | tee -a "$LOG"
  cat "/tmp/suite-$s.log" >> "$LOG"
done
echo "==============================================" | tee -a "$LOG"
printf "TOTAL PASS=%d FAIL=%d\n" "$TOTAL_P" "$TOTAL_F" | tee -a "$LOG"
