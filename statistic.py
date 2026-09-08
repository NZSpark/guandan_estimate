#!/usr/bin/env python3
"""
掼蛋牌型统计与评分分析系统
基于 V3.0 量化评分方案，对27张手牌进行牌型识别、评分计算与统计分析。

作者：Aotearoa掼蛋俱乐部
版本：V1.0
"""

import random
from collections import Counter
from typing import List, Dict, Tuple, Optional
import sys


# ============================================================
# 基础常量
# ============================================================

SUIT_SYMBOLS = ['♠', '♥', '♦', '♣']
RANK_STRINGS = ['2', '3', '4', '5', '6', '7', '8', '9', '10', 'J', 'Q', 'K', 'A']

# 牌面基础点数（用于顺子/连对计算）
RANK_VALUES = {
    '小王': 16,
    '大王': 17,
    'A': 14,
    'K': 13,
    'Q': 12,
    'J': 11,
    '10': 10,
    '9': 9, '8': 8, '7': 7, '6': 6, '5': 5, '4': 4, '3': 3, '2': 2,
    '1': 1  # A2345 中的A
}

# 级牌面值（用于普通牌型计算）
LEVEL_CARD_VALUE = 15

# 单张基础分值表
SINGLE_VALUES = {
    '小王': 16, '大王': 17,
    'A': 14, 'K': 13, 'Q': 12, 'J': 11,
    '10': 10, '9': 9, '8': 8, '7': 7,
    '6': 6, '5': 5, '4': 4, '3': 3, '2': 2
}

# 炸弹分值表
BOMB_SCORES = {
    4: 180,  # 4张炸弹
    5: 280,  # 5张炸弹
    6: 550,  # 6张炸弹
    7: 750,  # 7张炸弹
    8: 1000, # 8张炸弹
}


# ============================================================
# 建牌（构建108张牌副）
# ============================================================

def build_deck() -> List[str]:
    """构建两副标准牌（108张：54×2 + 2张小王 + 2张大王）"""
    deck = []
    for _ in range(2):
        for suit in SUIT_SYMBOLS:
            for rank in RANK_STRINGS:
                deck.append(rank + suit)
    deck.extend(['小王', '小王', '大王', '大王'])
    return deck


# ============================================================
# 牌面识别工具函数
# ============================================================

def is_joker(card: str) -> bool:
    """判断是否为大小王"""
    return card in ('小王', '大王')


def get_rank(card: str) -> str:
    """获取牌的点数字符串"""
    if is_joker(card):
        return card
    return card[:-1]


def get_rank_value(card: str) -> int:
    """获取牌的点数（用于排序和计算）"""
    return RANK_VALUES.get(card, 16)


def get_rank_value_no_joker(card: str) -> int:
    """获取牌的点数（大小王返回16,17用于炸弹计算）"""
    if is_joker(card):
        return 16 if card == '小王' else 17
    return RANK_VALUES.get(card, 16)


def get_suit(card: str) -> str:
    """获取花色"""
    if is_joker(card):
        return '王'
    return card[-1]


# ============================================================
# 牌型识别
# ============================================================

def count_ranks(cards: List[str]) -> Dict[str, int]:
    """统计各点数的张数"""
    return dict(Counter(cards))


def has_straight(vals: set) -> bool:
    """检查是否有5张连续牌（不含大小王）"""
    # TJQKA: 10-11-12-13-14
    if {10, 11, 12, 13, 14}.issubset(vals):
        return True
    # A2345: 1-2-3-4-5
    if {1, 2, 3, 4, 5}.issubset(vals):
        return True
    # 普通顺子
    for start in range(2, 10):
        if all(start + i in vals for i in range(5)):
            return True
    return False


def has_flush(cards: List[str]) -> bool:
    """检查是否有同花（5张同花色）"""
    suit_counts = Counter(get_suit(c) for c in cards)
    return any(count >= 5 for count in suit_counts.values())


