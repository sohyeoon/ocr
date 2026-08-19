"""
ocr_engine.py

PaddleOCR 3.8 Wrapper
"""

from pathlib import Path

from paddleocr import PaddleOCR

from config import (
    OCR_LANGUAGE,
    DEVICE,
    USE_GPU,
    USE_ANGLE_CLS,
)


import time


class OCREngine:
    """
    PaddleOCR Wrapper
    """

    _instance = None

    def __new__(cls):
        """
        OCR 모델은 한 번만 로드한다.
        """
        if cls._instance is None:
            cls._instance = super().__new__(cls)

            print("===================================")
            print(" Loading PaddleOCR 3.8 Model...")
            print("===================================")

            cls._instance.ocr = PaddleOCR(
                #운영서버
                #text_detection_model_dir="/root/.paddleocr/det",
                #text_detection_model_name="PP-OCRv5_server_det",
                #text_recognition_model_dir="/root/.paddleocr/rec",
                #text_recognition_model_name="korean_PP-OCRv5_mobile_rec",

                lang=OCR_LANGUAGE,
                device=DEVICE,
                enable_mkldnn=True,
                use_doc_orientation_classify=False,
                use_doc_unwarping=False,
                use_textline_orientation=False
            )

            print("PaddleOCR Loaded.")

        return cls._instance

    def recognize(self, image_path):
        """
        OCR 수행

        Args
        ----
        image_path : str | Path

        Returns
        -------
        list
            OCR 원본 결과
        """

        image_path = str(Path(image_path))
        print(f"[OCR] image_path = {image_path}")

        result = self.ocr.predict(image_path)

        return result

    def extract_text(self, image_path):

        start = time.time()

        print(f"OCR 시작 : {image_path}")

        result = self.recognize(image_path)

        print(
            f"OCR predict 완료 : {time.time() - start:.2f}초"
        )

        texts = []

        for page in result:

            data = page.json["res"]

            rec_texts = data.get("rec_texts", [])

            texts.extend(rec_texts)

        print(
            f"결과 파싱 완료 : {time.time() - start:.2f}초"
        )

        return "\n".join(texts)

    def extract_detail(self, image_path):
        start = time.time()
        print(f"OCR 시작 : {image_path}")

        result = self.recognize(image_path)

        print(f"OCR predict 완료 : {time.time() - start:.2f}초")

        outputs = []

        for page in result:

            data = page.json["res"]

            boxes = data.get("rec_boxes", [])
            texts = data.get("rec_texts", [])
            scores = data.get("rec_scores", [])

            for box, text, score in zip(boxes, texts, scores):

                outputs.append(
                    {
                        "text": text,
                        "score": float(score),
                        "box": box.tolist() if hasattr(box, "tolist") else box,
                    }
                )
                
        print(f"결과 파싱 완료 : {time.time() - start:.2f}초")

        return outputs