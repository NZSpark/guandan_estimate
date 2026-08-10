"""Small desktop annotator for visible playing-card corners in testcases/."""

from __future__ import annotations

import argparse
import json
import math
import tkinter as tk
from pathlib import Path
from tkinter import messagebox, ttk

from PIL import Image, ImageTk

from labels import RANKS, SUITS, validate_label


class Annotator:
    def __init__(self, root: tk.Tk, images_dir: Path, annotations_path: Path) -> None:
        self.root = root
        self.images_dir = images_dir.resolve()
        self.annotations_path = annotations_path.resolve()
        self.files = sorted(p for p in self.images_dir.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
        if not self.files:
            raise RuntimeError(f"No images found in {self.images_dir}")
        self.data = self._load()
        self.index = 0
        self.image: Image.Image | None = None
        self.preview: ImageTk.PhotoImage | None = None
        self.scale = 1.0
        self.offset_x = 0
        self.offset_y = 0
        self.drag_start: tuple[int, int] | None = None
        self.drag_rect: int | None = None
        self.drag_mode: str | None = None
        self.drag_handle: int | None = None
        self.drag_original: list[list[float]] | None = None
        self.drag_center: tuple[float, float] | None = None
        self.drag_start_angle: float | None = None
        self.selected: int | None = None

        root.title("掼蛋牌角标注工具")
        root.geometry("1400x900")
        toolbar = ttk.Frame(root, padding=6)
        toolbar.pack(fill=tk.X)
        ttk.Button(toolbar, text="上一张", command=self.previous).pack(side=tk.LEFT)
        ttk.Button(toolbar, text="下一张", command=self.next).pack(side=tk.LEFT, padx=4)
        ttk.Button(toolbar, text="保存", command=self.save).pack(side=tk.LEFT, padx=12)
        ttk.Button(toolbar, text="删除选中框", command=self.delete_selected).pack(side=tk.LEFT)
        ttk.Button(toolbar, text="自动标注当前", command=self.auto_current).pack(side=tk.LEFT, padx=(12, 3))
        ttk.Button(toolbar, text="自动标注全部未标注", command=self.auto_all).pack(side=tk.LEFT)
        self.angle = tk.DoubleVar(value=0.0)
        ttk.Label(toolbar, text="倾斜角°").pack(side=tk.LEFT, padx=(16, 4))
        ttk.Spinbox(toolbar, from_=-180, to=180, increment=1, textvariable=self.angle, width=6).pack(side=tk.LEFT)
        ttk.Button(toolbar, text="应用角度", command=self.apply_angle).pack(side=tk.LEFT, padx=4)
        ttk.Label(toolbar, text="点数").pack(side=tk.LEFT, padx=(20, 4))
        self.rank = tk.StringVar(value=RANKS[0])
        ttk.Combobox(toolbar, textvariable=self.rank, values=RANKS, width=5, state="readonly").pack(side=tk.LEFT)
        ttk.Label(toolbar, text="花色").pack(side=tk.LEFT, padx=(12, 4))
        self.suit = tk.StringVar(value=SUITS[0])
        ttk.Combobox(toolbar, textvariable=self.suit, values=SUITS, width=10, state="readonly").pack(side=tk.LEFT)
        ttk.Button(toolbar, text="应用标签/确认", command=self.apply_label).pack(side=tk.LEFT, padx=4)
        self.status = ttk.Label(toolbar)
        self.status.pack(side=tk.RIGHT)

        content = ttk.Frame(root)
        content.pack(fill=tk.BOTH, expand=True)
        self.canvas = tk.Canvas(content, bg="#202020", highlightthickness=0)
        self.canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        side = ttk.Frame(content, padding=6)
        side.pack(side=tk.RIGHT, fill=tk.Y)
        ttk.Label(side, text="标注框（单击选择）").pack(anchor=tk.W)
        self.box_list = tk.Listbox(side, width=25, exportselection=False)
        self.box_list.pack(fill=tk.Y, expand=True)
        self.box_list.bind("<<ListboxSelect>>", self.on_list_select)
        self.canvas.bind("<ButtonPress-1>", self.on_press)
        self.canvas.bind("<B1-Motion>", self.on_drag)
        self.canvas.bind("<ButtonRelease-1>", self.on_release)
        self.canvas.bind("<Button-3>", self.on_select)
        self.canvas.bind("<Configure>", lambda _: self.render())
        root.bind("<Delete>", lambda _: self.delete_selected())
        root.protocol("WM_DELETE_WINDOW", self.close)
        self.open_current()

    def _load(self) -> dict:
        if not self.annotations_path.exists():
            return {"version": 1, "images": {}}
        data = json.loads(self.annotations_path.read_text(encoding="utf-8"))
        if data.get("version") != 1 or not isinstance(data.get("images"), dict):
            raise ValueError("Unsupported annotations.json format")
        return data

    @property
    def name(self) -> str:
        return self.files[self.index].name

    @property
    def boxes(self) -> list[dict]:
        return self.data["images"].setdefault(self.name, [])

    def open_current(self) -> None:
        self.image = Image.open(self.files[self.index]).convert("RGB")
        self.selected = None
        self.render()

    def render(self) -> None:
        if self.image is None or self.canvas.winfo_width() < 10:
            return
        cw, ch = self.canvas.winfo_width(), self.canvas.winfo_height()
        self.scale = min(cw / self.image.width, ch / self.image.height)
        shown = self.image.resize((max(1, int(self.image.width * self.scale)), max(1, int(self.image.height * self.scale))))
        self.preview = ImageTk.PhotoImage(shown)
        self.offset_x = (cw - shown.width) // 2
        self.offset_y = (ch - shown.height) // 2
        self.canvas.delete("all")
        self.canvas.create_image(self.offset_x, self.offset_y, image=self.preview, anchor=tk.NW)
        for i, item in enumerate(self.boxes):
            quad = self.item_quad(item)
            points = [coordinate for point in quad for coordinate in self.to_canvas(*point)]
            color = "#ffd54f" if i == self.selected else "#ff7043" if item.get("needs_review") else "#00e676"
            self.canvas.create_polygon(*points, outline=color, fill="", width=3, tags=(f"box-{i}",))
            x1, y1 = self.to_canvas(*quad[0])
            self.canvas.create_text(x1 + 3, y1 + 3, text=f'{i + 1}. {item["rank"]}-{item["suit"]}', fill=color, anchor=tk.NW)
            if i == self.selected:
                for point in quad:
                    hx, hy = self.to_canvas(*point)
                    self.canvas.create_oval(hx - 7, hy - 7, hx + 7, hy + 7, fill="#ffd54f", outline="#202020", width=2)
                rx, ry = self.rotation_handle(quad)
                top_mid_x = (quad[0][0] + quad[1][0]) / 2
                top_mid_y = (quad[0][1] + quad[1][1]) / 2
                tx, ty = self.to_canvas(top_mid_x, top_mid_y)
                rhx, rhy = self.to_canvas(rx, ry)
                self.canvas.create_line(tx, ty, rhx, rhy, fill="#80d8ff", width=2)
                self.canvas.create_oval(rhx - 8, rhy - 8, rhx + 8, rhy + 8,
                                        fill="#40c4ff", outline="#202020", width=2)
        self.refresh_list()
        self.status.config(text=f"{self.index + 1}/{len(self.files)}  {self.name}  已标注 {len(self.boxes)} 张")

    def to_canvas(self, x: float, y: float) -> tuple[int, int]:
        return int(self.offset_x + x * self.scale), int(self.offset_y + y * self.scale)

    def to_image(self, x: int, y: int) -> tuple[int, int]:
        assert self.image is not None
        ix = int((x - self.offset_x) / self.scale)
        iy = int((y - self.offset_y) / self.scale)
        return max(0, min(self.image.width, ix)), max(0, min(self.image.height, iy))

    def to_image_unclamped(self, x: int, y: int) -> tuple[float, float]:
        """Convert canvas coordinates without clipping external edit handles."""
        return (x - self.offset_x) / self.scale, (y - self.offset_y) / self.scale

    def on_press(self, event: tk.Event) -> None:
        ix, iy = self.to_image(event.x, event.y)
        raw_ix, raw_iy = self.to_image_unclamped(event.x, event.y)
        # A left click also selects the topmost box, so editing does not require
        # a preceding right click or list selection.
        hit = next((i for i, item in reversed(list(enumerate(self.boxes)))
                    if self.point_in_polygon(ix, iy, self.item_quad(item))), None)
        if hit is not None and hit != self.selected:
            self.selected = hit
            self.sync_angle()
            self.rank.set(self.boxes[hit]["rank"])
            self.suit.set(self.boxes[hit]["suit"])
        if self.selected is not None:
            quad = self.item_quad(self.boxes[self.selected])
            rotate_point = self.rotation_handle(quad)
            rotate_canvas = self.to_canvas(*rotate_point)
            if math.dist((event.x, event.y), rotate_canvas) <= 16:
                cx = sum(point[0] for point in quad) / 4
                cy = sum(point[1] for point in quad) / 4
                self.drag_mode = "rotate"
                self.drag_center = (cx, cy)
                self.drag_start = (raw_ix, raw_iy)
                self.drag_start_angle = math.atan2(raw_iy - cy, raw_ix - cx)
                self.drag_original = [point.copy() for point in quad]
                return
            distances = [math.dist((event.x, event.y), self.to_canvas(*point)) for point in quad]
            nearest = min(range(4), key=distances.__getitem__)
            if distances[nearest] <= 16:
                self.drag_mode = "scale"
                self.drag_handle = nearest
                self.drag_start = (ix, iy)
                self.drag_original = [point.copy() for point in quad]
                return
            if self.point_in_polygon(ix, iy, quad):
                self.drag_mode = "move"
                self.drag_start = (ix, iy)
                self.drag_original = [point.copy() for point in quad]
                return
        self.drag_mode = "new"
        self.drag_start = (event.x, event.y)
        self.drag_rect = self.canvas.create_rectangle(event.x, event.y, event.x, event.y, outline="#ff5252", width=3)

    def on_drag(self, event: tk.Event) -> None:
        if self.drag_mode == "new" and self.drag_start and self.drag_rect:
            self.canvas.coords(self.drag_rect, self.drag_start[0], self.drag_start[1], event.x, event.y)
            return
        if self.selected is None or self.drag_start is None or self.drag_original is None:
            return
        ix, iy = (self.to_image_unclamped(event.x, event.y)
                  if self.drag_mode == "rotate" else self.to_image(event.x, event.y))
        if self.drag_mode == "scale" and self.drag_handle is not None:
            self.boxes[self.selected]["quad"] = self.scaled_quad(
                self.drag_original, self.drag_handle, ix, iy
            )
        elif self.drag_mode == "rotate" and self.drag_center is not None and self.drag_start_angle is not None:
            cx, cy = self.drag_center
            delta = math.atan2(iy - cy, ix - cx) - self.drag_start_angle
            cos_a, sin_a = math.cos(delta), math.sin(delta)
            self.boxes[self.selected]["quad"] = [
                [cx + (point[0] - cx) * cos_a - (point[1] - cy) * sin_a,
                 cy + (point[0] - cx) * sin_a + (point[1] - cy) * cos_a]
                for point in self.drag_original
            ]
        elif self.drag_mode == "move":
            dx, dy = ix - self.drag_start[0], iy - self.drag_start[1]
            assert self.image is not None
            self.boxes[self.selected]["quad"] = [
                [max(0, min(self.image.width, point[0] + dx)), max(0, min(self.image.height, point[1] + dy))]
                for point in self.drag_original
            ]
        self.boxes[self.selected].pop("bbox", None)
        self.render()

    def on_release(self, event: tk.Event) -> None:
        if self.drag_mode in {"scale", "rotate", "move"}:
            self.drag_mode = None
            self.drag_handle = None
            self.drag_start = None
            self.drag_original = None
            self.drag_center = None
            self.drag_start_angle = None
            self.sync_angle()
            self.render()
            return
        if not self.drag_start:
            return
        x1, y1 = self.to_image(*self.drag_start)
        x2, y2 = self.to_image(event.x, event.y)
        self.drag_start = None
        self.drag_mode = None
        if abs(x2 - x1) < 12 or abs(y2 - y1) < 20:
            self.render(); return
        rank, suit = self.rank.get(), self.suit.get()
        try:
            validate_label(rank, suit)
        except ValueError as error:
            messagebox.showerror("标签错误", str(error)); self.render(); return
        left, top = min(x1, x2), min(y1, y2)
        right, bottom = max(x1, x2), max(y1, y2)
        self.boxes.append({"quad": [[left, top], [right, top], [right, bottom], [left, bottom]], "rank": rank, "suit": suit})
        self.selected = len(self.boxes) - 1
        self.angle.set(0.0)
        self.render()

    def on_select(self, event: tk.Event) -> None:
        ix, iy = self.to_image(event.x, event.y)
        self.selected = next((i for i, item in reversed(list(enumerate(self.boxes)))
                              if self.point_in_polygon(ix, iy, self.item_quad(item))), None)
        self.sync_angle()
        if self.selected is not None:
            self.rank.set(self.boxes[self.selected]["rank"])
            self.suit.set(self.boxes[self.selected]["suit"])
        self.render()

    def on_list_select(self, _: tk.Event) -> None:
        selection = self.box_list.curselection()
        if selection:
            self.selected = int(selection[0])
            self.sync_angle()
            item = self.boxes[self.selected]
            self.rank.set(item["rank"])
            self.suit.set(item["suit"])
            self.render()

    def apply_label(self) -> None:
        if self.selected is None:
            messagebox.showinfo("修改标签", "请先选择一个标注框。")
            return
        try:
            validate_label(self.rank.get(), self.suit.get())
        except ValueError as error:
            messagebox.showerror("标签错误", str(error)); return
        item = self.boxes[self.selected]
        item["rank"], item["suit"] = self.rank.get(), self.suit.get()
        item["needs_review"] = False
        item["source"] = "manual"
        self.render()

    def auto_current(self) -> None:
        if self.boxes and not messagebox.askyesno("自动标注", "当前图片已有标注。是否保留已有标注，只追加不重叠的自动结果？"):
            return
        self._auto_one(self.index)
        self.render()

    def auto_all(self) -> None:
        count = 0
        for index, path in enumerate(self.files):
            if self.data["images"].get(path.name):
                continue
            self._auto_one(index)
            count += 1
            self.status.config(text=f"正在自动标注 {count} 张…")
            self.root.update_idletasks()
        self.save()
        self.open_current()
        messagebox.showinfo("自动标注", f"已为 {count} 张原本无标注的图片生成初始结果，请逐张人工核对。")

    def _auto_one(self, index: int) -> None:
        try:
            from auto_annotate import auto_annotate_image
        except ModuleNotFoundError as error:
            messagebox.showerror("缺少依赖", f"{error}\n请先运行：python -m pip install -r requirements.txt")
            return
        path = self.files[index]
        generated = auto_annotate_image(path, Path(__file__).resolve().parent.parent / "card_picture" / "card_png", self.rank.get())
        existing = self.data["images"].setdefault(path.name, [])
        for item in generated:
            center = [sum(point[axis] for point in item["quad"]) / 4 for axis in (0, 1)]
            if any(self.point_in_polygon(center[0], center[1], self.item_quad(old)) for old in existing):
                continue
            existing.append(item)
        if index == self.index and existing:
            self.selected = len(existing) - 1

    def delete_selected(self) -> None:
        if self.selected is None:
            messagebox.showinfo("删除标注", "请先在右侧列表单击标注，或在图片中右键选择。")
            return
        self.boxes.pop(self.selected)
        self.selected = min(self.selected, len(self.boxes) - 1) if self.boxes else None
        self.sync_angle()
        self.render()

    def apply_angle(self) -> None:
        if self.selected is None:
            messagebox.showinfo("旋转标注", "请先选择一个标注框。")
            return
        quad = self.item_quad(self.boxes[self.selected])
        cx = sum(p[0] for p in quad) / 4
        cy = sum(p[1] for p in quad) / 4
        width = math.dist(quad[0], quad[1])
        height = math.dist(quad[1], quad[2])
        radians = math.radians(self.angle.get())
        ux, uy = math.cos(radians), math.sin(radians)
        vx, vy = -uy, ux
        half_w, half_h = width / 2, height / 2
        self.boxes[self.selected]["quad"] = [
            [round(cx - ux * half_w - vx * half_h), round(cy - uy * half_w - vy * half_h)],
            [round(cx + ux * half_w - vx * half_h), round(cy + uy * half_w - vy * half_h)],
            [round(cx + ux * half_w + vx * half_h), round(cy + uy * half_w + vy * half_h)],
            [round(cx - ux * half_w + vx * half_h), round(cy - uy * half_w + vy * half_h)],
        ]
        self.boxes[self.selected].pop("bbox", None)
        self.render()

    def item_quad(self, item: dict) -> list[list[float]]:
        if "quad" in item:
            return item["quad"]
        x, y, w, h = item["bbox"]
        return [[x, y], [x + w, y], [x + w, y + h], [x, y + h]]

    def rotation_handle(self, quad: list[list[float]]) -> tuple[float, float]:
        """Return a point above the top edge, at a constant screen distance."""
        top_x = (quad[0][0] + quad[1][0]) / 2
        top_y = (quad[0][1] + quad[1][1]) / 2
        edge_x = quad[1][0] - quad[0][0]
        edge_y = quad[1][1] - quad[0][1]
        length = math.hypot(edge_x, edge_y) or 1.0
        distance = 34 / max(self.scale, .01)
        # For clockwise point order, the outward normal of the top edge is left.
        return top_x + edge_y / length * distance, top_y - edge_x / length * distance

    @staticmethod
    def scaled_quad(quad: list[list[float]], handle: int, x: float, y: float) -> list[list[float]]:
        """Resize an oriented rectangle from one corner, keeping the opposite fixed."""
        opposite = (handle + 2) % 4
        ox, oy = quad[opposite]
        ux = quad[(opposite + 1) % 4][0] - ox
        uy = quad[(opposite + 1) % 4][1] - oy
        vx = quad[(opposite - 1) % 4][0] - ox
        vy = quad[(opposite - 1) % 4][1] - oy
        u_len = math.hypot(ux, uy) or 1.0
        v_len = math.hypot(vx, vy) or 1.0
        ux, uy = ux / u_len, uy / u_len
        vx, vy = vx / v_len, vy / v_len
        dx, dy = x - ox, y - oy
        width = max(8.0, dx * ux + dy * uy)
        height = max(8.0, dx * vx + dy * vy)
        corner_u = [ox + ux * width, oy + uy * width]
        corner_v = [ox + vx * height, oy + vy * height]
        dragged = [corner_u[0] + vx * height, corner_u[1] + vy * height]
        result = [None, None, None, None]
        result[opposite] = [ox, oy]
        result[(opposite + 1) % 4] = corner_u
        result[(opposite - 1) % 4] = corner_v
        result[handle] = dragged
        return result

    def sync_angle(self) -> None:
        if self.selected is None:
            self.angle.set(0.0)
            return
        quad = self.item_quad(self.boxes[self.selected])
        self.angle.set(round(math.degrees(math.atan2(quad[1][1] - quad[0][1], quad[1][0] - quad[0][0])), 1))

    def refresh_list(self) -> None:
        wanted = self.selected
        self.box_list.delete(0, tk.END)
        for i, item in enumerate(self.boxes):
            quad = self.item_quad(item)
            angle = math.degrees(math.atan2(quad[1][1] - quad[0][1], quad[1][0] - quad[0][0]))
            review = "⚠" if item.get("needs_review") else "✓"
            confidence = f' {item.get("confidence", 1.0):.0%}' if item.get("source") == "auto" else ""
            self.box_list.insert(tk.END, f'{review} {i + 1:02d}  {item["rank"]}-{item["suit"]}  {angle:+.0f}°{confidence}')
        if wanted is not None and wanted < len(self.boxes):
            self.box_list.selection_set(wanted)
            self.box_list.see(wanted)

    @staticmethod
    def point_in_polygon(x: float, y: float, polygon: list[list[float]]) -> bool:
        inside = False
        j = len(polygon) - 1
        for i, (xi, yi) in enumerate(polygon):
            xj, yj = polygon[j]
            if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / ((yj - yi) or 1e-9) + xi:
                inside = not inside
            j = i
        return inside

    def save(self) -> None:
        self.annotations_path.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")
        self.status.config(text=f"已保存：{self.annotations_path.name}")

    def previous(self) -> None:
        self.save(); self.index = (self.index - 1) % len(self.files); self.open_current()

    def next(self) -> None:
        self.save(); self.index = (self.index + 1) % len(self.files); self.open_current()

    def close(self) -> None:
        self.save(); self.root.destroy()


def main() -> None:
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--images", type=Path, default=here.parent / "testcases")
    parser.add_argument("--annotations", type=Path, default=here / "annotations.json")
    args = parser.parse_args()
    root = tk.Tk()
    Annotator(root, args.images, args.annotations)
    root.mainloop()


if __name__ == "__main__":
    main()
