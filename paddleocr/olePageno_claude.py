# -*- coding: utf-8 -*-
"""
ole_page_fingerprint.py
==============================================================================
[목적]
    MS-Word 문서(doc, docx)에 삽입된 OLE 개체가 실제로 몇 번째 "페이지"에
    위치하는지 추정한다.

[버전 히스토리]
    v1  이미지 개수 순서 매칭 -> 다른 이미지가 섞이면 어긋남
    v2  이미지 pHash 매칭(그리디/DP) -> Word 기본 OLE 아이콘이 서로 완전히
        동일한 경우가 많아 이미지만으로는 페이지 구분이 원천적으로 불가능했음
    v3  텍스트 앵커(개체 주변 문단 텍스트) 매칭을 1순위 신호로 사용하도록 전환.
        이미지 pHash / 문서 내 상대적 위치는 보조/최후 신호로 격하.
    v4  (현재) 매칭 로직은 v3과 동일. 처리 속도만 개선:
          - 서로 의존관계가 없는 작업(OLE/미리보기 해시 계산 파이프라인,
            PDF 변환, Tika 교차검증)을 스레드풀로 병렬 실행.
            (soffice를 동시에 여러 개 띄울 때의 프로필 락 충돌을 막기 위해
             호출마다 격리된 -env:UserInstallation 프로필 사용)
          - PDF를 한 번만 열어 이미지/텍스트를 동시에 추출 (기존엔 두 번 오픈)
          - 같은 이미지 리소스(xref)가 여러 페이지에서 재사용될 때 중복
            디코딩/해시계산을 피하도록 캐싱
          - 텍스트 앵커의 청크 분할을 개체당 1회만 미리 계산해 DP 비용 테이블
            생성 시 (개체 수 x 페이지 수)번 반복되던 중복 연산 제거

[전체 처리 흐름]
    1) (A) OLE 파싱+미리보기 해시 계산 / (B) PDF 변환 / (C) Tika 교차검증
       을 병렬로 실행
    2) PDF에서 페이지별 이미지 pHash + 페이지별 텍스트를 한 번에 추출
    3) 텍스트 앵커(1순위) + 이미지 pHash(2순위) + 문서 내 상대적 위치(최후 보정)
       비용을 합산해 "등장 순서 == 페이지 순서(단조 비감소)" 제약 하에서
       DP로 전역 최적 매칭

[필요 라이브러리]
    pip install pymupdf pillow imagehash requests

[사전 준비]
    - LibreOffice 설치 경로 : C:\\Program Files\\LibreOffice
    - Tika 서버(원격, 선택사항, 개수 교차검증용) : http://172.21.0.219:9998/tika

[실행 방법 예시]
    python ole_page_fingerprint.py D:\\ocr_test\\sample\\sample.docx

[한계점]
    - 문서 본문(word/document.xml)만 파싱한다. 머리글/바닥글/각주/미주는
      Tika 교차검증에서 개수 불일치 경고로만 알 수 있다.
    - 개체 주변 문단이 전부 비어있으면(표의 빈 셀 등) 텍스트 앵커가 없어
      이미지/위치 기반 보조 신호로만 추정하며 정확도가 낮아질 수 있다.
==============================================================================
"""

import os
import re
import subprocess
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ET
import argparse
import time
import difflib
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from io import BytesIO
from typing import List, Optional, Dict, Tuple

import requests
from PIL import Image
import imagehash
import fitz  # PyMuPDF


# -----------------------------------------------------------------------
# 환경설정 (필요시 이 부분만 수정)
# -----------------------------------------------------------------------
LIBREOFFICE_HOME = r"C:\Program Files\LibreOffice"
SOFFICE_EXE = os.path.join(LIBREOFFICE_HOME, "program", "soffice.exe")
TIKA_BASE_URL = "http://172.21.0.219:9998/tika"
CONVERT_TIMEOUT_SEC = 120

HASH_MAX_BITS = 64  # imagehash 기본 pHash 크기(8x8)

# --- 비용(cost) 가중치 -----------------------------------------------------
TEXT_COST_SCALE = 100.0          # 텍스트 앵커 매칭 (1순위)
IMAGE_WEIGHT = 0.05              # 텍스트 앵커가 있을 때 이미지 pHash 가중치 (2순위)
IMAGE_WEIGHT_NO_TEXT = 0.3       # 텍스트 앵커가 없을 때 이미지 pHash 가중치
POSITION_TIEBREAK_WEIGHT = 0.01  # 정말 아무 신호도 없을 때의 최후 보정치

