import random
from collections import Counter

# 构建两副牌（108张）
suits = ['♠','♥','♦','♣']
ranks = ['2','3','4','5','6','7','8','9','10','J','Q','K','A']
# 级牌设为红心5，但在点数中仍用'5'，我们额外标记为级牌，但这里简化只按点数
deck = []
for _ in range(2):  # 两副
    for suit in suits:
        for rank in ranks:
            deck.append(rank + suit)
# 添加大小王（两副各两张）
deck.extend(['小王','小王','大王','大王'])

def rank_value(r):
    if r == '小王': return 16
    if r == '大王': return 17
    if r == 'A': return 14
    if r == 'K': return 13
    if r == 'Q': return 12
    if r == 'J': return 11
    if r == '10': return 10
    return int(r)  # 2-9

def has_straight(ranks_set):
    # ranks_set 是点数集合（set of int）
    for start in range(1, 11):  # A=14, 但A可作1，单独处理
        if all((start+i) in ranks_set for i in range(5)):
            return True
    # 特殊处理A2345 (1,2,3,4,5)
    if all(x in ranks_set for x in [1,2,3,4,5]):
        return True
    # 处理TJQKA (10,11,12,13,14)
    if all(x in ranks_set for x in [10,11,12,13,14]):
        return True
    return False

def has_flush_straight(cards):
    # cards 是牌面列表，返回是否有同花顺
    suits_dict = {}
    for card in cards:
        suit = card[-1] if card[-1] in '♠♥♦♣' else '王'  # 大小王不算
        if suit in suits_dict:
            suits_dict[suit].append(card)
        else:
            suits_dict[suit] = [card]
    for suit, suit_cards in suits_dict.items():
        if suit == '王': continue
        ranks = [rank_value(c[:-1]) for c in suit_cards]
        rank_set = set(ranks)
        if has_straight(rank_set):
            # 检查是否正好有5张连续且同花色（这里简单检查有连续5个点数即可，因为同花色）
            # 更严格：需要点数连续，这里has_straight已检查
            return True
    return False

def has_bomb(cards, n):
    # 检查是否有n张相同点数的牌（自然张数，级牌不另算）
    vals = [rank_value(c[:-1]) if c[0] not in '大小' else 16 if c[0]=='小' else 17 for c in cards]
    count = Counter(vals)
    return any(cnt >= n for cnt in count.values())

def has_pair(cards):
    vals = [rank_value(c[:-1]) if c[0] not in '大小' else 16 if c[0]=='小' else 17 for c in cards]
    return any(cnt >= 2 for cnt in Counter(vals).values())

def has_triple(cards):
    vals = [rank_value(c[:-1]) if c[0] not in '大小' else 16 if c[0]=='小' else 17 for c in cards]
    return any(cnt >= 3 for cnt in Counter(vals).values())

def has_full_house(cards):
    # 有三张和对子
    vals = [rank_value(c[:-1]) if c[0] not in '大小' else 16 if c[0]=='小' else 17 for c in cards]
    cnt = Counter(vals)
    has_three = any(c >= 3 for c in cnt.values())
    has_pair = any(c >= 2 for c in cnt.values())
    return has_three and has_pair

def has_consecutive_pairs(cards):
    # 连对：至少3个连续对子
    vals = [rank_value(c[:-1]) if c[0] not in '大小' else 16 if c[0]=='小' else 17 for c in cards]
    cnt = Counter(vals)
    pairs = [v for v, c in cnt.items() if c >= 2]
    if len(pairs) < 3:
        return False
    pairs_sorted = sorted(pairs)
    for i in range(len(pairs_sorted)-2):
        if pairs_sorted[i+1] == pairs_sorted[i]+1 and pairs_sorted[i+2] == pairs_sorted[i]+2:
            return True
    return False

def has_straight_triples(cards):
    # 钢板：两个连续三张
    vals = [rank_value(c[:-1]) if c[0] not in '大小' else 16 if c[0]=='小' else 17 for c in cards]
    cnt = Counter(vals)
    triples = [v for v, c in cnt.items() if c >= 3]
    if len(triples) < 2:
        return False
    triples_sorted = sorted(triples)
    for i in range(len(triples_sorted)-1):
        if triples_sorted[i+1] == triples_sorted[i]+1:
            return True
    return False

def has_rocket(cards):
    # 四王
    kings = sum(1 for c in cards if c in ['小王','大王'])
    return kings >= 4

# 模拟1000000手
N = 1000000
stats = {
    'single':0, 'pair':0, 'triple':0, 'straight':0,
    'full_house':0, 'consecutive_pairs':0, 'straight_triples':0,
    'bomb4':0, 'bomb5':0, 'bomb6':0, 'bomb7':0, 'bomb8':0,
    'bomb9':0, 'bomb10':0, 'flush_straight':0, 'rocket':0
}

for _ in range(N):
    hand = random.sample(deck, 27)
    # 检查各牌型
    stats['single'] += 1  # 总会有单张
    if has_pair(hand): stats['pair'] += 1
    if has_triple(hand): stats['triple'] += 1
    # 顺子：需要先获取所有点数集合
    vals = [rank_value(c[:-1]) if c[0] not in '大小' else 16 if c[0]=='小' else 17 for c in hand]
    if has_straight(set(vals)): stats['straight'] += 1
    if has_full_house(hand): stats['full_house'] += 1
    if has_consecutive_pairs(hand): stats['consecutive_pairs'] += 1
    if has_straight_triples(hand): stats['straight_triples'] += 1
    if has_bomb(hand, 4): stats['bomb4'] += 1
    if has_bomb(hand, 5): stats['bomb5'] += 1
    if has_bomb(hand, 6): stats['bomb6'] += 1
    if has_bomb(hand, 7): stats['bomb7'] += 1
    if has_bomb(hand, 8): stats['bomb8'] += 1
    if has_bomb(hand, 9): stats['bomb9'] += 1
    if has_bomb(hand, 10): stats['bomb10'] += 1
    if has_flush_straight(hand): stats['flush_straight'] += 1
    if has_rocket(hand): stats['rocket'] += 1

# 输出概率
for key, val in stats.items():
    print(f"{key}: {val}/{N} = {val/N*100:.1f}%")