# -*- coding: utf-8 -*-
"""
olePageno.py
==============================================================================
[목적]
    MS-Word 문서(doc, docx)에 삽입된 OLE 개체가 실제로 몇 번째 "페이지"에
    위치하는지 추정하여 반환하는 서비스 모듈.
==============================================================================
"""

import os
import re
import subprocess
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ET
import time
import difflib
import hashlib
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from io import BytesIO
from typing import List, Optional, Dict, Tuple

import requests
from PIL import Image
import imagehash
import fitz  # PyMuPDF


# -----------------------------------------------------------------------
# 환경설정
# -----------------------------------------------------------------------
LIBREOFFICE_HOME = r"C:\Program Files\LibreOffice"
SOFFICE_EXE = os.path.join(LIBREOFFICE_HOME, "program", "soffice.exe")
TIKA_BASE_URL = "http://172.21.0.219:9998/tika"
CONVERT_TIMEOUT_SEC = 120

HASH_MAX_BITS = 64

TEXT_COST_SCALE = 100.0
IMAGE_WEIGHT = 0.05
IMAGE_WEIGHT_NO_TEXT = 0.3
POSITION_TIEBREAK_WEIGHT = 0.01

TEXT_COST_WARN_THRESHOLD = TEXT_COST_SCALE * 0.5
HASH_MATCH_THRESHOLD = 30

MAX_ANCHOR_LEN = 400
TEXT_CHUNK_LEN = 40
PAGE_TEXT_COMPARE_LIMIT = 5000

NS = {
    "w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
    "v": "urn:schemas-microsoft-com:vml",
    "o": "urn:schemas-microsoft-com:office:office",
    "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
    "rel": "http://schemas.openxmlformats.org/package/2006/relationships",
}


@dataclass
class OleItem:
    ole_name: str
    preview_name: str
    order_hint: int
    preview_bytes: bytes = field(repr=False)
    preview_ext: str = ""
    anchor_text: str = ""
    phash: Optional[imagehash.ImageHash] = None
    matched_page: Optional[int] = None
    raw_path: str = ""
    file_hash: Optional[str] = None


