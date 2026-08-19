import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { searchDocuments } from '../api/client';

export default function Search() {
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState('');

  const { data, isLoading } = useQuery({
    queryKey: ['search', submitted],
    queryFn: () => searchDocuments({ q: submitted, size: 20 }),
    enabled: submitted.length >= 2,
  });

  const hits = data?.hits ?? [];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">통합 검색</h1>
        <p className="text-slate-500 mt-1">OpenSearch 기반 전문 검색 (문서명, 본문 내용)</p>
      </div>

      <div className="flex gap-3">
        <input
          type="text"
          placeholder="검색어를 입력하세요..."
          className="flex-1 border border-slate-300 rounded-xl px-4 py-3 text-base focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white"
          value={query}
          onChange={e => setQuery(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && setSubmitted(query)}
        />
        <button
          onClick={() => setSubmitted(query)}
          disabled={query.length < 2}
          className="px-6 py-3 bg-blue-600 text-white rounded-xl font-medium hover:bg-blue-700 disabled:opacity-40"
        >
          검색
        </button>
      </div>

      {submitted && (
        <div className="text-sm text-slate-500">
          {isLoading ? '검색 중...' : `"${submitted}" 검색 결과 ${data?.total ?? 0}건 (${data?.took ?? 0}ms)`}
        </div>
      )}

      <div className="space-y-3">
        {hits.map(hit => (
          <div key={hit.id} className="bg-white border border-slate-200 rounded-xl p-5 hover:border-blue-300 transition-colors">
            <div className="flex items-center gap-3 mb-2">
              <Link to={`/documents/${hit.id}`} className="font-semibold text-blue-700 hover:underline">{hit.fileName}</Link>
              <span className="text-xs bg-slate-100 text-slate-600 px-2 py-0.5 rounded font-mono">{hit.fileType}</span>
              <span className="text-xs text-slate-400 font-mono ml-auto">score: {hit.score?.toFixed(2)}</span>
            </div>
            <p className="text-xs text-slate-400 font-mono mb-2 truncate">{hit.filePath}</p>
            {hit.highlight && (
              <div
                className="text-sm text-slate-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2 font-mono leading-relaxed"
                dangerouslySetInnerHTML={{ __html: `...${hit.highlight}...` }}
              />
            )}
          </div>
        ))}
        {submitted && !isLoading && hits.length === 0 && (
          <div className="text-center py-12 text-slate-400">
            <p className="text-lg">검색 결과가 없습니다.</p>
            <p className="text-sm mt-1">다른 검색어를 시도해보세요.</p>
          </div>
        )}
      </div>
    </div>
  );
}
