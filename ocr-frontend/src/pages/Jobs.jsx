import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getJobs } from '../api/client';

const statusColors = {
  completed: 'bg-emerald-100 text-emerald-800',
  processing: 'bg-blue-100 text-blue-800',
  queued: 'bg-amber-100 text-amber-800',
  failed: 'bg-rose-100 text-rose-800',
};

export default function Jobs() {
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState('');
  const params = { page, limit: 20, ...(statusFilter ? { status: statusFilter } : {}) };
  const { data, isLoading } = useQuery({ queryKey: ['jobs', params], queryFn: () => getJobs(params), refetchInterval: 5000 });

  const items = data?.content ?? data?.items ?? [];
  const total = data?.totalElements ?? data?.total ?? 0;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">처리 작업</h1>
        <p className="text-slate-500 mt-1">스캔 및 문서 처리 작업 현황</p>
      </div>
      <div className="flex gap-3 bg-white p-4 rounded-xl border border-slate-200">
        <select className="border rounded-lg px-3 py-2 text-sm" value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setPage(1); }}>
          <option value="">전체 상태</option>
          <option value="queued">대기</option>
          <option value="processing">처리 중</option>
          <option value="completed">완료</option>
          <option value="failed">실패</option>
        </select>
      </div>
      <div className="bg-white border border-slate-200 rounded-xl overflow-hidden shadow-sm">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 border-b text-xs text-slate-500">
            <tr>
              <th className="px-4 py-3 text-left">ID</th>
              <th className="px-4 py-3 text-left">유형</th>
              <th className="px-4 py-3 text-left">상태</th>
              <th className="px-4 py-3 text-left">진행</th>
              <th className="px-4 py-3 text-left">오류</th>
              <th className="px-4 py-3 text-left">생성일</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {isLoading ? (
              <tr><td colSpan={6} className="py-8 text-center text-slate-400">불러오는 중...</td></tr>
            ) : items.length === 0 ? (
              <tr><td colSpan={6} className="py-12 text-center text-slate-400">작업이 없습니다.</td></tr>
            ) : items.map(job => {
              const progress = job.totalFiles ? Math.round((job.processedFiles / job.totalFiles) * 100) : 0;
              return (
                <tr key={job.id} className="hover:bg-slate-50">
                  <td className="px-4 py-3 font-mono text-xs text-slate-400">#{job.id}</td>
                  <td className="px-4 py-3"><span className="px-2 py-0.5 bg-slate-100 text-slate-600 rounded text-xs font-medium">{job.type}</span></td>
                  <td className="px-4 py-3"><span className={`px-2 py-0.5 rounded text-xs font-semibold ${statusColors[job.status]}`}>{job.status}</span></td>
                  <td className="px-4 py-3">
                    {job.totalFiles != null ? (
                      <div className="flex items-center gap-2">
                        <div className="flex-1 bg-slate-200 rounded-full h-1.5 w-24">
                          <div className="bg-blue-500 h-1.5 rounded-full" style={{ width: `${progress}%` }} />
                        </div>
                        <span className="font-mono text-xs text-slate-500">{job.processedFiles}/{job.totalFiles}</span>
                      </div>
                    ) : '-'}
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-rose-500 max-w-xs truncate">{job.errorMessage || '-'}</td>
                  <td className="px-4 py-3 font-mono text-xs text-slate-400">{new Date(job.createdAt).toLocaleString()}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {total > 0 && (
          <div className="p-4 border-t bg-slate-50 flex items-center justify-between text-sm">
            <span className="text-slate-500">총 {total}개</span>
            <div className="flex gap-2">
              <button onClick={() => setPage(p => Math.max(1, p - 1))} disabled={page === 1} className="px-3 py-1 border rounded hover:bg-slate-100 disabled:opacity-40">이전</button>
              <button onClick={() => setPage(p => p + 1)} disabled={page * 20 >= total} className="px-3 py-1 border rounded hover:bg-slate-100 disabled:opacity-40">다음</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