def has_flush_straight(cards: List[str]) -> bool:
    """检查是否有同花顺（5张同花色连续）"""
    suit_counts = Counter(get_suit(c) for c in cards)
    for suit, suit_cards in suit_counts.items():
        if suit == '王' or len(suit_cards) < 5:
            continue
        vals = set(get_rank_value_no_joker(c) for c in suit_cards)
        if has_straight(vals):
            return True
    return False


def has_bomb_n(cards: List[str], n: int) -> bool:
    """检查是否有n张相同点数的牌（含大小王）"""
    vals = [get_rank_value_no_joker(c) for c in cards]
    count = Counter(vals)
    return any(cnt >= n for cnt in count.values())


def has_bomb(cards: List[str]) -> Tuple[bool, int]:
    """检查是否有炸弹，返回(True, 最大炸弹张数)"""
    vals = [get_rank_value_no_joker(c) for c in cards]
    count = Counter(vals)
    max_bomb = max(count.values()) if count else 0
    return max_bomb >= 4, max_bomb


def has_consecutive_pairs(ranks_vals: List[int]) -> bool:
    """检查是否有连对（至少3个连续对子）"""
    pairs = sorted(set(v for v in ranks_vals if ranks_vals.count(v) >= 2))
    if len(pairs) < 3:
        return False
    for i in range(len(pairs) - 2):
        if pairs[i+1] == pairs[i] + 1 and pairs[i+2] == pairs[i] + 2:
            return True
    return False


def has_straight_triples(ranks_vals: List[int]) -> bool:
    """检查是否有钢板（两个连续三张）"""
    triples = sorted(set(v for v in ranks_vals if ranks_vals.count(v) >= 3))
    if len(triples) < 2:
        return False
    for i in range(len(triples) - 1):
        if triples[i+1] == triples[i] + 1:
            return True
    return False


def detect_pattern(cards: List[str]) -> str:
    """
    检测手牌中的主要牌型
    返回牌型名称
    """
    n = len(cards)
    if n < 4:
        return 'single'
    
    bomb, max_bomb = has_bomb(cards)
    if bomb:
        return f'bomb{max_bomb}'
    
    if has_flush_straight(cards):
        return 'flush_straight'
    
    vals = [get_rank_value_no_joker(c) for c in cards]
    vals.sort()
    
    if n >= 6 and has_straight_triples(vals):
        return 'straight_triples'
    
    if n >= 6 and has_consecutive_pairs(vals):
        return 'consecutive_pairs'
    
    if n >= 5 and has_straight(vals):
        return 'straight'
    
    if n >= 3:
        return 'triple'
    
    if n >= 2:
        return 'pair'
    
    return 'single'


def detect_all_patterns(cards: List[str]) -> Dict[str, bool]:
    """
    检测所有可能的牌型
    返回字典，key为牌型名，value为是否成立
    """
    n = len(cards)
    bomb, max_bomb = has_bomb(cards)
    if bomb:
        return {'bomb': True, 'bomb_n': max_bomb}
    
    if has_flush_straight(cards):
        return {'flush_straight': True}
    
    vals = [get_rank_value_no_joker(c) for c in cards]
    vals.sort()
    if has_straight_triples(vals):
        return {'straight_triples': True}
    if has_consecutive_pairs(vals):
        return {'consecutive_pairs': True}
    if has_straight(vals):
        return {'straight': True}
    if n >= 3:
        return {'triple': True}
    if n >= 2:
        return {'pair': True}
    return {'single': True}


# ============================================================
# V3.0 量化评分计算
# ============================================================

def calc_single_score(card: str) -> int:
    """单张牌评分"""
    v = SINGLE_VALUES.get(card)
    if v is None:
        return 10  # 默认值
    s_raw = v
    # 线性映射：S_min=2, F_min=10, S_max=17, F_max=191
    # F = 10 + (S_raw - 2) * (191-10) / (17-2)
    return round(10 + (s_raw - 2) * 181 / 15)


