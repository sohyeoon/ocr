"""
config.py

프로젝트 전체 설정 파일
"""

from pathlib import Path

# ==========================================================
# 프로젝트 경로
# ==========================================================
TEMP_DIR = "/tmp"

HOST = "0.0.0.0"

PORT = 8000

DEVICE = "cpu"

BASE_DIR = Path(__file__).resolve().parent

INPUT_DIR = BASE_DIR / "input"
OUTPUT_DIR = BASE_DIR / "output"

PDF_DIR = INPUT_DIR / "pdf"
IMAGE_DIR = INPUT_DIR / "images"
GT_DIR = INPUT_DIR / "gt"

OUTPUT_IMAGE_DIR = OUTPUT_DIR / "images"
OUTPUT_TEXT_DIR = OUTPUT_DIR / "text"
OUTPUT_RESULT_DIR = OUTPUT_DIR / "result"

# 출력 폴더 자동 생성
for path in [
    OUTPUT_IMAGE_DIR,
    OUTPUT_TEXT_DIR,
    OUTPUT_RESULT_DIR,
]:
    path.mkdir(parents=True, exist_ok=True)


# ==========================================================
# PaddleOCR 설정
# ==========================================================

OCR_LANGUAGE = "korean"

USE_GPU = False

USE_ANGLE_CLS = True

# PaddleOCR 3.x
TEXT_DETECTION_MODEL = None
TEXT_RECOGNITION_MODEL = None

# Recognition 옵션
USE_DOC_UNWARP = False
USE_DOC_ORIENTATION_CLASSIFY = False

# confidence threshold
SCORE_THRESHOLD = 0.5


# ==========================================================
# PDF 설정
# ==========================================================

PDF_DPI = 300

IMAGE_FORMAT = "png"


# ==========================================================
# 평가(CER/WER)
# ==========================================================

CALCULATE_CER = True
CALCULATE_WER = True


# ==========================================================
# Kafka (향후 사용)
# ==========================================================

KAFKA_BOOTSTRAP_SERVERS = "localhost:9092"

KAFKA_TOPIC_REQUEST = "ocr-request"

KAFKA_TOPIC_RESPONSE = "ocr-response"

KAFKA_GROUP_ID = "ocr-group"


# ==========================================================
# Logging
# ==========================================================

LOG_LEVEL = "INFO"


# ==========================================================
# 기타
# ==========================================================

SUPPORTED_IMAGE_EXTENSIONS = [
    ".png",
    ".jpg",
    ".jpeg",
    ".bmp",
    ".tif",
    ".tiff",
]

SUPPORTED_PDF_EXTENSION = ".pdf"

