RANKS = ["2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "SJ", "BJ"]
SUITS = ["CLUB", "DIAMOND", "HEART", "SPADE", "JOKER"]

# 大小王按颜色区分：大王有彩色印刷，小王是黑白线稿（用户口径）。
# 阈值用 327 张 Joker crop（T3.1，rotation=0）量出来的：
#   中心 60% 区域、HSV 饱和度 > 0.25 的像素占比
#   SJ 中位数 0.0118、BJ 中位数 0.1634；两侧分布仍有重叠（SJ max 0.0852 / BJ min 0.0232），
# 所以只在两端“确定区”覆盖模型结论，中间灰区保留模型判断，不硬猜。
JOKER_COLOR_BJ_MIN = 0.12
JOKER_COLOR_SJ_MAX = 0.01
COLOR_SATURATION_MIN = 0.25
COLOR_CENTER_MARGIN = 0.2


def colored_fraction(image) -> float:
    """中心 60% 区域里，高饱和度像素（彩色油墨）所占比例。"""
    import numpy as np

    width, height = image.size
    left, top = int(width * COLOR_CENTER_MARGIN), int(height * COLOR_CENTER_MARGIN)
    region = np.asarray(
        image.convert("RGB").crop((left, top, width - left, height - top)), dtype=np.float32
    ) / 255.0
    if region.size == 0:
        return 0.0
    maximum = region.max(axis=2)
    minimum = region.min(axis=2)
    saturation = np.where(maximum > 0, (maximum - minimum) / np.maximum(maximum, 1e-6), 0.0)
    return float((saturation > COLOR_SATURATION_MIN).mean())


def joker_rank_by_color(image) -> str | None:
    """彩色→BJ（大王），黑白→SJ（小王）；灰区返回 None 表示不下结论。"""
    fraction = colored_fraction(image)
    if fraction >= JOKER_COLOR_BJ_MIN:
        return "BJ"
    if fraction <= JOKER_COLOR_SJ_MAX:
        return "SJ"
    return None


def validate_label(rank: str, suit: str) -> None:
    if rank not in RANKS:
        raise ValueError(f"Unknown rank: {rank}")
    if suit not in SUITS:
        raise ValueError(f"Unknown suit: {suit}")
    if (rank in ("SJ", "BJ")) != (suit == "JOKER"):
        raise ValueError(f"Joker rank/suit mismatch: {rank}/{suit}")