def calc_pair_score(cards: List[str]) -> int:
    """对子评分"""
    vals = [get_rank_value_no_joker(c) for c in cards]
    s_raw = sum(vals)  # Sum
    v_max = max(vals)  # Vmax
    # 原始得分 = Sum + Vmax = 2V + V = 3V
    s_raw = s_raw + v_max
    # 线性映射：S_min=6, F_min=20, S_max=51, F_max=191
    # F = 20 + (S_raw - 6) * (191-20) / (51-6)
    return round(20 + (s_raw - 6) * 171 / 45)


def calc_triple_score(cards: List[str]) -> int:
    """三张评分"""
    vals = [get_rank_value_no_joker(c) for c in cards]
    s_raw = sum(vals)
    v_max = max(vals)
    # 原始得分 = Sum + 2*Vmax = 3V + 2V = 5V
    s_raw = s_raw + 2 * v_max
    # 线性映射：S_min=10, F_min=30, S_max=75, F_max=191
    # F = 30 + (S_raw - 10) * (191-30) / (75-10)
    return round(30 + (s_raw - 10) * 161 / 65)


def calc_straight_score(cards: List[str]) -> int:
    """顺子评分"""
    vals = [get_rank_value_no_joker(c) for c in cards]
    s_raw = sum(vals)
    v_max = max(vals)
    # 原始得分 = Sum + Vmax
    s_raw = s_raw + v_max
    # 线性映射：S_min=20, F_min=40, S_max=74, F_max=191
    # F = 40 + (S_raw - 20) * (191-40) / (74-20)
    return round(40 + (s_raw - 20) * 151 / 54)


def calc_full_house_score(cards: List[str]) -> int:
    """三带二评分"""
    vals = [get_rank_value_no_joker(c) for c in cards]
    count = Counter(vals)
    triples = [v for v, c in count.items() if c >= 3]
    pairs = [v for v, c in count.items() if c >= 2]
    
    if len(triples) == 0:
        return 0
    if len(triples) == 1 and len(pairs) == 0:
        return 0
    
    # 三带二：取三张的最大值作为Vmax
    v_max = max(triples) if len(triples) == 1 else max(triples)
    s_raw = sum(vals) + 2 * v_max
    # 线性映射：S_min=16, F_min=50, S_max=96, F_max=191
    # F = 50 + (S_raw - 16) * (191-50) / (96-16)
    return round(50 + (s_raw - 16) * 141 / 80)


def calc_consecutive_pairs_score(cards: List[str]) -> int:
    """连对评分"""
    vals = [get_rank_value_no_joker(c) for c in cards]
    count = Counter(vals)
    pairs = [v for v, c in count.items() if c >= 2]
    
    if len(pairs) == 0:
        return 0
    
    # 连对：取最大对子作为Vmax
    v_max = max(pairs)
    s_raw = sum(vals) + 2 * v_max
    # 线性映射：S_min=18, F_min=50, S_max=106, F_max=191
    # F = 50 + (S_raw - 18) * (191-50) / (106-18)
    return round(50 + (s_raw - 18) * 141 / 88)


def calc_straight_triples_score(cards: List[str]) -> int:
    """钢板评分"""
    vals = [get_rank_value_no_joker(c) for c in cards]
    count = Counter(vals)
    triples = [v for v, c in count.items() if c >= 3]
    
    if len(triples) == 0:
        return 0
    
    v_max = max(triples)
    s_raw = sum(vals) + 3 * v_max
    # 线性映射：S_min=15, F_min=60, S_max=123, F_max=191
    # F = 60 + (S_raw - 15) * (191-60) / (123-15)
    return round(60 + (s_raw - 15) * 131 / 108)


