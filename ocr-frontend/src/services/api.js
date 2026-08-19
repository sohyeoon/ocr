import axios from 'axios';

const API_BASE_URL = process.env.REACT_APP_API_URL || 'http://localhost:8080/api';

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

/**
 * 파일 스캔 시작
 */
export const startFileScan = () => {
  return api.post('/files/scan');
};

/**
 * 처리 상태 조회
 */
export const getProcessingStatus = () => {
  return api.get('/files/status');
};

/**
 * 파일 목록 조회
 */
export const getFiles = (page = 0, size = 20) => {
  return api.get('/files', { params: { page, size } });
};

/**
 * 통합 검색
 */
export const searchFiles = (query, page = 0, size = 10) => {
  return api.get('/search', { params: { q: query, page, size } });
};

/**
 * 파일 다운로드 URL 생성
 */
export const getFileDownloadUrl = (fileId) => {
  return `${API_BASE_URL}/files/${fileId}/download`;
};

/**
 * 파일 미리보기 URL 생성
 */
export const getFilePreviewUrl = (fileId) => {
  return `${API_BASE_URL}/files/${fileId}/preview`;
};

/**
 * 파일 다운로드 실행
 */
export const downloadFile = (fileId, fileName) => {
  const link = document.createElement('a');
  link.href = getFileDownloadUrl(fileId);
  link.download = fileName || 'download';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
};

/**
 * 파일 삭제 URL 생성
 */
export const deleteFileUrl = (fileId) => {
  return `${API_BASE_URL}/files/${fileId}`;
};

/**
 * 파일 삭제 실행
 */
export const deleteFile = async (fileId) => {
  const response = await fetch(
    deleteFileUrl(fileId),
    {
      method: 'DELETE',
    }
);

  if (!response.ok) {
    throw new Error('파일 삭제에 실패했습니다.');
  }

  return true;
};

export default api;