TEXT_COST_WARN_THRESHOLD = TEXT_COST_SCALE * 0.5  # 신뢰도 경고 출력 기준
HASH_MATCH_THRESHOLD = 30                          # 신뢰도 경고 출력 기준

MAX_ANCHOR_LEN = 400   # 앵커 텍스트 최대 길이 (비교 비용 상한선)
TEXT_CHUNK_LEN = 40    # 앵커 텍스트를 쪼개는 조각 길이 (페이지 경계에서 문단이 잘리는 것 대비)
PAGE_TEXT_COMPARE_LIMIT = 5000  # difflib 비교 시 페이지 텍스트 앞부분만 사용

# OOXML 네임스페이스 정의 (word/document.xml, .rels 파싱용)
NS = {
    "w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
    "v": "urn:schemas-microsoft-com:vml",
    "o": "urn:schemas-microsoft-com:office:office",
    "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
    "rel": "http://schemas.openxmlformats.org/package/2006/relationships",
}


@dataclass
class OleItem:
    """docx 내부에서 파싱된 OLE 개체 1건에 대한 정보"""
    ole_name: str                              # 예: word/embeddings/oleObject1.bin
    preview_name: str                          # 예: word/media/image1.emf
    order_hint: int                            # document.xml 내 등장 순서(0부터, 매칭 제약에 사용)
    preview_bytes: bytes = field(repr=False)   # 미리보기 이미지 원본 바이트
    preview_ext: str = ""                      # 미리보기 이미지 확장자(emf, png 등)
    anchor_text: str = ""                      # 개체 주변 문단에서 추출한 앵커 텍스트 (1순위 신호)
    phash: Optional[imagehash.ImageHash] = None    # 계산된 지각 해시(2순위 신호)
    matched_page: Optional[int] = None             # 최종 매칭된 페이지 번호
    raw_path: str = ""                              # 임시로 저장된 원본 이미지 파일 경로


