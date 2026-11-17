"""Mirror of the Kotlin decoder: run the exported ONNX on validation images with letterboxing and
measure recall / precision at IoU 0.5 against the ground-truth boxes."""
import glob, os, sys
import numpy as np
import onnxruntime as ort
from PIL import Image

onnx_path = sys.argv[1]; size = int(sys.argv[2]); root = sys.argv[3]
sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
inp = sess.get_inputs()[0]
print("onnx input:", inp.name, inp.shape, " outputs:", [(o.name, o.shape) for o in sess.get_outputs()])

def letterbox(img, s):
    w, h = img.size; r = min(s / w, s / h)
    nw, nh = int(round(w * r)), int(round(h * r))
    canvas = Image.new("RGB", (s, s), (114, 114, 114))
    px, py = (s - nw) // 2, (s - nh) // 2
    canvas.paste(img.resize((nw, nh), Image.BILINEAR), (px, py))
    return canvas, r, px, py

def iou(a, b):
    x1, y1 = max(a[0], b[0]), max(a[1], b[1]); x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0, x2 - x1) * max(0, y2 - y1)
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / ua if ua > 0 else 0

def nms(boxes, scores, thr=0.5):
    idx = np.argsort(-scores); keep = []
    while len(idx):
        i = idx[0]; keep.append(i)
        idx = np.array([j for j in idx[1:] if iou(boxes[i], boxes[j]) < thr])
    return keep

def decode(out, conf=0.30):
    o = out[0]
    if o.ndim == 3: o = o[0]
    if o.shape[-1] == 6:            # end-to-end: x1,y1,x2,y2,score,class
        return [(r[:4].tolist(), float(r[4])) for r in o if r[4] >= conf]
    if o.shape[0] < o.shape[1]:     # raw: (4+nc, anchors) -> transpose
        o = o.T
    boxes = o[:, :4]; sc = o[:, 4:].max(1)
    m = sc >= conf; boxes, sc = boxes[m], sc[m]
    xyxy = np.stack([boxes[:, 0] - boxes[:, 2] / 2, boxes[:, 1] - boxes[:, 3] / 2, boxes[:, 0] + boxes[:, 2] / 2, boxes[:, 1] + boxes[:, 3] / 2], 1)
    keep = nms(xyxy, sc)
    return [(xyxy[i].tolist(), float(sc[i])) for i in keep]

imgs = sorted(glob.glob(os.path.join(root, "valid", "images", "*")))
tp = fn = fp = 0; npred = 0
for p in imgs:
    im = Image.open(p).convert("RGB"); W, H = im.size
    lb, r, px, py = letterbox(im, size)
    x = np.asarray(lb, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0
    preds = decode(sess.run(None, {inp.name: x}))
    dets = [(((b[0] - px) / r, (b[1] - py) / r, (b[2] - px) / r, (b[3] - py) / r), s) for b, s in preds]
    npred += len(dets)
    lab = os.path.join(root, "valid", "labels", os.path.splitext(os.path.basename(p))[0] + ".txt")
    gts = []
    if os.path.exists(lab):
        for ln in open(lab).read().strip().splitlines():
            c, cx, cy, w, h = map(float, ln.split()[:5])
            gts.append(((cx - w / 2) * W, (cy - h / 2) * H, (cx + w / 2) * W, (cy + h / 2) * H))
    used = set()
    for g in gts:
        best, bj = 0, -1
        for j, (b, s) in enumerate(dets):
            if j in used: continue
            v = iou(g, b)
            if v > best: best, bj = v, j
        if best >= 0.5: tp += 1; used.add(bj)
        else: fn += 1
    fp += len(dets) - len(used)
rec = tp / max(1, tp + fn); prec = tp / max(1, tp + fp)
print(f"size={size} images={len(imgs)} gt={tp+fn} detections={npred} TP={tp} FN={fn} FP={fp} recall@0.5={rec:.3f} precision@0.5={prec:.3f}")
