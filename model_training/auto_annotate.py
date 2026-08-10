"""Offline heuristic pre-annotation of visible card corners.

This bootstrapper deliberately favors recall. Every result is marked for human review and must be
confirmed in annotate.py before it becomes training truth.
"""

from __future__ import annotations

import math
from pathlib import Path

import cv2
import numpy as np

from labels import RANKS


def _normalize_mask(mask: np.ndarray, width: int = 40, height: int = 64) -> np.ndarray:
    points = cv2.findNonZero(mask)
    if points is None:
        return np.zeros((height, width), np.uint8)
    x, y, w, h = cv2.boundingRect(points)
    crop = mask[y:y + h, x:x + w]
    scale = min((width - 4) / max(1, w), (height - 4) / max(1, h))
    resized = cv2.resize(crop, (max(1, round(w * scale)), max(1, round(h * scale))), interpolation=cv2.INTER_NEAREST)
    output = np.zeros((height, width), np.uint8)
    oy = (height - resized.shape[0]) // 2
    ox = (width - resized.shape[1]) // 2
    output[oy:oy + resized.shape[0], ox:ox + resized.shape[1]] = resized
    return output


def _foreground(image: np.ndarray) -> np.ndarray:
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    # Includes black, red, green and gold glyphs while rejecting the off-white card stock.
    return (((hsv[:, :, 1] > 65) & (hsv[:, :, 2] > 45)) | (gray < 120)).astype(np.uint8) * 255


def _rank_templates(asset_dir: Path) -> dict[str, np.ndarray]:
    templates = {}
    for rank in RANKS[:13]:
        name = f"Spade{rank}.png"
        image = cv2.imread(str(asset_dir / name))
        if image is None:
            continue
        h, w = image.shape[:2]
        roi = image[int(h * .025):int(h * .225), int(w * .025):int(w * .245)]
        templates[rank] = _normalize_mask(_foreground(roi))
    return templates


def _classify_rank(mask: np.ndarray, templates: dict[str, np.ndarray], fallback: str) -> tuple[str, float]:
    normalized = _normalize_mask(mask)
    scores = [(rank, 1.0 - np.mean(cv2.bitwise_xor(normalized, template) > 0)) for rank, template in templates.items()]
    if not scores:
        return fallback, 0.0
    scores.sort(key=lambda pair: pair[1], reverse=True)
    best = scores[0]
    margin = best[1] - (scores[1][1] if len(scores) > 1 else 0.0)
    return best[0], float(max(0.0, min(1.0, best[1] * .75 + margin * .25)))