# =========================================================================
# 0) 진입점
# =========================================================================
def main():
    global HASH_MATCH_THRESHOLD, POSITION_TIEBREAK_WEIGHT

    parser = argparse.ArgumentParser(
        description="OLE 개체 페이지 번호 추출 (텍스트 앵커 + 이미지 핑거프린팅 + DP 매칭, 병렬 처리)"
    )
    parser.add_argument("input_file", help="분석할 .doc 또는 .docx 파일 경로")
    parser.add_argument(
        "--work-dir", dest="work_dir",
        help="임시 파일 및 PDF를 저장할 디렉터리 (기본값: 입력 파일 디렉터리)",
    )
    parser.add_argument(
        "--threshold", type=int, default=HASH_MATCH_THRESHOLD,
        help="pHash 매칭 '신뢰도 경고' 기준 해밍 거리 (기본값: %(default)s)",
    )
    parser.add_argument(
        "--position-weight", type=float, default=POSITION_TIEBREAK_WEIGHT,
        help="아무 신호도 없을 때 문서 내 위치를 반영하는 가중치 (기본값: %(default)s)",
    )
    args = parser.parse_args()

    input_file = args.input_file
    if not os.path.exists(input_file):
        print(f"[오류] 입력 파일을 찾을 수 없습니다: {input_file}")
        sys.exit(1)

    work_dir = args.work_dir if args.work_dir else os.path.dirname(input_file)
    HASH_MATCH_THRESHOLD = args.threshold
    POSITION_TIEBREAK_WEIGHT = args.position_weight

    print("=" * 70)
    print(" OLE 개체 페이지번호 추출 (텍스트 앵커 + 이미지 핑거프린팅 + DP 매칭)")
    print(" 대상 문서 :", input_file)
    print("=" * 70)

    start_time = time.time()

    with tempfile.TemporaryDirectory(prefix="ole_fp_") as tmp_dir:
        ole_profile_dir = os.path.join(tmp_dir, "lo_profile_ole")
        pdf_profile_dir = os.path.join(tmp_dir, "lo_profile_pdf")

        # (A) OLE 파싱 + 미리보기 해시 계산, (B) PDF 변환, (C) Tika 교차검증을
        # 서로 의존관계가 없으므로 동시에 실행한다. soffice 두 개를 동시에
        # 띄우기 때문에 서로 다른 사용자 프로필(profile_dir)을 지정해 락 충돌을
        # 방지한다.
        with ThreadPoolExecutor(max_workers=3) as executor:
            ole_future = executor.submit(_prepare_ole_items, input_file, tmp_dir, ole_profile_dir)
            pdf_future = executor.submit(convert_to_pdf_with_libreoffice, input_file, work_dir, pdf_profile_dir)
            tika_future = executor.submit(_fetch_tika_ole_count, input_file)

            ole_items = ole_future.result()
            print(f"[1/4] OLE 개체 추출 + 미리보기 해시 계산 완료 (개체 수: {len(ole_items)})")
            for item in ole_items:
                anchor_preview = (item.anchor_text[:40] + "...") if len(item.anchor_text) > 40 else item.anchor_text
                print(f"      · {item.ole_name}  <->  {item.preview_name}")
                print(f"        앵커 텍스트: {anchor_preview!r}")

            if not ole_items:
                print("      -> OLE 개체를 찾지 못했습니다. 종료합니다.")
                return

            _print_tika_comparison(tika_future.result(), len(ole_items))

            pdf_path = pdf_future.result()
            print(f"[2/4] PDF 변환 완료 : {pdf_path}")

        # 2) PDF에서 페이지별 이미지 pHash + 텍스트를 한 번에 추출
        print("[3/4] PDF 페이지별 이미지/텍스트 추출 중...")
        page_images, page_texts, page_count = extract_pdf_page_data(pdf_path)
        print(f"      -> PDF 페이지 수 : {page_count}, 추출된 이미지 총 개수 : {len(page_images)}")

        # 3) DP 기반 전역 최적 매칭
        print("[4/4] DP 기반 페이지 매칭 수행 중...")
        match_ole_items_to_pages(ole_items, page_images, page_texts)

        print("\n================= OLE 개체 페이지 매칭 결과 =================")
        for item in sorted(ole_items, key=lambda i: i.order_hint):
            page_str = f"{item.matched_page} 페이지" if item.matched_page else "매칭실패(신호 없음)"
            print(f"  {item.ole_name:<35} : {page_str}")
        print("==============================================================")
        elapsed = time.time() - start_time
        print(f"[완료] 전체 처리 시간: {elapsed:.2f} 초")





def ensure_docx(input_path: str, tmp_dir: str, profile_dir: Optional[str] = None) -> str:
    ext = os.path.splitext(input_path)[1].lower()
    if ext == ".docx":
        return input_path
    if ext != ".doc":
        raise ValueError(f"지원하지 않는 확장자입니다: {ext}")

    cmd = [SOFFICE_EXE, "--headless", "--convert-to", "docx",
           "--outdir", tmp_dir, input_path]
    _run_soffice(cmd, profile_dir=profile_dir)

    base_name = os.path.splitext(os.path.basename(input_path))[0]
    converted_path = os.path.join(tmp_dir, base_name + ".docx")
    if not os.path.exists(converted_path):
        raise RuntimeError(f"doc -> docx 변환 결과 파일을 찾을 수 없습니다: {converted_path}")
    return converted_path


# =========================================================================
# docx 내부 구조를 직접 파싱하여
#    (a) OLE 개체 <-> 미리보기 이미지 관계
#    (b) OLE 개체 주변 문단의 앵커 텍스트
#    를 함께 추출한다.
# =========================================================================
def _normalize_text(s: str) -> str:
    return re.sub(r"\s+", " ", s or "").strip()


def _build_ole_anchor_texts(doc_root) -> Dict[int, str]:
    """각 <w:object> 요소(파이썬 object id 기준)에 대해, 그 개체가 속한 문단의
    텍스트(문단이 비어있으면 앞/뒤 문단 텍스트로 보강)를 반환한다."""
    w_ns = NS["w"]
    p_tag = f"{{{w_ns}}}p"
    t_tag = f"{{{w_ns}}}t"
    obj_tag = f"{{{w_ns}}}object"

    all_paragraphs = list(doc_root.iter(p_tag))
    para_index = {id(p): idx for idx, p in enumerate(all_paragraphs)}
    para_texts = []
    for p in all_paragraphs:
        texts = [t.text for t in p.iter(t_tag) if t.text]
        para_texts.append(_normalize_text("".join(texts)))

    parent_map: Dict[ET.Element, ET.Element] = {}
    for parent in doc_root.iter():
        for child in parent:
            parent_map[child] = parent

    def find_enclosing_paragraph(elem):
        cur = elem
        while cur in parent_map:
            cur = parent_map[cur]
            if cur.tag == p_tag:
                return cur
        return None

    anchors: Dict[int, str] = {}
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


