"""
pdf_processor.py

PDF 파일을 이미지(PNG)로 변환하는 모듈
PaddleOCR와는 독립적으로 동작한다.
"""

from pathlib import Path
import fitz  # PyMuPDF

from config import (
    PDF_DPI,
    IMAGE_FORMAT,
    OUTPUT_IMAGE_DIR,
)


class PDFProcessor:
    """
    PDF -> Image Converter
    """

    def __init__(self, dpi=PDF_DPI):
        self.dpi = dpi

    def pdf_to_images(self, pdf_path):
        """
        PDF를 페이지별 이미지로 저장

        Args:
            pdf_path (str | Path)

        Returns:
            list[Path]
                저장된 이미지 경로 목록
        """

        pdf_path = Path(pdf_path)

        if not pdf_path.exists():
            raise FileNotFoundError(pdf_path)

        doc = fitz.open(pdf_path) # pdf의 페이지들 저장

        image_paths = []

        zoom = self.dpi / 72
        matrix = fitz.Matrix(zoom, zoom)

        output_dir = OUTPUT_IMAGE_DIR / pdf_path.stem
        output_dir.mkdir(parents=True, exist_ok=True)

        for page_num in range(len(doc)):
            page = doc.load_page(page_num)

            pix = page.get_pixmap(matrix=matrix)

            image_path = output_dir / f"page_{page_num + 1}.{IMAGE_FORMAT}"

            pix.save(str(image_path))

            image_paths.append(image_path)

        doc.close()

        return image_paths

    def get_page_count(self, pdf_path):
        """
        PDF 페이지 수 반환
        """

        pdf_path = Path(pdf_path)

        with fitz.open(pdf_path) as doc:
            return len(doc)