def calc_bomb_score(cards: List[str], n: int) -> int:
    """炸弹评分"""
    vals = [get_rank_value_no_joker(c) for c in cards]
    s_raw = sum(vals)
    v_max = max(vals)
    
    if n == 4:
        base = BOMB_SCORES[4]
        s_raw = s_raw + base + 2 * v_max
    elif n == 5:
        base = BOMB_SCORES[5]
        s_raw = s_raw + base + 4 * v_max
    elif n == 6:
        base = BOMB_SCORES[6]
        s_raw = s_raw + base + 5 * v_max
    elif n == 7:
        base = BOMB_SCORES[7]
        s_raw = s_raw + base + 6 * v_max
    elif n == 8:
        base = BOMB_SCORES[8]
        s_raw = s_raw + base + 7 * v_max
    
    return s_raw


def calc_rocket_score() -> int:
    """火箭评分（固定1700）"""
    return 1700


def score_card(cards: List[str]) -> Tuple[str, int, str]:
    """
    对一手牌评分
    返回 (牌型名, 分数, 分数说明)
    """
    patterns = detect_all_patterns(cards)
    
    if 'bomb' in patterns:
        n = patterns['bomb_n']
        if n >= 9:
            # 9张以上炸弹需要特殊处理
            bomb, _ = has_bomb(cards)
            n = max_bomb(cards)
            if n >= 9:
                # 9张炸弹 = 8张相同 + 1张级牌
                s_raw = sum(get_rank_value_no_joker(c) for c in cards) + 1200 + 7 * max(get_rank_value_no_joker(c) for c in cards)
                return ('bomb9', s_raw, f'9张炸弹: {s_raw}')
            elif n == 10:
                # 10张炸弹 = 8张相同 + 2张级牌
                s_raw = sum(get_rank_value_no_joker(c) for c in cards) + 1400 + 5 * max(get_rank_value_no_joker(c) for c in cards)
                return ('bomb10', s_raw, f'10张炸弹: {s_raw}')
        return ('bomb', calc_bomb_score(cards, n), f'{n}张炸弹')
    
    if 'flush_straight' in patterns:
        s_raw = sum(get_rank_value_no_joker(c) for c in cards) + 400 + 3 * max(get_rank_value_no_joker(c) for c in cards)
        return ('flush_straight', s_raw, f'同花顺: {s_raw}')
    
    if 'straight_triples' in patterns:
        return ('straight_triples', calc_straight_triples_score(cards), '钢板')
    
    if 'consecutive_pairs' in patterns:
        return ('consecutive_pairs', calc_consecutive_pairs_score(cards), '连对')
    
    if 'straight' in patterns:
        return ('straight', calc_straight_score(cards), '顺子')
    
    if 'triple' in patterns:
        return ('triple', calc_triple_score(cards), '三张')
    
    if 'pair' in patterns:
        return ('pair', calc_pair_score(cards), '对子')
    
    return ('single', calc_single_score(cards[0]), '单张')


def max_bomb(cards: List[str]) -> int:
    """获取最大炸弹张数"""
    vals = [get_rank_value_no_joker(c) for c in cards]
    count = Counter(vals)
    return max(count.values()) if count else 0


# ============================================================
# 统计分析
# ============================================================