def extract_ole_preview_pairs(docx_path: str) -> List[OleItem]:
    with zipfile.ZipFile(docx_path, "r") as zf:
        document_xml = zf.read("word/document.xml")
        rels_xml = zf.read("word/_rels/document.xml.rels")

        rels_root = ET.fromstring(rels_xml)
        rid_to_target: Dict[str, str] = {}
        for rel in rels_root.findall("rel:Relationship", NS):
            rid = rel.get("Id")
            target = rel.get("Target")
            if target is None:
                continue
            target_norm = target.replace("\\", "/")
            if not (target_norm.startswith("media") or target_norm.startswith("embeddings")):
                continue
            rid_to_target[rid] = "word/" + target_norm

        doc_root = ET.fromstring(document_xml)
        anchors = _build_ole_anchor_texts(doc_root)

        ole_items: List[OleItem] = []
        order = 0

        for obj_elem in doc_root.iter("{%s}object" % NS["w"]):
            imagedata_elem = obj_elem.find(".//v:imagedata", NS)
            ole_elem = obj_elem.find(".//o:OLEObject", NS)

            if imagedata_elem is None or ole_elem is None:
                continue

            preview_rid = imagedata_elem.get("{%s}id" % NS["r"])
            ole_rid = ole_elem.get("{%s}id" % NS["r"])

            preview_target = rid_to_target.get(preview_rid)
            ole_target = rid_to_target.get(ole_rid)

            if not preview_target or not ole_target:
                continue

            preview_bytes = zf.read(preview_target)
            preview_ext = os.path.splitext(preview_target)[1].lstrip(".").lower()

            ole_items.append(OleItem(
                ole_name=ole_target,
                preview_name=preview_target,
                order_hint=order,
                preview_bytes=preview_bytes,
                preview_ext=preview_ext,
                anchor_text=anchors.get(id(obj_elem), ""),
            ))
            order += 1

    return ole_items


# =========================================================================
# (선택/교차검증) 원격 Tika 서버(rmeta)로 문서 내 OLE 개체 총 개수를 재확인
# =========================================================================
def _fetch_tika_ole_count(input_file: str) -> Optional[int]:
    try:
        rmeta_url = TIKA_BASE_URL.rsplit("/tika", 1)[0] + "/rmeta/text"
        with open(input_file, "rb") as f:
            resp = requests.put(
                rmeta_url, data=f, headers={"Accept": "application/json"}, timeout=60,
            )
        resp.raise_for_status()
        meta_list = resp.json()

        count = 0
        for meta in meta_list:
            resource_name = (meta.get("resourceName") or "").lower()
            content_type = (meta.get("Content-Type") or "").lower()
            if re.search(r"ole.*\.bin$", resource_name) or "ole" in content_type:
                count += 1
        return count
    except Exception as e:
        print(f"      [경고] Tika 교차검증을 건너뜁니다 (오류: {e})")
        return None


def _print_tika_comparison(tika_count: Optional[int], parsed_count: int):
    if tika_count is None:
        return
    if tika_count != parsed_count:
        print(f"      [경고] Tika 기준 OLE 개체 수({tika_count})와 "
              f"docx 직접 파싱 개수({parsed_count})가 다릅니다. "
              f"머리글/바닥글/각주 등에 별도 OLE가 있는지 확인해보세요.")
    else:
        print(f"      -> Tika 교차검증 통과 (개체 수 일치: {tika_count}개)")


