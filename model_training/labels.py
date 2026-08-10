RANKS = ["2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "SJ", "BJ"]
SUITS = ["CLUB", "DIAMOND", "HEART", "SPADE", "JOKER"]


def validate_label(rank: str, suit: str) -> None:
    if rank not in RANKS:
        raise ValueError(f"Unknown rank: {rank}")
    if suit not in SUITS:
        raise ValueError(f"Unknown suit: {suit}")
    if (rank in ("SJ", "BJ")) != (suit == "JOKER"):
        raise ValueError(f"Joker rank/suit mismatch: {rank}/{suit}")
