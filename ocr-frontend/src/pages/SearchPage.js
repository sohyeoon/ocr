import React, { useState } from 'react';
import { searchFiles, downloadFile } from '../services/api';
import './SearchPage.css';

function SearchPage() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  const handleSearch = async (page = 0) => {
    if (!query.trim()) return;

    setLoading(true);
    setError('');

    try {
      const response = await searchFiles(query, page);
      setResults(response.data);
      setCurrentPage(page);
    } catch (err) {
      setError('검색 중 오류가 발생했습니다. 서버 연결을 확인해주세요.');
      console.error('Search error:', err);
    } finally {
      setLoading(false);
    }
  };

  const handleKeyPress = (e) => {
    if (e.key === 'Enter') {
      handleSearch(0);
    }
  };

  const getFileIcon = (extension) => {
    const icons = {
      '.pdf': '📕',
      '.doc': '📘',
      '.docx': '📘',
      '.xls': '📗',
      '.xlsx': '📗',
      '.ppt': '📙',
      '.pptx': '📙',
      '.txt': '📄',
    };
    return icons[extension?.toLowerCase()] || '📄';
  };

  return (
    <div className="search-page">
      <div className="search-container">
        <h2 className="search-title">통합 검색</h2>
        <p className="search-subtitle">파일 내용을 검색합니다 (OpenSearch 기반)</p>

        <div className="search-box">
          <input
            type="text"
            className="search-input"
            placeholder="검색어를 입력하세요..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyPress={handleKeyPress}
          />
          <button
            className="search-button"
            onClick={() => handleSearch(0)}
            disabled={loading || !query.trim()}
          >
            {loading ? '검색 중...' : '🔍 검색'}
          </button>
        </div>

        {error && <div className="error-message">{error}</div>}

        {results && (
          <div className="search-results">
            <div className="results-info">
              <span>총 <strong>{results.totalHits}</strong>건 검색됨</span>
              <span className="search-time">({results.searchTimeMs}ms)</span>
            </div>

            {results.hits.length === 0 ? (
              <div className="no-results">검색 결과가 없습니다.</div>
            ) : (
              <div className="results-list">
                {results.hits.map((hit, index) => (
                  <div key={index} className="result-item">
                    <div className="result-header">
                      <span className="file-icon">{getFileIcon(hit.fileExtension)}</span>
                      <span className="file-name">{hit.fileName}</span>
                      <span className="file-score">관련도: {(hit.score * 10).toFixed(1)}%</span>
                    </div>
                    <div className="result-path">{hit.filePath}</div>
                    <div
                      className="result-snippet"
                      dangerouslySetInnerHTML={{ __html: hit.contentSnippet }}
                    />
                    <div className="result-actions">
                      <button
                        className="download-btn"
                        onClick={() => downloadFile(hit.fileId, hit.fileName)}
                      >
                        ⬇️ 다운로드
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}

            {results.totalHits > results.size && (
              <div className="pagination">
                <button
                  disabled={currentPage === 0}
                  onClick={() => handleSearch(currentPage - 1)}
                >
                  ◀ 이전
                </button>
                <span>
                  페이지 {currentPage + 1} / {Math.ceil(results.totalHits / results.size)}
                </span>
                <button
                  disabled={(currentPage + 1) * results.size >= results.totalHits}
                  onClick={() => handleSearch(currentPage + 1)}
                >
                  다음 ▶
                </button>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

export default SearchPage;