# =========================================================================
# 미리보기 이미지들을 PNG로 통일 변환하고 지각 해시(pHash) 계산 (2순위 신호)
# =========================================================================
def compute_preview_hashes(ole_items: List[OleItem], tmp_dir: str, profile_dir: Optional[str] = None):
    """OLE 미리보기 이미지들을 PNG로 변환하고 pHash를 계산한다.
    - EMF/WMF는 LibreOffice를 이용해 PNG로 변환하고, 변환된 파일을 바로 해시한다.
    - 변환·해시 작업을 병렬화해 CPU 코어 활용도를 높인다.
    """
    preview_dir = os.path.join(tmp_dir, "previews")
    os.makedirs(preview_dir, exist_ok=True)

    need_conversion = []
    for idx, item in enumerate(ole_items):
        raw_path = os.path.join(preview_dir, f"preview_{idx}.{item.preview_ext}")
        with open(raw_path, "wb") as f:
            f.write(item.preview_bytes)
        item.raw_path = raw_path
        if item.preview_ext in ("emf", "wmf"):
            need_conversion.append(raw_path)

    if need_conversion:
        cmd = [SOFFICE_EXE, "--headless", "--convert-to", "png",
               "--outdir", preview_dir] + need_conversion
        _run_soffice(cmd, profile_dir=profile_dir)

    # 해시 계산을 병렬화 (IO 바운드 + CPU 경량)
    def _hash_path(item: OleItem) -> None:
        png_path = os.path.splitext(item.raw_path)[0] + ".png" if item.preview_ext in ("emf", "wmf") else item.raw_path
        try:
            with Image.open(png_path) as img:
                item.phash = imagehash.phash(img.convert("RGB"))
        except Exception as e:
            print(f"      [경고] 미리보기 이미지 해시 계산 실패 ({item.preview_name}): {e}")
            item.phash = None

    with ThreadPoolExecutor() as hash_executor:
        list(hash_executor.map(_hash_path, ole_items) )


# =========================================================================
# (A) OLE 파싱 + 미리보기 해시 계산 파이프라인 (스레드풀에서 독립적으로 실행됨)
# =========================================================================
def _prepare_ole_items(input_file: str, tmp_dir: str, profile_dir: str) -> List[OleItem]:
    docx_path = ensure_docx(input_file, tmp_dir, profile_dir=profile_dir)
    ole_items = extract_ole_preview_pairs(docx_path)
    if ole_items:
        compute_preview_hashes(ole_items, tmp_dir, profile_dir=profile_dir)
    return ole_items

# =========================================================================
# (B) LibreOffice로 원본 문서를 PDF로 변환
# =========================================================================
def convert_to_pdf_with_libreoffice(input_file: str, outdir: str, profile_dir: Optional[str] = None) -> str:
    cmd = [SOFFICE_EXE, "--headless", "--convert-to", "pdf",
           "--outdir", outdir, input_file]
    _run_soffice(cmd, profile_dir=profile_dir)

    base_name = os.path.splitext(os.path.basename(input_file))[0]
    pdf_path = os.path.join(outdir, base_name + ".pdf")
    if not os.path.exists(pdf_path):
        raise RuntimeError(f"변환된 PDF를 찾을 수 없습니다: {pdf_path}")
    return pdf_path


# =========================================================================
# PDF에서 (a) 페이지별 이미지 pHash, (b) 페이지별 텍스트를 "한 번의 오픈"으로
# 함께 추출한다. 동일 xref(이미지 리소스)가 여러 페이지에서 재사용되는 경우
# 디코딩/해시계산을 1회만 수행하도록 캐싱한다.
# =========================================================================
def extract_pdf_page_data(pdf_path: str) -> Tuple[List[Dict], Dict[int, str], int]:
    page_images: List[Dict] = []
    page_texts: Dict[int, str] = {}
    xref_hash_cache: Dict[int, Optional[imagehash.ImageHash]] = {}

    with fitz.open(pdf_path) as doc:
        page_count = doc.page_count
        for page_index in range(page_count):
            page = doc[page_index]
            page_num = page_index + 1
            page_texts[page_num] = _normalize_text(page.get_text())

            for img_info in page.get_images(full=True):
                xref = img_info[0]
                if xref not in xref_hash_cache:
                    try:
                        base_image = doc.extract_image(xref)
                        with Image.open(BytesIO(base_image["image"])) as img:
                            xref_hash_cache[xref] = imagehash.phash(img.convert("RGB"))
                    except Exception:
                        xref_hash_cache[xref] = None
                page_images.append({"page": page_num, "xref": xref, "phash": xref_hash_cache[xref]})

    return page_images, page_texts, page_count