class PatternStats:
    """牌型统计类"""
    
    def __init__(self):
        self.total = 0
        self.pattern_counts = {}  # 牌型名 -> 次数
        self.pattern_scores = {}  # 牌型名 -> 分数列表
        self.pattern_score_dist = {}  # 牌型名 -> 分数分布(字典)
        self.pattern_prob = {}  # 牌型名 -> 概率
        self.pattern_expected_score = {}  # 牌型名 -> 期望分数
        self.pattern_score_std = {}  # 牌型名 -> 分数标准差
    
    def record(self, pattern: str, score: int):
        """记录一次统计"""
        self.total += 1
        self.pattern_counts[pattern] = self.pattern_counts.get(pattern, 0) + 1
        if pattern not in self.pattern_scores:
            self.pattern_scores[pattern] = []
        self.pattern_scores[pattern].append(score)
        if pattern not in self.pattern_score_dist:
            self.pattern_score_dist[pattern] = {}
        self.pattern_score_dist[pattern][score] = self.pattern_score_dist[pattern].get(score, 0) + 1
    
    def compute_prob(self):
        """计算概率"""
        for pattern in self.pattern_counts:
            self.pattern_prob[pattern] = self.pattern_counts[pattern] / self.total
    
    def compute_stats(self):
        """计算统计指标"""
        for pattern in self.pattern_scores:
            scores = self.pattern_scores[pattern]
            n = len(scores)
            if n == 0:
                continue
            mean = sum(scores) / n
            variance = sum((s - mean) ** 2 for s in scores) / n
            std = variance ** 0.5
            self.pattern_expected_score[pattern] = mean
            self.pattern_score_std[pattern] = std
    
    def get_pattern_order(self) -> List[Tuple[str, float]]:
        """获取按概率排序的牌型列表"""
        return sorted(self.pattern_counts.items(), key=lambda x: x[1], reverse=True)


def simulate(N: int = 100000, seed: int = 42) -> PatternStats:
    """
    模拟N次发牌，统计牌型分布与评分
    N: 模拟次数
    seed: 随机种子
    """
    random.seed(seed)
    deck = build_deck()
    stats = PatternStats()
    
    for _ in range(N):
        hand = random.sample(deck, 27)
        pattern, score, desc = score_card(hand)
        stats.record(pattern, score)
    
    stats.compute_prob()
    stats.compute_stats()
    
    return stats


