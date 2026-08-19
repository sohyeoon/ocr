from pathlib import Path

from pdf_processor import PDFProcessor
from ocr_engine import OCREngine


class OCRService:

    def __init__(self):
        self.pdf_processor = PDFProcessor()
        self.ocr = OCREngine()


    def process_file(self, file_path):

        file_path = Path(file_path)

        texts = []
        scores = []


        # PDF 처리
        if file_path.suffix.lower() == ".pdf":

            image_paths = self.pdf_processor.pdf_to_images(
                file_path
            )

            for image in image_paths:

                result = self.ocr.extract_detail(image)

                for item in result:
                    texts.append(item["text"])
                    scores.append(item["score"])


        # 이미지 처리
        else:

            result = self.ocr.extract_detail(file_path)

            for item in result:
                texts.append(item["text"])
                scores.append(item["score"])


        return {
            "success": True,
            "texts": texts,
            "scores": scores
        }