class OlePageLocator:
    def __init__(self, threshold=HASH_MATCH_THRESHOLD, position_weight=POSITION_TIEBREAK_WEIGHT):
        self.threshold = threshold
        self.position_weight = position_weight

    def get_ole_page_numbers(self, input_file: str) -> List[Dict]:
        """
        분석할 파일 경로를 받아 OLE 개체별 매칭된 페이지 번호 리스트를 반환한다.
        반환 형식: [{"ole_name": "...", "page": 1, "hash": "..."}, ...]
        """
        if not os.path.exists(input_file):
            raise FileNotFoundError(f"입력 파일을 찾을 수 없습니다: {input_file}")
        
        work_dir = os.path.dirname(input_file)
        
        with tempfile.TemporaryDirectory(prefix="ole_fp_") as tmp_dir:
            ole_profile_dir = os.path.join(tmp_dir, "lo_profile_ole")
            pdf_profile_dir = os.path.join(tmp_dir, "lo_profile_pdf")
        
            with ThreadPoolExecutor(max_workers=3) as executor:
                ole_future = executor.submit(self._prepare_ole_items, input_file, tmp_dir, ole_profile_dir)
                pdf_future = executor.submit(self.convert_to_pdf_with_libreoffice, input_file, work_dir, pdf_profile_dir)
                
                ole_items = ole_future.result()
                if not ole_items:
                    return []
        
                pdf_path = pdf_future.result()
        
            page_images, page_texts, page_count = self.extract_pdf_page_data(pdf_path)
            self.match_ole_items_to_pages(ole_items, page_images, page_texts)
        
        return [{"ole_name": item.ole_name, "page": item.matched_page, "hash": item.file_hash} for item in sorted(ole_items, key=lambda i: i.order_hint)]

    def _run_soffice(self, cmd: List[str], profile_dir: Optional[str] = None):
        exe_path = SOFFICE_EXE
        if not os.path.exists(exe_path):
            try:
                result = subprocess.run(["where", "soffice"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=5)
                candidates = [line.strip() for line in result.stdout.splitlines() if line.strip()]
                if candidates: exe_path = candidates[0]
            except Exception: pass

        if exe_path.lower().endswith('.com'):
            possible_exe = exe_path[:-4] + '.exe'
            if os.path.exists(possible_exe): exe_path = possible_exe

        if not os.path.exists(exe_path):
            raise FileNotFoundError(f"LibreOffice 실행파일을 찾을 수 없습니다: {SOFFICE_EXE}")

        if cmd and os.path.basename(cmd[0]).lower().endswith("soffice.exe"):
            cmd = [exe_path] + cmd[1:]

        if profile_dir:
            os.makedirs(profile_dir, exist_ok=True)
            profile_uri = "file:///" + os.path.abspath(profile_dir).replace("\\", "/").lstrip("/")
            cmd = [cmd[0], f"-env:UserInstallation={profile_uri}"] + cmd[1:]

        soffice_dir = os.path.dirname(exe_path)
        proc = subprocess.run(cmd, cwd=soffice_dir, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=CONVERT_TIMEOUT_SEC)
        if proc.returncode != 0:
            raise RuntimeError(f"LibreOffice 실행 실패 (returncode={proc.returncode})\n출력: {proc.stdout.decode(errors='ignore')}")

    def ensure_docx(self, input_path: str, tmp_dir: str, profile_dir: Optional[str] = None) -> str:
        ext = os.path.splitext(input_path)[1].lower()
        if ext == ".docx": return input_path
        if ext != ".doc": raise ValueError(f"지원하지 않는 확장자입니다: {ext}")

        cmd = [SOFFICE_EXE, "--headless", "--convert-to", "docx", "--outdir", tmp_dir, input_path]
        self._run_soffice(cmd, profile_dir=profile_dir)
        base_name = os.path.splitext(os.path.basename(input_path))[0]
        converted_path = os.path.join(tmp_dir, base_name + ".docx")
        if not os.path.exists(converted_path):
            raise RuntimeError(f"doc -> docx 변환 결과 파일을 찾을 수 없습니다: {converted_path}")
        return converted_path

    def _normalize_text(self, s: str) -> str:
        return re.sub(r"\s+", " ", s or "").strip()

    def _build_ole_anchor_texts(self, doc_root) -> Dict[int, str]:
        w_ns = NS["w"]
        p_tag, t_tag, obj_tag = f"{{{w_ns}}}p", f"{{{w_ns}}}t", f"{{{w_ns}}}object"
        all_paragraphs = list(doc_root.iter(p_tag))
        para_index = {id(p): idx for idx, p in enumerate(all_paragraphs)}
        para_texts = [self._normalize_text("".join([t.text for t in p.iter(t_tag) if t.text])) for p in all_paragraphs]
        
        parent_map = {}
        for parent in doc_root.iter():
            for child in parent: parent_map[child] = parent

        def find_enclosing_paragraph(elem):
            cur = elem
            while cur in parent_map:
                cur = parent_map[cur]
                if cur.tag == p_tag: return cur
            return None

        anchors = {}
        for obj_elem in doc_root.iter(obj_tag):
            p_elem = find_enclosing_paragraph(obj_elem)
            if p_elem is None or id(p_elem) not in para_index:
                anchors[id(obj_elem)] = ""
                continue
            idx = para_index[id(p_elem)]
            own_text = para_texts[idx]
            combined = own_text
            if len(combined) < 8:
                next_text = ""
                for j in range(idx + 1, len(para_texts)):
                    if para_texts[j]:
                        next_text = para_texts[j]
                        break
                combined = " ".join(t for t in (own_text, next_text) if t)
            anchors[id(obj_elem)] = combined[:MAX_ANCHOR_LEN]
        return anchors

    def extract_ole_preview_pairs(self, docx_path: str) -> List[OleItem]:
        with zipfile.ZipFile(docx_path, "r") as zf:
            document_xml = zf.read("word/document.xml")
            rels_xml = zf.read("word/_rels/document.xml.rels")
            rels_root = ET.fromstring(rels_xml)
            rid_to_target = {}
            for rel in rels_root.findall("rel:Relationship", NS):
                rid, target = rel.get("Id"), rel.get("Target")
                if target:
                    target_norm = target.replace("\\", "/")
                    if target_norm.startswith("media") or target_norm.startswith("embeddings"):
                        rid_to_target[rid] = "word/" + target_norm
            
            doc_root = ET.fromstring(document_xml)
            anchors = self._build_ole_anchor_texts(doc_root)
            ole_items, order = [], 0
            for obj_elem in doc_root.iter("{%s}object" % NS["w"]):
                imagedata_elem = obj_elem.find(".//v:imagedata", NS)
                ole_elem = obj_elem.find(".//o:OLEObject", NS)
                if imagedata_elem is None or ole_elem is None: continue
                preview_rid, ole_rid = imagedata_elem.get("{%s}id" % NS["r"]), ole_elem.get("{%s}id" % NS["r"])
                preview_target, ole_target = rid_to_target.get(preview_rid), rid_to_target.get(ole_rid)
                if not preview_target or not ole_target: continue
                
                # Calculate SHA-256 hash of the actual OLE object binary
                ole_bytes = zf.read(ole_target)
                file_hash = hashlib.sha256(ole_bytes).hexdigest()

                ole_items.append(OleItem(
                    ole_name=ole_target, preview_name=preview_target, order_hint=order,
                    preview_bytes=zf.read(preview_target), preview_ext=os.path.splitext(preview_target)[1].lstrip(".").lower(),
                    anchor_text=anchors.get(id(obj_elem), ""),
                    file_hash=file_hash
                ))
                order += 1
        return ole_items

    def compute_preview_hashes(self, ole_items: List[OleItem], tmp_dir: str, profile_dir: Optional[str] = None):
        preview_dir = os.path.join(tmp_dir, "previews")
        os.makedirs(preview_dir, exist_ok=True)
        need_conversion = []
        for idx, item in enumerate(ole_items):
            raw_path = os.path.join(preview_dir, f"preview_{idx}.{item.preview_ext}")
            with open(raw_path, "wb") as f: f.write(item.preview_bytes)
            item.raw_path = raw_path
            if item.preview_ext in ("emf", "wmf"): need_conversion.append(raw_path)
        if need_conversion:
            cmd = [SOFFICE_EXE, "--headless", "--convert-to", "png", "--outdir", preview_dir] + need_conversion
            self._run_soffice(cmd, profile_dir=profile_dir)
        def _hash_path(item: OleItem):
            png_path = os.path.splitext(item.raw_path)[0] + ".png" if item.preview_ext in ("emf", "wmf") else item.raw_path
            try:
                with Image.open(png_path) as img: item.phash = imagehash.phash(img.convert("RGB"))
            except Exception: item.phash = None
        with ThreadPoolExecutor() as hash_executor:
            list(hash_executor.map(_hash_path, ole_items))

    def _prepare_ole_items(self, input_file: str, tmp_dir: str, profile_dir: str) -> List[OleItem]:
        docx_path = self.ensure_docx(input_file, tmp_dir, profile_dir=profile_dir)
        ole_items = self.extract_ole_preview_pairs(docx_path)
        if ole_items: self.compute_preview_hashes(ole_items, tmp_dir, profile_dir=profile_dir)
        return ole_items

    def convert_to_pdf_with_libreoffice(self, input_file: str, outdir: str, profile_dir: Optional[str] = None) -> str:
        cmd = [SOFFICE_EXE, "--headless", "--convert-to", "pdf", "--outdir", outdir, input_file]
        self._run_soffice(cmd, profile_dir=profile_dir)
        base_name = os.path.splitext(os.path.basename(input_file))[0]
        pdf_path = os.path.join(outdir, base_name + ".pdf")
        if not os.path.exists(pdf_path): raise RuntimeError(f"변환된 PDF를 찾을 수 없습니다: {pdf_path}")
        return pdf_path

    def extract_pdf_page_data(self, pdf_path: str) -> Tuple[List[Dict], Dict[int, str], int]:
        page_images, page_texts, xref_hash_cache = [], {}, {}
        with fitz.open(pdf_path) as doc:
            page_count = doc.page_count
            for page_index in range(page_count):
                page = doc[page_index]
                page_num = page_index + 1
                page_texts[page_num] = self._normalize_text(page.get_text())
                for img_info in page.get_images(full=True):
                    xref = img_info[0]
                    if xref not in xref_hash_cache:
                        try:
                            base_image = doc.extract_image(xref)
                            with Image.open(BytesIO(base_image["image"])) as img:
                                xref_hash_cache[xref] = imagehash.phash(img.convert("RGB"))
                        except Exception: xref_hash_cache[xref] = None
                    page_images.append({"page": page_num, "xref": xref, "phash": xref_hash_cache[xref]})
        return page_images, page_texts, page_count

    def _prepare_anchor(self, anchor_text: str) -> Optional[Tuple[str, List[str]]]:
        anchor_norm = self._normalize_text(anchor_text)
        if not anchor_norm: return None
        chunks = [anchor_norm[i:i + TEXT_CHUNK_LEN] for i in range(0, len(anchor_norm), TEXT_CHUNK_LEN)]
        chunks = [c for c in chunks if len(c) >= 10]
        if not chunks: chunks = [anchor_norm]
        return anchor_norm, chunks

    def _text_match_cost(self, anchor_prepared: Optional[Tuple[str, List[str]]], page_text_norm: str) -> Optional[float]:
        if anchor_prepared is None: return None
        anchor_norm, chunks = anchor_prepared
        if not page_text_norm: return TEXT_COST_SCALE
        hit = sum(1 for c in chunks if c in page_text_norm)
        if hit > 0: return (1.0 - hit / len(chunks)) * (TEXT_COST_SCALE * 0.3)
        ratio = difflib.SequenceMatcher(None, anchor_norm, page_text_norm[:PAGE_TEXT_COMPARE_LIMIT]).quick_ratio()
        return (1.0 - ratio) * TEXT_COST_SCALE

    def _image_content_cost(self, item: OleItem, page: int, pages_hashes: Dict[int, List]) -> float:
        if item.phash is None: return HASH_MAX_BITS
        hashes = pages_hashes.get(page)
        if not hashes: return HASH_MAX_BITS
        return min(item.phash - h for h in hashes)

    def _position_cost(self, order_index: int, n: int, page: int, P: int) -> float:
        if n <= 1: return 0.0
        expected = 1 + (order_index / (n - 1)) * (P - 1)
        return abs(page - expected) * self.position_weight

    def match_ole_items_to_pages(self, ole_items: List[OleItem], page_images: List[Dict], page_texts: Dict[int, str]):
        if not ole_items: return
        max_page = max(page_texts.keys(), default=0)
        if max_page == 0:
            for item in ole_items: item.matched_page = None
            return
        ordered_items = sorted(ole_items, key=lambda i: i.order_hint)
        n, P = len(ordered_items), max_page
        pages_hashes = {}
        for pimg in page_images:
            if pimg["phash"]: pages_hashes.setdefault(pimg["page"], []).append(pimg["phash"])
        anchor_prepared_list = [self._prepare_anchor(item.anchor_text) for item in ordered_items]

        def item_cost(i: int, page: int) -> float:
            item = ordered_items[i]
            t_cost = self._text_match_cost(anchor_prepared_list[i], page_texts.get(page, ""))
            pos_cost = self._position_cost(i, n, page, P)
            if t_cost is not None:
                return t_cost + self._image_content_cost(item, page, pages_hashes) * IMAGE_WEIGHT + pos_cost
            return self._image_content_cost(item, page, pages_hashes) * IMAGE_WEIGHT_NO_TEXT + pos_cost

        cost = [[item_cost(i, j + 1) for j in range(P)] for i in range(n)]
        dp = [[0.0] * P for _ in range(n)]
        g = [[0.0] * P for _ in range(n)]
        argmin_g = [[0] * P for _ in range(n)]

        for j in range(P): dp[0][j] = cost[0][j]
        g[0][0], argmin_g[0][0] = dp[0][0], 0
        for j in range(1, P):
            if dp[0][j] <= g[0][j - 1]: g[0][j], argmin_g[0][j] = dp[0][j], j
            else: g[0][j], argmin_g[0][j] = g[0][j - 1], argmin_g[0][j - 1]

        for i in range(1, n):
            for j in range(P): dp[i][j] = cost[i][j] + g[i - 1][j]
            g[i][0], argmin_g[i][0] = dp[i][0], 0
            for j in range(1, P):
                if dp[i][j] <= g[i][j - 1]: g[i][j], argmin_g[i][j] = dp[i][j], j
                else: g[i][j], argmin_g[i][j] = g[i][j - 1], argmin_g[i][j - 1]

        best_last_page_idx = min(range(P), key=lambda j: dp[n - 1][j])
        assigned_0based = [0] * n
        assigned_0based[n - 1] = best_last_page_idx
        for i in range(n - 2, -1, -1): assigned_0based[i] = argmin_g[i][assigned_0based[i + 1]]
        for i, item in enumerate(ordered_items): item.matched_page = assigned_0based[i] + 1