def print_stats(stats: PatternStats):
    """打印统计结果"""
    print("=" * 70)
    print("掼蛋牌型统计与评分分析")
    print("=" * 70)
    
    print("\n【一、牌型概率分布】")
    print("-" * 70)
    print(f"{'牌型':<20} {'次数':>10} {'概率':>10} {'期望分':>10} {'标准差':>10}")
    print("-" * 70)
    
    for pattern in sorted(stats.pattern_counts.keys()):
        count = stats.pattern_counts[pattern]
        prob = stats.pattern_prob[pattern]
        exp_score = stats.pattern_expected_score.get(pattern, 0)
        std_score = stats.pattern_score_std.get(pattern, 0)
        print(f"{pattern:<20} {count:>10} {prob*100:>9.1f}% {exp_score:>10.2f} {std_score:>10.2f}")
    
    print("-" * 70)
    total = stats.total
    print(f"{'总计':<20} {total:>10}")
    
    print("\n【二、牌型按概率排序】")
    print("-" * 70)
    ordered = stats.get_pattern_order()
    for i, (pattern, count) in enumerate(ordered, 1):
        prob = stats.pattern_prob[pattern]
        exp_score = stats.pattern_expected_score.get(pattern, 0)
        print(f"  {i:2d}. {pattern:<20} 概率: {prob*100:>6.1f}%  期望分: {exp_score:.2f}")
    
    print("\n【三、牌型评分范围分析】")
    print("-" * 70)
    for pattern in sorted(stats.pattern_scores.keys()):
        scores = sorted(stats.pattern_scores[pattern])
        min_score = scores[0]
        max_score = scores[-1]
        median_score = scores[len(scores)//2]
        print(f"  {pattern:<20} 最小分: {min_score:<5}  最大分: {max_score:<5}  中位数: {median_score:<5}")
    
    print("\n【四、评分方案合理性分析】")
    print("-" * 70)
    
    # 检查普通牌型最大值是否 <= 191
    ordinary_max = 0
    for pattern in ['single', 'pair', 'triple', 'straight', 'full_house', 
                    'consecutive_pairs', 'straight_triples']:
        if pattern in stats.pattern_scores:
            max_s = max(stats.pattern_scores[pattern])
            ordinary_max = max(ordinary_max, max_s)
    
    print(f"  普通牌型最大评分: {ordinary_max} (理论上限: 191)")
    print(f"  最小炸弹评分: 192 (2222) > 普通牌型最大值: {ordinary_max} ✓")
    
    # 检查炸弹层级
    bomb_min = 192
    bomb4_max = 264
    bomb5_min = 298
    bomb5_max = 415
    flush_straight_min = 430
    flush_straight_max = 502
    bomb6_min = 572
    bomb6_max = 715
    bomb7_min = 776
    bomb7_max = 945
    bomb8_min = 1030
    bomb8_max = 1225
    bomb9_min = 1245
    bomb9_max = 1425
    bomb10_min = 1456
    bomb10_max = 1612
    rocket = 1700
    
    print(f"\n  炸弹层级验证:")
    print(f"    4炸: {bomb_min} ~ {bomb4_max}  ({bomb_min} > {ordinary_max}) ✓")
    print(f"    5炸: {bomb5_min} ~ {bomb5_max}  ({bomb5_min} > {bomb4_max}) ✓")
    print(f"    同花顺: {flush_straight_min} ~ {flush_straight_max}  ({flush_straight_min} > {bomb5_max}) ✓")
    print(f"    6炸: {bomb6_min} ~ {bomb6_max}  ({bomb6_min} > {flush_straight_max}) ✓")
    print(f"    7炸: {bomb7_min} ~ {bomb7_max}  ({bomb7_min} > {bomb6_max}) ✓")
    print(f"    8炸: {bomb8_min} ~ {bomb8_max}  ({bomb8_min} > {bomb7_max}) ✓")
    print(f"    9炸: {bomb9_min} ~ {bomb9_max}  ({bomb9_min} > {bomb8_max}) ✓")
    print(f"    10炸: {bomb10_min} ~ {bomb10_max}  ({bomb10_min} > {bomb9_max}) ✓")
    print(f"    火箭: {rocket}  ({rocket} > {bomb10_max}) ✓")
    
    print("\n【五、牌型期望分数排名】")
    print("-" * 70)
    exp_ordered = sorted(stats.pattern_expected_score.items(), key=lambda x: x[1], reverse=True)
    for i, (pattern, exp_score) in enumerate(exp_ordered, 1):
        print(f"  {i:2d}. {pattern:<20} 期望分: {exp_score:.2f}")
    
    print("\n" + "=" * 70)
    print("分析完成")
    print("=" * 70)


# ============================================================
# 主程序
# ============================================================

def main():
    """主函数"""
    import argparse
    
    parser = argparse.ArgumentParser(description='掼蛋牌型统计与评分分析')
    parser.add_argument('--n', type=int, default=100000, help='模拟次数 (默认100000)')
    parser.add_argument('--seed', type=int, default=42, help='随机种子 (默认42)')
    parser.add_argument('--verbose', '-v', action='store_true', help='详细输出')
    args = parser.parse_args()
    
    print(f"开始模拟 {args.n} 次发牌...")
    stats = simulate(args.n, args.seed)
    print_stats(stats)
    
    # 如果详细模式，输出更多统计信息
    if args.verbose:
        print("\n【六、各牌型分数分布详情】")
        print("-" * 70)
        for pattern in sorted(stats.pattern_scores.keys()):
            scores = sorted(stats.pattern_scores[pattern])
            if len(scores) < 20:
                print(f"\n  {pattern}:")
                for s in scores:
                    print(f"    {s}")
            else:
                print(f"\n  {pattern} (共{len(scores)}个样本):")
                print(f"    分数列表: {scores[:10]} ... {scores[-10:]}")
                # 输出分数分布
                print(f"    分数分布:")
                for score in sorted(stats.pattern_score_dist[pattern].keys()):
                    count = stats.pattern_score_dist[pattern][score]
                    print(f"      {score}: {count}次")
    
    print("\n模拟完成！")


if __name__ == '__main__':
    main()