# =========================================================================
# 매칭 비용 함수들
# =========================================================================
def _prepare_anchor(anchor_text: str) -> Optional[Tuple[str, List[str]]]:
    """앵커 텍스트를 정규화하고 청크로 미리 쪼개둔다 (개체당 1회만 호출)."""
    anchor_norm = _normalize_text(anchor_text)
    if not anchor_norm:
        return None
    chunks = [anchor_norm[i:i + TEXT_CHUNK_LEN] for i in range(0, len(anchor_norm), TEXT_CHUNK_LEN)]
    chunks = [c for c in chunks if len(c) >= 10]
    if not chunks:
        chunks = [anchor_norm]
    return anchor_norm, chunks


def _text_match_cost(anchor_prepared: Optional[Tuple[str, List[str]]], page_text_norm: str) -> Optional[float]:
    """앵커 텍스트와 페이지 텍스트를 비교해 비용(작을수록 유사)을 반환.
    앵커 텍스트가 없으면 None(=이 신호를 사용할 수 없음)을 반환한다."""
    if anchor_prepared is None:
        return None
    anchor_norm, chunks = anchor_prepared
    if not page_text_norm:
        return TEXT_COST_SCALE

    hit = sum(1 for c in chunks if c in page_text_norm)
    if hit > 0:
        ratio = hit / len(chunks)
        return (1.0 - ratio) * (TEXT_COST_SCALE * 0.3)

    ratio = difflib.SequenceMatcher(None, anchor_norm, page_text_norm[:PAGE_TEXT_COMPARE_LIMIT]).quick_ratio()
    return (1.0 - ratio) * TEXT_COST_SCALE


def _image_content_cost(item: OleItem, page: int, pages_hashes: Dict[int, List]) -> float:
    if item.phash is None:
        return HASH_MAX_BITS
    hashes = pages_hashes.get(page)
    if not hashes:
        return HASH_MAX_BITS
    return min(item.phash - h for h in hashes)


def _position_cost(order_index: int, n: int, page: int, P: int) -> float:
    if n <= 1:
        return 0.0
    expected = 1 + (order_index / (n - 1)) * (P - 1)
    return abs(page - expected) * POSITION_TIEBREAK_WEIGHT


# =========================================================================
# DP 기반 전역 최적 매칭
#    - "등장 순서 == 페이지 순서(단조 비감소)" 제약 하에서
#      텍스트(1순위) + 이미지(2순위) + 위치(최후 보정) 비용의 합을 최소화.
# =========================================================================
def match_ole_items_to_pages(
    ole_items: List[OleItem],
    page_images: List[Dict],
    page_texts: Dict[int, str],
):
    if not ole_items:
        return

    max_page = max(page_texts.keys(), default=0)
    if max_page == 0:
        for item in ole_items:
            item.matched_page = None
        return

    ordered_items = sorted(ole_items, key=lambda i: i.order_hint)
    n = len(ordered_items)
    P = max_page

    pages_hashes: Dict[int, List] = {}
    for pimg in page_images:
        if pimg["phash"] is None:
            continue
        pages_hashes.setdefault(pimg["page"], []).append(pimg["phash"])

    # 앵커 텍스트 청크 분할은 개체당 1회만 미리 계산해둔다.
    # (기존엔 DP 비용 테이블 생성 시 개체수 x 페이지수 만큼 반복 계산되던 부분)
    anchor_prepared_list = [_prepare_anchor(item.anchor_text) for item in ordered_items]

    def item_cost(i: int, page: int) -> float:
        item = ordered_items[i]
        t_cost = _text_match_cost(anchor_prepared_list[i], page_texts.get(page, ""))
        pos_cost = _position_cost(i, n, page, P)

        if t_cost is not None:
            img_cost = _image_content_cost(item, page, pages_hashes) * IMAGE_WEIGHT
            return t_cost + img_cost + pos_cost
        else:
            img_cost = _image_content_cost(item, page, pages_hashes) * IMAGE_WEIGHT_NO_TEXT
            return img_cost + pos_cost

    cost = [[item_cost(i, j + 1) for j in range(P)] for i in range(n)]

    # dp[i][j]      : i번째 개체를 "정확히" (j+1)페이지에 배정했을 때의 최소 누적 비용
    # g[i][j]       : dp[i][0..j] 의 prefix-min
    # argmin_g[i][j]: g[i][j] 를 만든 실제 페이지 인덱스(0-base) — 역추적용
    dp = [[0.0] * P for _ in range(n)]
    g = [[0.0] * P for _ in range(n)]
    argmin_g = [[0] * P for _ in range(n)]

    for j in range(P):
        dp[0][j] = cost[0][j]
    g[0][0] = dp[0][0]
    argmin_g[0][0] = 0
    for j in range(1, P):
        if dp[0][j] <= g[0][j - 1]:
            g[0][j] = dp[0][j]
            argmin_g[0][j] = j
        else:
            g[0][j] = g[0][j - 1]
            argmin_g[0][j] = argmin_g[0][j - 1]

    for i in range(1, n):
        for j in range(P):
            dp[i][j] = cost[i][j] + g[i - 1][j]
        g[i][0] = dp[i][0]
        argmin_g[i][0] = 0
        for j in range(1, P):
            if dp[i][j] <= g[i][j - 1]:
                g[i][j] = dp[i][j]
                argmin_g[i][j] = j
            else:
                g[i][j] = g[i][j - 1]
                argmin_g[i][j] = argmin_g[i][j - 1]

    last_row = dp[n - 1]
    best_last_page_idx = min(range(P), key=lambda j: last_row[j])

    assigned_0based = [0] * n
    assigned_0based[n - 1] = best_last_page_idx
    for i in range(n - 2, -1, -1):
        assigned_0based[i] = argmin_g[i][assigned_0based[i + 1]]

    for i, item in enumerate(ordered_items):
        page = assigned_0based[i] + 1
        item.matched_page = page

        t_cost = _text_match_cost(anchor_prepared_list[i], page_texts.get(page, ""))
        img_dist = _image_content_cost(item, page, pages_hashes)

        note = ""
        if t_cost is None:
            note = " [주의: 앵커 텍스트 없음, 이미지/위치 기반 추정]"
        elif t_cost > TEXT_COST_WARN_THRESHOLD:
            note = f" [주의: 텍스트 신뢰도 낮음(비용 {t_cost:.1f})]"

        print(f"      [디버그] {item.preview_name} -> {page} 페이지 "
              f"(텍스트비용: {t_cost if t_cost is not None else 'N/A'}, "
              f"이미지거리: {img_dist:.1f}){note}")


