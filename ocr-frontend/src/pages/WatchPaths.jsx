import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getWatchPaths, createWatchPath, deleteWatchPath, createJob } from '../api/client';

export default function WatchPaths() {
  const queryClient = useQueryClient();
  const [newPath, setNewPath] = useState('');
  const [newDesc, setNewDesc] = useState('');

  const { data: paths = [], isLoading } = useQuery({ queryKey: ['watch-paths'], queryFn: getWatchPaths });
  const create = useMutation({ mutationFn: createWatchPath, onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['watch-paths'] }); setNewPath(''); setNewDesc(''); } });
  const remove = useMutation({ mutationFn: (id) => deleteWatchPath(id), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['watch-paths'] }) });
  const scan = useMutation({ mutationFn: (watchPathId) => createJob({ watchPathId }), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }) });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">감시 경로</h1>
        <p className="text-slate-500 mt-1">문서를 스캔할 디렉토리 경로를 관리합니다.</p>
      </div>

      <div className="bg-white border border-slate-200 rounded-xl p-5">
        <h2 className="font-semibold text-slate-800 mb-4">경로 추가</h2>
        <div className="flex gap-3">
          <input type="text" placeholder="경로 (예: C:\OCR\testfile)" className="flex-1 border rounded-lg px-3 py-2 text-sm" value={newPath} onChange={e => setNewPath(e.target.value)} />
          <input type="text" placeholder="설명 (선택)" className="w-48 border rounded-lg px-3 py-2 text-sm" value={newDesc} onChange={e => setNewDesc(e.target.value)} />
          <button
            onClick={() => create.mutate({ path: newPath, description: newDesc, enabled: true })}
            disabled={!newPath.trim() || create.isPending}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 disabled:opacity-40"
          >
            추가
          </button>
        </div>
      </div>

      <div className="space-y-3">
        {isLoading && <p className="text-slate-400">불러오는 중...</p>}
        {paths.length === 0 && !isLoading && <p className="text-slate-400 text-sm">등록된 경로가 없습니다.</p>}
        {paths.map(wp => (
          <div key={wp.id} className="bg-white border border-slate-200 rounded-xl p-4 flex items-center gap-4">
            <div className="flex-1 min-w-0">
              <code className="text-sm font-mono text-slate-800 block truncate">{wp.path}</code>
              {wp.description && <p className="text-xs text-slate-500 mt-0.5">{wp.description}</p>}
              <p className="text-xs text-slate-400 mt-1">{new Date(wp.createdAt).toLocaleString()}</p>
            </div>
            <span className={`px-2 py-0.5 rounded text-xs font-semibold ${wp.enabled ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-100 text-slate-500'}`}>
              {wp.enabled ? '활성' : '비활성'}
            </span>
            <button
              onClick={() => scan.mutate(wp.id)}
              disabled={scan.isPending}
              className="px-3 py-1.5 bg-blue-50 text-blue-700 border border-blue-200 rounded-lg text-sm font-medium hover:bg-blue-100 disabled:opacity-40"
            >
              지금 스캔
            </button>
            <button
              onClick={() => { if (confirm('삭제하시겠습니까?')) remove.mutate(wp.id); }}
              className="px-3 py-1.5 text-rose-500 border border-rose-200 rounded-lg text-sm hover:bg-rose-50"
            >
              삭제
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
