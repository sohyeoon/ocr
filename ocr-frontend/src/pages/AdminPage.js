import React, { useState, useEffect, useRef } from 'react';
import { startFileScan, getProcessingStatus, getFiles, downloadFile } from '../services/api';
import './AdminPage.css';

function AdminPage() {
  const [status, setStatus] = useState(null);
  const [files, setFiles] = useState([]);
  const [loading, setLoading] = useState(false);
  const [scanLoading, setScanLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const intervalRef = useRef(null);

  useEffect(() => {
    fetchStatus();
    fetchFiles(0);
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, []);

  const fetchStatus = async () => {
    try {
      const response = await getProcessingStatus();
      setStatus(response.data);
    } catch (err) {
      console.error('상태 조회 실패:', err);
    }
  };

  const fetchFiles = async (page) => {
    setLoading(true);
    try {
      const response = await getFiles(page);
      setFiles(response.data.content || []);
      setTotalPages(response.data.totalPages || 0);
      setCurrentPage(page);
    } catch (err) {
      console.error('파일 목록 조회 실패:', err);
    } finally {
      setLoading(false);
    }
  };

  const handleStartScan = async () => {
    setScanLoading(true);
    setMessage('');

    try {
      const response = await startFileScan();
      setMessage(response.data.message);

      // 상태 폴링 시작
      intervalRef.current = setInterval(async () => {
        const statusRes = await getProcessingStatus();
        setStatus(statusRes.data);

        if (statusRes.data.status === 'COMPLETED' || statusRes.data.status === 'FAILED') {
          clearInterval(intervalRef.current);
          intervalRef.current = null;
          setScanLoading(false);
          fetchFiles(0);
        }
      }, 2000);
    } catch (err) {
      setMessage('파일 스캔 시작에 실패했습니다. 서버 연결을 확인해주세요.');
      setScanLoading(false);
    }
  };

  const getStatusColor = (statusValue) => {
    const colors = {
      IDLE: '#999',
      STARTED: '#ff9800',
      RUNNING: '#1a73e8',
      COMPLETED: '#4caf50',
      FAILED: '#f44336',
    };
    return colors[statusValue] || '#999';
  };

  const getFileStatusBadge = (fileStatus) => {
    const badges = {
      PENDING: { color: '#ff9800', bg: '#fff3e0', label: '대기' },
      PROCESSING: { color: '#1a73e8', bg: '#e3f2fd', label: '처리중' },
      COMPLETED: { color: '#4caf50', bg: '#e8f5e9', label: '완료' },
      FAILED: { color: '#f44336', bg: '#fdecea', label: '실패' },
    };
    const badge = badges[fileStatus] || badges.PENDING;
    return (
      <span
        className="status-badge"
        style={{ color: badge.color, backgroundColor: badge.bg }}
      >
        {badge.label}
      </span>
    );
  };

  return (
    <div className="admin-page">
      <h2 className="page-title">관리자 페이지</h2>

      {/* 파일 스캔 섹션 */}
      <section className="card scan-section">
        <h3>📂 파일 스캔</h3>
        <p className="scan-description">
          지정된 경로(<code>C:\OCR\testfile</code>)의 파일을 읽어 텍스트를 추출합니다.
        </p>
        <div className="scan-actions">
          <button
            className="scan-button"
            onClick={handleStartScan}
            disabled={scanLoading}
          >
            {scanLoading ? '⏳ 처리 중...' : '▶️ 파일 읽기 시작'}
          </button>
          <button className="refresh-button" onClick={fetchStatus}>
            🔄 상태 새로고침
          </button>
        </div>
        {message && <div className="message">{message}</div>}
      </section>

      {/* 처리 상태 섹션 */}
      <section className="card status-section">
        <h3>📊 프로세스 상태</h3>
        {status ? (
          <div className="status-content">
            <div className="status-grid">
              <div className="status-item">
                <span className="status-label">상태</span>
                <span
                  className="status-value"
                  style={{ color: getStatusColor(status.status) }}
                >
                  ● {status.status}
                </span>
              </div>
              <div className="status-item">
                <span className="status-label">전체 파일</span>
                <span className="status-value">{status.totalFiles}개</span>
              </div>
              <div className="status-item">
                <span className="status-label">처리 완료</span>
                <span className="status-value">{status.processedFiles}개</span>
              </div>
              <div className="status-item">
                <span className="status-label">실패</span>
                <span className="status-value" style={{ color: status.failedFiles > 0 ? '#f44336' : 'inherit' }}>
                  {status.failedFiles}개
                </span>
              </div>
            </div>

            {status.totalFiles > 0 && (
              <div className="progress-bar-container">
                <div className="progress-bar">
                  <div
                    className="progress-fill"
                    style={{ width: `${status.progressPercent}%` }}
                  />
                </div>
                <span className="progress-text">{status.progressPercent.toFixed(1)}%</span>
              </div>
            )}

            {status.startedAt && (
              <div className="status-time">
                <span>시작: {new Date(status.startedAt).toLocaleString('ko-KR')}</span>
                {status.completedAt && (
                  <span>완료: {new Date(status.completedAt).toLocaleString('ko-KR')}</span>
                )}
              </div>
            )}

            {status.errorMessage && (
              <div className="error-box">{status.errorMessage}</div>
            )}
          </div>
        ) : (
          <p className="no-data">상태 정보를 불러오는 중...</p>
        )}
      </section>

      {/* 파일 목록 섹션 */}
      <section className="card files-section">
        <h3>📋 처리된 파일 목록</h3>
        {loading ? (
          <p className="no-data">로딩 중...</p>
        ) : files.length === 0 ? (
          <p className="no-data">처리된 파일이 없습니다.</p>
        ) : (
          <>
            <table className="files-table">
              <thead>
                <tr>
                  <th>파일명</th>
                  <th>확장자</th>
                  <th>크기</th>
                  <th>상태</th>
                  <th>처리일시</th>
                  <th>다운로드</th>
                  {/* <th>삭제</th> */}
                </tr>
              </thead>
              <tbody>
                {files.map((file) => (
                  <tr key={file.id}>
                    <td className="file-name-cell" title={file.filePath}>
                      {file.fileName}
                    </td>
                    <td>{file.fileExtension}</td>
                    <td>{formatFileSize(file.fileSize)}</td>
                    <td>{getFileStatusBadge(file.status)}</td>
                    <td>
                      {file.processedAt
                        ? new Date(file.processedAt).toLocaleString('ko-KR')
                        : '-'}
                    </td>
                    <td>
                      <button
                        className="download-button"
                        onClick={() => downloadFile(file.id, file.fileName)}
                        title="파일 다운로드"
                      >
                        ⬇️ 다운로드
                      </button>
                    </td>
                    {/* <td>
                      <button
                        className="delete-button"
                        onClick={() => deleteFile(file.id)}
                        title="파일 삭제"
                      >
                         삭제
                      </button>
                    </td> */}
                  </tr>
                ))}
              </tbody>
            </table>

            {totalPages > 1 && (
              <div className="table-pagination">
                <button
                  disabled={currentPage === 0}
                  onClick={() => fetchFiles(currentPage - 1)}
                >
                  ◀ 이전
                </button>
                <span>
                  {currentPage + 1} / {totalPages}
                </span>
                <button
                  disabled={currentPage >= totalPages - 1}
                  onClick={() => fetchFiles(currentPage + 1)}
                >
                  다음 ▶
                </button>
              </div>
            )}
          </>
        )}
      </section>
    </div>
  );
}

function formatFileSize(bytes) {
  if (!bytes) return '-';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

export default AdminPage;