# =========================================================================
# 공통 유틸 : LibreOffice 프로세스 실행
# =========================================================================
def _run_soffice(cmd: List[str], profile_dir: Optional[str] = None):
    exe_path = SOFFICE_EXE
    if not os.path.exists(exe_path):
        try:
            result = subprocess.run(
                ["where", "soffice"],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                timeout=5,
            )
            candidates = [line.strip() for line in result.stdout.splitlines() if line.strip()]
            if candidates:
                exe_path = candidates[0]
        except Exception:
            pass

    if exe_path.lower().endswith('.com'):
        possible_exe = exe_path[:-4] + '.exe'
        if os.path.exists(possible_exe):
            exe_path = possible_exe

    if not os.path.exists(exe_path):
        raise FileNotFoundError(
            f"LibreOffice 실행파일을 찾을 수 없습니다. 지정된 경로: {SOFFICE_EXE}. "
            "PATH에서도 'soffice' 를 찾을 수 없었습니다. LibreOffice가 설치되어 있는지 확인하고, "
            "SOFFICE_EXE 변수 값을 올바른 경로로 수정하세요."
        )

    if cmd and os.path.basename(cmd[0]).lower().endswith("soffice.exe"):
        cmd = [exe_path] + cmd[1:]

    # 여러 soffice 프로세스를 동시에 띄울 때 사용자 프로필(락 파일) 충돌로
    # "이미 실행 중입니다" 오류가 나는 것을 막기 위해, 호출별로 격리된
    # 프로필 디렉터리를 지정한다.
    if profile_dir:
        os.makedirs(profile_dir, exist_ok=True)
        profile_uri = "file:///" + os.path.abspath(profile_dir).replace("\\", "/").lstrip("/")
        cmd = [cmd[0], f"-env:UserInstallation={profile_uri}"] + cmd[1:]

    soffice_dir = os.path.dirname(exe_path)
    proc = subprocess.run(
        cmd,
        cwd=soffice_dir,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=CONVERT_TIMEOUT_SEC,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"LibreOffice 실행 실패 (returncode={proc.returncode})\n"
            f"명령어: {' '.join(cmd)}\n출력: {proc.stdout.decode(errors='ignore')}"
        )


if __name__ == "__main__":
    main()