def _classify_suit(image: np.ndarray, rank_rect: tuple[int, int, int, int], angle: float) -> tuple[str, float]:
    x, y, w, h = rank_rect
    # The pip is immediately below the rank. A generous crop tolerates rotation and two-digit 10s.
    x1, x2 = max(0, x - w // 2), min(image.shape[1], x + w * 2)
    y1, y2 = max(0, y + h // 2), min(image.shape[0], y + h * 3)
    roi = image[y1:y2, x1:x2]
    if roi.size == 0:
        return "SPADE", 0.0
    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
    saturated = hsv[:, :, 1] > 70
    hues = hsv[:, :, 0][saturated]
    if hues.size:
        hue = float(np.median(hues))
        if hue < 12 or hue > 172:
            return "HEART", min(1.0, hues.size / 180.0)
        if 12 <= hue < 38:
            return "DIAMOND", min(1.0, hues.size / 180.0)
        if 38 <= hue < 95:
            return "CLUB", min(1.0, hues.size / 180.0)
    return "SPADE", .45


def _quad_from_rank(rect: tuple[int, int, int, int], angle: float, image_width: int, image_height: int) -> list[list[int]]:
    x, y, w, h = rect
    radians = math.radians(angle)
    ux, uy = math.cos(radians), math.sin(radians)
    vx, vy = -uy, ux
    box_w = max(w * 1.65, h * .72)
    box_h = h * 2.65
    cx = x + w / 2 + vx * h * .72
    cy = y + h / 2 + vy * h * .72
    result = []
    for along, down in ((-.5, -.5), (.5, -.5), (.5, .5), (-.5, .5)):
        px = cx + ux * box_w * along + vx * box_h * down
        py = cy + uy * box_w * along + vy * box_h * down
        result.append([round(max(0, min(image_width - 1, px))), round(max(0, min(image_height - 1, py)))])
    return result


def auto_annotate_image(image_path: Path, asset_dir: Path, fallback_rank: str = "2", expected_count: int = 27) -> list[dict]:
    image = cv2.imread(str(image_path))
    if image is None:
        raise ValueError(f"Cannot read {image_path}")
    original_height, original_width = image.shape[:2]
    scale = min(1.0, 1800.0 / max(original_width, original_height))
    working = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA) if scale < 1 else image
    mask = _foreground(working)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    ih, iw = working.shape[:2]
    candidates = []
    for contour in contours:
        x, y, w, h = cv2.boundingRect(contour)
        if not (ih * .018 <= h <= ih * .14 and 3 <= w <= iw * .11):
            continue
        if cv2.contourArea(contour) < w * h * .08:
            continue
        pad = round(max(w, h) * .45)
        lx1, ly1, lx2, ly2 = max(0, x - pad), max(0, y - pad), min(iw, x + w + pad), min(ih, y + h + pad)
        local = working[ly1:ly2, lx1:lx2]
        hsv = cv2.cvtColor(local, cv2.COLOR_BGR2HSV)
        white_fraction = np.mean((hsv[:, :, 1] < 85) & (hsv[:, :, 2] > 125))
        if white_fraction < .42:
            continue
        candidates.append([x, y, w, h, contour])

    # Merge the adjacent "1" and "0" of rank 10 and fragments of serif glyphs.
    merged = []
    for candidate in sorted(candidates, key=lambda c: (c[1], c[0])):
        x, y, w, h, contour = candidate
        target = next((item for item in merged if abs((item[1] + item[3] / 2) - (y + h / 2)) < max(item[3], h) * .35
                       and 0 <= x - (item[0] + item[2]) < max(item[3], h) * .55), None)
        if target is None:
            merged.append(candidate.copy())
        else:
            left, top = min(target[0], x), min(target[1], y)
            right, bottom = max(target[0] + target[2], x + w), max(target[1] + target[3], y + h)
            target[:4] = [left, top, right - left, bottom - top]
            target[4] = np.concatenate((target[4], contour))

    templates = _rank_templates(asset_dir)
    output = []
    centers = []
    for x, y, w, h, contour in merged:
        center = (x + w / 2, y + h / 2)
        if any(math.dist(center, previous) < ih * .03 for previous in centers):
            continue
        points = contour.reshape(-1, 2).astype(np.float32)
        if len(points) >= 5:
            covariance = np.cov(points.T)
            vector = np.linalg.eigh(covariance)[1][:, -1]
            vertical_angle = math.degrees(math.atan2(vector[1], vector[0]))
            angle = ((vertical_angle - 90 + 180) % 180) - 90
        else:
            angle = 0.0
        glyph = mask[y:y + h, x:x + w]
        rank, rank_confidence = _classify_rank(glyph, templates, fallback_rank)
        suit, suit_confidence = _classify_suit(working, (x, y, w, h), angle)
        factor = 1.0 / scale
        quad = _quad_from_rank((x, y, w, h), angle, iw, ih)
        quad = [[round(px * factor), round(py * factor)] for px, py in quad]
        output.append({
            "quad": quad,
            "rank": rank,
            "suit": "JOKER" if rank in ("SJ", "BJ") else suit,
            "source": "auto",
            "needs_review": True,
            "confidence": round(rank_confidence * .65 + suit_confidence * .35, 3),
        })
        centers.append(center)
    strongest = sorted(output, key=lambda item: item["confidence"], reverse=True)[:expected_count]
    return sorted(strongest, key=lambda item: (min(p[1] for p in item["quad"]), min(p[0] for p in item["quad"])))
