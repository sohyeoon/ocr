import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom';
import Dashboard from './pages/Dashboard';
import Documents from './pages/Documents';
import DocumentDetail from './pages/DocumentDetail';
import Jobs from './pages/Jobs';
import WatchPaths from './pages/WatchPaths';
import Search from './pages/Search';
import Config from './pages/Config';

const nav = [
  { to: '/', label: '대시보드', end: true },
  { to: '/documents', label: '문서 목록' },
  { to: '/jobs', label: '처리 작업' },
  { to: '/watch-paths', label: '감시 경로' },
  { to: '/search', label: '통합 검색' },
  { to: '/config', label: '서비스 설정' },
];

export default function App() {
  return (
    <BrowserRouter>
      <div className="flex h-screen overflow-hidden">
        {/* Sidebar */}
        <aside className="sidebar w-60 flex-shrink-0 flex flex-col">
          <div className="px-6 py-5 border-b border-slate-700">
            <h1 className="text-base font-bold text-white tracking-tight">문서 관리 시스템</h1>
            <p className="text-xs text-slate-400 mt-0.5">Document Management</p>
          </div>
          <nav className="flex-1 px-3 py-4 space-y-0.5">
            {nav.map(({ to, label, end }) => (
              <NavLink
                key={to}
                to={to}
                end={end}
                className={({ isActive }) =>
                  `block px-3 py-2 rounded-md text-sm font-medium transition-colors ${
                    isActive
                      ? 'bg-white/10 text-white'
                      : 'text-slate-400 hover:text-white hover:bg-white/5'
                  }`
                }
              >
                {label}
              </NavLink>
            ))}
          </nav>
          <div className="px-4 py-4 border-t border-slate-700 text-xs text-slate-500">
            Kafka · Tika · PaddleOCR · OpenSearch
          </div>
        </aside>

        {/* Main */}
        <main className="flex-1 overflow-y-auto bg-slate-50">
          <div className="p-8 max-w-7xl mx-auto">
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/documents" element={<Documents />} />
              <Route path="/documents/:id" element={<DocumentDetail />} />
              <Route path="/jobs" element={<Jobs />} />
              <Route path="/watch-paths" element={<WatchPaths />} />
              <Route path="/search" element={<Search />} />
              <Route path="/config" element={<Config />} />
            </Routes>
          </div>
        </main>
      </div>
    </BrowserRouter>
  );
}
